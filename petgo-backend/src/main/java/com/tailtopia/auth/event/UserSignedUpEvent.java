package com.tailtopia.auth.event;

/**
 * 用户首次建号完成（Google/Apple 首登，2026-08-03「建号即注册 IM」策略的事件化载体）。
 *
 * <p>登录事务 AFTER_COMMIT 后由 {@link com.tailtopia.shared.im.ImAccountRegistrationListener}
 * 异步消费——腾讯 IM REST（连接/读取各 5s 超时）绝不允许留在登录事务内钉住 DB 连接
 * （PR#34 finding #6）。
 *
 * @param userId   新用户 id（持久化后必有）
 * @param nickname 昵称初值（可空/可 blank，监听侧兜底「用户<id>」占位）
 */
public record UserSignedUpEvent(long userId, String nickname) {
}
