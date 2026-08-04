package com.tailtopia.profile.service;

import com.tailtopia.profile.event.MilestoneCompletedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
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
 * <p><b>本监听器不加 {@code @Async}</b>（code-review 2026-08-04）：{@code AnalyticsClient.capture}
 * 自己就是 {@code @Async("analyticsExecutor")}，这里再加一层等于每次达成向线程池投两次任务，
 * 第一跳纯粹是转手。去掉后仍然不阻塞提交链路 —— 调用注入的 client 会立刻返回。
 *
 * <p>属性只有三个受控值（code / level / path），无 PII、无健康内容 —— 里程碑 code 本身
 * （如 {@code C-M3}）不含用户数据。
 */
@Component
public class MilestoneAnalyticsListener {

    private final AnalyticsClient analytics;

    public MilestoneAnalyticsListener(AnalyticsClient analytics) {
        this.analytics = analytics;
    }

    @TransactionalEventListener
    public void onMilestoneCompleted(MilestoneCompletedEvent event) {
        analytics.capture(
                AnalyticsDistinctId.of(event.ownerId()),
                "milestone_achieved",
                Map.of(
                        "code", event.code(),
                        "level", event.level().name(),
                        "path", MilestoneAnalyticsPath.of(event.code(), event.source())));
    }
}
