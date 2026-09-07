-- V1.1.6 Story 16.4：config_change_logs.config_type 放开 FEED_RANK。时间戳版本号（决策 E6）。
--
-- 🔴 V78 给这一列加了 CHECK 白名单（'PRICING','PAWCOIN','TOPUP_TIER'）。
-- 在 Java 枚举里加一个取值**不够** —— 写日志时会撞约束，表现是"保存推荐算法参数报 500"，
-- 而错误栈指向 config_change_logs 而不是推荐算法，很容易被误判成审计模块坏了。
-- 本 story 撞过一次（先加枚举再跑测试才发现）。
--
-- ⚠️ V78 已合入 main，属**冻结**迁移：不改它，另起一条替换约束（决策 E6）。

ALTER TABLE config_change_logs DROP CONSTRAINT IF EXISTS ck_config_change_type;
ALTER TABLE config_change_logs ADD CONSTRAINT ck_config_change_type
    CHECK (config_type IN ('PRICING', 'PAWCOIN', 'TOPUP_TIER', 'FEED_RANK'));
