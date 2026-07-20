-- 会话↔订单直连（0718 兽医工作台「历史」卡显示到手金额 Diterima，ref36）。
-- 背景：consult_orders 与 consult_sessions 此前无外键，仅经 (user/vet/pet) 间接关联，对回头客歧义，
-- 无法按会话取到手金额。付费建单后 markSessionStarted 回填此列（同一次付费流内 session.id 在 scope）。
-- 迁移前的历史订单为 NULL（老历史卡到手金额显「—」，不回填脏历史）。
ALTER TABLE consult_orders ADD COLUMN consult_session_id bigint;

-- 兽医历史按 (vet_id, session_id 批) 批量取订单，建覆盖索引。
CREATE INDEX idx_consult_orders_vet_session ON consult_orders (vet_id, consult_session_id);
