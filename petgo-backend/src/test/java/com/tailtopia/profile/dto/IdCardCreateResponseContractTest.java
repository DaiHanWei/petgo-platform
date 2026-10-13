package com.tailtopia.profile.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约（V1.3.4 ktp Story 1.1，AD-4）：建卡响应 = 卡字段平铺 + {@code numberReused}；列表 / 详情不带该键。
 * 纯 Jackson 序列化（镜像生产 NON_NULL），无 Spring / DB。
 */
class IdCardCreateResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static IdCardResponse card(String cardType, String cardVariant) {
        return new IdCardResponse(5L, 11L, "Momo", "CAT", null, LocalDate.of(2024, 3, 10), null, null,
                "FEMALE", "TT600324020001", "TT02P2600001", null, null, null, null, cardType, cardVariant,
                null, null, false, Instant.parse("2026-10-13T00:00:00Z"));
    }

    @Test
    void createResponseFlattensCardFieldsAndAddsNumberReused() throws Exception {
        JsonNode node = json.readTree(json.writeValueAsString(
                IdCardCreateResponse.of(card("STUDENT", "BINTANG"), false)));

        assertThat(node.has("card")).isFalse(); // 平铺，不嵌套
        assertThat(node.get("id").asLong()).isEqualTo(5L);
        assertThat(node.get("cardType").asString()).isEqualTo("STUDENT");
        assertThat(node.get("cardVariant").asString()).isEqualTo("BINTANG");
        assertThat(node.get("cardNo").asString()).isEqualTo("TT600324020001");
        assertThat(node.get("numberReused").asBoolean()).isFalse();
        assertThat(node.get("numberReused").isBoolean()).isTrue();
    }

    @Test
    void listAndDetailResponseHasCardVariantButNoNumberReused() throws Exception {
        JsonNode student = json.readTree(json.writeValueAsString(card("STUDENT", "KLASIK")));
        assertThat(student.get("cardVariant").asString()).isEqualTo("KLASIK");
        assertThat(student.has("numberReused")).isFalse();

        JsonNode ktp = json.readTree(json.writeValueAsString(card("KTP", null)));
        assertThat(ktp.has("cardVariant")).isFalse(); // 非学生卡 null → NON_NULL 省略
    }
}
