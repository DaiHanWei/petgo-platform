package com.tailtopia.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 gift-pack Story 1-1 · AC1 迁移文本守卫（读文件，不连库）。 */
class GiftCategoryMigrationTextTest {

    private static final Path FILE =
            Path.of("src/main/resources/db/migration/V20261115_0200__create_gift_categories.sql");

    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    void fiveCategoriesVerbatim() throws IOException {
        assertThat(ddl()).contains("INSERT INTO gift_categories (name_en, name_id, sort_order) VALUES "
                + "('Snacks', 'Camilan', 10), ('Treats', 'Makan enak', 20), ('Toys', 'Mainan', 30), "
                + "('Style', 'Gaya', 40), ('Big gifts', 'Hadiah besar', 50);");
    }

    @Test
    void priceBucketBoundaries() throws IOException {
        String d = ddl();
        assertThat(d).contains("WHEN g.price <= 1000 THEN 'Snacks'").contains("WHEN g.price <= 5000 THEN 'Treats'")
                .contains("WHEN g.price <= 10000 THEN 'Toys'").contains("WHEN g.price <= 25000 THEN 'Style'")
                .contains("ELSE 'Big gifts' END");
        assertThat(d).contains("WHERE g.kind = 'NORMAL'");
    }

    @Test
    void assertionBlockAndConstraints() throws IOException {
        String d = ddl();
        assertThat(d).contains("RAISE EXCEPTION")
                .contains("SELECT 1 FROM gift_catalog WHERE kind = 'NORMAL' AND category_id IS NULL");
        assertThat(d).contains("ADD CONSTRAINT ck_gift_catalog_exclusive_no_category CHECK (kind = 'NORMAL' OR category_id IS NULL)");
        assertThat(d).contains("CREATE UNIQUE INDEX uq_gift_categories_name_en ON gift_categories (lower(name_en))")
                .contains("CREATE UNIQUE INDEX uq_gift_categories_name_id ON gift_categories (lower(name_id))");
        assertThat(d).contains("ALTER TABLE gift_catalog ADD COLUMN category_id BIGINT NULL REFERENCES gift_categories (id)");
    }

    @Test
    void noSmallintAndNoSharedChecks() throws IOException {
        String raw = Files.readString(FILE, StandardCharsets.UTF_8);
        assertThat(raw.toUpperCase()).doesNotContain("SMALLINT");
        assertThat(raw).doesNotContain("ck_notifications_type").doesNotContain("ck_payment_intents_purpose");
        assertThat(raw).contains("Q-1");
    }
}
