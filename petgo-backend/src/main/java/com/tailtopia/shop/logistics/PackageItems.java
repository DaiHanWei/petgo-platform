package com.tailtopia.shop.logistics;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ShopSku;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 包裹项工具（v1.4.2 shop Story 1.1）。 */
public final class PackageItems {

    private PackageItems() {
    }

    /**
     * 由 SKU 构造包裹项。包裹参数不全 → 422 {@code sku-not-sellable}
     * （选用 {@link AppException#skuNotSellable}，不另建 {@code SkuNotSellableException}）。
     */
    public static PackageItem of(ShopSku sku, String productName, int qty) {
        if (!sku.isSellable()) {
            throw AppException.skuNotSellable("该规格暂不可售");
        }
        String name = (productName == null ? "" : productName)
                + (sku.getSpecName() == null || sku.getSpecName().isBlank() ? "" : " " + sku.getSpecName());
        return new PackageItem(name.trim(), sku.getPrice(), qty, sku.getPackageWeightG(),
                sku.getPackageLengthCm(), sku.getPackageWidthCm(), sku.getPackageHeightCm());
    }

    /**
     * 包裹哈希（D-9，纯函数）：每项规范化为 {@code weightG,lengthCm,widthCm,heightCm,quantity}，
     * 字典序排序后以 {@code ;} 连接、前缀 {@code v1|}，取 SHA-256 小写十六进制（64 字符）。
     *
     * <p>🔴 只含包裹参数与数量，不含名称、单价——不传 {@code courier_insurance} 时 Biteship 价格与
     * {@code value} 无关。
     */
    public static String itemsHash(List<PackageItem> items) {
        String canonical = "v1|" + String.join(";", items.stream()
                .map(i -> i.weightG() + "," + i.lengthCm() + "," + i.widthCm() + "," + i.heightCm()
                        + "," + i.quantity())
                .sorted()
                .toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
