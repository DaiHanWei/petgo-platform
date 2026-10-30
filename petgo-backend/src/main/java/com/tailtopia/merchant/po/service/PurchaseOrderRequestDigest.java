package com.tailtopia.merchant.po.service;

import com.tailtopia.shop.order.domain.AddressSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 建单请求体摘要（契约 5.4「请求体摘要」，AD-17 幂等比较口径）= 规范化 JSON 的 UTF-8 字节的 SHA-256（小写十六进制）。
 *
 * <ol>
 *   <li>只取契约字段（未知字段不参与）；对象键按字典序，不含空白；</li>
 *   <li>{@code lines} 保持请求顺序（行顺序不同视为不同请求体）；</li>
 *   <li>整数十进制无前导零；字符串原样，按 RFC 8785 转义（只转义引号、反斜杠与 U+0000～U+001F）。</li>
 * </ol>
 *
 * <p>与商家平台 {@code CheckoutFingerprint} 逐字节同一口径 —— 那边用它判断「一个 checkout_key 只绑一个请求体」。
 */
public final class PurchaseOrderRequestDigest {

    private PurchaseOrderRequestDigest() {
    }

    public static String of(NewPurchaseOrder order) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(order).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 契约 5.4 第 4 条的结构，键已按字典序写出。🔒 含个人信息，不得写进日志。 */
    static String canonicalJson(NewPurchaseOrder order) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"lines\":[");
        boolean first = true;
        for (NewPurchaseOrder.Line l : order.lines()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"qty\":").append(l.qty()).append(",\"sku_token\":");
            string(sb, l.skuToken());
            sb.append(",\"wholesale_price\":").append(l.wholesalePrice()).append('}');
        }
        AddressSnapshot a = order.shippingAddress();
        sb.append("],\"shipping_address\":{\"address_line\":");
        string(sb, a.addressLine());
        sb.append(",\"kecamatan\":");
        string(sb, a.kecamatan());
        sb.append(",\"kode_pos\":");
        string(sb, a.kodePos());
        sb.append(",\"kota_kabupaten\":");
        string(sb, a.kotaKabupaten());
        sb.append(",\"provinsi\":");
        string(sb, a.provinsi());
        sb.append(",\"receiver_name\":");
        string(sb, a.receiverName());
        sb.append(",\"receiver_phone\":");
        string(sb, a.receiverPhone());
        sb.append("},\"shipping_method\":");
        string(sb, order.shippingMethod().name());
        sb.append(",\"store_snapshot\":{\"name\":");
        string(sb, order.storeName());
        sb.append(",\"phone\":");
        string(sb, order.storePhone());
        sb.append("}}");
        return sb.toString();
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
