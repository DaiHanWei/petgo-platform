package com.tailtopia.shared.analytics;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * H5 对外分享页的<b>匿名访客标识</b>（V1.1.6 Story 1.4 · E-24~E-26）。
 *
 * <p><b>为什么不能用 {@link AnalyticsDistinctId}</b>：那个要 {@code userId}，
 * 而 H5 是<b>无登录态的公开页</b> —— 访客压根没有 userId。
 *
 * <p>🔴 <b>更不能拿 cardToken / 宠物 id 哈希出来充数。</b>看起来"省事又稳定"，
 * 实际是把<b>同一个分享页的所有访客合并成一个人</b>：十个人点开会算成一个，
 * 「打开 → 点 CTA → 跳转结果」这条漏斗从此失真，而且<b>历史数据回不来</b>。
 * 必须是<b>每个访客一个</b>随机值。
 *
 * <p><b>载体是 Cookie</b>（服务端渲染页，服务端要读得到）：
 * {@code HttpOnly}（前端不需要读它 —— 上报走后端接口，由后端从 cookie 取）·
 * {@code SameSite=Lax} · {@code Path=/} · 月级有效期。
 *
 * <p>⚠️ 这个 id <b>不是 PII、也不承诺跨设备</b>：换浏览器、清 cookie 都会变成新访客。
 * 这是匿名统计的固有精度，不要试图用指纹之类的手段"提高准确率"。
 */
public final class AnonymousVisitorId {

    /** Cookie 名。改它会让存量访客全部变成新访客（漏斗断一次），非必要不动。 */
    public static final String COOKIE_NAME = "tt_vid";

    /** 月级有效期：够覆盖一次分享的传播周期，又不至于长到像永久追踪。 */
    private static final int MAX_AGE_SECONDS = 60 * 60 * 24 * 30;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AnonymousVisitorId() {
    }

    /**
     * 取当前访客的匿名 id；没有就<b>新生成并种 cookie</b>。
     *
     * <p>⚠️ 失效页那条路径也要调用它 —— 否则「打开了失效链接」的这批人串不进漏斗，
     * 而「分享出去的链接有多少已经失效」正是要看的数之一。
     */
    public static String resolveOrIssue(HttpServletRequest request, HttpServletResponse response) {
        String existing = readCookie(request);
        if (existing != null) {
            return existing;
        }
        String fresh = generate();
        Cookie cookie = new Cookie(COOKIE_NAME, fresh);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(MAX_AGE_SECONDS);
        cookie.setAttribute("SameSite", "Lax");
        // Secure 跟随当前请求：生产走 HTTPS 时自动带上，本地 http 调试不至于种不进去。
        cookie.setSecure(request.isSecure());
        response.addCookie(cookie);
        return fresh;
    }

    /** 只读，不种 cookie（上报端点用 —— 那时 cookie 应该已经在了）。 */
    public static String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName()) && isWellFormed(c.getValue())) {
                return c.getValue();
            }
        }
        return null;
    }

    /** 32 位十六进制随机串（128 bit）。不可枚举、不由任何业务 id 推导。 */
    private static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 形态校验：只认自己发出去的那种格式。
     * 🛡 防的是有人把任意字符串塞进 cookie 当 distinctId 灌脏数据。
     */
    private static boolean isWellFormed(String value) {
        if (value == null || value.length() != 32) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
