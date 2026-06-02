package com.petgo.shared.async;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 启用 {@code @Async}（架构护栏：异步只用 {@code @Async} + DB 状态机，禁引入 MQ/中间件）。
 * Story 2.8 名片 OG 图重渲染用此异步执行。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
