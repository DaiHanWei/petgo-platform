package com.tailtopia.notify.ops.service;

import java.time.Duration;

/**
 * 发送过程中的等待（批间隔 / 重试退避），可替换以便单测<b>不真睡</b>（V1.3.4 Story 6.2 · AC3.7）。
 *
 * <p>生产实现 {@link #THREAD_SLEEP}：{@code Thread.sleep}；被中断时<b>恢复中断标志</b>后返回，
 * 调用方（dispatcher）据 {@code Thread.currentThread().isInterrupted()} 停止发送。
 */
@FunctionalInterface
public interface OpsDispatchPacer {

    void sleep(Duration d);

    OpsDispatchPacer THREAD_SLEEP = d -> {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };
}
