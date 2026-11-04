package com.tailtopia.social.read;

import java.util.Collection;
import java.util.Set;

/**
 * 追更宠物关系<b>只读端口</b>（V1.4.2 social-topic · Story 1.1 · P-4）。
 *
 * <p><b>模块边界</b>同 {@link FollowReader}：{@code content}（1.3 关注流）/ {@code profile}（推荐、访客）/
 * {@code auth}（主页）/ {@code notify}（1.2）只依赖本接口，<b>禁止引用 {@code social.repository}</b>。
 *
 * <p><b>无缓存</b>：每次查库，禁止为其引入 Redis 或本地缓存（架构护栏）。
 */
public interface PetFollowReader {

    /** viewer 是否追更了这只宠物。 */
    boolean isFollowing(long viewerId, long petId);

    /** viewer 在这批宠物里追更了哪些 —— 一次查询；空入参返回空集、不查库。 */
    Set<Long> followedAmong(long viewerId, Collection<Long> petIds);

    /** 某用户追更的宠物数（注销后其追更行已清空 → 0）。 */
    long countFollowingOf(long userId);
}
