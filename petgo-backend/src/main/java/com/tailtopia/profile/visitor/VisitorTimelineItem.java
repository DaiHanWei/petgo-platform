package com.tailtopia.profile.visitor;

import com.tailtopia.profile.dto.TimelineItemType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 访客视图的时间线条目（V1.1.6 Story 2.1）。
 *
 * <p>🔴 <b>这是一个专门为访客新建的 DTO，不是复用作者态那个。</b>
 * 作者态的 {@code TimelineItemResponse} 带着四个健康相关字段 ——
 * {@code aiLevel}（AI 分诊等级）、<b>{@code symptomSummary}（症状摘要，这就是健康数据本身）</b>、
 * {@code healthRecordType}、{@code healthRecordId}。
 *
 * <p>即使今天的投影逻辑一个都不填，<b>将来任何人改动都可能填上，而那时不会有任何东西报错</b>。
 * 所以访客侧要有一个<b>物理上就没有这些字段</b>的容器：泄露因此成为「不可能」，
 * 而不是「靠每个改代码的人都记得别填」。
 *
 * <p>沿用 AD-1 Rule 2 的同一手法（「分类器不持有 Repository、结构上就不可能写库」）。
 *
 * <p>⚠️ 往这个 record 里加字段前先问一句：<b>访客有没有权利看到它</b>。
 * {@code VisitorProjectionFieldsTest} 会拦住任何带健康/问诊字样的新字段。
 *
 * @param itemType      五类视觉分类。⚠️ 访客侧<b>永远不会</b>出现 {@code HEALTH_RECORD}
 * @param date          排序用时间戳（UTC）
 * @param eventDate     事件日期（Diary 条目按它排）
 * @param postId        内容 id（可点开的公开内容才有）
 * @param imageUrls     对外图（已去 EXIF）
 * @param text          正文
 * @param milestoneCode 里程碑稳定 code（展示文案由客户端按 locale 出，杜绝中文泄漏）
 * @param milestoneLevel 里程碑级别 S/M/L
 * @param idCardSerial  身份证编号（类⑤）
 */
public record VisitorTimelineItem(
        TimelineItemType itemType,
        Instant date,
        LocalDate eventDate,
        Long postId,
        List<String> imageUrls,
        String text,
        String milestoneCode,
        String milestoneLevel,
        String idCardSerial) {
}
