package com.tailtopia.shop.cart.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.cart.domain.ShopCart;
import com.tailtopia.shop.cart.domain.ShopCartItem;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.repository.ShopCartItemRepository;
import com.tailtopia.shop.cart.repository.ShopCartRepository;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 购物车（Story 3.1，FR-96）。
 *
 * <p>🔴 <b>游客无购物车</b>：本类的每个方法都以 {@code userId} 开头，
 * <b>没有任何匿名路径</b>。这是有意的能力缺席——加购是转化漏斗上第一个需要身份的动作。
 * （对照：Toko 浏览与商品详情<b>刻意</b>对游客开放，FR-93A。两者不是同一个决定。）
 *
 * <p>🔴 <b>数量上限是「当下的可售库存」，不落库</b>：库存随时在变，
 * 把上限固化进购物车行只会让它很快变成错的。每次读车时重新判定。
 */
@Service
public class CartService {

    private final ShopCartRepository carts;
    private final ShopCartItemRepository items;
    private final ShopSkuRepository skus;
    private final ShopProductRepository products;
    private final InventoryService inventory;
    private final ShopImageUrlResolver imageUrls;

    public CartService(ShopCartRepository carts, ShopCartItemRepository items,
            ShopSkuRepository skus, ShopProductRepository products,
            InventoryService inventory, ShopImageUrlResolver imageUrls) {
        this.carts = carts;
        this.items = items;
        this.skus = skus;
        this.products = products;
        this.inventory = inventory;
        this.imageUrls = imageUrls;
    }

    @Transactional
    public ShopCart cartOf(long userId) {
        return carts.findByUserId(userId)
                .orElseGet(() -> carts.save(ShopCart.forUser(userId)));
    }

    /** 加购。同一 SKU 已在车里则累加数量。 */
    @Transactional
    public CartView add(long userId, String skuToken, int qty) {
        requirePositive(qty);
        ShopSku sku = requireSku(skuToken);
        ShopCart cart = cartOf(userId);

        int existing = items.findByCartIdAndSkuId(cart.getId(), sku.getId())
                .map(ShopCartItem::getQty).orElse(0);
        int target = existing + qty;
        requireWithinStock(sku, target);

        items.findByCartIdAndSkuId(cart.getId(), sku.getId())
                .ifPresentOrElse(i -> {
                    i.setQty(target);
                    items.save(i);
                }, () -> items.save(ShopCartItem.of(cart.getId(), sku.getId(), target)));
        cart.touch();
        return view(userId);
    }

    /** 改数量。qty ≤ 0 视为删除（前端的减号按钮减到 0 就是删）。 */
    @Transactional
    public CartView setQty(long userId, String skuToken, int qty) {
        ShopSku sku = requireSku(skuToken);
        ShopCart cart = cartOf(userId);
        var line = items.findByCartIdAndSkuId(cart.getId(), sku.getId())
                .orElseThrow(() -> AppException.notFound("购物车中没有该商品"));
        if (qty <= 0) {
            items.delete(line);
        } else {
            requireWithinStock(sku, qty);
            line.setQty(qty);
            items.save(line);
        }
        cart.touch();
        return view(userId);
    }

    @Transactional
    public CartView remove(long userId, String skuToken) {
        return setQty(userId, skuToken, 0);
    }

    /** 清空全部失效商品（已下架 / 已售罄）。 */
    @Transactional
    public CartView clearInvalid(long userId) {
        CartView v = view(userId);
        if (v.invalidLines().isEmpty()) {
            return v;
        }
        ShopCart cart = cartOf(userId);
        Map<String, Long> skuIdByToken = skus.findAll().stream()
                .collect(Collectors.toMap(ShopSku::getPublicToken, ShopSku::getId, (a, b) -> a));
        List<Long> ids = v.invalidLines().stream()
                .map(l -> skuIdByToken.get(l.skuToken()))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!ids.isEmpty()) {
            items.deleteByCartIdAndSkuIdIn(cart.getId(), ids);
        }
        return view(userId);
    }

    /**
     * 读车。
     *
     * <p>🔴 <b>失效判定在读时进行</b>，不落库：商品下架、库存归零都发生在购物车之外，
     * 把状态冻进行里只会让它过时。
     */
    @Transactional
    public CartView view(long userId) {
        ShopCart cart = cartOf(userId);
        List<ShopCartItem> rows = items.findByCartIdOrderByIdAsc(cart.getId());
        if (rows.isEmpty()) {
            return new CartView(List.of(), List.of(), 0L, 0);
        }

        List<Long> skuIds = rows.stream().map(ShopCartItem::getSkuId).toList();
        Map<Long, ShopSku> skuById = skus.findAllById(skuIds).stream()
                .collect(Collectors.toMap(ShopSku::getId, s -> s));
        Map<Long, ShopProduct> productById = products
                .findAllById(skuById.values().stream().map(ShopSku::getProductId).toList())
                .stream().collect(Collectors.toMap(ShopProduct::getId, p -> p));
        Map<Long, Long> availableBySku = inventory.availableBySkuId(skuIds);

        List<CartView.CartLine> valid = new ArrayList<>();
        List<CartView.CartLine> invalid = new ArrayList<>();
        long subtotal = 0L;
        int count = 0;

        for (ShopCartItem row : rows) {
            ShopSku sku = skuById.get(row.getSkuId());
            if (sku == null) {
                continue;   // SKU 已被物理删除（罕见）——不构造半截行
            }
            ShopProduct p = productById.get(sku.getProductId());
            long available = availableBySku.getOrDefault(sku.getId(), 0L);

            // 🔴 区分两种失效原因：下架是永久的（换个商品吧），售罄是暂时的（可以等）。
            //    给同一句话会让用户做错决定。
            String reason = null;
            if (p == null || !p.isActive()) {
                reason = CartView.REASON_DELISTED;
            } else if (available <= 0) {
                reason = CartView.REASON_OUT_OF_STOCK;
            }

            CartView.CartLine line = new CartView.CartLine(
                    sku.getPublicToken(),
                    p == null ? null : p.getPublicToken(),
                    p == null ? null : p.getName(),
                    sku.getSpecName(),
                    sku.getPrice(),
                    row.getQty(),
                    p == null ? null : imageUrls.publicUrl(p.getMainImageKey()),
                    available,
                    reason);

            if (reason == null) {
                valid.add(line);
                subtotal += sku.getPrice() * row.getQty();
                count += row.getQty();      // 🔴 件数，不是种类数
            } else {
                invalid.add(line);          // 🔴 不参与合计、不计入角标，但也不消失
            }
        }
        return new CartView(valid, invalid, subtotal, count);
    }

    // ---------- 内部 ----------

    private ShopSku requireSku(String skuToken) {
        return skus.findAll().stream()
                .filter(s -> s.getPublicToken().equals(skuToken))
                .findFirst()
                .orElseThrow(() -> AppException.notFound("规格不存在"));
    }

    /** 🔴 上限是可售库存，超出给明确错误——静默截断会让用户以为加进去了。 */
    private void requireWithinStock(ShopSku sku, int target) {
        long available = inventory.availableBySkuId(List.of(sku.getId()))
                .getOrDefault(sku.getId(), 0L);
        if (available <= 0) {
            throw AppException.conflict("该规格已售罄");
        }
        if (target > available) {
            throw AppException.conflict("库存不足，该规格最多可购买 %d 件".formatted(available));
        }
    }

    private static void requirePositive(int qty) {
        if (qty <= 0) {
            throw AppException.validation("数量必须为正");
        }
    }
}
