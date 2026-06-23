-- Story C: 兽医最终诊断表单 —— consult_sessions 增列存结构化诊断（JSONB，整表单定格）。
-- 兽医结束会话必填 Diagnosa；诊断推送给用户（IM 系统消息）+ 并入兽医历史摘要。
-- 健康数据：日志严禁明文输出本列内容。加列走新 ALTER（已提交迁移冻结，决策 E2）。
ALTER TABLE consult_sessions
    ADD COLUMN vet_diagnosis JSONB;
