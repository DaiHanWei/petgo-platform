-- V1.3.0 shop-v2 Story 3-2（AD-S7 / SHOP-FR-25）：工单的关联订单加"归哪张表"。时间戳版本号（决策 E7）。
--
-- 🔴 **为什么非加这一列不可**：`related_order_id` 今天被无条件解释成 `consult_orders.id`。
--    而 `consult_orders.id`（V66:28）与 `shop_orders.id`（V20260817_2308:16）**都是从 1 开始的
--    自增 bigint，数值空间完全重叠**。往这一列灌一个电商订单 id 42，后台会展示一条
--    **不相干的问诊单**，`ensureRefundRequest` 还会拿它去建退款请求 ——
--    **一次点击就能给无关订单开退款**。这不是理论风险，是 `findById` 一行代码的必然结果。
--
-- 🔴 **刻意不加任何跨表外键**，三条理由缺一不可：
--    ① 一列指两表，FK 在关系模型里表达不了 —— `related_order_id` 的含义依赖本列，
--      Postgres 没有「条件外键」。硬加只能二选一，另一类订单立刻插不进去。
--    ② 两类订单 id 数值空间重叠 ⇒ 就算只给 consult_orders 加 FK，一个电商单 id 也
--      **很可能刚好通过校验**（那个号在问诊表里确实存在）。FK 在这里给的是**假安全**：
--      它挡不住串单，只会让人以为串不了。
--    ③ feedback_tickets 已被 refund_requests.related_ticket_id 反向引用（V71:15）；
--      再往前挂两条出边，注销级联与测试 deleteAll 的顺序约束会变复杂。
--    正解是「类型列 + 服务层解析」：安全性由「解析时带归属校验 + 展示与退款按类型分流」
--    保证，不由数据库保证。代价是要靠测试守住 —— 类型守卫有专门的变异验证用例。
--
-- ⚠️ V70 已在生产应用 = **冻结**，不改它。

ALTER TABLE feedback_tickets
    ADD COLUMN related_order_type VARCHAR(16) NOT NULL DEFAULT 'CONSULT';

ALTER TABLE feedback_tickets
    ADD CONSTRAINT ck_feedback_tickets_related_order_type
    CHECK (related_order_type IN ('CONSULT', 'SHOP'));

COMMENT ON COLUMN feedback_tickets.related_order_type IS
    '关联订单所属表：CONSULT=consult_orders / SHOP=shop_orders。related_order_id 为 NULL 时本列无意义（存量绝大多数行如此，由 DEFAULT 回填成 CONSULT 是有意的：列语义是"id 归哪张表"，id 为空时这个问题没有答案，取什么值都行）。无跨表 FK —— 一列指两表且两表 id 均为自增 bigint、数值空间重叠，FK 给的是假安全。';
