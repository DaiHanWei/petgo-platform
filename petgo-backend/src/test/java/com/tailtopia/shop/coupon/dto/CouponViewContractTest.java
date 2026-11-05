package com.tailtopia.shop.coupon.dto;

import static com.tailtopia.shop.coupon.CouponFixtures.NOW;
import static com.tailtopia.shop.coupon.CouponFixtures.coupon;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.coupon.CouponFixtures;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** L0：v1.4.2 shop Story 2.2 AC11 —— {@code CouponView} JSON 字段名钉死；不外露 id / 发放量 / 总量 / 码。 */
class CouponViewContractTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private static final Set<String> EXPECTED = Set.of(
            "couponToken", "userCouponToken", "kind", "nameId", "nameEn", "amount", "minSpend",
            "scopeType", "scopeCategories", "scopeProductCount", "validFrom", "validUntil", "validDays",
            "expiringSoon", "claimed", "newbie", "isNew", "status",
            "lockedByPendingOrder", "usedAt", "usedOrderNo", "usedOrderToken");

    private Set<String> fieldNames(Object v) {
        JsonNode node = json.readTree(json.writeValueAsString(v));
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void ownedViewFieldNames() {
        ShopCoupon c = coupon(1L, CouponAcquireType.CLAIM, CouponScopeType.PRODUCT, List.of("p1", "p2"));
        CouponFixtures.quantity(c, 100, 42);
        ReflectionTestUtils.setField(c, "id", 123456L);
        var uc = CouponFixtures.userCoupon(654321L, 1L, c, UserCouponSource.CODE, UserCouponStatus.AVAILABLE,
                NOW.plus(Duration.ofDays(1)));
        ReflectionTestUtils.setField(uc, "publicToken", "ucTokenOpaque");
        CouponView v = CouponViews.owned(uc, c, NOW, true, null, null);
        Set<String> names = fieldNames(v);
        assertThat(names).isEqualTo(new TreeSet<>(EXPECTED));
        assertThat(names).doesNotContain("id", "issuedCount", "totalQuantity", "code", "new", "couponId", "userId");
        String wire = json.writeValueAsString(v);
        assertThat(wire).doesNotContain("123456").doesNotContain("654321");
        assertThat(wire).contains("\"isNew\":true").contains("\"scopeProductCount\":2");
        assertThat(v.scopeCategories()).isEmpty();
    }

    @Test
    void claimableRelativeViewHasValidDaysOnly() {
        ShopCoupon c = coupon(1L, CouponAcquireType.CLAIM, CouponScopeType.CATEGORY, List.of("MAKANAN"));
        ReflectionTestUtils.setField(c, "validMode", com.tailtopia.shop.coupon.domain.CouponValidMode.RELATIVE);
        ReflectionTestUtils.setField(c, "validDays", 7);
        CouponView v = CouponViews.claimable(c, false, NOW);
        assertThat(fieldNames(v)).isEqualTo(new TreeSet<>(EXPECTED));
        assertThat(v.validFrom()).isNull();
        assertThat(v.validUntil()).isNull();
        assertThat(v.validDays()).isEqualTo(7);
        assertThat(v.scopeCategories()).containsExactly("MAKANAN");
        assertThat(v.scopeProductCount()).isNull();
        assertThat(v.status()).isNull();
        assertThat(v.userCouponToken()).isNull();
    }
}
