package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.MilestoneLevel;
import com.tailtopia.profile.dto.TimelineItemResponse;
import com.tailtopia.profile.dto.TimelineItemType;
import com.tailtopia.profile.service.TimelineClassifier.ContentCandidate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Story 3.2 · L0：五步分类优先级 + 实时性 + 路径无关（FR-82 / AD-2）。
 *
 * <p><b>本类锁的是本 Story 最容易埋雷的那条</b>：分类必须**实时计算**。
 * {@link #ac3_backfilledArchiveMakesBannerDisappearImmediately()} 用「同一批里程碑数据 +
 * 有/无当天健康条目」两次调用来证明结果随事实变化 —— 分类器是纯函数、不持有任何 Repository，
 * 结构上不可能把结果写库。**该断言常驻，不得删除或弱化。**
 */
class TimelineClassifierTest {

    private static final LocalDate D = LocalDate.of(2026, 5, 20);
    private static final Instant T9 = Instant.parse("2026-05-20T09:00:00Z");
    private static final Instant T10 = Instant.parse("2026-05-20T10:00:00Z");
    /** 问诊存档的就诊日期（V1.1.6 起随条目下发）。本类用例不涉及"补录旧问诊"，取当天即可。 */
    private static final java.time.LocalDate DAY = java.time.LocalDate.parse("2026-05-20");

    private static ContentCandidate content(long id, Instant createdAt, LocalDate eventDate) {
        return new ContentCandidate(id, createdAt, eventDate, List.of("https://x/1.jpg"), "文字");
    }

    private static TimelineItemResponse of(List<TimelineItemResponse> items, TimelineItemType type) {
        return items.stream().filter(i -> i.itemType() == type).findFirst().orElse(null);
    }

    // ===== AC2 五步优先级，命中即停 =====

    @Test
    void step1_healthRecordAndConsultArchive_areClassFour() {
        List<TimelineItemResponse> health = List.of(
                TimelineItemResponse.healthRecord(7L, T9, D, "VACCINE", "第一针"),
                TimelineItemResponse.healthEvent(T10, DAY, "GREEN", "轻微腹泻", "AI_TRIAGE", "triage:1"));

        List<TimelineItemResponse> out =
                TimelineClassifier.classify(List.of(), health, List.of(), List.of());

        assertThat(out).hasSize(2);
        assertThat(out).allMatch(i -> i.itemType() == TimelineItemType.HEALTH_RECORD);
        // 结构化记录带类型与 id（供跳健康记录列表对应条目）；问诊存档统一 CONSULT
        assertThat(out.get(0).healthRecordType()).isEqualTo("VACCINE");
        assertThat(out.get(0).healthRecordId()).isEqualTo(7L);
        assertThat(out.get(1).healthRecordType()).isEqualTo("CONSULT");
    }

    @Test
    void step2_idCardFirstIssue_isClassFive() {
        List<TimelineItemResponse> out = TimelineClassifier.classify(List.of(), List.of(), List.of(),
                List.of(TimelineItemResponse.idCardIssued(T9, "#00842")));

        assertThat(out).singleElement()
                .returns(TimelineItemType.ID_CARD_ISSUED, TimelineItemResponse::itemType)
                .returns("#00842", TimelineItemResponse::idCardSerial);
    }

    @Test
    void step3_milestoneLinkedToContent_isClassTwo_andEmitsNoExtraItem() {
        List<MilestoneTimelineView> milestones =
                List.of(new MilestoneTimelineView("D-S13", MilestoneLevel.S, T9, 42L));

        List<TimelineItemResponse> out = TimelineClassifier.classify(
                List.of(content(42L, T9, D)), List.of(), milestones, List.of());

        // 关键：**只有一条**（同一条内容换样式，不额外生成里程碑条目，AC2 末条）
        assertThat(out).hasSize(1);
        assertThat(out.get(0).itemType()).isEqualTo(TimelineItemType.HAPPY_MOMENT_MILESTONE);
        assertThat(out.get(0).postId()).isEqualTo(42L);
        assertThat(out.get(0).milestoneCode()).isEqualTo("D-S13");
        assertThat(out.get(0).milestoneLevel()).isEqualTo("S");
    }

    /**
     * PR#34 finding #3：打卡里程碑的关联内容**不在本批**（补录旧日期照片 → 内容按 eventDate 落在
     * 别的分页）时，里程碑必须按类③ banner 呈现，不能因 linkedContentId 非空被无条件吞掉。
     */
    @Test
    void step3_linkedContentAbsentFromBatch_milestoneFallsBackToBanner() {
        List<MilestoneTimelineView> milestones =
                List.of(new MilestoneTimelineView("D-S13", MilestoneLevel.S, T9, 42L));

        // 本批 contents 不含 postId=42 的内容（它在别的分页）
        List<TimelineItemResponse> out = TimelineClassifier.classify(
                List.of(content(99L, T9, D)), List.of(), milestones, List.of());

        assertThat(out).hasSize(2);
        assertThat(of(out, TimelineItemType.MILESTONE_BANNER))
                .isNotNull()
                .returns("D-S13", TimelineItemResponse::milestoneCode);
        assertThat(of(out, TimelineItemType.HAPPY_MOMENT).postId()).isEqualTo(99L);
    }

    @Test
    void step4_systemAutoMilestone_isClassThreeBanner() {
        List<TimelineItemResponse> out = TimelineClassifier.classify(List.of(), List.of(),
                List.of(new MilestoneTimelineView("C-L2", MilestoneLevel.L, T9, null)), List.of());

        assertThat(out).singleElement()
                .returns(TimelineItemType.MILESTONE_BANNER, TimelineItemResponse::itemType)
                .returns("C-L2", TimelineItemResponse::milestoneCode)
                .returns("L", TimelineItemResponse::milestoneLevel);
    }

    @Test
    void step5_plainDiaryContent_isClassOne() {
        List<TimelineItemResponse> out = TimelineClassifier.classify(
                List.of(content(1L, T9, D)), List.of(), List.of(), List.of());

        assertThat(out).singleElement()
                .returns(TimelineItemType.HAPPY_MOMENT, TimelineItemResponse::itemType)
                .returns(null, TimelineItemResponse::milestoneCode);
    }

    // ===== 边界：同日既有日记又有疫苗记录 =====

    @Test
    void sameDay_diaryAndVaccine_bothSurvive_andDoNotContaminateEachOther() {
        List<TimelineItemResponse> out = TimelineClassifier.classify(
                List.of(content(1L, T10, D)),
                List.of(TimelineItemResponse.healthRecord(7L, T9, D, "VACCINE", null)),
                List.of(), List.of());

        assertThat(out).hasSize(2);
        assertThat(of(out, TimelineItemType.HAPPY_MOMENT)).isNotNull();
        assertThat(of(out, TimelineItemType.HEALTH_RECORD)).isNotNull();
    }

    @Test
    void sameDay_healthRecordDoesNotSwallowUnrelatedMilestoneBanner() {
        // 当天正好录了疫苗，但「100 天纪念」与健康记录无关 → banner 必须照常出现。
        // 若把「当天有健康条目」无差别地用于所有里程碑，这条会被误吃掉。
        List<TimelineItemResponse> out = TimelineClassifier.classify(List.of(),
                List.of(TimelineItemResponse.healthRecord(7L, T9, D, "VACCINE", null)),
                List.of(new MilestoneTimelineView("C-L2", MilestoneLevel.L, T10, null)), List.of());

        assertThat(out).hasSize(2);
        assertThat(of(out, TimelineItemType.MILESTONE_BANNER)).isNotNull();
    }

    // ===== AC3 实时计算（安全攸关，常驻断言） =====

    @Test
    void ac3_backfilledArchiveMakesBannerDisappearImmediately() {
        // M5「第一次看兽医」已完成（系统自动、无绑定内容）。
        List<MilestoneTimelineView> m5 =
                List.of(new MilestoneTimelineView("C-M5", MilestoneLevel.M, T10, null));

        // ① 用户问诊后**跳过存档** → 当天没有健康条目 → M5 显示为类③ banner。
        List<TimelineItemResponse> before =
                TimelineClassifier.classify(List.of(), List.of(), m5, List.of());
        assertThat(before).singleElement()
                .returns(TimelineItemType.MILESTONE_BANNER, TimelineItemResponse::itemType);

        // ② 日后**补存** → 当天出现问诊存档条目 → 立即重查：banner 消失、由类④ 胶囊承载。
        List<TimelineItemResponse> after = TimelineClassifier.classify(List.of(),
                List.of(TimelineItemResponse.healthEvent(T9, DAY, "GREEN", "轻微腹泻", "VET_CONSULT", "consult:9")),
                m5, List.of());

        assertThat(after).singleElement()
                .returns(TimelineItemType.HEALTH_RECORD, TimelineItemResponse::itemType);
        // 同一件事不出现两条：补存后总条数没有增加（banner 换成了胶囊，不是叠加）
        assertThat(after).hasSameSizeAs(before);
    }

    // ===== AC4 判定与完成路径无关 =====

    @Test
    void ac4_sameMilestoneViaAutoOrCheckin_classifiesIdentically_whenDayHasHealthItem() {
        List<TimelineItemResponse> health =
                List.of(TimelineItemResponse.healthRecord(7L, T9, D, "VACCINE", null));

        // 分类器只看「当天有无健康条目」，不看触发方式字段——两条数据的 source 不同也无所谓：
        // 传入同样的 (code, level, completedAt, linkedContentId=null)，结果必须一致。
        List<TimelineItemResponse> auto = TimelineClassifier.classify(List.of(), health,
                List.of(new MilestoneTimelineView("C-M3", MilestoneLevel.M, T10, null)), List.of());
        List<TimelineItemResponse> checkin = TimelineClassifier.classify(List.of(), health,
                List.of(new MilestoneTimelineView("C-M3", MilestoneLevel.M, T10, null)), List.of());

        assertThat(auto.stream().map(TimelineItemResponse::itemType).toList())
                .isEqualTo(checkin.stream().map(TimelineItemResponse::itemType).toList());
        // 疫苗里程碑当天有健康记录 → 由胶囊承载，不出 banner
        assertThat(of(auto, TimelineItemType.MILESTONE_BANNER)).isNull();
    }

    @Test
    void ac4_healthMilestoneSuffixes_coverAllSeriesPrefixes() {
        for (String prefix : List.of("C", "D", "G")) {
            assertThat(TimelineClassifier.isHealthMilestone(prefix + "-M3")).isTrue();
            assertThat(TimelineClassifier.isHealthMilestone(prefix + "-M4")).isTrue();
            assertThat(TimelineClassifier.isHealthMilestone(prefix + "-M5")).isTrue();
            assertThat(TimelineClassifier.isHealthMilestone(prefix + "-M9")).isTrue();
            assertThat(TimelineClassifier.isHealthMilestone(prefix + "-S4")).isTrue();
            // 非健康类：不参与「当天有健康条目即抑制」
            assertThat(TimelineClassifier.isHealthMilestone(prefix + "-L2")).isFalse();
            assertThat(TimelineClassifier.isHealthMilestone(prefix + "-S1")).isFalse();
        }
        assertThat(TimelineClassifier.isHealthMilestone(null)).isFalse();
    }

    // ===== AC7 类④ 不加里程碑标记（OQ-14 本版本不做） =====

    @Test
    void ac7_healthCapsuleCarriesNoMilestoneMark() {
        List<TimelineItemResponse> out = TimelineClassifier.classify(List.of(),
                List.of(TimelineItemResponse.healthRecord(7L, T9, D, "VACCINE", null)),
                List.of(new MilestoneTimelineView("C-M3", MilestoneLevel.M, T10, null)), List.of());

        TimelineItemResponse capsule = of(out, TimelineItemType.HEALTH_RECORD);
        assertThat(capsule).isNotNull();
        assertThat(capsule.milestoneCode()).isNull();
        assertThat(capsule.milestoneLevel()).isNull();
    }
}
