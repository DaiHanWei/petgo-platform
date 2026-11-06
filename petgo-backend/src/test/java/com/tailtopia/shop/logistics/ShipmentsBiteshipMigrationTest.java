package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 5.1 AC1 迁移文本守卫（读文件去注释，不连库）。 */
class ShipmentsBiteshipMigrationTest {

    private static final Path FILE = Path.of(
            "src/main/resources/db/migration/V20261105_1640__shipments_biteship_and_logistics_exception.sql");

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

    @Test
    @DisplayName("ck_shipments_status 四值；carrier 放宽且去掉三值 CHECK；tracking_no / shipped_at 放开")
    void shipmentsStatusAndCarrier() throws IOException {
        String d = ddl();
        assertThat(d).contains("ADD CONSTRAINT ck_shipments_status CHECK (status IN ('CREATED', 'SHIPPED', "
                + "'DELIVERED', 'CANCELLED'))");
        assertThat(d).contains("DROP CONSTRAINT ck_shipments_carrier")
                .contains("ALTER COLUMN carrier TYPE VARCHAR(32)")
                .contains("ALTER COLUMN tracking_no DROP NOT NULL")
                .contains("ALTER COLUMN shipped_at DROP NOT NULL");
        assertThat(d).doesNotContain("ADD CONSTRAINT ck_shipments_carrier");
        assertThat(d).doesNotContain("DROP CONSTRAINT ck_shipments_delivered_at")
                .doesNotContain("DROP INDEX");
    }

    @Test
    @DisplayName("provider 默认 MANUAL；ck_shipments_provider_fields：手工必有单号、Biteship 必有订单 id")
    void providerFields() throws IOException {
        String d = ddl();
        assertThat(d).contains("ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'MANUAL'");
        assertThat(d).contains("ADD CONSTRAINT ck_shipments_provider_fields CHECK ( "
                + "(provider = 'MANUAL' AND tracking_no IS NOT NULL) OR "
                + "(provider = 'BITESHIP' AND biteship_order_id IS NOT NULL))");
        assertThat(d).contains("CREATE UNIQUE INDEX uq_shipments_biteship_order ON shipments (biteship_order_id) "
                + "WHERE biteship_order_id IS NOT NULL");
        assertThat(d).contains("ADD COLUMN insurance_amount BIGINT NOT NULL DEFAULT 0")
                .contains("ADD COLUMN cancel_requested_at TIMESTAMPTZ");
    }

    @Test
    @DisplayName("ck_shop_orders_delivery_source = V20260818_0357 原 4 值 + BITESHIP；物流异常按 C-6 拆 LOST / DAMAGED")
    void orderConstraints() throws IOException {
        String d = ddl();
        assertThat(d).contains("DROP CONSTRAINT ck_shop_orders_delivery_source");
        assertThat(d).contains("delivery_source IS NULL OR delivery_source IN ( 'SHIPMENTS_ALL_DELIVERED', "
                + "'ADMIN_MARK', 'USER_CONFIRM', 'AUTO_TIMEOUT', 'BITESHIP'))");
        String original = Files.readString(Path.of("src/main/resources/db/migration/V20260818_0357__init_shipments.sql"),
                StandardCharsets.UTF_8);
        for (String v : new String[] {"SHIPMENTS_ALL_DELIVERED", "ADMIN_MARK", "USER_CONFIRM", "AUTO_TIMEOUT"}) {
            assertThat(original).contains("'" + v + "'");
        }
        assertThat(d).contains("logistics_exception IN ('PICKUP_FAILED', 'RETURNED', 'STALLED', 'LOST', 'DAMAGED')");
        assertThat(d).doesNotContain("LOST_DAMAGED");
        assertThat(d).contains("ADD COLUMN waybill_attempts INTEGER NOT NULL DEFAULT 0");
    }

    @Test
    @DisplayName("biteship_webhook_events 建表与未处理部分索引")
    void webhookInbox() throws IOException {
        String d = ddl();
        assertThat(d).contains("CREATE TABLE biteship_webhook_events")
                .contains("payload JSONB NOT NULL")
                .contains("attempts INTEGER NOT NULL DEFAULT 0")
                .contains("CREATE INDEX ix_biteship_webhook_events_pending ON biteship_webhook_events (received_at) "
                        + "WHERE processed_at IS NULL");
    }

    @Test
    @DisplayName("文件中不得出现 SMALLINT")
    void noSmallint() throws IOException {
        assertThat(raw().toUpperCase()).doesNotContain("SMALLINT");
    }
}
