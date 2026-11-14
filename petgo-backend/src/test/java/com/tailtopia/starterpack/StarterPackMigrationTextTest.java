package com.tailtopia.starterpack;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotRentalSource;
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

/** L0：V1.5.0 gift-pack Story 4-2 AC1.3 —— 两条迁移的文本守卫（读文件去注释，不连库）。 */
class StarterPackMigrationTextTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");
    private static final Path PURPOSE = DIR.resolve("V20261115_0225__starter_pack_purchases_and_purpose.sql");
    private static final Path SLOT = DIR.resolve("V20261115_0230__pet_slot_pack_kinds.sql");

    private static String ddl(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    /** {@code ADD CONSTRAINT <name> CHECK (<col> IN (...))} 的值集合。 */
    private static Set<String> checkValues(String d, String constraint) {
        Matcher m = Pattern.compile("ADD CONSTRAINT " + constraint + " CHECK \\(\\w+ IN \\(([^)]*)\\)\\)").matcher(d);
        assertThat(m.find()).as(constraint).isTrue();
        return Arrays.stream(m.group(1).split(","))
                .map(s -> s.trim().replace("'", ""))
                .collect(Collectors.toSet());
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    @Test
    void purposeCheckIsDropAddWithAllThirteenEnumValues() throws IOException {
        String d = ddl(PURPOSE);
        int drop = d.indexOf("ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_purpose");
        int add = d.indexOf("ALTER TABLE payment_intents ADD CONSTRAINT ck_payment_intents_purpose");
        assertThat(drop).isGreaterThanOrEqualTo(0);
        assertThat(add).isGreaterThan(drop);
        Set<String> values = checkValues(d, "ck_payment_intents_purpose");
        assertThat(values).hasSize(13).isEqualTo(names(PaymentPurpose.values()));
    }

    @Test
    void growthTierRowAndPricingDefaults() throws IOException {
        String d = ddl(PURPOSE);
        assertThat(d).contains("INSERT INTO growth_spend_tiers (purpose, tier, locked) VALUES ('STARTER_PACK', 'DIGITAL', false);");
        assertThat(d).contains("ADD COLUMN starter_pack_tier1_price BIGINT NOT NULL DEFAULT 8000;");
        assertThat(d).contains("ADD COLUMN starter_pack_tier2_price BIGINT NOT NULL DEFAULT 11000;");
        assertThat(d).contains("ADD COLUMN starter_pack_tier3_price BIGINT NOT NULL DEFAULT 13000;");
        assertThat(d).contains("ADD COLUMN starter_pack_enabled BOOLEAN NOT NULL DEFAULT true;");
        assertThat(d).contains("CONSTRAINT uq_starter_pack_purchases_idem_key UNIQUE (idem_key)");
        assertThat(d).contains("CREATE UNIQUE INDEX uq_starter_pack_purchases_one_pending ON starter_pack_purchases (user_id) WHERE status = 'PENDING';");
    }

    @Test
    void petSlotKindAndSourceChecksMatchEnums() throws IOException {
        String d = ddl(SLOT);
        assertThat(d.indexOf("DROP CONSTRAINT ck_pet_slot_purchases_kind"))
                .isLessThan(d.indexOf("ADD CONSTRAINT ck_pet_slot_purchases_kind")).isGreaterThanOrEqualTo(0);
        assertThat(d.indexOf("DROP CONSTRAINT ck_pet_slot_rentals_source"))
                .isLessThan(d.indexOf("ADD CONSTRAINT ck_pet_slot_rentals_source")).isGreaterThanOrEqualTo(0);
        assertThat(checkValues(d, "ck_pet_slot_purchases_kind")).isEqualTo(names(PetSlotPurchaseKind.values()));
        assertThat(checkValues(d, "ck_pet_slot_rentals_source")).isEqualTo(names(PetSlotRentalSource.values()));
        assertThat(d).contains("ADD COLUMN starter_pack_purchase_id BIGINT NULL REFERENCES starter_pack_purchases (id) ON DELETE SET NULL;");
    }
}
