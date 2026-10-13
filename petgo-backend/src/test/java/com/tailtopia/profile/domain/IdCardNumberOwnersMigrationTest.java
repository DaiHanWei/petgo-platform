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
 * L0（V1.3.4 ktp Story 2.2，AD-3 / NFR6 / cloud-rules）：归属表迁移的**形态**断言。
 *
 * <p>自检 DO 块的失败分支在正常建库里造不出数据（迁移前两列全局 UNIQUE），故钉住文本：
 * 有自检且 RAISE、按「自检 → 建表 → 回填 → 删 UNIQUE」顺序、回填与建表同一支迁移、不碰 uq_id_cards_serial。
 */
class IdCardNumberOwnersMigrationTest {

    private static final Path MIGRATION_DIR = Path.of("src", "main", "resources", "db", "migration");
    private static final Pattern TIMESTAMP_NAME = Pattern.compile(
            "^V20\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])_([01]\\d|2[0-3])[0-5]\\d__[a-z0-9_]+\\.sql$");

    private static Path migration() throws IOException {
        try (var files = Files.list(MIGRATION_DIR)) {
            List<Path> hits = files.filter(p -> p.getFileName().toString().endsWith("__id_card_number_owners.sql"))
                    .toList();
            assertThat(hits).hasSize(1);
            return hits.get(0);
        }
    }

    /** 去掉 SQL 行注释，便于只看代码。 */
    private static String code() throws IOException {
        return Files.readString(migration(), StandardCharsets.UTF_8).replaceAll("--[^\\n]*", "");
    }

    @Test
    void timestampNamed() throws IOException {
        assertThat(migration().getFileName().toString()).matches(TIMESTAMP_NAME);
    }

    @Test
    void selfCheckRaisesOnCrossUserDuplicates() throws IOException {
        String c = code();
        assertThat(c).contains("HAVING COUNT(DISTINCT user_id) > 1");
        assertThat(c).contains("RAISE EXCEPTION");
    }

    @Test
    void orderIsSelfCheckThenCreateThenBackfillThenDropUniques() throws IOException {
        String c = code();
        int check = c.indexOf("RAISE EXCEPTION");
        int create = c.indexOf("CREATE TABLE id_card_number_owners");
        int backfillC = c.indexOf("SELECT DISTINCT 'C', card_no, user_id FROM id_cards");
        int backfillP = c.indexOf("SELECT DISTINCT 'P', passport_no, user_id FROM id_cards");
        int dropC = c.indexOf("DROP CONSTRAINT uq_id_cards_card_no");
        int dropP = c.indexOf("DROP CONSTRAINT uq_id_cards_passport_no");
        assertThat(List.of(check, create, backfillC, backfillP, dropC, dropP)).doesNotContain(-1);
        assertThat(check).isLessThan(create);
        assertThat(create).isLessThan(backfillC);
        assertThat(backfillP).isLessThan(dropC);
        assertThat(backfillP).isLessThan(dropP);
    }

    @Test
    void ownershipKeyedByKindAndNumberAndSerialUniqueUntouched() throws IOException {
        String c = code();
        assertThat(c).contains("PRIMARY KEY (kind, number)");
        assertThat(c).contains("CHECK (kind IN ('C', 'P'))");
        assertThat(c).doesNotContain("uq_id_cards_serial");
    }
}
