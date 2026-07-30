-- Story 3.2 [OPEN] 收口 + D2（2026-07-16 产品拍板）：consult_requests 增列存病例 + 来源。
--
-- 缺口背景：3-1 建表未含病例列；3-2 [OPEN]「symptomText/imageObjectKeys 是否存 consult_requests」
-- 留「dev 与产品确认」后未闭环 → 付费队列只能展示宠物身份 + 等待时长，兽医无法「看病例选择接单」。
-- D1：兽医接单前可看完整病例（症状 + 私密图）；D2：分诊升级（AI_UPGRADE）与自填病例走同一条付费路径。
--
-- 图片存私密桶对象 key 引用（取用时经 SignedUrlService 现签短 TTL URL，绝不入库签名 URL）。
-- 红线：ai_danger_level 仅 GREEN|YELLOW —— 红色态零兽医引流，RED 永不入队（后端兜底拒绝，库约束再加一层）。
-- 列结构照 V15（consult_sessions AI 上下文）保持两表病例语义一致；时间戳一律 UTC。

ALTER TABLE consult_requests
    ADD COLUMN source            VARCHAR(16) NOT NULL DEFAULT 'DIRECT',  -- DIRECT | AI_UPGRADE
    ADD COLUMN triage_task_id    BIGINT,                                 -- 来源分诊任务（source=AI_UPGRADE 时填）
    ADD COLUMN ai_danger_level   VARCHAR(8),                             -- GREEN | YELLOW（绝不含 RED）
    ADD COLUMN symptom_text      TEXT,                                   -- 症状描述（健康数据：日志严禁明文）
    ADD COLUMN image_object_keys JSONB;                                  -- 私密桶对象 key 列表（引用，非签名 URL）

ALTER TABLE consult_requests
    ADD CONSTRAINT ck_consult_requests_source CHECK (source IN ('DIRECT', 'AI_UPGRADE'));

-- 红色态零变现：库层兜底，RED 永不落入队请求。
ALTER TABLE consult_requests
    ADD CONSTRAINT ck_consult_requests_ai_danger CHECK (
        ai_danger_level IS NULL OR ai_danger_level IN ('GREEN', 'YELLOW'));
