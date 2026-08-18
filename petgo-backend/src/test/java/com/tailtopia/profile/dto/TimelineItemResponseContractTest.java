package com.tailtopia.profile.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标（CROSS-STORY C4/C5）：钉死时间线条目对外 JSON 形状（Story 3.2 / FR-82）。
 *
 * <p><b>三处必须同步，任一漂移即契约破坏（本测试会红）：</b>
 * <ul>
 *   <li>后端 —— {@link TimelineItemResponse} / {@link TimelineItemType}</li>
 *   <li>App  —— {@code petgo_app/lib/features/profile/domain/timeline_item.dart}
 *       （Story 2.2 已落 {@code TimelineItemType} 五值枚举 + 契约字段）</li>
 *   <li>本测试的字段集与枚举取值</li>
 * </ul>
 * <p>C5 原文写「四处」含 App mock，**已过时**——提交 {@code 8e85b40d} 已删除全部 mock 子系统。
 *
 * <p>护栏：{@code itemType} 取值必须是 App 侧 Story 2.2 定义的五个 UPPER_SNAKE 字面量，
 * **后端不得另立一套**。纯 Jackson 序列化、无 Spring/DB → 云端 headless 可跑（L0）。
 */
class TimelineItemResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    /** 全字段集（NON_NULL 下按类型出现子集；本集合是并集上界）。 */
    private static final Set<String> ALL_FIELDS = Set.of(
            "kind", "itemType", "date", "eventDate", "postId", "imageUrls", "text",
            "aiLevel", "symptomSummary", "sourceType", "sourceRef",
            "milestoneCode", "milestoneLevel", "healthRecordType", "healthRecordId", "idCardSerial");

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void itemTypeVocabulary_matchesAppStory22Definition() {
        // ⚠️ 顺序与字面量都钉死：App 侧 timeline_item.dart 的 wire 值与此一一对应。
        assertThat(List.of(TimelineItemType.values()).stream().map(Enum::name).toList())
                .containsExactly(
                        "HAPPY_MOMENT",
                        "HAPPY_MOMENT_MILESTONE",
                        "MILESTONE_BANNER",
                        "HEALTH_RECORD",
                        "ID_CARD_ISSUED");
    }

    @Test
    void itemTypeSerializesAsUpperSnakeString() {
        Map<String, Object> w = wire(TimelineItemResponse.milestoneBanner(
                Instant.parse("2026-05-20T09:00:00Z"), "C-L2", "L"));
        assertThat(w.get("itemType")).isEqualTo("MILESTONE_BANNER");
    }

    @Test
    void allFactoriesStayWithinDeclaredFieldSet() {
        List<TimelineItemResponse> samples = List.of(
                TimelineItemResponse.happyMoment(1L, Instant.parse("2026-05-20T09:00:00Z"),
                        LocalDate.of(2026, 5, 20), List.of("https://x/1.jpg"), "文字"),
                TimelineItemResponse.happyMomentWithMilestone(1L, Instant.parse("2026-05-20T09:00:00Z"),
                        LocalDate.of(2026, 5, 20), List.of("https://x/1.jpg"), "文字", "D-S13", "S"),
                TimelineItemResponse.healthEvent(Instant.parse("2026-05-20T09:00:00Z"),
                        LocalDate.of(2026, 5, 20), "GREEN", "摘要", "AI_TRIAGE", "triage:1"),
                TimelineItemResponse.healthRecord(7L, Instant.parse("2026-05-20T09:00:00Z"),
                        LocalDate.of(2026, 5, 20), "VACCINE", "第一针"),
                TimelineItemResponse.milestoneBanner(Instant.parse("2026-05-20T09:00:00Z"), "C-L2", "L"),
                TimelineItemResponse.idCardIssued(Instant.parse("2026-05-20T09:00:00Z"), "#00842"));

        for (TimelineItemResponse s : samples) {
            assertThat(wire(s).keySet()).isSubsetOf(ALL_FIELDS);
            assertThat(wire(s)).containsKey("itemType"); // 每条都必须带分类标识
            assertThat(wire(s)).containsKey("kind"); // 源标识保留（V1.0.0 既有契约）
        }
    }

    @Test
    void milestoneItemsExposeCodeNotDbId() {
        // 对外只给稳定 code（展示文案客户端按 locale 出，杜绝后端中文泄漏）。
        Map<String, Object> w = wire(TimelineItemResponse.milestoneBanner(
                Instant.parse("2026-05-20T09:00:00Z"), "C-L2", "L"));
        assertThat(w).containsEntry("milestoneCode", "C-L2");
        assertThat(w).doesNotContainKey("id");
        assertThat(w).doesNotContainKey("petMilestoneId");
        assertThat(w).doesNotContainKey("milestoneId");
    }

    @Test
    void idCardItemOmitsSerialWhenAbsent() {
        // 老档案未申请编号 → serial 为 null，NON_NULL 下字段整体省略（前端不渲染编号位）。
        Map<String, Object> w = wire(
                TimelineItemResponse.idCardIssued(Instant.parse("2026-05-20T09:00:00Z"), null));
        assertThat(w).doesNotContainKey("idCardSerial");
        assertThat(w).containsEntry("itemType", "ID_CARD_ISSUED");
    }

    @Test
    void healthEventKeepsV1SourceFieldsForDeepLink() {
        // bug 20260702-231 / 20260706-259 的两个字段不得因新增分类而丢失。
        Map<String, Object> w = wire(TimelineItemResponse.healthEvent(
                Instant.parse("2026-05-20T09:00:00Z"), LocalDate.of(2026, 5, 20),
                "GREEN", "摘要", "VET_CONSULT", "consult:9"));
        assertThat(w).containsEntry("sourceType", "VET_CONSULT");
        assertThat(w).containsEntry("sourceRef", "consult:9");
        assertThat(w).containsEntry("healthRecordType", "CONSULT");
    }
}
