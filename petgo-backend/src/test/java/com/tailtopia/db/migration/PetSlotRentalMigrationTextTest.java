package com.tailtopia.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0：V1.4.0 multi-pet Story 2.2 · AC1 / AC2 迁移文本守卫（读文件，不连库；照 PetSlotMigrationTextTest）。 */
class PetSlotRentalMigrationTextTest {

    private static final Path TABLES =
            Path.of("src/main/resources/db/migration/V20261018_1601__init_pet_slot_rentals_purchases.sql");
    private static final Path PRICE =
            Path.of("src/main/resources/db/migration/V20261018_1602__pricing_pet_slot_monthly_price.sql");

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
    void purchasesTableShape() throws IOException {
        String d = ddl(TABLES);
        assertThat(d).contains("CREATE TABLE pet_slot_purchases")
                .contains("public_token VARCHAR(32) NOT NULL")
                .contains("idem_key VARCHAR(160) NOT NULL")
                .contains("rental_ids BIGINT[] NULL")
                .contains("days INT NOT NULL DEFAULT 30")
                .contains("UNIQUE (public_token)")
                .contains("UNIQUE (payment_intent_id)")
                .contains("UNIQUE (idem_key)")
                .contains("CHECK (kind IN ('RENT', 'RENEW', 'ADMIN_GRANT'))")
                .contains("CHECK (quantity BETWEEN 1 AND 5)")
                .contains("CHECK (unit_price >= 0)")
                .contains("CHECK (total >= 0)")
                .contains("CHECK (channel IN ('QRIS', 'PAWCOIN'))")
                .contains("CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED', 'ORPHAN_PAID', 'OVERPAID'))")
                .contains("CHECK (days >= 1)")
                .contains("ON pet_slot_purchases (user_id, created_at DESC, id DESC)");
        assertThat(d).as("不加每人一笔 PENDING 的部分唯一索引").doesNotContain("WHERE status = 'PENDING'");
    }

    @Test
    void rentalsTableShape() throws IOException {
        String d = ddl(TABLES);
        assertThat(d).contains("CREATE TABLE pet_slot_rentals")
                .contains("status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'")
                .contains("purchase_id BIGINT NOT NULL REFERENCES pet_slot_purchases (id)")
                .contains("CHECK (source IN ('RENT', 'ADMIN_GRANT'))")
                .contains("CHECK (status IN ('ACTIVE', 'EXPIRED'))")
                .contains("CHECK (expires_at > starts_at)")
                .contains("CREATE INDEX ix_pet_slot_rentals_status_expires ON pet_slot_rentals (status, expires_at)")
                .contains("CREATE INDEX ix_pet_slot_rentals_user_status ON pet_slot_rentals (user_id, status)")
                .contains("CREATE INDEX ix_pet_slot_rentals_purchase ON pet_slot_rentals (purchase_id)");
    }

    @Test
    void everyColumnCommented() throws IOException {
        String raw = Files.readString(TABLES, StandardCharsets.UTF_8);
        for (String col : new String[] {"id", "public_token", "user_id", "kind", "quantity", "unit_price", "total",
                "channel", "payment_intent_id", "idem_key", "status", "rental_ids", "days", "operator_admin_id", "reason",
                "created_at", "paid_at"}) {
            assertThat(raw).as(col).contains("COMMENT ON COLUMN pet_slot_purchases." + col + " IS");
        }
        for (String col : new String[] {"id", "public_token", "user_id", "source", "starts_at", "expires_at", "status",
                "reminded_at", "expired_at", "purchase_id", "created_at"}) {
            assertThat(raw).as(col).contains("COMMENT ON COLUMN pet_slot_rentals." + col + " IS");
        }
    }

    @Test
    void priceMigrationVerbatim() throws IOException {
        String d = ddl(PRICE);
        assertThat(d).contains("ALTER TABLE pricing_config ADD COLUMN pet_slot_monthly_price BIGINT;")
                .contains("UPDATE pricing_config SET pet_slot_monthly_price = 5000;")
                .contains("ALTER TABLE pricing_config ALTER COLUMN pet_slot_monthly_price SET NOT NULL;")
                .contains("ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_pet_slot_monthly_min "
                        + "CHECK (pet_slot_monthly_price >= 100);")
                .contains("COMMENT ON COLUMN pricing_config.pet_slot_monthly_price IS");
    }

    @Test
    void sharedConstraintsUntouched() throws IOException {
        for (Path f : new Path[] {TABLES, PRICE}) {
            String raw = Files.readString(f, StandardCharsets.UTF_8);
            String d = ddl(f);
            assertThat(d).as(f.toString()).doesNotContain("ck_payment_intents_purpose")
                    .doesNotContain("ck_keepsake_purchases_sku").doesNotContain("ck_notifications_type");
            assertThat(raw).doesNotContain("DROP CONSTRAINT");
        }
        assertThat(ddl(TABLES)).doesNotContain("UPDATE ").doesNotContain("INSERT ").doesNotContain("DELETE ");
    }
}
