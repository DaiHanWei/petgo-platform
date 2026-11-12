package com.tailtopia.analytics.store;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 明细层生产标记判定（analytics-pipeline Story 1.6 AC8，架构 A5）。无状态、无 IO、无 Spring。
 *
 * <h2>上下文（App 事件，键名与 Story 2.5 契约一致，扁平 camelCase）</h2>
 * 任一成立即<b>非生产</b>：
 * <ul>
 *   <li>{@code isProduction == false}（App 自判）；</li>
 *   <li>{@code isPhysicalDevice == false}（模拟器）；</li>
 *   <li>{@code installSource} 为 {@code simulator} / {@code testflight} / {@code sideload}（商店为 {@code store}）。</li>
 * </ul>
 * 上述键全部缺失视为生产（已知偏差）。{@code isProduction == true} 时仍检查其余键：自相矛盾时宁可少算生产。
 * 服务端 / H5 事件没有设备上下文，只按用户判定。
 *
 * <h2>用户（业务库 {@code users}，按批窄查询）</h2>
 * 有 {@code user_id} 时：{@code role != 'USER'} 或 {@code account_type != 'REAL'} → 非生产；业务库查不到该用户 → 非生产
 * （{@link Verdict#UNKNOWN_USER}，调用方计数）。
 */
public final class ProductionFlag {

    /** 业务库 {@code users} 里判定用的两列。 */
    public record UserAccount(String role, String accountType) {
    }

    /** 判定结果。 */
    public enum Verdict {
        PRODUCTION,
        NON_PRODUCTION,
        /** 有 {@code user_id} 但业务库查不到：记为非生产并计数。 */
        UNKNOWN_USER;

        public boolean isProduction() {
            return this == PRODUCTION;
        }
    }

    static final Set<String> NON_PRODUCTION_INSTALL_SOURCES = Set.of("simulator", "testflight", "sideload");

    private ProductionFlag() {
    }

    /**
     * @param context 事件上下文（可空 / 非对象按空）
     * @param userId  校验后的内部用户 id，可空
     * @param users   本批用户的角色与账号类型（按 id）
     */
    public static Verdict judge(JsonNode context, Long userId, Map<Long, UserAccount> users) {
        if (contextSaysNonProduction(context)) {
            return Verdict.NON_PRODUCTION;
        }
        if (userId == null) {
            return Verdict.PRODUCTION;
        }
        UserAccount u = users.get(userId);
        if (u == null) {
            return Verdict.UNKNOWN_USER;
        }
        return "USER".equals(u.role()) && "REAL".equals(u.accountType())
                ? Verdict.PRODUCTION : Verdict.NON_PRODUCTION;
    }

    /** 上下文里是否有模拟器 / TestFlight / 侧载 / App 自判非生产的标记。 */
    static boolean contextSaysNonProduction(JsonNode context) {
        if (context == null || !context.isObject()) {
            return false;
        }
        if (isFalse(context.get("isProduction")) || isFalse(context.get("isPhysicalDevice"))) {
            return true;
        }
        JsonNode source = context.get("installSource");
        return source != null && source.isString()
                && NON_PRODUCTION_INSTALL_SOURCES.contains(source.stringValue().trim().toLowerCase(Locale.ROOT));
    }

    /** 布尔 false，或字符串 {@code "false"}（容错）。缺失 / null / 其他类型都不算。 */
    private static boolean isFalse(JsonNode v) {
        if (v == null) {
            return false;
        }
        if (v.isBoolean()) {
            return !v.booleanValue();
        }
        return v.isString() && "false".equalsIgnoreCase(v.stringValue().trim());
    }
}
