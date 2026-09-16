package com.tailtopia.shop.service;

import com.tailtopia.shared.error.AppException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * C 端商品列表游标（Story 4-5 · SHOP-FR-13）。
 *
 * <p>范式照 {@code content.service.FeedCursor}：编码复合排序键、base64-url 对外。
 * 区别只在键本身 —— 商品列表的排序是 {@code sort_weight DESC, id DESC}
 * （既有索引 {@code idx_shop_products_listing}），不是 Feed 的 {@code (created_at, id)}。
 * <b>不要为分页另造一套排序</b>：运营调过的权重在两个入口必须一致。
 *
 * @param sortWeight 该条的运营权重
 * @param id         该条 id（tie-breaker，保证同权重时分页不漂移）
 */
public record ShopProductCursor(int sortWeight, long id) {

    /**
     * 🔴 <b>「取全部」的哨兵游标</b>：比任何真实行都大，因此 keyset 条件对所有行成立。
     *
     * <p>它让<b>首页与后续页走同一条查询路径</b> —— 没有「第一页特判」，
     * 也就没有「第一页和第二页排序/过滤不一致」这一整类 bug。
     */
    public static final ShopProductCursor START =
            new ShopProductCursor(Integer.MAX_VALUE, Long.MAX_VALUE);

    /** 编码为对外 token：{@code base64url("<sortWeight>:<id>")}。 */
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((sortWeight + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码；格式非法一律 422（不外泄内部细节）。
     *
     * <p>空 / 空白 → {@link #START}：「不带游标」就是「从头开始」，
     * 与既有 {@code q} 空白等同于不传是同一条待客之道。
     */
    public static ShopProductCursor decode(String token) {
        if (token == null || token.isBlank()) {
            return START;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            return new ShopProductCursor(Integer.parseInt(raw.substring(0, sep)),
                    Long.parseLong(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw AppException.validation("游标无效");
        }
    }
}
