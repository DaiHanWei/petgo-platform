package com.tailtopia.notify.ops.service;

import java.time.Duration;
import java.util.List;

/**
 * 运营通知运行参数（V1.3.4 Story 6.2 · 决策日志 D-20，<b>写死，不得自定</b>）。
 * 扫描周期另走 {@code petgo.ops-notify.scan-ms}（默认 60000，{@code fixedDelay}）。
 *
 * @param batchSize       每批人数
 * @param batchInterval   批与批之间间隔（最后一批之后不等）
 * @param retryBackoffs   写通知中心失败重试的退避（次数 = 列表长度）；推送失败不重推（bug 604）
 * @param progressEvery   每推送多少人刷新一次 {@code last_progress_at}
 * @param stallTimeout    SENDING 超过多久无进展 → PARTIAL_FAILED（不自动重发）
 * @param scanLimit       一轮到期扫描最多投递多少个活动
 */
public record OpsNotifyParams(
        int batchSize,
        Duration batchInterval,
        List<Duration> retryBackoffs,
        int progressEvery,
        Duration stallTimeout,
        int scanLimit) {

    public static final OpsNotifyParams DEFAULTS = new OpsNotifyParams(
            500,
            Duration.ofSeconds(1),
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)),
            50,
            Duration.ofMinutes(15),
            20);
}
