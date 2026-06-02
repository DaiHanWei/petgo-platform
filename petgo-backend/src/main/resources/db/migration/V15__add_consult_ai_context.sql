-- Story 5.4: AI 升级至兽医的上下文传递 —— consult_sessions 增列存 AI 上下文快照。
-- 「快照定格」：升级当下把 AI 评级/症状描述定格为 consult 上下文，triage 后续变更不影响已升级会话（FR-4B）。
-- 图片存私密桶对象 key 引用（取用时经 SignedUrlService 现签短 TTL URL，绝不入库签名 URL）。
-- 红线：ai_danger_level 仅 GREEN|YELLOW —— 红色态零兽医引流，RED 永不升级（后端兜底拒绝，库约束再加一层）。

ALTER TABLE consult_sessions
    ADD COLUMN triage_task_id  BIGINT,                 -- 来源分诊任务（source=AI_UPGRADE 时填）
    ADD COLUMN ai_danger_level VARCHAR(8),             -- GREEN | YELLOW（绝不含 RED）
    ADD COLUMN ai_symptom_text TEXT,                   -- 症状描述快照（健康数据：日志严禁明文）
    ADD COLUMN ai_image_refs   JSONB;                  -- 私密桶对象 key 列表（引用，非签名 URL）

ALTER TABLE consult_sessions
    ADD CONSTRAINT ck_consult_sessions_ai_danger CHECK (
        ai_danger_level IS NULL OR ai_danger_level IN ('GREEN', 'YELLOW'));
