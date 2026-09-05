-- ============================================================
-- V2: auth_user_db 种子数据（库表设计文档 7.1）
-- 默认部门 + 3 角色 + 42 行默认权限矩阵 + 初始 admin
-- ============================================================

-- 默认部门
INSERT INTO department (name) VALUES ('默认部门');

-- 角色（3 条，PRD 3.1）
INSERT INTO role (role_key, name) VALUES
    ('admin', '系统管理员'),
    ('taskAdmin', '任务管理员'),
    ('user', '普通用户');

-- 初始 admin 用户（开发环境初始密码 Admin@123，生产部署由脚本生成随机密码覆盖；
-- must_change_password = TRUE，首次登录强制改密）
INSERT INTO app_user (account, name, password_hash, email, department_id, role_id, status, must_change_password)
SELECT 'admin', '系统管理员',
       '$2y$10$yQBFrOqebPqT2IVrlWx2ZuxG2N9ReAO7qo05/.2NRMOS5TMg93Xk.',
       'admin@taskflow.local', d.id, r.id, 'active', TRUE
FROM department d, role r
WHERE d.name = '默认部门' AND r.role_key = 'admin';

-- 权限矩阵（42 条 = 3 角色 × 14 权限点，按 PRD 3.3 默认矩阵）
-- admin：14 项全 true
INSERT INTO role_permission (role_id, permission_key, enabled)
SELECT r.id, p.key, TRUE
FROM role r CROSS JOIN (VALUES
    ('viewAll'), ('create'), ('editOwn'), ('deleteOwn'), ('transferOwn'),
    ('prioOwn'), ('dueOwn'), ('viewAssigned'), ('updateAssigned'),
    ('transferAssigned'), ('viewStats'), ('exportData'), ('manageUser'), ('setPerm')
) AS p(key)
WHERE r.role_key = 'admin';

-- taskAdmin：除 viewAll / manageUser / setPerm 外全 true
INSERT INTO role_permission (role_id, permission_key, enabled)
SELECT r.id, p.key, p.key NOT IN ('viewAll', 'manageUser', 'setPerm')
FROM role r CROSS JOIN (VALUES
    ('viewAll'), ('create'), ('editOwn'), ('deleteOwn'), ('transferOwn'),
    ('prioOwn'), ('dueOwn'), ('viewAssigned'), ('updateAssigned'),
    ('transferAssigned'), ('viewStats'), ('exportData'), ('manageUser'), ('setPerm')
) AS p(key)
WHERE r.role_key = 'taskAdmin';

-- user：仅 viewAssigned / updateAssigned / transferAssigned 为 true
INSERT INTO role_permission (role_id, permission_key, enabled)
SELECT r.id, p.key, p.key IN ('viewAssigned', 'updateAssigned', 'transferAssigned')
FROM role r CROSS JOIN (VALUES
    ('viewAll'), ('create'), ('editOwn'), ('deleteOwn'), ('transferOwn'),
    ('prioOwn'), ('dueOwn'), ('viewAssigned'), ('updateAssigned'),
    ('transferAssigned'), ('viewStats'), ('exportData'), ('manageUser'), ('setPerm')
) AS p(key)
WHERE r.role_key = 'user';
