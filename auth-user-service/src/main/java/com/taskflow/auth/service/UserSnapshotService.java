package com.taskflow.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.auth.entity.AppUser;
import com.taskflow.auth.entity.Department;
import com.taskflow.auth.mapper.AppUserMapper;
import com.taskflow.auth.mapper.DepartmentMapper;
import com.taskflow.common.RedisUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户快照缓存服务（架构文档 3.2 铁律 2：展示类信息走 Redis 快照）。
 *
 * <p>键 {@code auth:user:snapshot}，值为 JSON：
 * {@code {"1": {"name": "张伟", "departmentName": "研发部"}, ...}}</p>
 *
 * <p>刷新时机：服务启动（ApplicationRunner）+ 用户/部门任何写操作后。
 * 其他服务（task-service 等）只读本键，不回调本服务。</p>
 */
@Service
public class UserSnapshotService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSnapshotService.class);

    /** 快照键：单键 JSON，用户数有限（企业内网量级），整体覆盖写 */
    public static final String SNAPSHOT_KEY = "auth:user:snapshot";

    private final AppUserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final RedisUtils redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserSnapshotService(AppUserMapper userMapper, DepartmentMapper departmentMapper,
                               RedisUtils redis) {
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.redis = redis;
    }

    /** 服务启动时刷新快照（兜底：Redis 清空后自愈） */
    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    /**
     * 全量刷新用户快照（用户/部门变更后调用）。
     */
    public void refresh() {
        Map<Long, String> deptNames = departmentMapper.selectList(null).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        List<AppUser> users = userMapper.selectList(null);

        Map<String, Map<String, String>> snapshot = new HashMap<>();
        for (AppUser u : users) {
            snapshot.put(String.valueOf(u.getId()), Map.of(
                    "name", u.getName(),
                    "account", u.getAccount(),
                    "departmentName", deptNames.getOrDefault(u.getDepartmentId(), ""),
                    "status", u.getStatus()));
        }
        try {
            // 快照不设 TTL：由写操作主动刷新，避免过期空窗
            redis.set(SNAPSHOT_KEY, objectMapper.writeValueAsString(snapshot), Duration.ofDays(30));
            log.info("用户快照已刷新: {} 个用户", users.size());
        } catch (JsonProcessingException e) {
            log.error("用户快照序列化失败", e);
        }
    }
}
