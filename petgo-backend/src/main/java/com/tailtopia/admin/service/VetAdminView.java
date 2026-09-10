package com.tailtopia.admin.service;

import com.tailtopia.vet.domain.VetAccount;
import java.time.Instant;

/**
 * Admin 后台兽医列表行视图（Story 5.1；2.2 扩资质/在线/均分列）。绝不含 {@code passwordHash}。
 *
 * <p>{@code username} 即登录邮箱/账号；{@code qualStatus} 为 2.1 资质 6 态名（null=简单视图未取）；
 * {@code presence} 为 ONLINE/BUSY/OFFLINE；{@code ratingAvg} 均分（无评分为 null，显示「—」）。
 *
 * <p>{@code lastSeenAt}（V1.3.0 Story 9.1a）：最后在线时刻，来自在线集合的 score。
 * 🔴 **离线兽医没有这个值**（下线即被移出集合，不是「记着上次什么时候在」）——
 * 所以 null 的含义是「当前不在线」，不是「数据缺失」，界面显示「—」。
 * 新字段追加在**末尾**：既有模板按名字取值，但 record 的构造器是位置参数，插在中间会静默改变含义。
 *
 * <p>{@code ratedCount} / {@code totalVolume}（V1.3.0 Story 9.1b）：已评数与总问诊量。
 * 🔴 摆出来是因为列表支持「按问诊量排序」—— 一个**看不见的数**上的排序，运营没法判断
 * 结果对不对。两个数与均分同源（都来自评分总览的同一次聚合），且都跟随
 * 「评价时间段」筛选，与均分口径一致。
 */
public record VetAdminView(long id, String username, String displayName, String status,
        Instant createdAt, String qualStatus, String presence, Double ratingAvg, String avatarUrl,
        Instant lastSeenAt, int ratedCount, int totalVolume) {

    /** 简单视图（仅账号字段，资质/在线/均分留空）——供单兽医详情/评分页导航等无需附加列处。 */
    public static VetAdminView from(VetAccount v) {
        return new VetAdminView(v.getId(), v.getUsername(), v.getDisplayName(),
                v.getStatus().name(), v.getCreatedAt(), null, null, null, v.getAvatarUrl(), null, 0, 0);
    }

    /** 列表完整视图（2.2）：资质 + 在线 + 均分（9.1a 起含最后在线，9.1b 起含已评数 / 总量）。 */
    public static VetAdminView of(VetAccount v, String qualStatus, String presence, Double ratingAvg,
            Instant lastSeenAt, int ratedCount, int totalVolume) {
        return new VetAdminView(v.getId(), v.getUsername(), v.getDisplayName(),
                v.getStatus().name(), v.getCreatedAt(), qualStatus, presence, ratingAvg,
                v.getAvatarUrl(), lastSeenAt, ratedCount, totalVolume);
    }
}
