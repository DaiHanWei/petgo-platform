-- Story 8（内容审核后台增强）：manual_review_queue 加优先级列（§5.1）。
-- 枚举落库 varchar（P0/P1/P2）；长度 >1 无 Hibernate CHAR(1) 陷阱。
-- 默认 'P1'（未显式标注的历史/降级入队项归为高优先，避免默认沉底）。
ALTER TABLE manual_review_queue
    ADD COLUMN priority VARCHAR(8) NOT NULL DEFAULT 'P1';

ALTER TABLE manual_review_queue
    ADD CONSTRAINT ck_manual_review_queue_priority
        CHECK (priority IN ('P0', 'P1', 'P2'));

-- 队列页排序：PENDING 内按 priority 升序 + submitted_at 升序。
-- 'P0' < 'P1' < 'P2' 字典序与优先级序天然一致 → 复合索引直接支撑 ORDER BY。
CREATE INDEX idx_manual_review_queue_pending_order
    ON manual_review_queue (status, priority, submitted_at);
