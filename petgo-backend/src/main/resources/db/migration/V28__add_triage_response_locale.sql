-- Story 4.x 联调：分诊回复语言。submit 时按 Accept-Language 归一为 id/en（默认 en）落库，
-- @Async 处理时取用喂 Gemini，保证回复语言跟随 app 语言（英语兜底，绝不中文）。
-- 既有行 NULL → 处理时按英语默认。
ALTER TABLE triage_tasks ADD COLUMN response_locale varchar(8);
