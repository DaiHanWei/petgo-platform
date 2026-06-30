package com.tailtopia.content.service;

/**
 * 人工审核出站端口（Story 4.3，AB-3C）。**content 模块定义、admin.moderation 提供实现 bean**——
 * content 只依赖本接口，不反向依赖 admin 包（保持模块依赖方向）。
 *
 * <p>V1.0.0 默认 {@link #enabled()} 返回 false（{@code admin_settings.manual_review_enabled=false}），
 * 发布走现网 FR-12 路径（自动审核拦截即 throw）；开关打开后未过审内容改为落 {@code UNDER_REVIEW} + 入队挂起。
 */
public interface ManualReviewGate {

    /** 人工审核是否已激活（读 admin_settings 单行开关，缺省 false）。 */
    boolean enabled();

    /** 将一条挂起内容入人工审核队列（PENDING）。仅在 {@link #enabled()} 为 true 的挂起路径调用。 */
    void enqueue(long contentId);
}
