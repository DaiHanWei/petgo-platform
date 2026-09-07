package com.tailtopia.shop.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.domain.FeedingGuideEntry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * L0：每日建议喂量的 JSON 契约（Story 1.1 AC2）。
 *
 * <p>🔴 该字段是 FR-109 粮量见底预估的<b>唯一计算依据</b>，必须是结构化数组而非自由文本。
 * 本测试钉住 JSON 形状——字段名一旦漂移，落库的历史数据就再也解析不出来。
 */
class FeedingGuideEntryContractTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("序列化字段名固定为 weightMinKg / weightMaxKg / gramsPerDay")
    void serializesWithStableFieldNames() {
        String out = json.writeValueAsString(new FeedingGuideEntry(5, 10, 110));
        assertThat(out).isEqualTo("{\"weightMinKg\":5,\"weightMaxKg\":10,\"gramsPerDay\":110}");
    }

    @Test
    @DisplayName("数组往返无损 —— 多段体重区间原样取回")
    void arrayRoundTrip() {
        List<FeedingGuideEntry> src = List.of(
                new FeedingGuideEntry(1, 5, 60),
                new FeedingGuideEntry(5, 10, 110),
                new FeedingGuideEntry(10, 25, 210));
        String out = json.writeValueAsString(src);
        List<FeedingGuideEntry> back = json.readValue(out,
                json.getTypeFactory().constructCollectionType(List.class, FeedingGuideEntry.class));
        assertThat(back).isEqualTo(src);
    }
}
