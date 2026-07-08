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

    /**
     * 将一条三方降级挂起评论入人工审核队列（PENDING，{@code content_type=COMMENT}，捕获 {@code contentVersion}）。
     *
     * <p><b>不受 {@link #enabled()} 开关门控</b>：评论降级入队是 fail-closed 安全属性，必须无条件生效
     * （否则降级评论会静默永久挂起或错误放行）。开关仅是 story 2 帖子高风险自动路由的激活位。
     */
    void enqueueComment(long commentId, int contentVersion);
}
