package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.MilestoneLevel;
import com.tailtopia.profile.event.AccountMilestoneCompletedEvent;
import com.tailtopia.profile.event.MilestoneCompletedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 里程碑达成埋点订阅（V1.1.2 Story 6.1 · T-12）。事件名 {@code milestone_achieved}。
 *
 * <p>为什么挂在既有领域事件上而不是塞进 {@code MilestoneCompletionService}：那个服务是
 * 「幂等写入 + 不可撤销」的核心，只发一次事件已经天然满足「每次**新**达成才上报一次」——
 * 往里再插一段分析代码只会让核心逻辑变脏，也容易被后来人误改成重复上报。
 *
 * <p>{@code @TransactionalEventListener}：**提交后**才上报。否则事务回滚了看板上却多一条达成，
 * 数据比没有更糟。
 *
 * <p><b>本监听器不加 {@code @Async}</b>（code-review 2026-08-04）：{@code AnalyticsClient.captureForUser}
 * 只是内存非阻塞入队（analytics-pipeline Story 1.9 起写自建埋点接收队列），调用立刻返回、不抛异常，
 * 不阻塞提交链路。
 *
 * <p>属性只有三个受控值（code / level / path），无 PII、无健康内容 —— 里程碑 code 本身
 * （如 {@code C-M3}）不含用户数据。
 */
@Component
public class MilestoneAnalyticsListener {

    /**
     * 事件名。提成常量供 {@code AnalyticsEventGuard} 的白名单引用（Story 1-2）——
     * 白名单抄一份字面量的话，改了这里编译照过、测试照绿，线上这条埋点却当场停掉。
     */
    public static final String EVENT_MILESTONE_ACHIEVED = "milestone_achieved";

    private final AnalyticsClient analytics;

    public MilestoneAnalyticsListener(AnalyticsClient analytics) {
        this.analytics = analytics;
    }

    @TransactionalEventListener
    public void onMilestoneCompleted(MilestoneCompletedEvent event) {
        analytics.captureForUser(
                event.ownerId(),
                EVENT_MILESTONE_ACHIEVED,
                Map.of(
                        "code", event.code(),
                        "level", event.level().name(),
                        "path", MilestoneAnalyticsPath.of(event.code(), event.source())));
    }

    /**
     * 账号级里程碑（V1.4.2 Story 2.1，拍板点 2）：按<b>去重后的物种编码</b>各报一次（1 猫 1 狗 → C-S19、D-S19 两条；
     * 2 猫 → 1 条），既保留「新编码自动覆盖」，又不按宠物数放大。无 ACTIVE 宠物（codes 为空）不报。
     */
    @TransactionalEventListener
    public void onAccountMilestone(AccountMilestoneCompletedEvent event) {
        for (String code : new LinkedHashSet<>(event.codes())) {
            analytics.captureForUser(
                    event.userId(),
                    EVENT_MILESTONE_ACHIEVED,
                    Map.of(
                            "code", code,
                            "level", MilestoneLevel.S.name(),
                            "path", MilestoneAnalyticsPath.of(code, MilestoneCompletionSource.SYSTEM_AUTO)));
        }
    }
}
