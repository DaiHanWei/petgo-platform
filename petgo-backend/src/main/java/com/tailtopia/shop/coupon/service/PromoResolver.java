package com.tailtopia.shop.coupon.service;

import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.domain.ProductCategory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品卡「Lagi promo」批量判定（v1.4.2 shop Story 2.2 AC9，D-6 / FR-133.3）。
 *
 * <p>{@code promo = true} ⇔ 存在「发放期内、ACTIVE、CLAIM 类模板覆盖该商品」<b>或</b>「当前登录用户持有 AVAILABLE
 * 且未过期的用户券（任意来源）覆盖该商品」（拍板 ⑥：因此同一商品对不同用户结果可能不同）。
 *
 * <p>🔴 <b>一次</b>查生效 CLAIM 模板、<b>一次</b>查用户可用券的模板（游客不查），内存匹配；
 * 禁止逐商品查库（{@code PromoResolverTest} 断言调用次数与商品数无关）。
 */
@Service
public class PromoResolver {

    /** 商品引用：public_token + 品类（范围匹配所需的全部信息）。 */
    public record ProductRef(String token, ProductCategory category) {
    }

    private final ShopCouponRepository coupons;

    public PromoResolver(ShopCouponRepository coupons) {
        this.coupons = coupons;
    }

    /**
     * @param viewerUserId 只认 {@code role=USER} 的登录用户 id；游客 / 非 USER → null（只算 CLAIM 模板）
     * @return 处于促销的商品 token 集合
     */
    @Transactional(readOnly = true)
    public Set<String> resolve(Collection<ProductRef> products, Long viewerUserId, Instant now) {
        if (products == null || products.isEmpty()) {
            return Set.of();
        }
        List<ShopCoupon> effective = new ArrayList<>(coupons.findIssuable(CouponAcquireType.CLAIM, now));
        if (viewerUserId != null) {
            effective.addAll(coupons.findHeldAvailableByUser(viewerUserId, now));
        }
        if (effective.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (ProductRef p : products) {
            for (ShopCoupon c : effective) {
                if (CouponScopeMatcher.covers(c, p.token(), p.category())) {
                    out.add(p.token());
                    break;
                }
            }
        }
        return out;
    }
}
