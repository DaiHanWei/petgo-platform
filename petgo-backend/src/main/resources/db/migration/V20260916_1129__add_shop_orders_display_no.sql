-- V1.3.0 shop-v2 Story 4-3（SHOP-FR-29 / SHOP-FR-30 · AD-S3）：电商订单展示号落库 + 存量回填。
-- 时间戳版本号（决策 E7）。
--
-- 解决三个互相纠缠的问题：
--
-- ① **号是算出来的，而且可枚举**。旧算法 `OrderDisplayNo.of` 的序号段就是 shop_orders.id
--    零填充 6 位（`TOKO-20260819-000673`）—— 任何用户拿自己的订单号即可推断平台当日单量
--    与累计单量，还能顺着序号试探别人的单。这正是「对外暴露标识一律不可枚举」要挡的事。
--
-- ② **一单两号**。订单中心列表/详情显示 `TOKO-…`，而电商订单详情页显示的是 22 位
--    public_token —— 同一张单，用户在两个页面看到两个完全不同的字符串。
--
-- ③ **后台搜不到用户报出来的号**。后台订单搜索只对 public_token 精确匹配，
--    运营拿到用户报的 `TOKO-20260819-000673` 今天根本搜不出这一单。
--
-- 🔴 **为什么保留 TOKO 前缀**：财务要能一眼区分自营实物与虚拟商品收入。
--    ⚠️ 核实过：对账 SQL（ShopFinanceDashboardService）是**按表圈定**、不解析任何前缀，
--    所以前缀服务的是**人眼识别**与既有测试断言，不是一条会崩的代码路径。
--    写在这里是为了免得有人以为「反正没代码依赖」就顺手改了。
--
-- 🔴 **为什么留 legacy_display_no**：旧号早已发到用户手里、印在他们和客服的聊天记录里。
--    不留，后台就搜不到它，等于让客服对着一个「系统里不存在的订单号」跟用户解释。
--    它**不对外展示**，只作搜索命中用；新订单该列为 NULL。
--
-- 🔴 **回填用的 random() 不是密码学随机**，这是刻意取舍：存量订单的旧号本就是可枚举的、
--    早已发出去了，回填新号的目的是「让它有一个稳定可搜的新号」，**不是追溯保密**。
--    新订单一律走 Java 侧 SecureRandom（ShopOrderDisplayNoGenerator）。

-- ① 建列（先可空，回填后再收紧）
ALTER TABLE shop_orders
    ADD COLUMN display_no        VARCHAR(32),
    ADD COLUMN legacy_display_no VARCHAR(32);

-- ② 回填旧号：与 OrderDisplayNo.of(ECOMMERCE, id, created_at) 逐字符等价。
--    日期段同样取 WIB —— 与 Java 侧 ZoneId.of("Asia/Jakarta") 必须一致，
--    否则跨午夜的那几单回填出来的旧号跟用户手里那张对不上，而那恰恰是本列存在的理由。
UPDATE shop_orders
   SET legacy_display_no = 'TOKO-'
        || to_char(created_at AT TIME ZONE 'Asia/Jakarta', 'YYYYMMDD')
        || '-' || lpad(id::text, 6, '0');

-- ③ 回填新号（随机 + 查重重试）。
--    ⚠️ 本支是本仓第一个 DO $$ 块。存量电商订单量极小，逐行循环成本可忽略。
--    字母表是 Crockford Base32：0123456789ABCDEFGHJKMNPQRSTVWXYZ —— 32 个字符，
--    **刻意不含 I / L / O / U**（I 与 1、O 与 0 念出来分不清；U 避免拼出脏词）。
--    用户要把这个号逐位念给客服，认错一位就是查错单。
DO $$
DECLARE
    r         RECORD;
    candidate TEXT;
    tries     INT;
BEGIN
    FOR r IN SELECT id, created_at FROM shop_orders WHERE display_no IS NULL ORDER BY id LOOP
        tries := 0;
        LOOP
            candidate := 'TOKO-'
                || to_char(r.created_at AT TIME ZONE 'Asia/Jakarta', 'YYYYMMDD') || '-'
                || (SELECT string_agg(
                        substr('0123456789ABCDEFGHJKMNPQRSTVWXYZ',
                               1 + floor(random() * 32)::int, 1), '')
                    FROM generate_series(1, 6));
            EXIT WHEN NOT EXISTS (SELECT 1 FROM shop_orders WHERE display_no = candidate);
            tries := tries + 1;
            IF tries > 5 THEN
                RAISE EXCEPTION '回填 display_no 连续 5 次冲突，order id=%', r.id;
            END IF;
        END LOOP;
        UPDATE shop_orders SET display_no = candidate WHERE id = r.id;
    END LOOP;
END $$;

-- ④ 收紧为 NOT NULL（回填完才能加 —— 反过来会让这支迁移在有存量数据的库上直接失败）
ALTER TABLE shop_orders
    ALTER COLUMN display_no SET NOT NULL;

-- ⑤ 唯一约束（兜底并发穿越）+ 旧号索引（后台按旧号搜要走它）
ALTER TABLE shop_orders
    ADD CONSTRAINT uq_shop_orders_display_no UNIQUE (display_no);

CREATE INDEX ix_shop_orders_legacy_display_no
    ON shop_orders (legacy_display_no);

COMMENT ON COLUMN shop_orders.display_no IS
    '对外展示号 TOKO-yyyyMMdd-XXXXXX（Story 4-3）。随机段为 Crockford Base32 六位、由 SecureRandom 产生 —— 它取代的旧算法把自增主键零填充直接外露，用户据此可推断平台单量并试探他人订单。日期段取下单时刻的 WIB 日期，与 created_at 同一个 Instant。一单只有这一个号，App 四个出口全部展示它。';
COMMENT ON COLUMN shop_orders.legacy_display_no IS
    '旧算法算出来的号（仅存量订单有值，新订单为 NULL）。不对外展示，只为让用户手里那张早已发出去的旧号在后台还能搜到 —— 否则客服只能对着一个「系统里不存在的订单号」跟用户解释。';
