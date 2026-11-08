package com.tailtopia.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0（v1.4.2 shop Story 6.1 AC1）：迁移 ⑤ 文本守卫（去注释，不连库）。 */
class ShopBannerScheduleMigrationTest {

    static final Path DIR = Path.of("src/main/resources/db/migration");
    private static final Path M5 = DIR.resolve("V20261105_1645__shop_banner_schedule_and_coupon_target.sql");
    private static final Path PREV = DIR.resolve("V20261030_0330__shop_banner_token_and_target.sql");

    static String ddl(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    void scheduleColumnsAndCheck() throws IOException {
        String d = ddl(M5);
        assertThat(d).contains("ADD COLUMN start_at TIMESTAMPTZ", "ADD COLUMN end_at TIMESTAMPTZ");
        assertThat(d).contains("ck_shop_banners_schedule CHECK (start_at IS NULL OR end_at IS NULL OR start_at < end_at)");
        assertThat(d.toUpperCase()).doesNotContain("SMALLINT").doesNotContain("NOT NULL");
        assertThat(d).as("不动 NONE ⇔ 值为空 的约束").doesNotContain("ck_shop_banners_target_value");
    }

    @Test
    void targetTypeRebuiltWithFullSetPlusCoupon() throws IOException {
        String d = ddl(M5);
        assertThat(d).contains("DROP CONSTRAINT ck_shop_banners_target_type");
        assertThat(d).contains(
                "CHECK (target_type IN ('NONE', 'PRODUCT', 'CATEGORY', 'EXTERNAL', 'COUPON'))");
        // 全集取自上一条建它的迁移：原 4 值一个不少
        String prev = ddl(PREV);
        for (String v : new String[] {"'NONE'", "'PRODUCT'", "'CATEGORY'", "'EXTERNAL'"}) {
            assertThat(prev).contains(v);
        }
    }
}
