-- ============================================================
-- V1: auth_user_db 初始 schema（库表设计文档第 3 章）
-- ============================================================

CREATE TABLE department (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE CHECK (LENGTH(name) BETWEEN 1 AND 50),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  department IS '部门（单层列表，PRD 4.5.2；存在在职用户的部门不可删除）';
COMMENT ON COLUMN department.name IS '部门名称，全局唯一，≤ 50 字符';
COMMENT ON COLUMN department.created_at IS '创建时间（UTC）';

CREATE TABLE role (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_key    TEXT NOT NULL UNIQUE CHECK (role_key IN ('admin', 'taskAdmin', 'user')),
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  role IS '预置角色（固定 3 条，PRD 3.1；一期不支持自定义角色）';
COMMENT ON COLUMN role.role_key IS '角色键：admin 系统管理员 / taskAdmin 任务管理员 / user 普通用户';
COMMENT ON COLUMN role.name IS '角色显示名';
COMMENT ON COLUMN role.created_at IS '创建时间（UTC）';

CREATE TABLE app_user (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account              TEXT NOT NULL UNIQUE
                         CHECK (account ~ '^[a-zA-Z0-9_]{4,32}$'),
    name                 TEXT NOT NULL,
    password_hash        TEXT NOT NULL,
    email                TEXT NOT NULL
                         CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'),
    department_id        BIGINT NOT NULL REFERENCES department(id),
    role_id              BIGINT NOT NULL REFERENCES role(id),
    status               TEXT NOT NULL DEFAULT 'active'
                         CHECK (status IN ('active', 'disabled')),
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  app_user IS '用户（PRD 4.5.1；不可物理删除，停用走 status）';
COMMENT ON COLUMN app_user.account IS '登录账号，唯一，字母数字下划线 4-32 位';
COMMENT ON COLUMN app_user.name IS '姓名';
COMMENT ON COLUMN app_user.password_hash IS '密码哈希（BCrypt，PRD 7.3.1）';
COMMENT ON COLUMN app_user.email IS '邮箱（通知邮件接收地址）';
COMMENT ON COLUMN app_user.department_id IS '归属部门';
COMMENT ON COLUMN app_user.role_id IS '绑定角色（每用户仅一个，PRD 4.5.3）';
COMMENT ON COLUMN app_user.status IS '在职状态：active 在职 / disabled 停用（禁止登录，名下未完成任务保留）';
COMMENT ON COLUMN app_user.must_change_password IS '下次登录强制改密（新增用户与重置密码后为 TRUE）';
COMMENT ON COLUMN app_user.created_at IS '创建时间（UTC）';
CREATE INDEX idx_app_user_department ON app_user (department_id);
CREATE INDEX idx_app_user_role ON app_user (role_id);

CREATE TABLE role_permission (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_id         BIGINT NOT NULL REFERENCES role(id),
    permission_key  TEXT NOT NULL CHECK (permission_key IN (
                        'viewAll', 'create', 'editOwn', 'deleteOwn', 'transferOwn',
                        'prioOwn', 'dueOwn', 'viewAssigned', 'updateAssigned',
                        'transferAssigned', 'viewStats', 'exportData',
                        'manageUser', 'setPerm')),
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (role_id, permission_key)
);
COMMENT ON TABLE  role_permission IS '角色权限矩阵（3 角色 × 14 权限点，PRD 3.2/4.5.4；勾选即改即存）';
COMMENT ON COLUMN role_permission.role_id IS '角色';
COMMENT ON COLUMN role_permission.permission_key IS '权限点键（14 个之一，PRD 3.2）';
COMMENT ON COLUMN role_permission.enabled IS '开关：TRUE 授予 / FALSE 收回（admin 的 manageUser、setPerm 恒为 TRUE）';
COMMENT ON COLUMN role_permission.created_at IS '创建时间（UTC）';

CREATE TABLE audit_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator_id     BIGINT NOT NULL,
    action          TEXT NOT NULL,
    change_detail   JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  audit_log IS '审计日志（只增不改，PRD 4.5.4；在线 1 年后转冷存储，PRD 7.5）';
COMMENT ON COLUMN audit_log.operator_id IS '操作人（逻辑引用 app_user.id）';
COMMENT ON COLUMN audit_log.action IS '操作类型，如 permission.matrix.update';
COMMENT ON COLUMN audit_log.change_detail IS '变更前后差异，如 {"viewAll": {"taskAdmin": [false, true]}}';
COMMENT ON COLUMN audit_log.created_at IS '操作时间（UTC）';
CREATE INDEX idx_audit_log_operator ON audit_log (operator_id);
CREATE INDEX idx_audit_log_created ON audit_log (created_at DESC);
-- 只增不改的 REVOKE 约束由运维账号在部署时执行（见库表设计文档 7.2）

CREATE TABLE api_key (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          TEXT NOT NULL,
    user_id       BIGINT NOT NULL REFERENCES app_user(id),
    key_hash      TEXT NOT NULL UNIQUE,
    key_prefix    TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'active'
                  CHECK (status IN ('active', 'disabled')),
    last_used_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE  api_key IS 'OpenAPI 接入密钥（接口设计文档第 2 章；静态 Key，仅开放 POST /api/tasks）';
COMMENT ON COLUMN api_key.name IS '调用方名称，如 "运维监控系统"';
COMMENT ON COLUMN api_key.user_id IS '绑定的专用服务账号（角色固定 taskAdmin）';
COMMENT ON COLUMN api_key.key_hash IS 'API Key 哈希（明文仅签发时响应一次，不落库）';
COMMENT ON COLUMN api_key.key_prefix IS '明文前 8 位（如 tfk_a1b2），列表展示与排查用';
COMMENT ON COLUMN api_key.status IS '状态：active 有效 / disabled 停用（停用即 401）';
COMMENT ON COLUMN api_key.last_used_at IS '最近使用时间（回源鉴权时刷新，缓存命中不逐次刷）';
COMMENT ON COLUMN api_key.created_at IS '签发时间（UTC）';
CREATE INDEX idx_api_key_user ON api_key (user_id);
