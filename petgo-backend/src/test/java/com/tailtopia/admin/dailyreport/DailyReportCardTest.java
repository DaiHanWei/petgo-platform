package com.tailtopia.admin.dailyreport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：日报指标计算与飞书卡片结构。 */
class DailyReportCardTest {

    private static DailyReport.Metrics m(Long dau, long comments, long newUsers, long paid) {
        return new DailyReport.Metrics(newUsers, dau, 10, comments, 40, 3, 2, paid, 5, 1_234_000);
    }

    @Test
    @DisplayName("环比：正常百分比；分母为 0 或任一侧缺失 → null")
    void changePct() {
        assertThat(DailyReport.changePct(120, 100)).isEqualTo(20.0);
        assertThat(DailyReport.changePct(80, 100)).isEqualTo(-20.0);
        assertThat(DailyReport.changePct(5, 0)).isNull();
        assertThat(DailyReport.changePct(null, 10)).isNull();
        assertThat(DailyReport.changePct(10, null)).isNull();
    }

    @Test
    @DisplayName("活跃率 = 评论数或 like 数 ÷ 日活；日活缺失或为 0 → null")
    void rates() {
        assertThat(m(50L, 20, 0, 0).commentRate()).isEqualTo(0.4);
        assertThat(m(50L, 20, 0, 0).likeRate()).isEqualTo(0.8);
        assertThat(m(null, 20, 0, 0).commentRate()).isNull();
        assertThat(m(0L, 20, 0, 0).likeRate()).isNull();
    }

    @Test
    @DisplayName("格式：印尼千分位「.」、金额带 Rp、活跃率百分比、环比箭头")
    void formatting() {
        assertThat(DailyReportCard.count(1234567)).isEqualTo("1.234.567");
        assertThat(DailyReportCard.idr(50000)).isEqualTo("Rp 50.000");
        assertThat(DailyReportCard.rate(0.4)).isEqualTo("40.0%");
        assertThat(DailyReportCard.rate(null)).isEqualTo("—");
        assertThat(DailyReportCard.change(12.345)).contains("↑ +12.3%");
        assertThat(DailyReportCard.change(-5.0)).contains("↓ -5.0%");
        assertThat(DailyReportCard.change(0.0)).contains("持平");
        assertThat(DailyReportCard.change(null)).contains("环比 —");
    }

    @Test
    @DisplayName("卡片：interactive + header 带日期 + 四个模块 hr 分隔 + 末尾 note 口径")
    @SuppressWarnings("unchecked")
    void cardStructure() throws Exception {
        DailyReport r = new DailyReport(LocalDate.of(2026, 9, 24),
                m(50L, 20, 12, 150_000), m(40L, 10, 10, 100_000));

        Map<String, Object> payload = DailyReportCard.build(r);
        String json = new ObjectMapper().writeValueAsString(payload);

        assertThat(payload.get("msg_type")).isEqualTo("interactive");
        Map<String, Object> card = (Map<String, Object>) payload.get("card");
        assertThat(json).contains("TailTopia 日报 · 2026-09-24");
        List<Map<String, Object>> els = (List<Map<String, Object>>) card.get("elements");
        assertThat(els.stream().filter(e -> "hr".equals(e.get("tag"))).count()).isEqualTo(3);
        assertThat(els.getLast().get("tag")).isEqualTo("note");
        assertThat(json).contains("昨日新增").contains("昨日日活").contains("评论活跃率").contains("like 活跃率")
                .contains("付费订单数").contains("付费用户数").contains("付费金额").contains("电商订单数").contains("GMV");
        assertThat(json).contains("Rp 150.000").contains("↑ +50.0%");   // 付费金额 150k vs 100k
        assertThat(json).contains("Rp 1.234.000");                      // GMV
    }

    @Test
    @DisplayName("日活缺失（上线前 / 上线当天）→ 显示「—」，两个活跃率也是「—」，不编数字")
    void dauMissing() throws Exception {
        DailyReport r = new DailyReport(LocalDate.of(2026, 9, 25), m(null, 20, 12, 0), m(null, 10, 10, 0));
        String json = new ObjectMapper().writeValueAsString(DailyReportCard.build(r));

        assertThat(json).contains("**昨日日活**\\n—").contains("**评论活跃率**\\n—").contains("**like 活跃率**\\n—");
    }
}
