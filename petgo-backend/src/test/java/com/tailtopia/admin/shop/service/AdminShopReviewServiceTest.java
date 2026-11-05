package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.review.domain.ReviewStatus;
import com.tailtopia.shop.review.domain.ShopReview;
import com.tailtopia.shop.review.repository.ShopReviewRepository;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（v1.4.2 shop Story 6.1 AC9.3）：评价隐藏 / 撤销 / 展示开关的后台 service。 */
class AdminShopReviewServiceTest {

    private static final String SECRET_CONTENT = "anjing saya sakit setelah makan ini";

    private ShopReviewRepository reviews;
    private ShopProductRepository products;
    private ShippingSettingsRepository settings;
    private AdminAuditService audit;
    private AdminShopReviewService service;
    private ShopReview review;
    private ShippingSettings row;

    @BeforeEach
    void setUp() throws Exception {
        reviews = mock(ShopReviewRepository.class);
        products = mock(ShopProductRepository.class);
        settings = mock(ShippingSettingsRepository.class);
        audit = mock(AdminAuditService.class);
        service = new AdminShopReviewService(reviews, products, settings, audit);
        review = ShopReview.of(1L, 2L, 3L, 4L, 2, SECRET_CONTENT, List.of());
        ReflectionTestUtils.setField(review, "id", 77L);
        ReflectionTestUtils.setField(review, "reviewStatus", ReviewStatus.PUBLISHED);
        when(reviews.findById(77L)).thenReturn(Optional.of(review));
        ShopProduct p = new ShopProduct() {
        };
        ReflectionTestUtils.setField(p, "publicToken", "prodTok");
        when(products.findById(3L)).thenReturn(Optional.of(p));
        var c = ShippingSettings.class.getDeclaredConstructor();
        c.setAccessible(true);
        row = c.newInstance();
        when(settings.findById(ShippingSettings.SINGLETON_ID)).thenReturn(Optional.of(row));
    }

    @Test
    void hideIsIdempotentAndAuditsOnceWithoutContent() {
        service.hide(77L, 9L);
        service.hide(77L, 9L);
        assertThat(review.isHidden()).isTrue();
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit, times(1)).record(eq(9L), eq(AuditActions.SHOP_REVIEW_HIDDEN), eq("SHOP_REVIEW"),
                eq("77"), summary.capture());
        assertThat(summary.getValue()).contains("77").contains("prodTok").doesNotContain(SECRET_CONTENT);
        verify(reviews, times(1)).save(review);
    }

    @Test
    void unhideIsIdempotent() {
        service.unhide(77L, 9L);
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString());
        service.hide(77L, 9L);
        service.unhide(77L, 9L);
        service.unhide(77L, 9L);
        assertThat(review.isHidden()).isFalse();
        verify(audit, times(1)).record(eq(9L), eq(AuditActions.SHOP_REVIEW_UNHIDDEN), eq("SHOP_REVIEW"),
                eq("77"), anyString());
    }

    @Test
    void missingReviewIs404() {
        when(reviews.findById(anyLong())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.hide(1L, 9L)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.unhide(1L, 9L)).isInstanceOf(AppException.class);
    }

    @Test
    void displayToggleReadsAndWritesSettings() {
        assertThat(service.isDisplayEnabled()).as("默认关").isFalse();
        service.setDisplayEnabled(true, 9L);
        assertThat(row.isReviewDisplayEnabled()).isTrue();
        assertThat(service.isDisplayEnabled()).isTrue();
        verify(settings).save(row);
        verify(audit).record(eq(9L), eq(AuditActions.SHOP_REVIEW_DISPLAY_TOGGLED), eq("SHIPPING_SETTINGS"),
                eq("1"), anyString());
        service.setDisplayEnabled(false, 9L);
        assertThat(service.isDisplayEnabled()).isFalse();
    }

    @Test
    void missingSettingsRowReadsAsOff() {
        when(settings.findById(ShippingSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        assertThat(service.isDisplayEnabled()).isFalse();
    }

    /** 复审修复：无操作人（系统 / stag）同样写审计，不跳过。 */
    @Test
    void nullActorStillAudits() {
        service.hide(77L, null);
        verify(audit).record(org.mockito.ArgumentMatchers.isNull(), eq(AuditActions.SHOP_REVIEW_HIDDEN),
                eq("SHOP_REVIEW"), eq("77"), anyString());
        service.unhide(77L, null);
        verify(audit).record(org.mockito.ArgumentMatchers.isNull(), eq(AuditActions.SHOP_REVIEW_UNHIDDEN),
                eq("SHOP_REVIEW"), eq("77"), anyString());
        service.setDisplayEnabled(true, null);
        verify(audit).record(org.mockito.ArgumentMatchers.isNull(), eq(AuditActions.SHOP_REVIEW_DISPLAY_TOGGLED),
                eq("SHIPPING_SETTINGS"), eq("1"), anyString());
    }
}
