package com.tailtopia.moderation.throttle.service;

import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.moderation.throttle.domain.RankThrottle;
import com.tailtopia.moderation.throttle.domain.ThrottleDuration;
import com.tailtopia.moderation.throttle.domain.ThrottleScope;
import com.tailtopia.moderation.throttle.repository.RankThrottleRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 限流（降权）读写服务（V1.1.6 Story 17.1）。
 *
 * <h2>读侧：{@link #factorsFor}</h2>
 * 供推荐序打分链路调用，接上 16.2 留的「限流系数」入口（AC6）。
 * 🛡 <b>无限流记录 ⇒ 该内容不出现在返回的 Map 里 ⇒ 引擎按缺省 1.0 处理</b>，
 * 而不是在这里回填一堆 1.0（回填等于把「没有处置」和「处置成 1.0」混成一件事）。
 *
 * <h2>🛡 AC3：本类不发通知</h2>
 * 刻意<b>不注入任何通知/推送依赖</b> —— 让「限流不通知用户」在结构上成立。
 * ⚠️ 「警告」处置是要发通知的，别把它那条路径搬过来。
 *
 * <h2>⚠️ AC7：分类 Tab 无处施加</h2>
 * 分类 Tab 走纯时间倒序、不打分，所以限流在那里<b>没有作用点</b>。
 * 🛡 不要因此把限流补成一个过滤器 —— 那就变成下架了（AC2）。
 * 界面上如何提示运营这件事归 17-2。
 */
@Service
public class RankThrottleService {

    private final RankThrottleRepository throttles;
    private final PlatformConfigService platformConfig;

    public RankThrottleService(RankThrottleRepository throttles,
            PlatformConfigService platformConfig) {
        this.throttles = throttles;
        this.platformConfig = platformConfig;
    }

    /** 打分链路的一条候选：内容 id + 作者 id。账号级限流靠 authorId 展开到逐条。 */
    public record Target(long postId, long authorId) {
    }

    /**
     * 批量取限流系数：内容 id → 系数。**只包含真的被限流的**（AC6：无记录恒 1.0）。
     *
     * <p>🔴 账号级限流在这里展开成逐条系数（AC1）：某作者被限流 ⇒ 他的每一条候选都拿到系数，
     * 包含限流期内新发的 —— 新内容不需要任何回填就自动受限，因为展开发生在**每次打分时**。
     *
     * <p>同一条内容同时命中「内容级」和「账号级」时，取<b>一份</b>系数而不是相乘：
     * 系数是平台级配置的处置强度（AC5），不是可叠加的惩罚值。相乘会让 0.2 变成 0.04，
     * 那是配置里根本调不出来、也没人想要的强度。
     */
    @Transactional(readOnly = true)
    public Map<Long, Double> factorsFor(List<Target> targets, Instant now) {
        if (targets.isEmpty()) {
            return Map.of();
        }
        Set<Long> postIds = new LinkedHashSet<>();
        Set<Long> authorIds = new LinkedHashSet<>();
        for (Target t : targets) {
            postIds.add(t.postId());
            authorIds.add(t.authorId());
        }

        Set<Long> throttledPosts = new LinkedHashSet<>();
        Set<Long> throttledAuthors = new LinkedHashSet<>();
        for (RankThrottle t : throttles.findCandidates(ThrottleScope.POST, postIds,
                ThrottleScope.ACCOUNT, authorIds)) {
            if (!t.isActiveAt(now)) {
                continue; // 已到期或已手动解除 ⇒ 不生效（AC4，无残留）
            }
            if (t.getScope() == ThrottleScope.POST) {
                throttledPosts.add(t.getTargetId());
            } else {
                throttledAuthors.add(t.getTargetId());
            }
        }
        if (throttledPosts.isEmpty() && throttledAuthors.isEmpty()) {
            return Map.of();
        }

        double factor = platformConfig.feedRank().getThrottleFactor();
        Map<Long, Double> out = new HashMap<>();
        for (Target t : targets) {
            if (throttledPosts.contains(t.postId()) || throttledAuthors.contains(t.authorId())) {
                out.put(t.postId(), factor);
            }
        }
        return out;
    }

    /** 单条内容限流（AC1）。 */
    @Transactional
    public RankThrottle throttlePost(long postId, ThrottleDuration duration, Instant now,
            Long operatorId, Long reportId, String reason) {
        return throttle(ThrottleScope.POST, postId, duration, now, operatorId, reportId, reason);
    }

    /** 账号级限流（AC1）：作用于该账号全部已发布内容，含限流期内新发的。 */
    @Transactional
    public RankThrottle throttleAccount(long userId, ThrottleDuration duration, Instant now,
            Long operatorId, Long reportId, String reason) {
        return throttle(ThrottleScope.ACCOUNT, userId, duration, now, operatorId, reportId, reason);
    }

    private RankThrottle throttle(ThrottleScope scope, long targetId, ThrottleDuration duration,
            Instant now, Long operatorId, Long reportId, String reason) {
        // 已有生效中的同目标限流 ⇒ 先解除旧的再记新的，而不是留两行同时生效。
        // 两行并存时「解除」只解一行，运营看到状态还是限流中，会以为解除按钮坏了。
        for (RankThrottle existing : throttles
                .findByScopeAndTargetIdOrderByCreatedAtDesc(scope, targetId)) {
            if (existing.isActiveAt(now) && operatorId != null) {
                existing.lift(now, operatorId);
            }
        }
        return throttles.save(RankThrottle.create(scope, targetId, duration, now, operatorId,
                reportId, reason));
    }

    /**
     * 手动提前解除（AC4）。
     *
     * <p>🛡 解除后系数立即回 1.0：{@link #factorsFor} 每次都重新判定
     * {@link RankThrottle#isActiveAt}，没有缓存也没有需要清理的派生状态。
     */
    @Transactional
    public boolean lift(long throttleId, Instant now, long adminId) {
        Optional<RankThrottle> found = throttles.findById(throttleId);
        if (found.isEmpty() || !found.get().isActiveAt(now)) {
            return false;
        }
        found.get().lift(now, adminId);
        return true;
    }

    /** 后台列表（17-2 用）：某目标的限流历史，最近在前。 */
    @Transactional(readOnly = true)
    public List<RankThrottle> history(ThrottleScope scope, long targetId) {
        return throttles.findByScopeAndTargetIdOrderByCreatedAtDesc(scope, targetId);
    }
}
