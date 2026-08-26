package com.tailtopia.content.rank;

/**
 * 推荐序缓存的键空间（V1.1.6 Story 16.1 · AC4）。
 *
 * <p>登录用户按 userId，未登录游客按<b>客户端生成的匿名会话 id</b>。
 * 🛡 <b>曝光衰减对游客不生效</b> —— 匿名会话 id 只在一次会话内有效，没有跨会话曝光记录可言；
 * 硬要按它记曝光，只会得到「同一台设备换个会话就全忘」的假记录。序列快照对游客照常生效
 * （它解决的是同一次会话内的翻页重复，与跨会话无关）。
 *
 * @param namespace Redis 键中间段，已规范化
 * @param guest     是否游客（决定曝光记录是否生效）
 */
public record FeedRankCacheKey(String namespace, boolean guest) {

    /** 匿名会话 id 允许的最大长度 —— 超出即截断。 */
    static final int MAX_ANON_LENGTH = 64;

    public static FeedRankCacheKey forUser(long userId) {
        return new FeedRankCacheKey("u:" + userId, false);
    }

    /**
     * 游客键空间。
     *
     * <p>🔴 <b>匿名会话 id 是客户端传来的字符串，直接拼进 Redis 键是注入面</b>：
     * 冒号会伪造出别人的键空间（{@code a:x} + {@code ":u:1"} 落到 {@code feed:seen:a:x:u:1}），
     * 超长串能把内存刷爆。所以只保留 {@code [A-Za-z0-9_-]}、截断到 {@value #MAX_ANON_LENGTH}。
     * ⚠️ 规范化后为空（例如整串都是冒号）→ 归入一个共享的兜底键空间，
     * <b>而不是抛错</b>：游客刷首页不该因为客户端传了个怪串就 500。
     * 兜底键空间会被所有这类游客共用，序列快照因此可能互相覆盖 —— 只影响翻页体验，可接受。
     */
    public static FeedRankCacheKey forGuest(String anonSessionId) {
        String cleaned = anonSessionId == null ? "" : anonSessionId.replaceAll("[^A-Za-z0-9_-]", "");
        if (cleaned.length() > MAX_ANON_LENGTH) {
            cleaned = cleaned.substring(0, MAX_ANON_LENGTH);
        }
        return new FeedRankCacheKey("a:" + (cleaned.isEmpty() ? "anon" : cleaned), true);
    }

    /** 登录则按用户、否则按匿名会话（调用方统一入口，免得两边各判一次 null）。 */
    public static FeedRankCacheKey of(Long viewerId, String anonSessionId) {
        return viewerId == null ? forGuest(anonSessionId) : forUser(viewerId);
    }
}
