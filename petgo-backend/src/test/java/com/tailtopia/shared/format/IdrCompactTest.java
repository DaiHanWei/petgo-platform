package com.tailtopia.shared.format;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * L0：卢比金额缩写（V1.4.0 gift Story 2.1 · AC7）。
 *
 * <p>🔴 用例表是单一事实源（story 2.1 AC7.3）。App 端对应测试
 * {@code petgo_app/test/shared/format/idr_compact_test.dart}（Story 2.2）<b>逐行照抄同一张表</b>，
 * 改一边必须改另一边。
 */
class IdrCompactTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "-5, Rp0",
            "0, Rp0",
            "999, Rp999",
            "1000, Rp1rb",
            "1500, 'Rp1,5rb'",
            "1999, 'Rp1,9rb'",
            "53000, Rp53rb",
            "188000, Rp188rb",
            "188500, 'Rp188,5rb'",
            "999999, 'Rp999,9rb'",
            "1000000, Rp1jt",
            "1050000, Rp1jt",
            "1200000, 'Rp1,2jt'",
            "1290000, 'Rp1,2jt'",
            "1999999, 'Rp1,9jt'",
            "25000000, Rp25jt",
            "1234567890, 'Rp1234,5jt'"
    })
    void formatFollowsTheSharedTable(long amount, String expected) {
        assertThat(IdrCompact.format(amount)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → ({1}, {2})")
    @CsvSource({
            "188000, 188, rb",
            "1500, '1,5', rb",
            "0, 0, ''",
            "1200000, '1,2', jt"
    })
    void partsSplitNumberAndUnit(long amount, String number, String unit) {
        assertThat(IdrCompact.parts(amount)).isEqualTo(new IdrCompact.Parts(number, unit == null ? "" : unit));
    }
}
