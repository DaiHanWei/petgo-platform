package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：混合支付模型扩展（Story 3.3，AD-1 / AD-3）。
 *
 * <p>🔴🔴 <b>本 Story 触碰三人共享表 {@code payment_intents}。</b>
 * 当前依据是**产品负责人的临时授权**（HEX-SIGNOFF §签字前的临时授权），Hex 尚未书面确认。
 * 若事后有异议，须照 {@code V97} 的写法取并集重建约束并回归两边的支付类型。
 *
 * <p>本类看的是 <b>DB 层的不变式</b>——把 {@code coin + cash = amount} 放在 DB 而不是只放在
 * 应用层，因为<b>拆分金额对不上就是账对不平，而账不平只在退款时才暴露，那时已经动过真钱了</b>。
 */
class MixedPaymentShapeIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "pay" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "pay" + n);
    }

    /** 直接 INSERT 一行 payment_intents，返回是否被 DB 拒绝。 */
    private boolean insertRejected(long userId, String channel, Long coin, Long cash,
            String ratio, long amount) {
        try {
            jdbc.update("""
                    INSERT INTO payment_intents
                        (public_token, user_id, purpose, channel, amount, currency, status,
                         coin_amount, cash_amount, coin_ratio)
                    VALUES (?, ?, 'VET_CONSULT', ?, ?, 'IDR', 'PENDING', ?, ?, CAST(? AS NUMERIC))
                    """, "pi" + SEQ.incrementAndGet(), userId, channel, amount, coin, cash, ratio);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    // ---------- 渠道 CHECK 放宽 ----------

    @Test
    @DisplayName("MIXED 已被 ck_payment_intents_channel 接受；未知渠道仍被拒")
    void mixedAcceptedUnknownStillRejected() {
        long uid = seedUser();
        assertThat(insertRejected(uid, "MIXED", 30_000L, 70_000L, "0.3", 100_000L)).isFalse();
        assertThat(insertRejected(uid, "GOPAY_DIRECT", null, null, null, 100_000L))
                .as("放宽不等于放开——只加 MIXED 一个值").isTrue();
    }

    @Test
    @DisplayName("🔴 既有两值仍被接受（放宽 CHECK 时最容易顺手丢掉的东西）")
    void existingChannelsStillAccepted() {
        long uid = seedUser();
        // 2026-07-30 事故正是「两边各自 DROP+ADD 同一个 CHECK，合并后一方取值整类失效」
        assertThat(insertRejected(uid, "QRIS", null, null, null, 100_000L)).isFalse();
        assertThat(insertRejected(uid, "PAWCOIN", null, null, null, 100_000L)).isFalse();
    }

    // ---------- 🔴 AD-1 核心不变式 ----------

    @Test
    @DisplayName("🔴 MIXED 时 coin + cash 必须等于 amount —— 对不上就是账对不平")
    void mixedAmountsMustSumToTotal() {
        long uid = seedUser();
        assertThat(insertRejected(uid, "MIXED", 30_000L, 70_000L, "0.3", 100_000L))
                .as("30000 + 70000 = 100000 应被接受").isFalse();
        assertThat(insertRejected(uid, "MIXED", 30_000L, 69_999L, "0.3", 100_000L))
                .as("差 1 分钱也必须被 DB 拒——账不平只在退款时才暴露，那时已动过真钱")
                .isTrue();
    }

    @Test
    @DisplayName("🔴 MIXED 时三列不得为空、不得为负")
    void mixedRequiresAllThreeColumnsNonNegative() {
        long uid = seedUser();
        assertThat(insertRejected(uid, "MIXED", null, 100_000L, "0", 100_000L)).isTrue();
        assertThat(insertRejected(uid, "MIXED", 100_000L, null, "1", 100_000L)).isTrue();
        assertThat(insertRejected(uid, "MIXED", 100_000L, 0L, null, 100_000L)).isTrue();
        assertThat(insertRejected(uid, "MIXED", -1L, 100_001L, "0", 100_000L)).isTrue();
    }

    @Test
    @DisplayName("🔴 非 MIXED 时三列必须全 NULL —— 防止旧流程被无意写入拆分字段")
    void nonMixedMustHaveAllThreeNull() {
        long uid = seedUser();
        assertThat(insertRejected(uid, "QRIS", 1L, 99_999L, "0.00001", 100_000L)).isTrue();
        assertThat(insertRejected(uid, "PAWCOIN", 100_000L, 0L, "1", 100_000L)).isTrue();
    }

    // ---------- 🔴 既有 purpose 不受影响 ----------

    @Test
    @DisplayName("🔴 迁移不回填不改写：既有行的三列全为 NULL")
    void migrationDidNotBackfillExistingRows() {
        Integer polluted = jdbc.queryForObject("""
                SELECT count(*) FROM payment_intents
                 WHERE channel <> 'MIXED'
                   AND (coin_amount IS NOT NULL OR cash_amount IS NOT NULL
                        OR coin_ratio IS NOT NULL)
                """, Integer.class);
        assertThat(polluted).as("非 MIXED 行不该有任何拆分字段").isZero();
    }

    // ---------- 🔒 AD-3 纵深防御 ----------

    @Test
    @DisplayName("🔒 只放宽 payment_intents 一处；三张虚拟商品表的 CHECK 刻意保持不放宽")
    void virtualProductTablesStayNarrow() {
        for (String table : new String[] {
            "consult_orders", "ai_consult_orders", "id_card_hd_purchases"}) {
            Integer widened = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_constraint c
                     JOIN pg_class t ON t.oid = c.conrelid
                     WHERE t.relname = ? AND pg_get_constraintdef(c.oid) LIKE '%MIXED%'
                    """, Integer.class, table);
            assertThat(widened)
                    .as("%s 的 CHECK 被顺手对齐了 —— 虚拟商品恒单渠道，窄 CHECK 是纵深防御（AD-3）",
                            table)
                    .isZero();
        }
    }

    @Test
    @DisplayName("🔴 PayChannel 枚举：MIXED 在末尾追加，既有两值顺序与拼写未变（契约 E-1）")
    void payChannelAppendOnly() {
        PayChannel[] values = PayChannel.values();
        assertThat(values[0]).isEqualTo(PayChannel.QRIS);
        assertThat(values[1]).isEqualTo(PayChannel.PAWCOIN);
        assertThat(values[values.length - 1]).isEqualTo(PayChannel.MIXED);
        // 枚举序数会进 DB 的场合，重排等于静默改写历史数据的含义
        assertThat(PayChannel.QRIS.ordinal()).isZero();
        assertThat(PayChannel.PAWCOIN.ordinal()).isEqualTo(1);
    }
}
