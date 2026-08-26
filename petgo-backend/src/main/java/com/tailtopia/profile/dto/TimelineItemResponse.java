package com.tailtopia.profile.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 成长时间线条目（Story 2.4 建立 · Story 3.2 扩到四源五类）。
 *
 * <p><b>两个维度不要混淆</b>：
 * <ul>
 *   <li>{@code kind} —— 这条数据**来自哪个源**（V1.0.0 既有契约：{@code HAPPY_MOMENT} / {@code HEALTH_EVENT}，
 *       Story 3.2 新增 {@code HEALTH_RECORD} / {@code MILESTONE} / {@code ID_CARD}）；</li>
 *   <li>{@code itemType} —— 这条数据**长什么样**（五类视觉分类，见 {@link TimelineItemType}）。</li>
 * </ul>
 * 同一个 {@code HAPPY_MOMENT} 源可能是类① 也可能是类②，故两者不可互相替代。
 *
 * <p>排序：时间线按 {@code effectiveDate} 倒序、**同日按发布/完成时刻正序**（FR-82 / Story 3.2 AC5）；
 * 当天详情按 {@code date}(createdAt) 正序。{@code date} 兼作同日排序键与游标分量。
 * Jackson NON_NULL：非本类字段省略。时间 ISO-8601 UTC / 日期 ISO LocalDate。
 *
 * @param itemType 五类视觉分类（Story 3.2 起必填下发；实时计算、不落库）
 * @param milestoneCode 里程碑稳定 code（类②③）。**只给 code，展示文案由客户端按 locale 出**，杜绝中文泄漏
 * @param milestoneLevel 里程碑级别 S/M/L（类③ banner 配色与等级角标）
 * @param healthRecordType 健康记录类型（类④）：结构化取 VACCINE/DEWORM/... ；问诊存档取 {@code CONSULT}
 * @param healthRecordId 结构化健康记录 id（类④ 点击跳健康记录列表对应条目；owner 域内资源，非跨用户标识）
 * @param idCardSerial 身份证编号（类⑤，老档案未申请时为 null → 前端不渲染编号位）
 */
public record TimelineItemResponse(
        String kind,
        TimelineItemType itemType,
        Instant date,
        LocalDate eventDate,
        Long postId,
        List<String> imageUrls,
        String text,
        String aiLevel,
        String symptomSummary,
        String sourceType,
        String sourceRef,
        String milestoneCode,
        String milestoneLevel,
        String healthRecordType,
        Long healthRecordId,
        String idCardSerial,
        /**
         * 内容装饰标签（V1.1.6 Story 5.2 · FR-75）。**只有快乐时刻类条目**可能有。
         *
         * <p>🔴 由 {@code TimelineService} 在**条目组装完成后统一贴一次**（一个响应一次批量），
         * 所以各个工厂方法一律传 null —— 时间线是五类混排、组装点不止一处，
         * 在每个组装点各取一遍既容易漏、又必然退化成逐条查。
         */
        java.util.List<com.tailtopia.content.dto.ContentTagView> decorationTags) {

    /** 贴上装饰标签的副本（record 不可变，统一贴标那一步用）。 */
    public TimelineItemResponse withDecorationTags(
            java.util.List<com.tailtopia.content.dto.ContentTagView> tags) {
        return new TimelineItemResponse(kind, itemType, date, eventDate, postId, imageUrls, text,
                aiLevel, symptomSummary, sourceType, sourceRef, milestoneCode, milestoneLevel,
                healthRecordType, healthRecordId, idCardSerial,
                (tags == null || tags.isEmpty()) ? null : tags);
    }

    public static final String HAPPY_MOMENT = "HAPPY_MOMENT";
    public static final String HEALTH_EVENT = "HEALTH_EVENT";
    /** 结构化健康记录源（Story 3.2 新增）。 */
    public static final String HEALTH_RECORD = "HEALTH_RECORD";
    /** 里程碑完成源（Story 3.2 新增）。 */
    public static final String MILESTONE = "MILESTONE";
    /** 身份证生成源（Story 3.2 新增）。 */
    public static final String ID_CARD = "ID_CARD";

    /** 类① 普通快乐时刻（Diary 内容）。 */
    public static TimelineItemResponse happyMoment(Long postId, Instant date, LocalDate eventDate,
            List<String> imageUrls, String text) {
        return new TimelineItemResponse(HAPPY_MOMENT, TimelineItemType.HAPPY_MOMENT, date, eventDate,
                postId, imageUrls, text, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 类② 打卡关联型里程碑 —— **同一条 Diary 内容**换个样式（照片卡 + 金徽章），
     * 不是新条目（AC2 末条）。
     */
    public static TimelineItemResponse happyMomentWithMilestone(Long postId, Instant date,
            LocalDate eventDate, List<String> imageUrls, String text, String milestoneCode,
            String milestoneLevel) {
        return new TimelineItemResponse(HAPPY_MOMENT, TimelineItemType.HAPPY_MOMENT_MILESTONE, date,
                eventDate, postId, imageUrls, text, null, null, null, null, milestoneCode,
                milestoneLevel, null, null, null, null);
    }

    /**
     * 类④ 问诊存档条目（V1.0.0 既有源）。{@code sourceType} = AI_TRIAGE / VET_CONSULT，
     * 前端据此区分 AI/兽医（bug 20260702-231）；{@code sourceRef} = 问诊/会话 token（幂等键），
     * 前端据此深链到对应结果页（bug 20260706-259）。
     */
    /**
     * 类④ 问诊存档。
     *
     * <p>🔴 V1.1.6 修正：加入 {@code eventDate}（<b>就诊那天</b>）。
     * {@code date} 是<b>归档进档案的时刻</b> —— 一次三个月前的问诊今天才归档，两者差三个月。
     * 不带就诊日期的话 {@link #effectiveDate()} 会回退到归档时刻，条目就显示在「今天」。
     */
    public static TimelineItemResponse healthEvent(Instant date, LocalDate eventDate, String aiLevel,
            String symptomSummary, String sourceType, String sourceRef) {
        return new TimelineItemResponse(HEALTH_EVENT, TimelineItemType.HEALTH_RECORD, date, eventDate,
                null, null, null, aiLevel, symptomSummary, sourceType, sourceRef, null, null,
                "CONSULT", null, null, null);
    }

    /** 类④ 结构化健康记录（疫苗/驱虫/绝育/月经/自定义）的只读镜像。 */
    public static TimelineItemResponse healthRecord(Long recordId, Instant createdAt,
            LocalDate eventDate, String type, String text) {
        return new TimelineItemResponse(HEALTH_RECORD, TimelineItemType.HEALTH_RECORD, createdAt,
                eventDate, null, null, text, null, null, null, null, null, null, type, recordId, null, null);
    }

    /** 类③ 系统自动型里程碑 banner 的只读镜像（无「发布时间」概念 → 取完成时间戳参与同日排序）。 */
    public static TimelineItemResponse milestoneBanner(Instant completedAt, String code, String level) {
        return new TimelineItemResponse(MILESTONE, TimelineItemType.MILESTONE_BANNER, completedAt, null,
                null, null, null, null, null, null, null, code, level, null, null, null, null);
    }

    /** 类⑤ 身份证首次生成的只读镜像（取生成时间戳参与同日排序）。 */
    public static TimelineItemResponse idCardIssued(Instant createdAt, String serial) {
        return new TimelineItemResponse(ID_CARD, TimelineItemType.ID_CARD_ISSUED, createdAt, null, null,
                null, null, null, null, null, null, null, null, null, null, serial, null);
    }

    /** 排序/显示有效日期（有 eventDate 取之；否则取 date 的 UTC 日）。 */
    public LocalDate effectiveDate() {
        if (eventDate != null) {
            return eventDate;
        }
        return date.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }
}
