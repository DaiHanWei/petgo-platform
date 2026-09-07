package com.tailtopia.moderation.throttle.domain;

/**
 * 限流粒度（V1.1.6 Story 17.1 · AC1）。落库 varchar + UPPER_SNAKE。
 *
 * <p>两级是**不同的处置强度**，不是同一件事的两种写法：{@link #POST} 针对一条越线内容，
 * {@link #ACCOUNT} 针对反复越线的人。
 */
public enum ThrottleScope {

    /** 单条内容限流：{@code targetId} 是 {@code content_posts.id}。 */
    POST,

    /**
     * 账号级限流：{@code targetId} 是 {@code users.id}，作用于该账号**全部已发布内容**。
     *
     * <p>🔴 含存量与限流期内新发的（AC1）。覆盖面在**打分时**按作者展开，
     * 所以新发内容自动同样受限——没有「删了重发就绕过」这个口子。
     */
    ACCOUNT
}
