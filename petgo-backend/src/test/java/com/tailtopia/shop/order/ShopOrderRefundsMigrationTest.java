package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.RefundScope;
import com.tailtopia.shop.order.refund.ShopRefundStatus;
import com.tailtopia.shop.returns.domain.CashDestination;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 4.1 AC1 迁移文本守卫（读文件去注释，不连库）。 */
class ShopOrderRefundsMigrationTest {

    private static final Path FILE = Path.of(
            "src/main/resources/db/migration/V20261105_1635__create_shop_order_refunds.sql");

    private static String raw() throws IOException {
        return Files.readString(FILE, StandardCharsets.UTF_8);
    }

    /** 去掉 {@code --} 注释并压缩空白。 */
    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : raw().split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    private static String quoted(List<String> values) {
        return values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(","));
    }

    @Test
    @DisplayName("列与类型：金额 BIGINT、件数 INTEGER、对外 public_token、加密列 VARCHAR(255)、@Version 列")
    void columns() throws IOException {
        String sql = ddl();
        assertThat(sql).contains("CREATE TABLE shop_order_refunds (");
        for (String col : new String[] {
                "public_token VARCHAR(32) NOT NULL",
                "order_id BIGINT NOT NULL REFERENCES shop_orders (id)",
                "scope VARCHAR(16) NOT NULL",
                "order_line_id BIGINT REFERENCES shop_order_lines (id)",
                "line_qty INTEGER,",
                "reason VARCHAR(32) NOT NULL",
                "goods_refund BIGINT NOT NULL DEFAULT 0",
                "shipping_refund BIGINT NOT NULL DEFAULT 0",
                "insurance_refund BIGINT NOT NULL DEFAULT 0",
                "total_refund BIGINT NOT NULL",
                "coin_refund BIGINT NOT NULL",
                "cash_refund BIGINT NOT NULL",
                "cash_destination VARCHAR(16)",
                "payout_channel VARCHAR(16)",
                "payout_account_enc VARCHAR(255)",
                "payout_account_holder_enc VARCHAR(255)",
                "payout_fee BIGINT NOT NULL DEFAULT 0",
                "compensation_amount BIGINT NOT NULL DEFAULT 0",
                "status VARCHAR(24) NOT NULL",
                "destination_chosen_at TIMESTAMPTZ", "paid_out_at TIMESTAMPTZ", "payout_failed_at TIMESTAMPTZ",
                "payout_fail_note VARCHAR(200)", "destination_reminded_at TIMESTAMPTZ",
                "created_by_admin_id BIGINT",
                "version BIGINT NOT NULL DEFAULT 0",
                "created_at TIMESTAMPTZ NOT NULL DEFAULT now()",
                "updated_at TIMESTAMPTZ NOT NULL DEFAULT now()",
                "CONSTRAINT uq_shop_order_refunds_token UNIQUE (public_token)"}) {
            assertThat(sql).as(col).contains(col);
        }
        assertThat(sql.toUpperCase(Locale.ROOT)).doesNotContain("SMALLINT");
    }

    @Test
    @DisplayName("CHECK 全集与枚举一致：scope / reason / destination / channel / status")
    void checkSetsMatchEnums() throws IOException {
        String sql = ddl();
        assertThat(sql).contains("CHECK (scope IN (" + quoted(Arrays.stream(RefundScope.values())
                .map(Enum::name).toList()) + "))");
        // reason 只含会生成取消退款单的七类（未付款两类不生成退款单）
        List<String> reasons = Arrays.stream(CancelReason.values()).map(Enum::name)
                .filter(n -> !n.equals("USER_UNPAID_CANCEL") && !n.equals("PAYMENT_TIMEOUT")).toList();
        assertThat(sql).contains("CHECK (reason IN ( " + quoted(reasons).replace(",'USER_FAULT_RETURNED'",
                ", 'USER_FAULT_RETURNED'") + "))");
        assertThat(sql).contains("CHECK (cash_destination IS NULL OR cash_destination IN ("
                + quoted(List.of(CashDestination.TO_PAWCOIN.name(), CashDestination.TO_BANK.name())) + "))");
        assertThat(sql).contains("CHECK (payout_channel IS NULL OR payout_channel IN ("
                + quoted(Arrays.stream(PayoutChannel.values()).map(Enum::name).toList()) + "))");
        assertThat(sql).contains("CHECK (status IN ( " + quoted(Arrays.stream(ShopRefundStatus.values())
                .map(Enum::name).toList()) + "))");
        assertThat(sql).doesNotContain("COIN_REFUNDED");
    }

    @Test
    @DisplayName("形状与金额约束：LINE 必带行与件数、非 LINE 必空；两组加总 = total；全部非负")
    void shapeAndAmountChecks() throws IOException {
        String sql = ddl();
        assertThat(sql).contains("(scope = 'LINE' AND order_line_id IS NOT NULL AND line_qty > 0) "
                + "OR (scope <> 'LINE' AND order_line_id IS NULL AND line_qty IS NULL)");
        assertThat(sql).contains("coin_refund + cash_refund = total_refund")
                .contains("goods_refund + shipping_refund + insurance_refund = total_refund")
                .contains("goods_refund >= 0 AND shipping_refund >= 0 AND insurance_refund >= 0")
                .contains("coin_refund >= 0 AND cash_refund >= 0 AND payout_fee >= 0 AND compensation_amount >= 0");
    }

    @Test
    @DisplayName("索引：按订单 + 未完成部分索引")
    void indexes() throws IOException {
        String sql = ddl();
        assertThat(sql).contains("CREATE INDEX ix_shop_order_refunds_order ON shop_order_refunds (order_id, created_at)");
        assertThat(sql).contains("CREATE INDEX ix_shop_order_refunds_open ON shop_order_refunds (status, created_at) "
                + "WHERE status IN ('AWAITING_DESTINATION','PAYOUT_PENDING','PAYOUT_FAILED')");
    }

    @Test
    @DisplayName("头注释四条：不复用退货单、复用累计列、S-5 遗留、加密列")
    void headerNotes() throws IOException {
        String text = raw();
        assertThat(text).contains("不复用 return_requests").contains("refunded_qty")
                .contains("refunded_total / refunded_coin").contains("S-5").contains("EncryptedStringConverter");
    }
}
