package com.tailtopia.auth.dto;

import com.tailtopia.growth.domain.GrowthBadge;
import java.util.List;

/**
 * 作者展示投影（Story 3.2）。供 content Feed 经 service 取作者昵称/头像，**不让 content 直 join users 表**。
 *
 * <p>注销匿名化（NFR-8）：作者已注销时 {@code deleted=true}、{@code nickname}/{@code avatarUrl}=null，
 * 由前端渲染本地化「已注销用户」+ 默认头像，且头像不可点（Story 3.8 联动）。
 *
 * @param userId   作者 id（注销后仍返回，前端据 {@code deleted} 决定可点性）
 * @param nickname 昵称（注销时 null）
 * @param avatarUrl 头像（注销时 null）
 * @param deleted  是否已注销
 * @param tags     运营标签（V1.1.6 Story 5.1 · FR-74）。<b>最多 3 个</b>，按分配时间倒序。
 *                 注销作者恒为空表 —— 匿名化之后不该再挂着身份标识。
 * @param growth   作者等级展示（V1.4.0 ① Story 3.1 · AD-14）；已注销 / 已停用 / 不展示等级的账号为 null
 */
public record AuthorView(long userId, String nickname, String avatarUrl, boolean deleted,
        List<UserTagView> tags, GrowthBadge growth) {

    /** 与加 {@code growth} 之前完全相同的 5 参构造器（{@code growth = null}），既有调用点不改也能编译。 */
    public AuthorView(long userId, String nickname, String avatarUrl, boolean deleted, List<UserTagView> tags) {
        this(userId, nickname, avatarUrl, deleted, tags, null);
    }

    public static AuthorView anonymized(long userId) {
        return new AuthorView(userId, null, null, true, List.of());
    }

    /** 挂上标签的副本（作者投影是 record，批量取完标签后统一贴上去）。🔴 {@code growth} 原样带过去。 */
    public AuthorView withTags(List<UserTagView> tags) {
        return new AuthorView(userId, nickname, avatarUrl, deleted,
                tags == null ? List.of() : tags, growth);
    }

    /** 挂上等级展示的副本（Story 3.1：{@code basicViews} 整批取完后统一贴上去）。 */
    public AuthorView withGrowth(GrowthBadge growth) {
        return new AuthorView(userId, nickname, avatarUrl, deleted, tags, growth);
    }

    // ---- Story 3.1：拍平 DTO 用的三字段（已注销 / 无等级 → 全部 null，NON_NULL 下键整体消失） ----
    // 方法名不带 get 前缀：直接序列化 AuthorView 的地方（PlaceDetailResponse.markedBy）不会多出这三个键。

    /** 展示等级；无 → null。 */
    public Integer badgeLevel() {
        return deleted || growth == null ? null : growth.level();
    }

    /** 段位代码（{@code GrowthTier} 名）；无 → null。 */
    public String badgeTier() {
        return deleted || growth == null || growth.tier() == null ? null : growth.tier().name();
    }

    /** 生效头像框代码（BASIC / UPGRADED / ANIMATED）；无框 → null。 */
    public String badgeFrame() {
        return deleted || growth == null || growth.frame() == null ? null : growth.frame().name();
    }
}
