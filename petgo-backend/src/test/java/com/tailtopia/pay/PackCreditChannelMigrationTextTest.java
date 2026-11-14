package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PayChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 gift-pack Story 4-3 AC1 —— {@code V20261115_0235} 文本守卫（读文件去注释，不连库）。 */
class PackCreditChannelMigrationTextTest {

    private static final Path FILE = Path.of("src/main/resources/db/migration/V20261115_0235__pack_credit_channel.sql");

    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    private static Set<String> values(String d, String constraint) {
        Matcher m = Pattern.compile("ADD CONSTRAINT " + constraint + " CHECK \\(\\w+ IN \\(([^)]*)\\)\\)").matcher(d);
        assertThat(m.find()).as(constraint).isTrue();
        return Arrays.stream(m.group(1).split(",")).map(s -> s.trim().replace("'", "")).collect(Collectors.toSet());
    }

    private static void dropBeforeAdd(String d, String table, String constraint) {
        int drop = d.indexOf("ALTER TABLE " + table + " DROP CONSTRAINT " + constraint + ";");
        int add = d.indexOf("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " ");
        assertThat(drop).as(constraint).isGreaterThanOrEqualTo(0);
        assertThat(add).as(constraint).isGreaterThan(drop);
    }

    @Test
    void giftOrderChannelAndTotalRebuilt() throws IOException {
        String d = ddl();
        dropBeforeAdd(d, "gift_orders", "ck_gift_orders_channel");
        dropBeforeAdd(d, "gift_orders", "ck_gift_orders_total");
        Set<String> channels = values(d, "ck_gift_orders_channel");
        assertThat(channels).containsExactlyInAnyOrder("PAWCOIN", "QRIS", "PACK_CREDIT");
        Set<String> enumNames = Arrays.stream(PayChannel.values()).map(Enum::name).collect(Collectors.toSet());
        assertThat(enumNames).containsAll(channels);
        assertThat(d).contains("(channel = 'PACK_CREDIT' AND total = 0 AND unit_price = 1000 AND payment_intent_id IS NULL)"
                + " OR (channel <> 'PACK_CREDIT' AND total = unit_price * quantity)");
    }

    @Test
    void idCardHdChannelRebuiltWithThreeValues() throws IOException {
        String d = ddl();
        dropBeforeAdd(d, "id_card_hd_purchases", "ck_id_card_hd_purchases_channel");
        assertThat(values(d, "ck_id_card_hd_purchases_channel")).containsExactlyInAnyOrder("QRIS", "PAWCOIN",
                "PACK_CREDIT");
    }
}
