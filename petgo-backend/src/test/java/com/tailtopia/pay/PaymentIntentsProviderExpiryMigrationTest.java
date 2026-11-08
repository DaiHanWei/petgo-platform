package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 3.3 AC1 迁移文本守卫（读文件去注释，不连库）。 */
class PaymentIntentsProviderExpiryMigrationTest {

    private static final Path FILE = Path.of(
            "src/main/resources/db/migration/V20261105_1630__payment_intents_provider_expiry_and_late_paid.sql");

    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("三列：provider_expires_at / paid_after_close / shop_order_id")
    void columns() throws IOException {
        String sql = ddl();
        assertThat(sql).contains("ADD COLUMN provider_expires_at TIMESTAMPTZ")
                .contains("ADD COLUMN paid_after_close BOOLEAN NOT NULL DEFAULT false")
                .contains("ADD COLUMN shop_order_id BIGINT REFERENCES shop_orders (id) ON DELETE SET NULL");
    }

    @Test
    @DisplayName("两条 CHECK：paid_after_close / shop_order_id 只允许 SHOP_ORDER")
    void checks() throws IOException {
        String sql = ddl();
        assertThat(sql).contains("ADD CONSTRAINT ck_payment_intents_paid_after_close_shop "
                        + "CHECK (NOT paid_after_close OR purpose = 'SHOP_ORDER')")
                .contains("ADD CONSTRAINT ck_payment_intents_shop_order_purpose "
                        + "CHECK (shop_order_id IS NULL OR purpose = 'SHOP_ORDER')");
    }

    @Test
    @DisplayName("回填当前意图 + 两个部分索引")
    void backfillAndIndexes() throws IOException {
        String sql = ddl();
        assertThat(sql).contains("UPDATE payment_intents pi SET shop_order_id = o.id FROM shop_orders o "
                + "WHERE pi.purpose = 'SHOP_ORDER' AND o.payment_intent_token = pi.public_token");
        assertThat(sql).contains("CREATE INDEX ix_payment_intents_shop_order ON payment_intents (shop_order_id) "
                + "WHERE shop_order_id IS NOT NULL");
        assertThat(sql).contains("CREATE INDEX ix_payment_intents_shop_reconcile ON payment_intents (updated_at) "
                + "WHERE purpose = 'SHOP_ORDER' AND gateway_ref IS NOT NULL");
    }

    @Test
    @DisplayName("不新增意图状态（不碰 status CHECK），无 SMALLINT")
    void noNewStatusNoSmallint() throws IOException {
        String sql = ddl().toUpperCase(Locale.ROOT);
        assertThat(sql).doesNotContain("SMALLINT").doesNotContain("LATE_PAID'").doesNotContain("CK_PAYMENT_INTENTS_STATUS");
    }
}
