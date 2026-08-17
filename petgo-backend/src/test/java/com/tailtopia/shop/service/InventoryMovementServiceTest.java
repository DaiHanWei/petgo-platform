package com.tailtopia.shop.service;

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
import com.tailtopia.shop.domain.InventoryMovement;
import com.tailtopia.shop.domain.InventoryMovementType;
import com.tailtopia.shop.domain.SkuInventory;
import com.tailtopia.shop.repository.InventoryMovementRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * L0：库存增减的四条合法路径（Story 1.4 AC2/AC3/AC4，🔒 安全攸关）。
 *
 * <p>本类验的核心是<b>不变式没有被写坏</b>：报损上限是可售、盘点下限是锁定、
 * 拒收货单价不得静默取 0、审计详情不得泄露进货单价。
 *
 * <p>⚠️ 决策 S-3：<b>真正的超卖来源是盘点/报损，不是并发。</b>本 Story 打开的正是那个来源。
 */
class InventoryMovementServiceTest {

    private SkuInventoryRepository inventory;
    private InventoryMovementRepository movements;
    private ShopSkuRepository skus;
    private AdminAuditService audit;
    private InventoryMovementService service;

    @BeforeEach
    void setUp() {
        inventory = Mockito.mock(SkuInventoryRepository.class);
        movements = Mockito.mock(InventoryMovementRepository.class);
        skus = Mockito.mock(ShopSkuRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        service = new InventoryMovementService(inventory, movements, skus, audit);

        when(skus.findById(anyLong())).thenReturn(Optional.empty());
        when(movements.save(any(InventoryMovement.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * 造一个库存行。
     *
     * <p>⚠️ 用反射构造<b>真实实体</b>而非 Mockito mock：这些 row(...) 调用出现在
     * {@code when(...).thenReturn(row(...))} 的参数位置上，在未完成的 stubbing 里再 stub 另一个
     * mock 会触发 {@code UnfinishedStubbingException}。真实实体没有这个问题，
     * 而且 {@code available()} 走的是真实的 {@code actual - locked}，断言更可信。
     */
    private static SkuInventory row(long skuId, long actual, long locked) {
        try {
            var ctor = SkuInventory.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            SkuInventory r = ctor.newInstance();
            set(r, "skuId", skuId);
            set(r, "actual", actual);
            set(r, "locked", locked);
            return r;
        } catch (Exception e) {
            throw new AssertionError("构造 SkuInventory 失败", e);
        }
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ---------- AC3 采购入库 ----------

    @Test
    @DisplayName("采购入库：actual 增加，流水前后值 = after ∓ qty")
    void purchaseRecordsBeforeAndAfter() {
        when(inventory.restock(1L, 10L)).thenReturn(1);
        when(inventory.findBySkuId(1L)).thenReturn(Optional.of(row(1L, 10L, 0L)));

        service.receivePurchase(1L, 10L, "PO-001", "供应商A", 190_000L, LocalDate.now(), 7L);

        ArgumentCaptor<InventoryMovement> cap = ArgumentCaptor.forClass(InventoryMovement.class);
        verify(movements).save(cap.capture());
        InventoryMovement m = cap.getValue();
        assertThat(m.getMovementType()).isEqualTo(InventoryMovementType.PURCHASE_INBOUND);
        assertThat(m.getQtyDelta()).isEqualTo(10L);
        assertThat(m.getActualBefore()).isZero();
        assertThat(m.getActualAfter()).isEqualTo(10L);
        assertThat(m.getCostPrice()).isEqualTo(190_000L);
        assertThat(m.getOperatorAccountId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("🔴 S-9：采购入库缺进货单价 → 拒绝（入库单不允许留空）")
    void purchaseRequiresCostPrice() {
        assertThatThrownBy(() ->
                service.receivePurchase(1L, 10L, "PO-001", null, null, null, 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("进货单价");
        verify(inventory, never()).restock(anyLong(), anyLong());
    }

    @Test
    @DisplayName("🔴 S-9：采购入库缺采购单号 → 拒绝")
    void purchaseRequiresPurchaseNo() {
        assertThatThrownBy(() ->
                service.receivePurchase(1L, 10L, "  ", null, 100L, null, 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("采购单号");
    }

    // ---------- AC3 拒收货 / 退货入库（SPEC-11 · S-9） ----------

    @Test
    @DisplayName("🔴 S-9：退货入库自动带出最近一次【采购】单价，且记为退货入库批次")
    void returnInboundInheritsLastPurchaseCost() {
        InventoryMovement lastPurchase = InventoryMovement.inbound(1L,
                InventoryMovementType.PURCHASE_INBOUND, 5L, 0L, 5L, "PO-OLD", "A",
                188_000L, LocalDate.now(), 7L);
        when(movements.findFirstBySkuIdAndMovementTypeOrderByCreatedAtDescIdDesc(
                1L, InventoryMovementType.PURCHASE_INBOUND)).thenReturn(Optional.of(lastPurchase));
        when(inventory.restock(1L, 2L)).thenReturn(1);
        when(inventory.findBySkuId(1L)).thenReturn(Optional.of(row(1L, 7L, 0L)));

        service.receiveReturn(1L, 2L, "ORD-XYZ", null, 7L);

        ArgumentCaptor<InventoryMovement> cap = ArgumentCaptor.forClass(InventoryMovement.class);
        verify(movements).save(cap.capture());
        InventoryMovement m = cap.getValue();
        assertThat(m.getMovementType()).isEqualTo(InventoryMovementType.RETURN_INBOUND);
        assertThat(m.getCostPrice()).isEqualTo(188_000L);
        // S-9：采购单号填【原订单号】
        assertThat(m.getPurchaseNo()).isEqualTo("ORD-XYZ");
    }

    @Test
    @DisplayName("🔴 该 SKU 无采购记录 → 明确拒绝，绝不静默以 0 入库（0 成本会让毛利虚高）")
    void returnInboundRejectsWhenNoPurchaseHistory() {
        when(movements.findFirstBySkuIdAndMovementTypeOrderByCreatedAtDescIdDesc(
                1L, InventoryMovementType.PURCHASE_INBOUND)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receiveReturn(1L, 2L, "ORD-XYZ", null, 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("尚无采购入库记录");
        verify(inventory, never()).restock(anyLong(), anyLong());
        verify(movements, never()).save(any());
    }

    @Test
    @DisplayName("🔴 带出单价只看 PURCHASE_INBOUND，不看 RETURN_INBOUND（否则错价沿退货链传播）")
    void lastCostLooksAtPurchaseOnly() {
        when(movements.findFirstBySkuIdAndMovementTypeOrderByCreatedAtDescIdDesc(
                anyLong(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.lastPurchaseCostPrice(1L))
                .isInstanceOf(AppException.class);
        verify(movements).findFirstBySkuIdAndMovementTypeOrderByCreatedAtDescIdDesc(
                1L, InventoryMovementType.PURCHASE_INBOUND);
    }

    // ---------- AC4 报损：上限是可售，不是 actual ----------

    @Test
    @DisplayName("🔒 报损：actual=10 locked=7 → 报损 3 件成功（可售恰好够）")
    void writeOffAtAvailableBoundarySucceeds() {
        when(inventory.damage(1L, 3L)).thenReturn(1);
        when(inventory.findBySkuId(1L)).thenReturn(Optional.of(row(1L, 7L, 7L)));

        service.writeOff(1L, 3L, "仓库进水", 7L);

        ArgumentCaptor<InventoryMovement> cap = ArgumentCaptor.forClass(InventoryMovement.class);
        verify(movements).save(cap.capture());
        assertThat(cap.getValue().getQtyDelta()).isEqualTo(-3L);   // 🔴 报损落库为负
        assertThat(cap.getValue().getActualBefore()).isEqualTo(10L);
        assertThat(cap.getValue().getActualAfter()).isEqualTo(7L);
    }

    @Test
    @DisplayName("🔒 报损：可售不足（影响 0 行）→ 抛错，且不落流水不写审计")
    void writeOffBeyondAvailableRejected() {
        when(inventory.damage(1L, 4L)).thenReturn(0);

        assertThatThrownBy(() -> service.writeOff(1L, 4L, "仓库进水", 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("可售库存不足");

        verify(movements, never()).save(any());
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("报损原因必填（AB-10C：每次调整须留存操作人、原因与前后值）")
    void writeOffRequiresReason() {
        assertThatThrownBy(() -> service.writeOff(1L, 1L, "   ", 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("原因");
        verify(inventory, never()).damage(anyLong(), anyLong());
    }

    // ---------- AC4 盘点：下限是 locked ----------

    @Test
    @DisplayName("🔒 盘点：actual=10 locked=7 → 盘到 7 成功（恰好等于锁定量）")
    void stocktakeAtLockedBoundarySucceeds() {
        when(inventory.findBySkuId(1L)).thenReturn(Optional.of(row(1L, 10L, 7L)));
        when(inventory.stocktakeTo(1L, 7L, 10L)).thenReturn(1);

        service.stocktake(1L, 7L, "月度盘点差异", 7L);

        ArgumentCaptor<InventoryMovement> cap = ArgumentCaptor.forClass(InventoryMovement.class);
        verify(movements).save(cap.capture());
        assertThat(cap.getValue().getActualBefore()).isEqualTo(10L);
        assertThat(cap.getValue().getActualAfter()).isEqualTo(7L);
        assertThat(cap.getValue().getQtyDelta()).isEqualTo(-3L);
    }

    @Test
    @DisplayName("🔒 盘点：盘点值低于锁定量（影响 0 行）→ 抛错，不落流水")
    void stocktakeBelowLockedRejected() {
        when(inventory.findBySkuId(1L)).thenReturn(Optional.of(row(1L, 10L, 7L)));
        when(inventory.stocktakeTo(1L, 6L, 10L)).thenReturn(0);

        assertThatThrownBy(() -> service.stocktake(1L, 6L, "月度盘点差异", 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("盘点");
        verify(movements, never()).save(any());
    }

    @Test
    @DisplayName("🔴 盘点用 CAS：期望前值作为 UPDATE 条件传入，并发改动时失败是响亮的")
    void stocktakePassesExpectedBeforeAsCondition() {
        when(inventory.findBySkuId(1L)).thenReturn(Optional.of(row(1L, 10L, 0L)));
        when(inventory.stocktakeTo(1L, 12L, 10L)).thenReturn(1);

        service.stocktake(1L, 12L, "盘盈", 7L);

        // 🔴 期望前值必须进 WHERE：否则并发改动会把【错误的前值】写进审计流水，
        //    而 DB 的 delta 一致性 CHECK 查不出来（流水行自身仍自洽）。
        verify(inventory).stocktakeTo(1L, 12L, 10L);
    }

    @Test
    @DisplayName("盘点值为负 → 拒绝")
    void stocktakeRejectsNegative() {
        assertThatThrownBy(() -> service.stocktake(1L, -1L, "x", 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能为负");
    }

    // ---------- AC5 审计：绝不泄露进货单价 ----------

    @Test
    @DisplayName("🔒 入库审计详情【不含进货单价数值】——审计页可见范围与 cost_view 不同")
    void auditDetailNeverContainsCostPrice() {
        when(inventory.restock(1L, 10L)).thenReturn(1);
        when(inventory.findBySkuId(1L)).thenReturn(Optional.of(row(1L, 10L, 0L)));

        service.receivePurchase(1L, 10L, "PO-001", "供应商A", 190_000L, LocalDate.now(), 7L);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(anyLong(), anyString(), anyString(), anyString(), detail.capture());
        assertThat(detail.getValue())
                .as("审计详情写进货单价 = 绕过 shop.cost_view 权限位")
                .doesNotContain("190000")
                .doesNotContain("190,000");
    }

    // ---------- 源码级护栏 ----------

    /** 剥掉注释再扫 —— 护栏要看的是代码，不是散文（Story 1.2 踩过这个坑）。 */
    private static String codeOnly(String path) throws IOException {
        return Files.readString(Path.of(path))
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    /**
     * 取某个仓储方法自己的 {@code @Query} 文本。
     *
     * <p>🔴 <b>必须按方法定位，不能只断言「文件里含某段 SQL」</b>：{@code lock()} 的 WHERE 与报损
     * 应有的 WHERE 逐字相同，整文件 {@code contains} 会被 {@code lock()} 满足，
     * 于是报损条件被改坏时护栏照样绿——变异验证实测过这个假阳性。
     */
    private static String queryOf(String code, String methodSignature) {
        int m = code.indexOf(methodSignature);
        assertThat(m).as("未找到方法 " + methodSignature).isNotNegative();
        String before = code.substring(0, m);
        int q = before.lastIndexOf("@Query(");
        assertThat(q).as(methodSignature + " 上方未找到 @Query").isNotNegative();
        return before.substring(q);
    }

    @Test
    @DisplayName("🔴 源码护栏：报损 SQL 的条件必须是可售（actual - locked），不是 actual")
    void damageSqlGuardsAvailableNotActual() throws IOException {
        String code = codeOnly(
                "src/main/java/com/tailtopia/shop/repository/SkuInventoryRepository.java");

        String damageQuery = queryOf(code, "int damage(");
        assertThat(damageQuery)
                .as("报损条件写成 i.actual >= :qty 会允许把已锁定的货报损掉，破坏 locked <= actual")
                .contains("i.actual - i.locked >= :qty");

        String stocktakeQuery = queryOf(code, "int stocktakeTo(");
        assertThat(stocktakeQuery)
                .as("盘点必须同时带 CAS 前值与锁定量下限两个条件")
                .contains("i.actual = :expectedBefore")
                .contains("i.locked <= :counted");
    }

    @Test
    @DisplayName("🔴 源码护栏：所有「写完要同事务读回」的原语都必须清持久化上下文")
    void primitivesThatAreReadBackClearPersistenceContext() throws IOException {
        String code = codeOnly(
                "src/main/java/com/tailtopia/shop/repository/SkuInventoryRepository.java");

        // 🔴 逐个原语点名，不用「出现次数 >= N」。
        //    原先写的是 >= 2（damage + stocktakeTo），于是 restock 漏标时护栏照样绿——
        //    而 restock 恰恰也走「UPDATE → 同事务读回 actual → 写流水前后值」这条路径。
        //    计数式断言的通病：加一个新的合规项就能替一个违规项把数凑够。
        for (String primitive : new String[] {"int restock(", "int damage(", "int stocktakeTo("}) {
            int m = code.indexOf(primitive);
            assertThat(m).as("未找到原语 " + primitive).isNotNegative();
            String block = code.substring(0, m);
            int lastModifying = block.lastIndexOf("@Modifying");
            assertThat(block.substring(lastModifying))
                    .as(primitive + " 未标 clearAutomatically —— 调用方同事务读回时会拿到一级缓存里的"
                            + "旧实体，前后值同步错位，而 delta 一致性 CHECK 拦不住（错位后仍自洽）")
                    .startsWith("@Modifying(clearAutomatically = true, flushAutomatically = true)");
        }
    }

    @Test
    @DisplayName("🔴 源码护栏：服务层不得出现分布式锁 / Redis / synchronized（NFR-1 · AD-6）")
    void noForbiddenConcurrencyMechanisms() throws IOException {
        String code = codeOnly(
                "src/main/java/com/tailtopia/shop/service/InventoryMovementService.java");
        assertThat(code).doesNotContain("synchronized");
        assertThat(code).doesNotContainIgnoringCase("RedisTemplate");
        assertThat(code).doesNotContainIgnoringCase("Redisson");
        assertThat(code).doesNotContainIgnoringCase("ReentrantLock");
        assertThat(code).doesNotContainIgnoringCase("for update");
    }

    @Test
    @DisplayName("🔴 源码护栏：流水仓储不得提供任何删改（append-only）")
    void movementRepositoryIsAppendOnly() throws IOException {
        String code = codeOnly(
                "src/main/java/com/tailtopia/shop/repository/InventoryMovementRepository.java");
        assertThat(code).doesNotContain("@Modifying");
        assertThat(code).doesNotContainIgnoringCase("delete");
        assertThat(code).doesNotContainIgnoringCase("update ");
    }

    @Test
    @DisplayName("🔴 流水实体所有字段 updatable=false —— JPA 层面也改不动历史")
    void movementFieldsAreImmutable() throws IOException {
        String code = codeOnly(
                "src/main/java/com/tailtopia/shop/domain/InventoryMovement.java");
        long columnCount = code.lines().filter(l -> l.contains("@Column")).count();
        long updatableFalse = code.lines().filter(l -> l.contains("updatable = false")).count();
        assertThat(updatableFalse)
                .as("append-only：每个 @Column 都应标 updatable = false")
                .isEqualTo(columnCount);
        // 实体不得暴露 setter
        for (Field f : com.tailtopia.shop.domain.InventoryMovement.class.getDeclaredFields()) {
            assertThat(com.tailtopia.shop.domain.InventoryMovement.class.getMethods())
                    .noneMatch(m -> m.getName().equalsIgnoreCase(
                            "set" + f.getName()));
        }
    }
}
