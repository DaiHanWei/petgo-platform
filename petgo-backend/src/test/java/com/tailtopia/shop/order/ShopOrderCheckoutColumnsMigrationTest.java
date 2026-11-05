package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.pricing.ShippingDiscountSource;
import com.tailtopia.shop.order.refund.CancelReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 3.2 AC1 迁移文本守卫（读文件去注释，不连库）。 */
class ShopOrderCheckoutColumnsMigrationTest {

    private static final Path FILE = Path.of(
            "src/main/resources/db/migration/V20261105_1625__shop_orders_checkout_columns.sql");

    /** 去掉 {@code --} 注释并压缩空白。 */
    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("新增列齐全、类型对得上实体（BIGINT / BOOLEAN / TIMESTAMPTZ / VARCHAR）")
    void columns() throws IOException {
        String sql = ddl();
        for (String col : new String[] {
                "checkout_source VARCHAR(16)",
                "discount_coupon_amount BIGINT NOT NULL DEFAULT 0",
                "discount_user_coupon_id BIGINT REFERENCES user_coupons (id) ON DELETE SET NULL",
                "shipping_discount_source VARCHAR(24)",
                "free_shipping_user_coupon_id BIGINT REFERENCES user_coupons (id) ON DELETE SET NULL",
                "insurance_fee BIGINT NOT NULL DEFAULT 0",
                "insurance_selected BOOLEAN NOT NULL DEFAULT false",
                "courier_code VARCHAR(32)", "courier_service_code VARCHAR(32)",
                "courier_name VARCHAR(64)", "courier_service_name VARCHAR(64)",
                "courier_eta_text VARCHAR(32)",
                "shipping_quote_id BIGINT REFERENCES shipping_quotes (id)",
                "coin_selected BOOLEAN", "cancel_reason VARCHAR(32)", "cancelled_at TIMESTAMPTZ",
                "reorder_of_order_id BIGINT REFERENCES shop_orders (id)",
                "late_paid_amount BIGINT", "late_paid_at TIMESTAMPTZ",
                "late_paid_intent_id BIGINT REFERENCES payment_intents (id)",
                "late_payment_resolution VARCHAR(16)"}) {
            assertThat(sql).as(col).contains("ADD COLUMN " + col);
        }
        assertThat(sql).contains(
                "ALTER TABLE shop_order_lines ADD COLUMN discount_eligible BOOLEAN NOT NULL DEFAULT false");
    }

    @Test
    @DisplayName("CHECK 值集与枚举同集合")
    void checkValueSets() throws IOException {
        String sql = ddl();
        assertThat(sql).contains("checkout_source IN (" + quoted(Arrays.stream(CheckoutSource.values())
                .map(Enum::name).toArray(String[]::new)) + ")");
        assertThat(sql).contains("shipping_discount_source IN (" + quoted(Arrays.stream(
                ShippingDiscountSource.values()).map(Enum::name).toArray(String[]::new)) + ")");
        assertThat(sql.replace(" ", "")).contains("cancel_reasonIN(" + quoted(Arrays.stream(
                CancelReason.values()).map(Enum::name).toArray(String[]::new)) + ")");
        assertThat(sql).contains("late_payment_resolution IN ('REORDERED','REFUNDED')");
        assertThat(sql).contains("ck_shop_orders_late_paid_shape");
        assertThat(sql).contains("discount_coupon_amount >= 0 AND insurance_fee >= 0");
    }

    @Test
    @DisplayName("🔴 应付公式约束带 NOT VALID（不扫存量），公式与 shipping_discount ≤ 0 口径一致")
    void totalFormulaNotValid() throws IOException {
        String sql = ddl();
        assertThat(sql).contains("ADD CONSTRAINT ck_shop_orders_total_formula CHECK (total_amount = "
                + "goods_subtotal - discount_coupon_amount + shipping_fee + shipping_discount + insurance_fee) NOT VALID");
        assertThat(sql).contains("CREATE UNIQUE INDEX uq_shop_orders_reorder_of");
        assertThat(sql).contains("CREATE INDEX ix_shop_orders_late_paid");
    }

    @Test
    @DisplayName("🔴 无 SMALLINT、无同义金额列（I5：金额沿用已有列）")
    void noSmallintNoSynonyms() throws IOException {
        String sql = ddl().toLowerCase(Locale.ROOT);
        assertThat(sql).doesNotContain("smallint");
        for (String bad : new String[] {"goods_amount", "payable", "shipping_fee_paid", "coin_paid",
                "cancelled_qty", "shipping_discount_amount", "total_payable", "cash_paid"}) {
            assertThat(sql).as(bad).doesNotContain(bad);
        }
        // 已有金额列不被重新加一遍
        for (String existing : new String[] {"goods_subtotal", "shipping_fee", "shipping_discount",
                "total_amount", "coin_amount", "cash_amount", "refunded_qty"}) {
            assertThat(sql).as(existing).doesNotContain("add column " + existing + " ");
        }
    }

    private static String quoted(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            sb.append(i == 0 ? "" : ",").append('\'').append(values[i]).append('\'');
        }
        return sb.toString();
    }
}
