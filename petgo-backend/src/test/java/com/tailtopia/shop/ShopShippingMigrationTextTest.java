package com.tailtopia.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 1.1 · 迁移 ① ② ⑧ 文本守卫（读文件去注释，不连库）。 */
class ShopShippingMigrationTextTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");
    private static final Path M1 = DIR.resolve("V20261105_1600__add_shop_skus_package_dimensions.sql");
    private static final Path M2 = DIR.resolve(
            "V20261105_1605__shipping_settings_origin_cap_insurance_and_zone_postal_code.sql");
    private static final Path M8 = DIR.resolve("V20261105_1610__create_shipping_quotes.sql");

    /** 去掉 {@code --} 注释并压缩空白。 */
    private static String ddl(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("三个迁移文件存在，且都不含 SMALLINT")
    void filesExistWithoutSmallint() throws IOException {
        for (Path p : new Path[] {M1, M2, M8}) {
            assertThat(p).exists();
            assertThat(ddl(p).toUpperCase()).doesNotContain("SMALLINT");
        }
    }

    @Test
    @DisplayName("① 四列 INTEGER 可空 + 四个 > 0 CHECK")
    void m1() throws IOException {
        String d = ddl(M1);
        for (String col : new String[] {"package_weight_g", "package_length_cm", "package_width_cm",
                "package_height_cm"}) {
            assertThat(d).contains("ADD COLUMN " + col + " INTEGER;");
            assertThat(d).containsPattern("CHECK \\(" + col + " IS NULL OR " + col + " > 0\\)");
        }
        assertThat(d).contains("ck_shop_skus_package_weight", "ck_shop_skus_package_length",
                "ck_shop_skus_package_width", "ck_shop_skus_package_height");
        assertThat(d).doesNotContain("NOT NULL").doesNotContain("UPDATE ");
    }

    @Test
    @DisplayName("② 六列、insurance_rate_bp INTEGER NOT NULL DEFAULT 50、两个邮编正则 CHECK")
    void m2() throws IOException {
        String d = ddl(M2);
        assertThat(d).contains("ADD COLUMN origin_postal_code VARCHAR(5);",
                "ADD COLUMN origin_contact_name VARCHAR(60);",
                "ADD COLUMN origin_contact_phone VARCHAR(20);",
                "ADD COLUMN origin_address VARCHAR(300);",
                "ADD COLUMN free_shipping_cap BIGINT;",
                "ADD COLUMN insurance_rate_bp INTEGER NOT NULL DEFAULT 50;",
                "ALTER TABLE shipping_zones ADD COLUMN postal_code VARCHAR(5);");
        assertThat(d).contains("CHECK (origin_postal_code IS NULL OR origin_postal_code ~ '^[1-9][0-9]{4}$')");
        assertThat(d).contains("CHECK (postal_code IS NULL OR postal_code ~ '^[1-9][0-9]{4}$')");
        assertThat(d).contains("CHECK (insurance_rate_bp BETWEEN 0 AND 10000)");
        assertThat(d).contains("CHECK (free_shipping_cap IS NULL OR free_shipping_cap >= 0)");
        assertThat(d).doesNotContain("DROP COLUMN").doesNotContain("UPDATE ");
    }

    @Test
    @DisplayName("⑧ shipping_quotes 表结构、ck_shipping_quotes_lowest、复用索引列序")
    void m8() throws IOException {
        String d = ddl(M8);
        assertThat(d).contains("CREATE TABLE shipping_quotes (");
        assertThat(d).contains("public_token VARCHAR(32) NOT NULL",
                "user_id BIGINT REFERENCES users (id) ON DELETE SET NULL",
                "provider_mode VARCHAR(8) NOT NULL",
                "origin_postal_code VARCHAR(5) NOT NULL",
                "dest_postal_code VARCHAR(5) NOT NULL",
                "items_hash VARCHAR(64) NOT NULL",
                "serviceable BOOLEAN NOT NULL",
                "response JSONB NOT NULL",
                "lowest_price BIGINT,",
                "expires_at TIMESTAMPTZ NOT NULL",
                "CONSTRAINT uq_shipping_quotes_public_token UNIQUE (public_token)",
                "CHECK (provider_mode IN ('STUB', 'SANDBOX', 'LIVE'))",
                "CHECK (expires_at > created_at)");
        assertThat(d).contains("CONSTRAINT ck_shipping_quotes_lowest CHECK ( "
                + "(serviceable AND lowest_price IS NOT NULL AND lowest_price >= 0) "
                + "OR (NOT serviceable AND lowest_price IS NULL))");
        assertThat(d).contains("CREATE INDEX idx_shipping_quotes_reuse ON shipping_quotes "
                + "(dest_postal_code, items_hash, origin_postal_code, provider_mode, expires_at DESC);");
        assertThat(d).contains("CREATE INDEX idx_shipping_quotes_created ON shipping_quotes (created_at);");
        assertThat(d).doesNotContain("shop_orders");
    }
}
