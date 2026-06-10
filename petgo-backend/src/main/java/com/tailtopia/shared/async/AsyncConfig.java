package com.tailtopia.shared.async;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 {@code @Async} + {@code @Scheduled}（架构护栏：异步只用 {@code @Async} + DB 状态机定时重扫，
 * 禁引入 MQ/中间件）。Story 2.8 名片 OG 图重渲染用 @Async；Story 5.6 评分门 30min 超时关闭用 @Scheduled 扫描。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
}
