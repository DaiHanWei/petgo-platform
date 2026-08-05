package com.tailtopia.shared.async;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 启用 {@code @Async} + {@code @Scheduled}（架构护栏：异步只用 {@code @Async} + DB 状态机定时重扫，
 * 禁引入 MQ/中间件）。Story 2.8 名片 OG 图重渲染用 @Async；Story 5.6 评分门 30min 超时关闭用 @Scheduled 扫描。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * 埋点专用执行器（V1.1.2 code-review 2026-08-04）。
     *
     * <p><b>为什么不能与业务 {@code @Async} 共池</b>：默认的 {@code applicationTaskExecutor}
     * 同时承担里程碑自动完成、达成通知、名片重渲染、注销级联等业务异步，且队列无界。
     * 出网上报一旦遇到对端慢或半开连接，就会把线程逐个挂住 → 里程碑落库与通知整体停摆、
     * 队列无上限增长。埋点文档 §8.4 的原话是「丢几条事件可以接受，拖慢里程碑落库不行」，
     * 共池恰好造成后者。
     *
     * <p><b>有界 + 满则丢弃</b>：埋点是可损数据。队列满时 {@link ThreadPoolExecutor.DiscardPolicy}
     * 直接丢事件，绝不回压到调用方线程（{@code CallerRunsPolicy} 会把阻塞传染回业务线程，
     * 正是要避免的东西）。配合客户端侧的 3s 超时，最坏情况有明确上界。
     */
    @Bean("analyticsExecutor")
    public TaskExecutor analyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("analytics-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
