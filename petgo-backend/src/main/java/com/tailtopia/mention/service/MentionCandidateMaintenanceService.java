package com.tailtopia.mention.service;

import com.tailtopia.mention.repository.MentionCandidateRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @ 候选集的**维护**（V1.3.0 batch-b1 Story 3.1 · AC2）。
 *
 * <h2>🔴 AC2 是这条 story 的主体，不是附属</h2>
 * story 标题写的是「候选集」，容易被理解成"写个查询就行"。查询那部分很简单；
 * <b>难的是这张表怎么被喂饱、怎么不撑爆</b>。两条口径定死在这里：
 *
 * <table>
 *   <tr><th>写入时机</th><td><b>异步</b>（{@code @Async} + 既有领域事件，见
 *       {@link MentionCandidateListener}）。评论 / 点赞是热路径，而候选集晚半秒没人看得出来。</td></tr>
 *   <tr><th>裁剪策略</th><td><b>写入时顺手淘汰</b>：每次记完互动就把该 owner 最新
 *       {@value #KEEP_PER_OWNER} 条之外的删掉。表的上界因此是硬的
 *       （行数 ≤ 用户数 × {@value #KEEP_PER_OWNER}，与互动量无关）。</td></tr>
 * </table>
 *
 * <p>🔴 <b>不引任何调度中间件</b>（CLAUDE.md 护栏）。这里连 {@code @Scheduled} 都不需要 ——
 * 定时裁剪意味着「两次扫描之间可以涨到任意大」，还多一个会挂掉的东西。
 *
 * <h2>⚠️ 互动的方向是有讲究的，不要一律双向</h2>
 * PRD §2.3 列的候选口径是「<b>评论过我 / 我评论过 / 赞过我的</b> / 与我互相回复对话过」：
 * <ul>
 *   <li><b>评论是双向的</b>：我评论了他 → 他进我的候选；同时也是「评论过我」→ 我进他的候选。</li>
 *   <li><b>点赞是单向的</b>：只有「赞过我的」，<b>没有「我赞过的」</b>。
 *       所以点赞只把点赞者写进**作者**的候选集，反过来不写。
 *       ⚠️ 顺手写成双向的话，一个只会点赞、从不说话的人的候选集里会塞满他赞过的所有作者 ——
 *       而他跟那些人其实并没有"打过交道"。</li>
 * </ul>
 */
@Service
public class MentionCandidateMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MentionCandidateMaintenanceService.class);

    /**
     * 每人保留多少条。
     *
     * <p>🔴 <b>为什么是 50 而不是正好 30</b>：对外只给 30 人（{@code MentionCandidateQueryService}），
     * 而<b>拉黑排除是在查询时做的</b>。表里正好只存 30 人时，其中 3 个被拉黑，
     * 用户就只剩 27 个候选。留冗余才能保证排除后仍有 30 个可选。
     */
    public static final int KEEP_PER_OWNER = 50;

    private final MentionCandidateRepository candidates;

    public MentionCandidateMaintenanceService(MentionCandidateRepository candidates) {
        this.candidates = candidates;
    }

    /**
     * 记一次**双向**互动（评论 / 回复）：两个人互相进对方的候选集。
     *
     * <p>⚠️ 两行用**同一个时间戳**：同一次互动的两个方向如果差出几微秒，
     * 排序上就成了两个"不同新旧"的人。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMutual(long a, long b, Instant at) {
        recordOneWay(a, b, at);
        recordOneWay(b, a, at);
    }

    /**
     * 记一次**单向**互动：{@code candidateId} 进 {@code ownerId} 的候选集，反之不写。
     *
     * <p>🛡 自己与自己的互动（自评 / 自赞）**直接跳过** —— 库里那条 CHECK 也会拦，
     * 但让它走到数据库再报错等于把一个正常场景变成一次异常。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOneWay(long ownerId, long candidateId, Instant at) {
        if (ownerId == candidateId) {
            return;
        }
        candidates.touch(ownerId, candidateId, at);
        // 写完顺手淘汰。正常情况这一句删 0 行（只有越过 50 的那一次才真删）。
        int trimmed = candidates.trimToNewest(ownerId, KEEP_PER_OWNER);
        if (trimmed > 0 && log.isDebugEnabled()) {
            // ⚠️ 日志只记 id 与条数，**不记昵称**（SLF4J JSON，严禁 PII）。
            log.debug("@候选集裁剪 ownerId={} trimmed={}", ownerId, trimmed);
        }
    }

    /**
     * 注销级联（D1/D2）：把这个人从候选集里**两个方向**都抹掉。
     *
     * <p>🔴 只删 owner 侧的话，他还会继续出现在**别人**的 @ 候选里 ——
     * 一个已注销的账号被 @ 出来，点进去是「用户不存在」。
     */
    @Transactional
    public int purgeUser(long userId) {
        return candidates.deleteAllForUser(userId);
    }
}
