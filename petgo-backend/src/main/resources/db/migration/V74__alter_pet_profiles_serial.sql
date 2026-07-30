-- Story 6.1（V1.1 Epic 6 开篇）：宠物身份证全平台自增流水号 serial_id + 号池回收机制（FR-49A）。
-- 接 V73 顺延 → 本迁移 V74（架构旧稿 V55 / sprint 旧计划 V70 均已被 Epic 4 占用：
--   V70 feedback_tickets / V71 refund_requests / V72 notification_types_v11 / V73 refund_approval_fields，当前最大 V73）。
--   决策 E2「实际 merge 时号单调顺延」；跨分支撞号是本项目历史反复踩点，故按当时最大号 +1。
-- serial_id 仅作展示编号，绝不作对外资源标识（分享/深链/快照定位仍用 card_token / 内部 id）。
-- 惰性分配：老用户 serial_id 为 NULL（前端「尚未生成」引导态）；用户主动生成身份证才分配。
-- ddl-auto=validate，schema 归 Flyway。id_card_hd_purchases（6.3）另起迁移，本迁移只做 serial。

-- 1) 加流水号列（nullable：老用户/未生成为 NULL；Postgres UNIQUE 允许多行 NULL）+ 唯一约束（撞号终极兜底）。
ALTER TABLE pet_profiles ADD COLUMN serial_id BIGINT;
ALTER TABLE pet_profiles ADD CONSTRAINT uq_pet_profiles_serial_id UNIQUE (serial_id);

-- 2) 高水位自增序列（新号从 1 起）。
CREATE SEQUENCE pet_serial_seq START 1 INCREMENT 1;

-- 3) 回收号 free-list（删除档案时把已分配的号入池；分配优先出池、否则 nextval → 编号紧凑、被删的号可复用）。
CREATE TABLE pet_serial_pool (
    serial_id BIGINT PRIMARY KEY
);
