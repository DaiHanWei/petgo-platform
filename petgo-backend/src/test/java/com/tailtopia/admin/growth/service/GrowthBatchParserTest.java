package com.tailtopia.admin.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** V1.4.0 ① Story 4.3 · AC3.1（L0）：批量发放的用户 ID 解析。 */
class GrowthBatchParserTest {

    private static String code(Throwable t) {
        return ((AppException) t).getMessageCode();
    }

    @Test
    void mixedSeparatorsAndSeveralIdsPerLine() {
        GrowthBatchParser.Parsed p = GrowthBatchParser.parse("1, 2，3;4 5\n6\t7\r\n8", null);
        assertThat(p.ids()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(p.malformed()).isEmpty();
    }

    @Test
    void duplicatesCountOnceAndAreNotInvalid() {
        GrowthBatchParser.Parsed p = GrowthBatchParser.parse("5 5 5\n6", "user_id\n6\n5");
        assertThat(p.ids()).containsExactly(5L, 6L);
        assertThat(p.malformed()).isEmpty();
    }

    @Test
    void nonNumericTokensAreMalformed() {
        GrowthBatchParser.Parsed p = GrowthBatchParser.parse("12 abc -3 4.5 99", null);
        assertThat(p.ids()).containsExactly(12L, 99L);
        assertThat(p.malformed()).containsExactly("abc", "-3", "4.5");
        assertThat(p.normalized()).isEqualTo("12,99,abc,-3,4.5");
    }

    @Test
    void bomQuotesAndHeaderInCsvFirstColumnOnly() {
        GrowthBatchParser.Parsed p = GrowthBatchParser.parse(null,
                "﻿\"User_ID\",note\n\"101\",x\n'102';y\n103\t z\n\n");
        assertThat(p.ids()).containsExactly(101L, 102L, 103L);
        assertThat(p.malformed()).isEmpty();
        assertThat(GrowthBatchParser.parse(null, "uid\n7").ids()).containsExactly(7L);
        assertThat(GrowthBatchParser.parse(null, "ID\n8").ids()).containsExactly(8L);
        // 表头只认首行
        assertThat(GrowthBatchParser.parse(null, "9\nuser_id").malformed()).containsExactly("user_id");
    }

    @Test
    void pastedAndCsvAreUnioned() {
        assertThat(GrowthBatchParser.parse("1 2", "user_id\n2\n3").ids()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void reparsingNormalizedGivesTheSameResult() {
        GrowthBatchParser.Parsed p = GrowthBatchParser.parse("3 x 1 3", "user_id\n2");
        GrowthBatchParser.Parsed again = GrowthBatchParser.parse(p.normalized(), null);
        assertThat(again).isEqualTo(p);
    }

    @Test
    void emptyInputIs422() {
        assertThatThrownBy(() -> GrowthBatchParser.parse("  \n ,, ", "user_id\n"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.batch.idsRequired"));
        assertThatThrownBy(() -> GrowthBatchParser.parse(null, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.batch.idsRequired"));
    }

    @Test
    void moreThanMaxDistinctIdsIs422() {
        String max = IntStream.rangeClosed(1, GrowthBatchParser.MAX_IDS).mapToObj(String::valueOf)
                .collect(Collectors.joining(" "));
        assertThat(GrowthBatchParser.parse(max + " 1 2", null).ids()).hasSize(GrowthBatchParser.MAX_IDS);
        assertThatThrownBy(() -> GrowthBatchParser.parse(max + " 5001", null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.batch.idsTooMany"))
                .satisfies(t -> assertThat(((AppException) t).getMessageArgs()).containsExactly(5000));
    }

    @Test
    void malformedCsvCellWithSpacesDoesNotTurnIntoAnIdOnReparse() {
        GrowthBatchParser.Parsed p = GrowthBatchParser.parse(null, "user_id\n10001 alice\n10002");
        assertThat(p.ids()).containsExactly(10002L);
        assertThat(p.malformed()).containsExactly("10001_alice");
        assertThat(GrowthBatchParser.parse(p.normalized(), null)).isEqualTo(p);
        // 「← 上一步」把逗号换成换行回填，再预览也不变
        assertThat(GrowthBatchParser.parse(p.normalized().replace(",", "\n"), null)).isEqualTo(p);
    }
}
