package com.tailtopia.shop.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.ActorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.MediaProperties;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.dto.ShopProductDetailView;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.review.domain.ReviewStatus;
import com.tailtopia.shop.review.domain.ShopReview;
import com.tailtopia.shop.review.dto.ProductReviewsView;
import com.tailtopia.shop.review.repository.ShopReviewRepository;
import com.tailtopia.shop.review.repository.ShopReviewStats;
import com.tailtopia.shop.review.service.ProductReviewQueryService;
import com.tailtopia.shop.review.web.ShopReviewController;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0（v1.4.2 shop Story 6.1 AC10 / AC11）：{@code GET /shop/products/{token}/reviews} 扩展与详情评价摘要。
 * 控制器 + 真实 {@link ProductReviewQueryService}，仓储 mock。
 */
class ShopReviewControllerTest {

    private static final long PRODUCT_ID = 1L;
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00.123456Z");

    private ShopReviewRepository reviews;
    private ShippingSettingsRepository settings;
    private ShopOrderLineRepository lines;
    private AccountQueryService accounts;
    private ShopProductRepository products;
    private ProductReviewQueryService query;
    private ShopReviewController controller;
    private ShippingSettings row;

    @BeforeEach
    void setUp() {
        reviews = mock(ShopReviewRepository.class);
        settings = mock(ShippingSettingsRepository.class);
        lines = mock(ShopOrderLineRepository.class);
        accounts = mock(AccountQueryService.class);
        products = mock(ShopProductRepository.class);
        MediaProperties mp = new MediaProperties();
        mp.getOss().setCdnBaseUrl("https://cdn.test");
        query = new ProductReviewQueryService(reviews, settings, lines, accounts, new ShopImageUrlResolver(mp));
        controller = new ShopReviewController(query, products);

        ShopProduct p = new ShopProduct() {
        };
        ReflectionTestUtils.setField(p, "id", PRODUCT_ID);
        when(products.findByPublicTokenAndActiveTrue("tokA")).thenReturn(Optional.of(p));
        row = instantiate();
        when(settings.findById(ShippingSettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(accounts.findActorViews(any())).thenReturn(Map.of(
                7L, new ActorView(7L, "Dewi Ayu Sartika", "utok", true),
                8L, new ActorView(8L, null, null, false)));
        when(lines.findAllById(any())).thenAnswer(inv -> {
            List<ShopOrderLine> out = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                ShopOrderLine l = mock(ShopOrderLine.class);
                when(l.getId()).thenReturn((Long) id);
                when(l.getSpecName()).thenReturn("spec-" + id);
                out.add(l);
            }
            return out;
        });
    }

    private static ShippingSettings instantiate() {
        try {
            var c = ShippingSettings.class.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ShopReview review(long id, long userId, Instant at) {
        ShopReview r = ShopReview.of(100 + id, 11L, PRODUCT_ID, userId, 5, "bagus", List.of("r/" + id + ".jpg"));
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "createdAt", at);
        ReflectionTestUtils.setField(r, "reviewStatus", ReviewStatus.PUBLISHED);
        return r;
    }

    private List<ShopReview> reviewsDesc(int n) {
        List<ShopReview> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(review(50 - i, i % 2 == 0 ? 7L : 8L, T0.minusSeconds(i)));
        }
        return out;
    }

    @Test
    @DisplayName("开关关（默认）→ items=[]、hasMore=false、无 nextCursor；total / averageRating 照给，不查列表")
    void displayOffGivesStatsOnly() {
        when(reviews.visibleStats(PRODUCT_ID)).thenReturn(new ShopReviewStats(24L, Double.valueOf(4.8)));
        ProductReviewsView v = controller.forProduct("tokA", null, null);
        assertThat(v.displayEnabled()).isFalse();
        assertThat(v.items()).isEmpty();
        assertThat(v.hasMore()).isFalse();
        assertThat(v.nextCursor()).isNull();
        assertThat(v.total()).isEqualTo(24);
        assertThat(v.averageRating()).isEqualTo(4.8);
        org.mockito.Mockito.verify(reviews, org.mockito.Mockito.never())
                .pageVisible(anyLong(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("无评价 → total=0、averageRating=null（不是 0）、items=[]")
    void noReviews() {
        row.setReviewDisplayEnabled(true);
        when(reviews.visibleStats(PRODUCT_ID)).thenReturn(ShopReviewStats.empty());
        ProductReviewsView v = controller.forProduct("tokA", null, null);
        assertThat(v.total()).isZero();
        assertThat(v.averageRating()).isNull();
        assertThat(v.items()).isEmpty();
        assertThat(v.hasMore()).isFalse();
    }

    @Test
    @DisplayName("开关开 → 游标分页：取 size+1 判 hasMore，nextCursor 指向本页末条；条目带昵称与规格名")
    void displayOnPages() {
        row.setReviewDisplayEnabled(true);
        when(reviews.visibleStats(PRODUCT_ID)).thenReturn(new ShopReviewStats(5L, Double.valueOf(4.2)));
        List<ShopReview> rows = reviewsDesc(3);   // size=2 → 多取 1 条
        when(reviews.pageVisible(eq(PRODUCT_ID), any(), anyLong(), any())).thenReturn(rows);

        ProductReviewsView v = controller.forProduct("tokA", 2, null);
        assertThat(v.displayEnabled()).isTrue();
        assertThat(v.items()).hasSize(2);
        assertThat(v.hasMore()).isTrue();
        KeysetCursor next = KeysetCursor.decodeOrNull(v.nextCursor());
        assertThat(next.id()).isEqualTo(49L);
        assertThat(next.createdAt()).isEqualTo(T0.minusSeconds(1));
        assertThat(v.items().get(0).nickname()).as("社区昵称原样不出现，只给打码值").isEqualTo("Dewi S.");
        assertThat(v.items().get(1).nickname()).as("注销 / 封号 → null").isNull();
        assertThat(v.items().get(0).specName()).isEqualTo("spec-150");
        assertThat(v.items().get(0).imageUrls()).containsExactly("https://cdn.test/r/50.jpg");
        assertThat(v.items().get(0).reviewStatus()).isNull();

        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(reviews).pageVisible(eq(PRODUCT_ID), any(), anyLong(), pg.capture());
        assertThat(pg.getValue().getPageSize()).isEqualTo(3);

        // 下一页：带上游标，查询拿到的就是游标里的 (ts, id)
        when(reviews.pageVisible(PRODUCT_ID, next.createdAt(), 49L, pg.getValue())).thenReturn(List.of());
        ProductReviewsView last = controller.forProduct("tokA", 2, v.nextCursor());
        assertThat(last.items()).isEmpty();
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    @DisplayName("坏游标按首页（哨兵 id = Long.MAX），不报错；limit 上限 20")
    void badCursorIsFirstPage() {
        row.setReviewDisplayEnabled(true);
        when(reviews.visibleStats(PRODUCT_ID)).thenReturn(new ShopReviewStats(1L, Double.valueOf(5.0)));
        when(reviews.pageVisible(eq(PRODUCT_ID), any(), anyLong(), any())).thenReturn(reviewsDesc(1));
        ProductReviewsView v = controller.forProduct("tokA", 99, "%%%not-base64");
        assertThat(v.items()).hasSize(1);
        assertThat(v.hasMore()).isFalse();
        ArgumentCaptor<Long> id = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(reviews).pageVisible(eq(PRODUCT_ID), any(), id.capture(), pg.capture());
        assertThat(id.getValue()).isEqualTo(Long.MAX_VALUE);
        assertThat(pg.getValue().getPageSize()).isEqualTo(21);
    }

    @Test
    @DisplayName("商品不存在或已下架 → 404（评价接口不改 410）")
    void unknownOrDelistedProductIs404() {
        when(products.findByPublicTokenAndActiveTrue("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.forProduct("gone", null, null))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("详情摘要：开关关 → items 空；开 → 最多 3 条（PageRequest size 3）")
    void detailSummary() {
        when(reviews.visibleStats(PRODUCT_ID)).thenReturn(new ShopReviewStats(24L, Double.valueOf(4.8)));
        ShopProductDetailView.ReviewSummary off = query.summaryFor(PRODUCT_ID);
        assertThat(off.displayEnabled()).isFalse();
        assertThat(off.items()).isEmpty();
        assertThat(off.total()).isEqualTo(24);

        row.setReviewDisplayEnabled(true);
        when(reviews.findByProductIdAndReviewStatusAndHiddenAtIsNullOrderByCreatedAtDescIdDesc(
                eq(PRODUCT_ID), eq(ReviewStatus.PUBLISHED), any())).thenReturn(reviewsDesc(3));
        ShopProductDetailView.ReviewSummary on = query.summaryFor(PRODUCT_ID);
        assertThat(on.displayEnabled()).isTrue();
        assertThat(on.items()).hasSize(3);
        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(reviews).findByProductIdAndReviewStatusAndHiddenAtIsNullOrderByCreatedAtDescIdDesc(
                eq(PRODUCT_ID), eq(ReviewStatus.PUBLISHED), pg.capture());
        assertThat(pg.getValue().getPageSize()).isEqualTo(3);
        // 批量：昵称、规格名各一次
        org.mockito.Mockito.verify(accounts, org.mockito.Mockito.times(1)).findActorViews(any());
        org.mockito.Mockito.verify(lines, org.mockito.Mockito.times(1)).findAllById(any());
    }

    @Test
    @DisplayName("配送设置行缺失 → 开关按关")
    void missingSettingsRowMeansOff() {
        when(settings.findById(ShippingSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        assertThat(query.isDisplayEnabled()).isFalse();
    }
}
