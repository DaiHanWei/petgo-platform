package com.tailtopia.social.read;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 关注关系<b>只读端口</b>（V1.3.4 follow · Story 1.1 · AC5）。
 *
 * <p><b>模块边界</b>同 {@link UserHideRelationReader}：{@code auth} / {@code content} / {@code notify}
 * 只依赖本接口，<b>禁止引用 {@code social.repository}</b>。
 *
 * <p><b>无缓存</b>：每次查库（主键 / 列表索引），禁止为其引入 Redis 或本地缓存（架构护栏）。
 */
public interface FollowReader {

    /** viewer 与 target 之间的关注关系（两个方向）。 */
    FollowState followState(long viewerId, long targetId);

    /**
     * 批量版：viewer 与这批人之间的关注关系 —— <b>一次查询</b>覆盖整批（AD-16）。
     *
     * <p>空集合直接返回空表、不发查询；入参里的每个 id 都有值，缺失的填 {@link FollowState#NONE}。
     */
    Map<Long, FollowState> followStates(long viewerId, Collection<Long> targetIds);

    /**
     * {@code followeeId} 的全部关注者 id（V1.5.0 onboarding-recall Story 5.1 · OR-13：关注更新推送扇出）——<b>一次查询</b>，
     * 不分 {@code source}（手动 / 系统自动关注都算），不过滤有效性（调用方自行批量过滤）。顺序按关注时间升序。
     */
    List<Long> followerIdsOf(long followeeId);
}
