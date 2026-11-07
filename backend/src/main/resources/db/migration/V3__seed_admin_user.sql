-- =============================================================================
-- V3__seed_admin_user.sql —— 默认登录账号（见 §2.2「默认账号」）
--
-- 账号：admin / admin123
-- 哈希：BCrypt（cost=10），由 BCryptPasswordEncoder.matches() 校验，库内不存明文。
-- 安全声明：此账号仅用于本地演示与联调，**上线前必须修改密码或停用**。
--           生产环境不允许出现固定口令账号（T14 的 prod profile 会校验此点）。
--
-- 主键固定为 1：雪花 ID 从 1.5e18 量级起跳，不会与种子数据冲突。
-- =============================================================================
INSERT INTO app_user (id, username, password_hash, display_name, enabled)
VALUES (1, 'admin', '$2a$10$utgIc.I3VQhOrjLdPJTjK.IOpxhirXIIDWpgfPQd9H/Zjh3l9VFoG', '管理员', 1);
