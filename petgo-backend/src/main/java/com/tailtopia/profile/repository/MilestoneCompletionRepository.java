package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.MilestoneCompletion;
import com.tailtopia.profile.service.MilestoneTimelineView;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MilestoneCompletionRepository extends JpaRepository<MilestoneCompletion, Long> {

    List<MilestoneCompletion> findByPetMilestoneIdIn(Collection<Long> petMilestoneIds);

    long countByPetMilestoneIdIn(Collection<Long> petMilestoneIds);

    Optional<MilestoneCompletion> findByPetMilestoneId(long petMilestoneId);

    boolean existsByPetMilestoneId(long petMilestoneId);

    boolean existsByLinkedContentId(long linkedContentId);

    void deleteByPetMilestoneIdIn(Collection<Long> petMilestoneIds);

    /**
     * 「已完成且未庆祝」计数（V1.3.0 Story 1.4 · AD-A1.3 / AD-A2.2）：角标就用这个数。
     *
     * <p>由**成长档案页头部本来就要发的那个请求**顺带下发（那里已经在取「已完成 / 总数」），
     * 不新开接口、不为角标多发一次请求。
     */
    long countByPetMilestoneIdInAndCelebratedAtIsNull(Collection<Long> petMilestoneIds);

    /**
     * 幂等置位「已庆祝」（V1.3.0 Story 1.4 · AC4/AC5 · AD-A3.2）。
     *
     * <p>🔴 <b>两条硬约束都在这一个方法签名里，别拆开看：</b>
     * <ol>
     *   <li>{@code celebratedAt is null} —— 已有值**不覆盖**，保住首次庆祝时刻（幂等）。</li>
     *   <li>{@code petMilestoneId in :ids} —— <b>只置位调用方点名的那些</b>。
     *       <b>绝不能</b>退化成「把这只宠物所有 celebrated_at IS NULL 的都置位」：
     *       客户端从读取列表到回报之间有几百毫秒，这期间完全可能被点赞、被评论而新解锁一条，
     *       mark-all 会把它静默盖章为已庆祝、<b>永不补弹</b> —— 等于用 FR-111 造出 FR-111 要修的 bug
     *       （对抗性评审 H-2 直接指出的洞）。</li>
     * </ol>
     *
     * @return 实际置位的行数（已庆祝过的不计入）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MilestoneCompletion c set c.celebratedAt = :now "
            + "where c.petMilestoneId in :ids and c.celebratedAt is null")
    int markCelebrated(@Param("ids") Collection<Long> petMilestoneIds, @Param("now") Instant now);

    /**
     * 时间线只读视图：该宠物在锚点之前完成的里程碑（Story 3.2 · AC1）。
     *
     * <p>里程碑无独立 event_date —— 有效日期由 {@code completedAt} 的 UTC 日推导、与之单调同序，
     * 故复合锚点在本源上退化为单键上界（见 {@code TimelineAnchor#createdAtUpperBound()}）。
     *
     * <p>与 {@code PetMilestone} 的 join 属**同模块内**联表（都在 profile 域），不违反模块边界；
     * 跨模块（content / consult）一律经各自 service 接口取数。
     */
    @Query("select new com.tailtopia.profile.service.MilestoneTimelineView("
            + "m.code, m.level, c.completedAt, c.linkedContentId) "
            + "from MilestoneCompletion c join PetMilestone m on m.id = c.petMilestoneId "
            + "where m.petProfileId = :petProfileId and c.completedAt < :upperBound "
            + "order by c.completedAt desc")
    List<MilestoneTimelineView> findTimelineViewsBefore(@Param("petProfileId") long petProfileId,
            @Param("upperBound") Instant upperBound, Pageable page);
}
