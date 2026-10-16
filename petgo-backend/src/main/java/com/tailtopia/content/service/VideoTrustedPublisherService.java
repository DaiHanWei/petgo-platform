package com.tailtopia.content.service;

/**
 * 视频免审名单只读端口（专题线 video · Story 1.1 / 1.3，决策 V-25）。**content 模块定义、admin.usermgmt 提供实现 bean**
 * —— content 只依赖本接口，不反向依赖 admin 包（同 {@link ManualReviewGate}）。
 *
 * <p>🔴 免审<b>只认这份名单</b>：不要用 {@code AccountQueryService.isOfficialAccount} 或 {@code role} 判断 ——
 * 1.3.4 的「官方号」是 role = ADMIN 的后台作者 shim，不登录 App，与这里的官方号不是一回事。
 */
public interface VideoTrustedPublisherService {

    /** 该用户是否在视频免审名单内。 */
    boolean isTrusted(long userId);
}
