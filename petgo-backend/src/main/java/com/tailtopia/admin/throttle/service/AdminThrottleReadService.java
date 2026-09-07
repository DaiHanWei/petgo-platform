package com.tailtopia.admin.throttle.service;

import com.tailtopia.admin.throttle.dto.ThrottleStatusRow;
import com.tailtopia.moderation.throttle.domain.RankThrottle;
import com.tailtopia.moderation.throttle.domain.ThrottleScope;
import com.tailtopia.moderation.throttle.repository.RankThrottleRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台限流状态读服务（V1.1.6 Story 17.2 · AC3）。
 *
 * <p>🔴 <b>整页一次取</b>：列表页每行都要显示限流状态，逐行查就是 N+1
 * （同一份教训 14.1 的物种推导已经吃过一次）。
 *
 * <p>⚠️ 只读、只服务后台。用户侧任何接口都不该调它 —— 17.1 的 AC3 明令限流对用户不可见。
 */
@Service
public class AdminThrottleReadService {

    private final RankThrottleRepository throttles;

    public AdminThrottleReadService(RankThrottleRepository throttles) {
        this.throttles = throttles;
    }

    /**
     * 内容列表用：内容 id → 当前生效的限流状态（没有就不在 Map 里）。
     *
     * <p>同一条内容既有内容级又有账号级时，展示<b>内容级</b>那条 ——
     * 运营在内容列表上要解除的通常就是这一条；账号级的解除入口在工单页。
     */
    @Transactional(readOnly = true)
    public Map<Long, ThrottleStatusRow> forPosts(Collection<Long> postIds,
            Map<Long, Long> postToAuthor, Instant now) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> authorIds = new LinkedHashSet<>(postToAuthor.values());
        if (authorIds.isEmpty()) {
            authorIds = Set.of(-1L); // in () 需要非空集合
        }
        Map<Long, ThrottleStatusRow> byPost = new HashMap<>();
        Map<Long, ThrottleStatusRow> byAuthor = new HashMap<>();
        for (RankThrottle t : throttles.findCandidates(ThrottleScope.POST, postIds,
                ThrottleScope.ACCOUNT, authorIds)) {
            if (!t.isActiveAt(now)) {
                continue;
            }
            (t.getScope() == ThrottleScope.POST ? byPost : byAuthor)
                    .put(t.getTargetId(), row(t));
        }
        Map<Long, ThrottleStatusRow> out = new HashMap<>();
        for (Long postId : postIds) {
            ThrottleStatusRow r = byPost.get(postId);
            if (r == null) {
                r = byAuthor.get(postToAuthor.get(postId));
            }
            if (r != null) {
                out.put(postId, r);
            }
        }
        return out;
    }

    /** 工单页用：账号 id → 当前生效的账号级限流（没有就不在 Map 里）。 */
    @Transactional(readOnly = true)
    public Map<Long, ThrottleStatusRow> forAccounts(Collection<Long> userIds, Instant now) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ThrottleStatusRow> out = new HashMap<>();
        for (RankThrottle t : throttles.findCandidates(ThrottleScope.POST, Set.of(-1L),
                ThrottleScope.ACCOUNT, userIds)) {
            if (t.isActiveAt(now)) {
                out.put(t.getTargetId(), row(t));
            }
        }
        return out;
    }

    private static ThrottleStatusRow row(RankThrottle t) {
        return new ThrottleStatusRow(t.getId(), t.getScope(), t.getTargetId(), t.getDuration(),
                t.getExpiresAt());
    }
}
