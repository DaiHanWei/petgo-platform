package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.MilestoneAutoEvent;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时类里程碑自动完成（Story 8.3，FR-42）。**护栏**：Spring 原生 {@code @Scheduled} 每日扫描
 * （同 6.7 定时推送范式 / 决策 F5）—— **禁 Quartz/MQ/新中间件**。当前仅一类：
 * <b>C-M8/D-M8/G-M3 陪伴满 30 天</b>（{@code created_at + 30d} 已到）→ 幂等完成（SYSTEM_AUTO）。
 *
 * <p>陪伴 100/365 天为 L 级 PUSH_PUBLISH（用户当天发布触发，非此自动），不在此处。
 * 计数类（成长日历满 10/30）在发布事件即时判定（{@link MilestoneAutoCompleteListener}），非定时。
 *
 * <p>≤500 DAU 单机日扫足够；已完成项经唯一约束短路（{@link MilestoneCompletionService} 幂等），无重复写。
 */
@Component
public class MilestoneScheduledCompleter {

    private static final Logger log = LoggerFactory.getLogger(MilestoneScheduledCompleter.class);

    private static final Duration COMPANION_30 = Duration.ofDays(30);

    private final PetProfileRepository profiles;
    private final MilestoneCompletionService completion;

    public MilestoneScheduledCompleter(PetProfileRepository profiles,
            MilestoneCompletionService completion) {
        this.profiles = profiles;
        this.completion = completion;
    }

    /** 每日 01:10 UTC 扫描（错开 6.7 的 01:00 推送扫描）。 */
    @Scheduled(cron = "${petgo.milestone.companion-scan.cron:0 10 1 * * *}", zone = "UTC")
    public void completeCompanionThirtyDays() {
        Instant threshold = Instant.now().minus(COMPANION_30);
        int newlyCompleted = 0;
        for (PetProfile pet : profiles.findByCreatedAtLessThanEqual(threshold)) {
            // 物种差异（猫狗 M8 / 通用 G-M3）本就在这条路径上被正确处理过，V1.3.0 Story 1.1 只是把它
            // 从这里的局部 switch 收进 MilestoneAutoCompleteMap —— 行为一字不变，但从此与其余自动
            // 路径共用同一张表，不会再出现「某一条路径单独漏改」的分叉。
            if (completion.complete(pet.getId(), pet.getPetType(),
                    MilestoneAutoEvent.COMPANION_30_DAYS,
                    MilestoneCompletionSource.SYSTEM_AUTO, null)) {
                newlyCompleted++;
            }
        }
        if (newlyCompleted > 0) {
            log.info("companion-30 milestone scan: {} newly completed", newlyCompleted);
        }
    }
}
