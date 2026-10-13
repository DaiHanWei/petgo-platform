package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.dto.CreateIdCardRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.3.4 ktp Story 1.1，AD-1）：卡种 / 配色 / 物种的请求白名单正则与枚举、库内 CHECK 同源。
 * 任何一处单加值都会让本测试红——三处必须一起改。
 */
class IdCardTypeWhitelistTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V20261013_0411__id_cards_card_variant_and_checks.sql");

    private static Set<String> regexValues(String regex) {
        return Set.of(regex.split("\\|"));
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    /** 取迁移里某条 CHECK 的 IN (...) 字面量集合。 */
    private static Set<String> checkValues(String constraint) throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(constraint + "\\s+CHECK\\s*\\([^)]*?IN\\s*\\(([^)]*)\\)").matcher(sql);
        assertThat(m.find()).as("迁移里找不到 %s", constraint).isTrue();
        return Arrays.stream(m.group(1).split(","))
                .map(v -> v.trim().replace("'", ""))
                .collect(Collectors.toSet());
    }

    @Test
    void cardTypeRegexMatchesEnumAndCheck() throws Exception {
        assertThat(regexValues(CreateIdCardRequest.CARD_TYPES)).isEqualTo(names(IdCardType.values()));
        assertThat(checkValues("ck_id_cards_card_type")).isEqualTo(names(IdCardType.values()));
    }

    @Test
    void cardVariantRegexMatchesEnumAndCheck() throws Exception {
        assertThat(regexValues(CreateIdCardRequest.CARD_VARIANTS)).isEqualTo(names(StudentVariant.values()));
        assertThat(checkValues("ck_id_cards_card_variant")).isEqualTo(names(StudentVariant.values()));
    }

    @Test
    void petTypeRegexMatchesEnum() {
        assertThat(regexValues(CreateIdCardRequest.PET_TYPES)).isEqualTo(names(PetType.values()));
    }

    @Test
    void enumsHaveExactlyTheAgreedValues() {
        assertThat(names(IdCardType.values())).containsExactlyInAnyOrder("KTP", "PASSPORT", "KITAS", "SIM", "STUDENT");
        assertThat(names(StudentVariant.values())).containsExactlyInAnyOrder("BINTANG", "MALAM", "CERIA", "KLASIK");
    }
}
