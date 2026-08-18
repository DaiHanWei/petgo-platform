package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 成长日历「快乐时刻」时间线视图（Story 2.4）。content 模块对外暴露的只读投影，
 * 供 profile 时间线聚合**经 service 接口**取数（不暴露实体、不让 profile join content 表）。
 *
 * <p>{@code eventDate}（F9，Story 2.3 加列）决定档案侧时间线/日历显示位置；{@code createdAt} 决定同日内
 * 排序与 Feed 排序（两者解耦）。
 */
public record GrowthMomentView(
        Long id,
        Instant createdAt,
        LocalDate eventDate,
        List<String> imageUrls,
        String text,
        /**
         * 可见范围（V1.1.6 Story 2.3 加）。{@code PRIVATE} = 作者关闭了同步的私密 Diary。
         *
         * <p>⚠️ <b>刻意没有默认值</b>：这是安全相关字段，「忘了传就默认公开」正是要避免的失守方式 ——
         * 那种错是静默的（私密内容悄悄变得可点开），不会有任何东西报错。
         */
        ContentVisibility visibility,
        /** 审核状态（V1.1.6 Story 2.3 加）。同上，无默认值。 */
        PostStatus status) {

    /**
     * 是否对访客<b>可点开</b>（V1.1.6 Story 2.3 · AD-3 Rule 1）。
     *
     * <p>判定是<b>两条同时成立</b>：可见范围为公开 <b>且</b> 状态为已发布。
     *
     * <p>⚠️ 访客投影的取数本来就只要 {@code PUBLISHED}，所以现在第二条恒真 ——
     * 但<b>仍然把两条都写出来</b>：将来若有人放宽了那个查询，这里不会跟着静默失守。
     */
    public boolean openableByVisitor() {
        return visibility == ContentVisibility.PUBLIC && status == PostStatus.PUBLISHED;
    }

    /** 首图（无图返回 null），供日历格子背景缩略图取用。 */
    public String firstImageUrl() {
        return (imageUrls == null || imageUrls.isEmpty()) ? null : imageUrls.get(0);
    }
}
