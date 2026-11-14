package com.tailtopia.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 gift-pack Story 1-3 · AC1 迁移文本守卫。 */
class GiftOrderMessageMigrationTextTest {

    private static final Path FILE =
            Path.of("src/main/resources/db/migration/V20261115_0205__gift_orders_message_and_opened.sql");

    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    void columnsAndCheck() throws IOException {
        String d = ddl();
        assertThat(d).contains("ALTER TABLE gift_orders ADD COLUMN message VARCHAR(500) NULL;")
                .contains("ALTER TABLE gift_orders ADD COLUMN opened_at TIMESTAMPTZ NULL;")
                .contains("ALTER TABLE gift_orders ADD COLUMN message_hidden_at TIMESTAMPTZ NULL;")
                .contains("ADD CONSTRAINT ck_gift_orders_message_len CHECK (message IS NULL OR char_length(message) BETWEEN 1 AND 500);");
    }

    @Test
    void backfillsAllExistingOrdersAsOpened() throws IOException {
        assertThat(ddl()).contains(
                "UPDATE gift_orders SET opened_at = COALESCE(delivered_at, created_at) WHERE opened_at IS NULL;");
    }

    @Test
    void partialIndexes() throws IOException {
        String d = ddl();
        assertThat(d).contains("CREATE INDEX ix_gift_orders_pet_delivered ON gift_orders (pet_id, delivered_at DESC, id DESC) WHERE status = 'DELIVERED';")
                .contains("CREATE INDEX ix_gift_orders_pet_unopened ON gift_orders (pet_id) WHERE status = 'DELIVERED' AND opened_at IS NULL;");
    }

    @Test
    void noSharedChecksNoSmallint() throws IOException {
        String raw = Files.readString(FILE, StandardCharsets.UTF_8);
        assertThat(raw.toUpperCase()).doesNotContain("SMALLINT");
        assertThat(raw).doesNotContain("ck_gift_orders_channel").doesNotContain("ck_gift_orders_total")
                .doesNotContain("ck_notifications_type");
    }
}
