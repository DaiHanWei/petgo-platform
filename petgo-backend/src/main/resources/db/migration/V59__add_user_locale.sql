-- 用户语言偏好（bug 20260625-105：系统推送按用户语言渲染）。
-- 由 App 请求的 Accept-Language 捕获（id/en）；空视为默认 id。决策 E2：db/migration 实测最大号+1 = V59。
ALTER TABLE users ADD COLUMN locale VARCHAR(8);
