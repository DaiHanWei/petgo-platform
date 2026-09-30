package com.tailtopia.passport;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.place.domain.PlaceAvailability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * V1.3.2 Story 1.2 · L0：号源 SQL 与章聚合 SQL 的形状（真库行为在 L1 集成测试）。
 *
 * <p>号源每个条件都在防一个真实事故（story 关键设计点）——漏一个就是把上一只宠物 / 别的物种的号
 * 发给这只，或撞唯一约束中止整个打卡事务。
 */
class PetPassportSqlShapeTest {

    private static String src(String rel) throws IOException {
        return Files.readString(Path.of("src/main/java/com/tailtopia/" + rel), StandardCharsets.UTF_8);
    }

    @Test
    void ktpSourceQueryHasEveryGuard() throws IOException {
        String s = src("profile/repository/IdCardRepository.java");
        assertThat(s).contains("c.user_id = :userId")
                .contains("c.profile_deleted_at IS NULL")
                .contains("c.created_at >= :petCreatedAt")
                .contains("c.passport_no IS NOT NULL")
                .contains("substring(c.passport_no, 3, 2) = :speciesCode")
                .contains("NOT EXISTS (SELECT 1 FROM pet_passports pp WHERE pp.passport_no = c.passport_no)")
                .contains("ORDER BY c.created_at ASC");
        // 🔴 只读：号源所在仓库不得出现对 id_cards 护照号的写。
        assertThat(s).doesNotContain("set c.passportNo").doesNotContain("UPDATE id_cards");
    }

    @Test
    void stampAggregationGroupsByCurrentPlaceAndOrdersNewestLast() throws IOException {
        String s = src("place/service/PlaceStampQueryService.java");
        assertThat(s).contains("JOIN places p ON p.id = c.place_id")
                .contains("MIN(c.visit_date)")
                .contains("ORDER BY first_visit ASC, first_id ASC")
                .contains("COUNT(DISTINCT c.place_id)")
                // Story 1.3：地址取出后只对 ACTIVE 保留。
                .contains("p.address_text")
                // Story 1.4：专属章 key 读取时现算 URL（换章对已盖出的章立即生效）。
                .contains("p.stamp_object_key")
                .contains("stampUrlOf(rs.getString(\"stamp_object_key\"))")
                .contains("availability == PlaceAvailability.ACTIVE ? rs.getString(\"address_text\") : null");
    }

    @Test
    void availabilityHasOnlyTwoValues() {
        assertThat(PlaceAvailability.values()).containsExactly(PlaceAvailability.ACTIVE, PlaceAvailability.UNAVAILABLE);
        assertThat(PlaceAvailability.of("ACTIVE", null)).isEqualTo(PlaceAvailability.ACTIVE);
        assertThat(PlaceAvailability.of("ACTIVE", Instant.now())).isEqualTo(PlaceAvailability.UNAVAILABLE);
        assertThat(PlaceAvailability.of("DELISTED", null)).isEqualTo(PlaceAvailability.UNAVAILABLE);
        assertThat(PlaceAvailability.of("MERGED", null)).isEqualTo(PlaceAvailability.UNAVAILABLE);
    }
}
