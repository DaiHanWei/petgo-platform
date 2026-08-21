package com.tailtopia.shared.boundary;

import java.util.regex.Pattern;

/**
 * 🔴🔴 <b>问诊与商品的边界：商品链接策略</b>（Story 9.1，FR-110，<b>安全攸关·约束性需求</b>）。
 *
 * <p><b>decision-log N-3：</b>《战略决策快照》「靠什么赢」第一条是 AI 问诊与本地信任。
 * <b>此约束优先于任何转化率优化</b> —— 问诊一旦被感知为销售前端，护城河即失效，
 * 其损失远大于电商的短期转化收益。
 *
 * <p>兽医<b>可能手工在消息里粘贴商品链接</b>（我们拦不住他打字）。能拦的是<b>渲染</b>：
 * 🔴 <b>这类链接不得被渲染为可点击卡片</b> —— 一条纯文本 URL 和一张带图带价的商品卡，
 * 在用户感知里是两件完全不同的事。
 *
 * <p>⚠️ <b>本类只做「识别 + 降级为纯文本」，不做删改</b>：
 * 删掉兽医写的字会让他以为消息发失败了，然后换个写法再发一次。
 */
public final class ShopLinkPolicy {

    /**
     * 商品链接特征：本站商品详情路径 / 深链。
     *
     * <p>覆盖 {@code /shop/products/<token>}、{@code /api/v1/shop/products/<token>}
     * 与 App 深链 {@code tailtopia://shop/products/<token>}。
     */
    private static final Pattern SHOP_LINK = Pattern.compile(
            "(?i)(?:https?://[^\\s]*|tailtopia://)?/?(?:api/v\\d+/)?shop/products/[A-Za-z0-9_-]+");

    private ShopLinkPolicy() {
    }

    /** 文本里是否含商品链接。 */
    public static boolean containsShopLink(String text) {
        return text != null && SHOP_LINK.matcher(text).find();
    }

    /**
     * 🔴 <b>把商品链接降级为不可点击的纯文本</b>（在链接前后加零宽标记由渲染层识别的做法太脆，
     * 这里直接把 scheme 与斜杠拆掉，使任何自动 linkify 都不再认得它）。
     *
     * <p>⚠️ <b>不删除内容</b>：兽医写的字仍在，用户仍能读懂他想说什么 ——
     * 只是没有那一下「点进去就能买」。
     */
    public static String neutralize(String text) {
        if (text == null) {
            return null;
        }
        return SHOP_LINK.matcher(text).replaceAll(m -> "[tautan produk]");
    }
}
