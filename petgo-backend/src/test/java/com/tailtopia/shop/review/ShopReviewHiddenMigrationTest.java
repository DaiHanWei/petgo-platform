package com.tailtopia.shop.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0（v1.4.2 shop Story 6.1 AC2）：迁移 ⑥ 文本守卫（去注释，不连库）。 */
class ShopReviewHiddenMigrationTest {

    private static final Path M6 = Path.of("src/main/resources/db/migration",
            "V20261105_1650__shop_review_hidden_and_display_flag.sql");

    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(M6, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    void hiddenAtAndVisiblePartialIndex() throws IOException {
        String d = ddl();
        assertThat(d).contains("ALTER TABLE shop_reviews ADD COLUMN hidden_at TIMESTAMPTZ;");
        assertThat(d).contains("CREATE INDEX ix_shop_reviews_product_visible ON shop_reviews "
                + "(product_id, created_at DESC, id DESC) WHERE review_status = 'PUBLISHED' AND hidden_at IS NULL;");
        assertThat(d).as("旧索引保留").doesNotContain("DROP INDEX");
    }

    @Test
    void displayFlagDefaultsOff() throws IOException {
        String d = ddl();
        assertThat(d).contains(
                "ALTER TABLE shipping_settings ADD COLUMN review_display_enabled BOOLEAN NOT NULL DEFAULT false;");
        assertThat(d.toUpperCase()).doesNotContain("SMALLINT");
    }
}
