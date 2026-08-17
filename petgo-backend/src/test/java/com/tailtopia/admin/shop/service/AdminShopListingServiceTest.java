package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * L0：上下架与在售 SKU 上限（Story 1.5 AC1/AC2/AC4/AC5）。
 *
 * <p>本类验两件事：
 * <ul>
 *   <li><b>上限口径没被写歪</b>——计数对象是「在售商品的 SKU 总数」，判定看的是<b>上架之后</b>的总数；</li>
 *   <li><b>下架不碰库存</b>（SPEC-7 口径）——用源码护栏证明<b>能力缺席</b>，而不是靠「记得别调」。</li>
 * </ul>
 */
class AdminShopListingServiceTest {

    private ShopProductRepository products;
    private ShopSkuRepository skus;
    private AdminAuditService audit;
    private AdminShopListingService service;

    private static final int CAP = 30;

    @BeforeEach
    void setUp() {
        products = Mockito.mock(ShopProductRepository.class);
        skus = Mockito.mock(ShopSkuRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        service = new AdminShopListingService(products, skus, audit, CAP);
    }

    /** 造一个未上架商品（实体无公开 setter，用反射填字段）。 */
    private static ShopProduct product(long id, boolean active) {
        try {
            var ctor = ShopProduct.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ShopProduct p = ctor.newInstance();
            set(p, "id", id);
            set(p, "publicToken", "tok" + id);
            set(p, "name", "商品" + id);
            set(p, "active", active);
            return p;
        } catch (Exception e) {
            throw new AssertionError("构造 ShopProduct 失败", e);
        }
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private void given(long productId, boolean active, long ownSkus, long activeSkusNow) {
        when(products.findById(productId)).thenReturn(Optional.of(product(productId, active)));
        when(skus.countByProductId(productId)).thenReturn(ownSkus);
        when(skus.countActiveSkus()).thenReturn(activeSkusNow);
    }

    // ---------- AC4 上限边界：epics 明确要求 29 / 30 / 31 ----------

    @Test
    @DisplayName("边界 29：在售 28 + 本商品 1 个 SKU = 29 ≤ 30 → 上架成功")
    void listAt29Succeeds() {
        given(1L, false, 1L, 28L);
        assertThat(service.list(1L, 7L).isActive()).isTrue();
        verify(audit).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("边界 30：在售 29 + 本商品 1 个 = 30，恰好等于上限 → 上架成功（≤ 上限即可）")
    void listAt30Succeeds() {
        given(1L, false, 1L, 29L);
        assertThat(service.list(1L, 7L).isActive()).isTrue();
    }

    @Test
    @DisplayName("🔴 边界 31：在售 30 + 本商品 1 个 = 31 > 30 → 拒绝，且不写审计不改状态")
    void listAt31Rejected() {
        given(1L, false, 1L, 30L);

        assertThatThrownBy(() -> service.list(1L, 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("超过上限");

        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 多 SKU 跨越上限：在售 28 + 本商品 3 个 = 31 → 拒绝")
    void listMultiSkuCrossingCapRejected() {
        // 只看「当前是否已达 30」会放行这一单（28 < 30），从而越过上限到 31
        given(1L, false, 3L, 28L);

        assertThatThrownBy(() -> service.list(1L, 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("超过上限");
    }

    @Test
    @DisplayName("多 SKU 恰好触顶：在售 27 + 本商品 3 个 = 30 → 成功")
    void listMultiSkuExactlyAtCap() {
        given(1L, false, 3L, 27L);
        assertThat(service.list(1L, 7L).isActive()).isTrue();
    }

    // ---------- AC4 下架不受上限约束 ----------

    @Test
    @DisplayName("🔴 已超限时下架仍必须成功——否则会出现「超限了反而下架不掉」的死锁")
    void delistNeverBlockedByCap() {
        when(products.findById(1L)).thenReturn(Optional.of(product(1L, true)));
        when(skus.countActiveSkus()).thenReturn(999L);   // 远超上限

        assertThat(service.delist(1L, 7L).isActive()).isFalse();
        verify(audit).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("下架不查 SKU 数量——上限判定与下架无关")
    void delistDoesNotConsultSkuCount() {
        when(products.findById(1L)).thenReturn(Optional.of(product(1L, true)));

        service.delist(1L, 7L);

        verify(skus, never()).countByProductId(anyLong());
        verify(skus, never()).countActiveSkus();
    }

    // ---------- AC1 幂等 ----------

    @Test
    @DisplayName("重复上架幂等：不重复写审计")
    void listIsIdempotent() {
        when(products.findById(1L)).thenReturn(Optional.of(product(1L, true)));
        service.list(1L, 7L);
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("重复下架幂等：不重复写审计")
    void delistIsIdempotent() {
        when(products.findById(1L)).thenReturn(Optional.of(product(1L, false)));
        service.delist(1L, 7L);
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("商品不存在 → notFound")
    void missingProductRejected() {
        when(products.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(9L, 7L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.delist(9L, 7L)).isInstanceOf(AppException.class);
    }

    // ---------- 🔒 AC2 源码护栏：能力缺席 ----------

    /** 剥掉注释再扫——护栏要看的是代码，不是散文（Story 1.2 踩过这个坑）。 */
    private static String codeOnly(String path) throws IOException {
        return Files.readString(Path.of(path))
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    @Test
    @DisplayName("🔒 上下架服务【根本引用不到】任何库存类型——SPEC-7「下架不碰库存」靠能力缺席保证")
    void listingServiceCannotTouchInventory() throws IOException {
        String code = codeOnly(
                "src/main/java/com/tailtopia/admin/shop/service/AdminShopListingService.java");

        // 🔴 逐个类型点名，不用「不含 inventory 字样」这类模糊断言——
        //    后者会被无关的注释残留或变量名影响，且新增一个库存类型时不会变红。
        for (String forbidden : new String[] {
                "InventoryService", "SkuInventoryRepository", "InventoryMovementService",
                "InventoryMovementRepository", "SkuInventory"}) {
            assertThat(code)
                    .as("下架必须只改可见性（SPEC-7）。引用 " + forbidden
                            + " 就让「下架顺手动一下库存」变成可写的了——这不是靠自觉，是靠够不着")
                    .doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("🔒 上下架服务不发通知、不碰订单（下架不得触发任何副作用）")
    void listingServiceHasNoSideEffects() throws IOException {
        String code = codeOnly(
                "src/main/java/com/tailtopia/admin/shop/service/AdminShopListingService.java");
        for (String forbidden : new String[] {
                "NotificationService", "NotifyService", "OrderCenterService", "OrderRepository"}) {
            assertThat(code).doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("🔴 源码护栏：在售 SKU 总数的定义只能有一处（口径漂移 = 报警了却还能上架）")
    void activeSkuCountHasSingleDefinition() throws IOException {
        String service = codeOnly(
                "src/main/java/com/tailtopia/admin/shop/service/AdminShopListingService.java");
        String controller = codeOnly(
                "src/main/java/com/tailtopia/admin/shop/web/AdminShopProductController.java");

        // 服务层只经仓储的 countActiveSkus() 取数
        assertThat(service).contains("skus.countActiveSkus()");
        // 🔴 控制器不得自己算一遍：告警条必须走 listing.activeSkuCount()
        assertThat(controller)
                .as("控制器另算一遍必然与服务层漂移，表现为「明明报警了却还能上架」")
                .doesNotContain("countActiveSkus")
                .contains("listing.activeSkuCount()");
    }
}
