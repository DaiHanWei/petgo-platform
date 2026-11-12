-- analytics-pipeline Story 1.7：明细层重算请求表（只在埋点库执行）。D-7 / D-8 / FR-13。
-- 重算入口 = 本表 + DetailRecomputeJob 定时消费（「@Scheduled + DB 状态机」）；不新增任何 HTTP 接口 / 后台页面 / CLI 参数。
-- 由 Dai 用写入账号插一行触发：INSERT INTO detail_recompute_requests(from_date, to_date) VALUES ('2026-11-01','2026-11-07');
-- 🔴 不授权给只读角色：这是运维控制表，不是查数用的。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

CREATE TABLE detail_recompute_requests (
    id            bigserial    PRIMARY KEY,
    from_date     date         NOT NULL,
    to_date       date         NOT NULL,
    status        varchar(16)  NOT NULL DEFAULT 'PENDING',
    requested_at  timestamptz  NOT NULL DEFAULT now(),
    started_at    timestamptz,
    done_at       timestamptz,
    error_code    varchar(64),
    CONSTRAINT ck_detail_recompute_requests_range CHECK (from_date <= to_date),
    CONSTRAINT ck_detail_recompute_requests_status
        CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'REJECTED'))
);

COMMENT ON TABLE detail_recompute_requests IS '明细层重算请求：插一行 PENDING，DetailRecomputeJob 按 id 顺序消费；只给写入账号用';
COMMENT ON COLUMN detail_recompute_requests.from_date IS 'WIB 日期，含两端：重算 event_date ∈ [from_date, to_date] 的明细行';
COMMENT ON COLUMN detail_recompute_requests.to_date IS 'WIB 日期，含两端：重算 event_date ∈ [from_date, to_date] 的明细行';
COMMENT ON COLUMN detail_recompute_requests.status IS 'PENDING → RUNNING → DONE / FAILED / REJECTED；FAILED 不自动重试，人工改回 PENDING 再跑';
COMMENT ON COLUMN detail_recompute_requests.error_code IS 'FAILED：异常类名；REJECTED：RAW_RETENTION_EXCEEDED（原始层已清理）';
