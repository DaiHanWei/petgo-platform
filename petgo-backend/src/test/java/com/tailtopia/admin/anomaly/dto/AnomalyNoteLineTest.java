package com.tailtopia.admin.anomaly.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：备注时间线编码 / 解析（V1.3.0 Story 2.6 AC2）。 */
class AnomalyNoteLineTest {

    @Test
    void encodeThenParseRoundTrips() {
        Instant t = Instant.parse("2026-09-09T06:00:00Z");
        String a = AnomalyNoteLine.encode(t, "运营|甲\n", "已电话联系\r\n用户 | 无异议");
        String raw = a + "\n" + AnomalyNoteLine.encode(t.plusSeconds(60), "乙", "第二条");
        List<AnomalyNoteLine> lines = AnomalyNoteLine.parse(raw);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).author()).isEqualTo("运营｜甲");
        assertThat(lines.get(0).at()).isEqualTo(t);
        assertThat(lines.get(0).text()).isEqualTo("已电话联系 用户 | 无异议");
        assertThat(lines.get(1).text()).isEqualTo("第二条");
    }

    @Test
    void legacySingleNoteBecomesOneUntimedLine() {
        List<AnomalyNoteLine> lines = AnomalyNoteLine.parse("用户已电话安抚");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).author()).isNull();
        assertThat(lines.get(0).at()).isNull();
        assertThat(lines.get(0).text()).isEqualTo("用户已电话安抚");
        assertThat(AnomalyNoteLine.parse(null)).isEmpty();
        assertThat(AnomalyNoteLine.parse("  \n ")).isEmpty();
        // 形似编码但时间非数字 / 不是 13 位毫秒 → 旧数据整行保留
        assertThat(AnomalyNoteLine.parse("a|b|c").get(0).text()).isEqualTo("a|b|c");
        assertThat(AnomalyNoteLine.parse("12|a|b").get(0).text()).isEqualTo("12|a|b");
        assertThat(AnomalyNoteLine.parse("12|a|b").get(0).at()).isNull();
    }
}
