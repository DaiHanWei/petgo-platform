package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.3.4 ktp Story 2.1，AD-2 / D-8）：hd_unlocked_at 迁移的**形态**断言（迁移本身要 DB，L1）。
 * 钉住：时间戳命名、建列与回填同一支迁移、QRIS 取到账（PAID 的 updated_at）而非下单 purchased_at、
 * 兜底 created_at、只回填已解锁卡、口径与后台 KTP_SQL 同一个表达式。
 */
class HdUnlockedAtMigrationTest {

    private static final Path MIGRATION_DIR = Path.of("src", "main", "resources", "db", "migration");
    private static final Pattern TIMESTAMP_NAME = Pattern.compile(
            "^V20\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])_([01]\\d|2[0-3])[0-5]\\d__[a-z0-9_]+\\.sql$");

    private static Path migration() throws IOException {
        try (var files = Files.list(MIGRATION_DIR)) {
            List<Path> hits = files.filter(p -> p.getFileName().toString().endsWith("__id_cards_hd_unlocked_at.sql"))
                    .toList();
            assertThat(hits).hasSize(1);
            return hits.get(0);
        }
    }

    private static String sql() throws IOException {
        return Files.readString(migration(), StandardCharsets.UTF_8);
    }

    /** 去掉 SQL 注释、压缩空白，便于按语义比对。 */
    private static String code(String s) {
        return s.replaceAll("--[^\\n]*", "").replaceAll("\\s+", " ").trim();
    }

    @Test
    void timestampNamedAndAfterCardVariantMigration() throws IOException {
        String name = migration().getFileName().toString();
        assertThat(name).matches(TIMESTAMP_NAME);
        String sibling = "V20261013_0411__id_cards_card_variant_and_checks.sql";
        assertThat(MIGRATION_DIR.resolve(sibling)).as("Story 1.1 迁移仍在").exists();
        assertThat(name.compareTo(sibling)).isGreaterThan(0);
    }

    @Test
    void addsColumnAndBackfillsInSameMigration() throws IOException {
        String c = code(sql());
        assertThat(c).contains("ALTER TABLE id_cards ADD COLUMN hd_unlocked_at TIMESTAMPTZ");
        assertThat(c).contains("UPDATE id_cards c SET hd_unlocked_at = COALESCE(");
        assertThat(c).contains("WHERE c.hd_unlocked;");
    }

    @Test
    void backfillUsesPaidUpdatedAtNotQrisPurchasedAtAndCreatedAtFallback() throws IOException {
        String c = code(sql());
        assertThat(c).contains("WHEN h.pay_channel = 'PAWCOIN' THEN h.purchased_at");
        assertThat(c).contains("WHEN pi.status = 'PAID' THEN pi.updated_at");
        assertThat(c).contains("c.created_at)");
    }

    @Test
    void backfillExpressionMatchesAdminKtpSql() throws IOException {
        String admin = code(Files.readString(Path.of(
                "src/main/java/com/tailtopia/admin/usermgmt/service/AdminUserPurchasesQuery.java"), StandardCharsets.UTF_8));
        String sub = "(SELECT MIN(CASE WHEN h.pay_channel = 'PAWCOIN' THEN h.purchased_at"
                + " WHEN pi.status = 'PAID' THEN pi.updated_at END)"
                + " FROM id_card_hd_purchases h LEFT JOIN payment_intents pi ON pi.id = h.payment_intent_id"
                + " WHERE h.card_id = c.id)";
        assertThat(code(sql())).contains(sub);
        assertThat(admin).contains(sub);
    }
}
