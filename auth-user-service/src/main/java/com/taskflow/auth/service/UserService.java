package com.taskflow.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.auth.entity.AppUser;
import com.taskflow.auth.entity.Department;
import com.taskflow.auth.entity.Role;
import com.taskflow.auth.mapper.AppUserMapper;
import com.taskflow.auth.mapper.DepartmentMapper;
import com.taskflow.auth.mapper.RoleMapper;
import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户 / 部门 / 角色管理服务（PRD 4.5.1-4.5.3）。
 *
 * <p>要点：用户不可物理删除；停用保留数据并禁止登录；
 * 角色指派即时生效（角色变更后其请求按新角色鉴权——权限缓存按角色键缓存，天然即时）。</p>
 */
@Service
public class UserService {

    private final AppUserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final RoleMapper roleMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(AppUserMapper userMapper, DepartmentMapper departmentMapper,
                       RoleMapper roleMapper, BCryptPasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 用户分页列表（PRD 4.5.1：姓名/账号/部门/角色/邮箱/状态/创建时间）。
     *
     * @param keyword      关键字（姓名或账号模糊），可空
     * @param departmentId 部门筛选，可空
     * @param roleId       角色筛选，可空
     * @param status       状态筛选，可空
     * @param page         页码（1 起）
     * @param size         每页条数（10/20/50）
     * @return 分页结果（list 携部门名与角色键，免前端二次查询）
     */
    public Page<Map<String, Object>> pageUsers(String keyword, Long departmentId, Long roleId,
                                               String status, int page, int size) {
        LambdaQueryWrapper<AppUser> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(AppUser::getName, keyword).or().like(AppUser::getAccount, keyword));
        }
        qw.eq(departmentId != null, AppUser::getDepartmentId, departmentId)
                .eq(roleId != null, AppUser::getRoleId, roleId)
                .eq(StringUtils.hasText(status), AppUser::getStatus, status)
                .orderByDesc(AppUser::getCreatedAt);

        Page<AppUser> raw = userMapper.selectPage(new Page<>(page, size), qw);

        // 部门名与角色键 join 展示（数据量小，内存映射即可）
        Map<Long, String> deptNames = departmentMapper.selectList(null).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        Map<Long, Role> roles = roleMapper.selectList(null).stream()
                .collect(Collectors.toMap(Role::getId, r -> r));

        Page<Map<String, Object>> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(u -> {
            Role role = roles.get(u.getRoleId());
            return Map.<String, Object>of(
                    "id", u.getId(),
                    "name", u.getName(),
                    "account", u.getAccount(),
                    "departmentId", u.getDepartmentId(),
                    "departmentName", deptNames.getOrDefault(u.getDepartmentId(), ""),
                    "roleId", u.getRoleId(),
                    "roleKey", role == null ? "" : role.getRoleKey(),
                    "email", u.getEmail(),
                    "status", u.getStatus(),
                    "createdAt", u.getCreatedAt().toString());
        }).collect(Collectors.toList()));
        return result;
    }

    /**
     * 新增用户：账号唯一校验，生成随机初始密码（仅此一次返回），强制首次改密。
     *
     * @return 新用户 id 与初始密码明文
     * @throws BizException 3007 账号已存在 / 1002 部门或角色不存在
     */
    public Map<String, Object> createUser(String name, String account, Long departmentId,
                                          Long roleId, String email) {
        if (userMapper.exists(new LambdaQueryWrapper<AppUser>().eq(AppUser::getAccount, account))) {
            throw new BizException(ErrorCode.ACCOUNT_EXISTS);
        }
        if (departmentMapper.selectById(departmentId) == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "部门不存在");
        }
        if (roleMapper.selectById(roleId) == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "角色不存在");
        }
        String initialPassword = AuthService.newToken().substring(0, 8) + "A1"; // 满足字母+数字规则
        AppUser user = new AppUser();
        user.setName(name);
        user.setAccount(account);
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setEmail(email);
        user.setDepartmentId(departmentId);
        user.setRoleId(roleId);
        user.setStatus("active");
        user.setMustChangePassword(true);
        userMapper.insert(user);
        return Map.of("id", user.getId(), "initialPassword", initialPassword);
    }

    /**
     * 编辑用户（仅部门与邮箱可改，PRD 4.5.1）。
     */
    public void updateUser(Long id, Long departmentId, String email) {
        AppUser user = mustExist(id);
        if (departmentMapper.selectById(departmentId) == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "部门不存在");
        }
        user.setDepartmentId(departmentId);
        user.setEmail(email);
        userMapper.updateById(user);
    }

    /**
     * 停用 / 启用用户。停用保留数据、禁止登录；其名下未完成任务保留（PRD 4.5.1）。
     */
    public void changeStatus(Long id, String status) {
        AppUser user = mustExist(id);
        user.setStatus(status);
        userMapper.updateById(user);
    }

    /**
     * 角色指派（即时生效：权限缓存按角色键而非用户缓存，换角色后下次请求即走新角色）。
     */
    public void assignRole(Long id, Long roleId) {
        AppUser user = mustExist(id);
        if (roleMapper.selectById(roleId) == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "角色不存在");
        }
        user.setRoleId(roleId);
        userMapper.updateById(user);
    }

    /**
     * 重置密码：生成随机新密码（仅此一次返回），强制下次登录改密。
     *
     * @return 新密码明文
     */
    public Map<String, Object> resetPassword(Long id) {
        AppUser user = mustExist(id);
        String newPassword = AuthService.newToken().substring(0, 8) + "A1";
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        userMapper.updateById(user);
        return Map.of("newPassword", newPassword);
    }

    // ========== 部门（PRD 4.5.2）==========

    /**
     * 部门列表（含在职用户数）。
     */
    public List<Map<String, Object>> listDepartments() {
        return departmentMapper.selectList(new LambdaQueryWrapper<Department>().orderByAsc(Department::getId))
                .stream().map(d -> {
                    long count = userMapper.selectCount(new LambdaQueryWrapper<AppUser>()
                            .eq(AppUser::getDepartmentId, d.getId())
                            .eq(AppUser::getStatus, "active"));
                    return Map.<String, Object>of(
                            "id", d.getId(), "name", d.getName(),
                            "userCount", count, "createdAt", d.getCreatedAt().toString());
                }).collect(Collectors.toList());
    }

    /**
     * 新增部门（名称唯一由 DB UNIQUE 约束兜底）。
     */
    public Map<String, Object> createDepartment(String name) {
        Department d = new Department();
        d.setName(name);
        try {
            departmentMapper.insert(d);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "部门名称已存在");
        }
        return Map.of("id", d.getId(), "name", d.getName());
    }

    /**
     * 编辑部门名称。
     */
    public void updateDepartment(Long id, String name) {
        Department d = departmentMapper.selectById(id);
        if (d == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "部门不存在");
        }
        d.setName(name);
        departmentMapper.updateById(d);
    }

    /**
     * 删除部门：存在在职用户禁止删除（3008，PRD 4.5.2）。
     */
    public void deleteDepartment(Long id) {
        long activeUsers = userMapper.selectCount(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getDepartmentId, id)
                .eq(AppUser::getStatus, "active"));
        if (activeUsers > 0) {
            throw new BizException(ErrorCode.DEPARTMENT_HAS_USERS,
                    ErrorCode.DEPARTMENT_HAS_USERS.getMessage(), Map.of("userCount", activeUsers));
        }
        departmentMapper.deleteById(id);
    }

    /**
     * 角色与权限点目录（PRD 3.1 / 3.2，供矩阵页与用户表单渲染）。
     */
    public Map<String, Object> roleCatalog() {
        return Map.of(
                "roles", roleMapper.selectList(null),
                "permissionKeys", PermissionService.PERMISSION_CATALOG);
    }

    /** 取用户或抛 1002 */
    private AppUser mustExist(Long id) {
        AppUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        return user;
    }
}
