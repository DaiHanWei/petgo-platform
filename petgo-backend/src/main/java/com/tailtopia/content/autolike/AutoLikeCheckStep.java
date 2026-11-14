package com.tailtopia.content.autolike;

import com.tailtopia.content.autolike.AutoLikeTargetRepository.CheckCandidate;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自动点赞一轮计划里的「首轮判定 + 关闭 + 检查」三步（V1.5.0 Story 2.1，FR-213 / FR-215 / FR-221，架构 L-8 第 3–5 步）。
 *
 * <p>要求调用方已开启事务（计划事务，持 advisory 锁，Story 2.2）。所有年龄都按 {@code runStart}（本轮开始时刻）算。
 * <ol>
 *   <li>首轮 = 目标集表一行都没有（含已离开的）。<b>必须先判</b>，再关闭、再检查；</li>
 *   <li>关闭：超龄 → {@code MAX_AGE}；失败次数到上限 → {@code TOO_MANY_FAILURES}（Q-4：在下一轮开始时统一关）；</li>
 *   <li>检查：发帖 {@code [checkWindowMin, checkWindowMax)}（首轮上界放宽到 {@code maxPostAge}）、尚未检查过的真人帖，
 *       逐条：作者在对照组 → {@code HOLDOUT}；赞数已达标 → {@code AT_TARGET_ON_CHECK}；否则进集合。</li>
 * </ol>
 */
@Component
public class AutoLikeCheckStep {

    private final AutoLikeTargetRepository targets;
    private final AutoLikeHoldout holdout;
    private final AutoLikeProperties props;

    public AutoLikeCheckStep(AutoLikeTargetRepository targets, AutoLikeHoldout holdout, AutoLikeProperties props) {
        this.targets = targets;
        this.holdout = holdout;
        this.props = props;
    }

    /** 一轮检查的结果计数。 */
    public record CheckResult(boolean launch, int checked, int joined, int closedMaxAge, int closedFailures) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CheckResult run(Instant runStart) {
        // 1. 首轮判定：必须在关闭与检查之前（否则本轮写入的行会让它永远为 false）。
        boolean launch = !targets.existsAny();

        // 2. 关闭
        int closedMaxAge = targets.closeMaxAge(runStart, runStart.minus(props.getMaxPostAge()));
        int closedFailures = targets.closeTooManyFailures(runStart, props.getMaxFailures());

        // 3. 检查
        Instant minCut = minCut(runStart);
        Instant maxCut = maxCut(runStart, launch);
        List<CheckCandidate> candidates = targets.findCheckCandidates(minCut, maxCut);
        int checked = 0;
        int joined = 0;
        for (CheckCandidate c : candidates) {
            AutoLikeLeaveReason reason = decide(c.authorId(), c.likeCount());
            if (targets.insertCheck(c, runStart, reason) == 1) {
                checked++;
                if (reason == null) {
                    joined++;
                }
            }
        }
        return new CheckResult(launch, checked, joined, closedMaxAge, closedFailures);
    }

    /** 入表决策：{@code null} = 进集合；否则为不进集合的原因。对照组优先于赞数（A-AC47）。 */
    AutoLikeLeaveReason decide(long authorId, long likeCount) {
        if (holdout.isHoldout(authorId)) {
            return AutoLikeLeaveReason.HOLDOUT;
        }
        if (likeCount >= props.getLikeTarget()) {
            return AutoLikeLeaveReason.AT_TARGET_ON_CHECK;
        }
        return null;
    }

    /** 发帖时间上限（含）：满 {@code checkWindowMin} 才检查。 */
    Instant minCut(Instant runStart) {
        return runStart.minus(props.getCheckWindowMin());
    }

    /** 发帖时间下限（开区间）：普通轮 {@code checkWindowMax}，首轮 {@code maxPostAge}。 */
    Instant maxCut(Instant runStart, boolean launch) {
        return runStart.minus(launch ? props.getMaxPostAge() : props.getCheckWindowMax());
    }
}
