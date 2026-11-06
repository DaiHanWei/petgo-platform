package com.tailtopia.shop.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 2.1 AC1 —— 迁移 ⑦ 文本守卫（读文件去注释，不连库）。 */
class CouponMigrationTextTest {

    private static final Path FILE = Path.of(
            "src/main/resources/db/migration/V20261105_1615__create_shop_coupons_user_coupons_codes_notify_log.sql");

    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    private static String table(String d, String name) {
        Matcher m = Pattern.compile("CREATE TABLE " + name + " \\((.*?)\\);").matcher(d);
        assertThat(m.find()).as("表 " + name).isTrue();
        return m.group(1);
    }

    @Test
    void fourTablesInDependencyOrder() throws IOException {
        String d = ddl();
        int coupons = d.indexOf("CREATE TABLE shop_coupons ");
        int codes = d.indexOf("CREATE TABLE shop_coupon_codes ");
        int userCoupons = d.indexOf("CREATE TABLE user_coupons ");
        int log = d.indexOf("CREATE TABLE coupon_expiry_notify_log ");
        assertThat(coupons).isGreaterThanOrEqualTo(0);
        assertThat(codes).isGreaterThan(coupons);
        assertThat(userCoupons).isGreaterThan(codes);
        assertThat(log).isGreaterThan(userCoupons);
    }

    @Test
    void userCouponsColumnsAndFks() throws IOException {
        String uc = table(ddl(), "user_coupons");
        assertThat(uc).contains("expiry_notified_at TIMESTAMPTZ");
        assertThat(uc).contains("locked_order_id BIGINT REFERENCES shop_orders (id) ON DELETE SET NULL");
        assertThat(uc).contains("used_order_id BIGINT REFERENCES shop_orders (id) ON DELETE SET NULL");
        assertThat(uc).contains("user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE");
        assertThat(uc).contains("CONSTRAINT uq_user_coupons_public_token UNIQUE (public_token)");
        assertThat(uc).contains("CHECK (status IN ('AVAILABLE', 'LOCKED', 'USED', 'VOIDED'))");
        assertThat(uc).contains("CHECK (source IN ('NEWBIE', 'CLAIM', 'CODE', 'BANNER', 'REISSUE'))");
        // 不加「LOCKED ⇔ locked_order_id 非空」CHECK（会挡住删订单）
        assertThat(uc).doesNotContainPattern("CHECK \\([^)]*locked_order_id");
    }

    @Test
    void notifyLogPrimaryKey() throws IOException {
        String d = ddl();
        assertThat(table(d, "coupon_expiry_notify_log"))
                .contains("CONSTRAINT pk_coupon_expiry_notify_log PRIMARY KEY (user_id, wib_date)");
        assertThat(d).contains("CREATE INDEX idx_coupon_expiry_notify_log_date ON coupon_expiry_notify_log (wib_date)");
    }

    @Test
    void keyChecks() throws IOException {
        String d = ddl();
        assertThat(d).contains("CONSTRAINT ck_shop_coupons_discount_below_min CHECK "
                + "(NOT (kind = 'DISCOUNT' AND min_spend > 0 AND amount >= min_spend))");
        assertThat(d).contains("CONSTRAINT ck_shop_coupons_validity CHECK");
        assertThat(d).contains("CONSTRAINT ck_shop_coupons_quantity CHECK");
        assertThat(d).contains("CONSTRAINT ck_shop_coupon_codes_format CHECK (code ~ '^[A-Z0-9]{4,32}$')");
        assertThat(d).contains("CONSTRAINT uq_shop_coupon_codes_code UNIQUE (code)");
        assertThat(table(d, "shop_coupons")).contains("void_on_stop BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("stopped_at TIMESTAMPTZ").contains("CONSTRAINT uq_shop_coupons_public_token UNIQUE (public_token)");
        assertThat(d).contains("WHERE status = 'AVAILABLE' AND expiry_notified_at IS NULL");
    }

    @Test
    void noSmallint() throws IOException {
        // 头注释里写着「禁止 SMALLINT」，故只查去注释后的 DDL
        assertThat(ddl().toUpperCase()).doesNotContain("SMALLINT");
    }
}
