package com.tailtopia.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.StockStatus;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * L0：库存原语（Story 1.2 AC2/AC3/AC4，🔒 安全攸关）。
 *
 * <p>本类验的核心不是「数字算对了」，而是<b>防超卖的实现方式没有被写坏</b>：
 * 四条原语必须靠<b>单条条件原子 UPDATE 的影响行数</b>判定成败，
 * 且服务层<b>不得出现「先查可售、再扣减」</b>——后者在单线程下 100% 正确、在并发下静默超卖。
 */
class InventoryServiceTest {

    private SkuInventoryRepository repo;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(SkuInventoryRepository.class);
        service = new InventoryService(repo, 5L);
    }

    // ---------- AC2 锁定 ----------

    @Test
    @DisplayName("lock：影响 1 行 → 成功")
    void lockSucceedsWhenOneRowAffected() {
        when(repo.lock(1L, 2L)).thenReturn(1);
        service.lock(1L, 2L);
        Mockito.verify(repo).lock(1L, 2L);
    }

    @Test
    @DisplayName("🔒 lock：影响 0 行 → 抛「已售罄」，不静默、不重试")
    void lockThrowsWhenZeroRowsAffected() {
        when(repo.lock(1L, 99L)).thenReturn(0);

        assertThatThrownBy(() -> service.lock(1L, 99L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已售罄");

        // 不得有任何重试
        Mockito.verify(repo, Mockito.times(1)).lock(1L, 99L);
    }

    @Test
    @DisplayName("🔒 lock 全程不做任何预查询 —— 「先查后改」是超卖的唯一成因")
    void lockNeverPreReads() {
        when(repo.lock(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong())).thenReturn(1);

        service.lock(1L, 3L);

        Mockito.verify(repo, Mockito.never()).findBySkuId(ArgumentMatchers.anyLong());
        Mockito.verify(repo, Mockito.never()).findBySkuIdIn(ArgumentMatchers.anyList());
        Mockito.verify(repo, Mockito.never()).findById(ArgumentMatchers.anyLong());
    }

    // ---------- AC3 其余三条 ----------

    @Test
    @DisplayName("release：影响 0 行 → 抛错而非静默吞掉（状态机不一致必须暴露）")
    void releaseThrowsWhenZeroRows() {
        when(repo.release(1L, 2L)).thenReturn(0);
        assertThatThrownBy(() -> service.release(1L, 2L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("释放");
    }

    @Test
    @DisplayName("commit：影响 0 行 → 抛错")
    void commitThrowsWhenZeroRows() {
        when(repo.commit(1L, 2L)).thenReturn(0);
        assertThatThrownBy(() -> service.commit(1L, 2L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("出库");
    }

    @Test
    @DisplayName("restock：影响 0 行 → 库存记录不存在")
    void restockThrowsWhenZeroRows() {
        when(repo.restock(1L, 2L)).thenReturn(0);
        assertThatThrownBy(() -> service.restock(1L, 2L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("四条原语均拒绝非正数量")
    void allPrimitivesRejectNonPositiveQty() {
        for (long bad : new long[] {0L, -1L}) {
            assertThatThrownBy(() -> service.lock(1L, bad)).isInstanceOf(AppException.class);
            assertThatThrownBy(() -> service.release(1L, bad)).isInstanceOf(AppException.class);
            assertThatThrownBy(() -> service.commit(1L, bad)).isInstanceOf(AppException.class);
            assertThatThrownBy(() -> service.restock(1L, bad)).isInstanceOf(AppException.class);
        }
        Mockito.verifyNoInteractions(repo);
    }

    // ---------- AC4 三态 ----------

    @Test
    @DisplayName("三态边界：0 → 售罄；1..阈值 → 低库存；阈值+1 → 充足")
    void stockStatusBoundaries() {
        assertThat(service.statusOf(0)).isEqualTo(StockStatus.OUT_OF_STOCK);
        assertThat(service.statusOf(1)).isEqualTo(StockStatus.LOW_STOCK);
        assertThat(service.statusOf(5)).isEqualTo(StockStatus.LOW_STOCK);
        assertThat(service.statusOf(6)).isEqualTo(StockStatus.IN_STOCK);
    }

    @Test
    @DisplayName("负可售（理论上 DB CHECK 已挡住）仍判为售罄，不抛异常")
    void negativeAvailableIsOutOfStock() {
        assertThat(service.statusOf(-3)).isEqualTo(StockStatus.OUT_OF_STOCK);
    }

    @Test
    @DisplayName("阈值来自配置而非硬编码")
    void thresholdComesFromConfig() {
        InventoryService custom = new InventoryService(repo, 20L);
        assertThat(custom.lowStockThreshold()).isEqualTo(20L);
        assertThat(custom.statusOf(20)).isEqualTo(StockStatus.LOW_STOCK);
        assertThat(custom.statusOf(21)).isEqualTo(StockStatus.IN_STOCK);
    }

    @Test
    @DisplayName("批量取可售：空入参短路，不查库")
    void availableBySkuIdShortCircuitsOnEmpty() {
        assertThat(service.availableBySkuId(java.util.List.of())).isEmpty();
        Mockito.verify(repo, Mockito.never()).findBySkuIdIn(ArgumentMatchers.anyList());
    }

    // ---------- 源码级护栏 ----------

    /**
     * 剥掉注释后再扫源码 —— 护栏要看的是<b>代码</b>，不是散文。
     * （否则 javadoc 里写「禁 SELECT ... FOR UPDATE」这句话本身就会把护栏绊倒。）
     */
    private static String codeOnly(String path) throws IOException {
        String src = Files.readString(Path.of(path));
        return src.replaceAll("(?s)/\\*.*?\\*/", " ")   // 块注释与 javadoc
                  .replaceAll("(?m)//.*$", " ");            // 行注释
    }

    @Test
    @DisplayName("🔴 源码护栏：InventoryService 代码内不得出现分布式锁 / Redis / synchronized")
    void noForbiddenConcurrencyMechanisms() throws IOException {
        String code = codeOnly("src/main/java/com/tailtopia/shop/service/InventoryService.java");
        assertThat(code).doesNotContain("synchronized");
        assertThat(code).doesNotContainIgnoringCase("RedisTemplate");
        assertThat(code).doesNotContainIgnoringCase("Redisson");
        assertThat(code).doesNotContainIgnoringCase("ReentrantLock");
        assertThat(code).doesNotContainIgnoringCase("for update");
    }

    @Test
    @DisplayName("🔴 源码护栏：四条原语的 SQL 必须带条件 WHERE，且不得用 FOR UPDATE")
    void repositoryQueriesAreConditional() throws IOException {
        String code = codeOnly(
                "src/main/java/com/tailtopia/shop/repository/SkuInventoryRepository.java");
        assertThat(code).doesNotContainIgnoringCase("for update");
        // lock 必须带可售充足的条件，否则就是无条件加锁量 = 超卖
        assertThat(code).contains("i.actual - i.locked >= :qty");
        // release / commit 必须带锁定量充足的条件
        assertThat(code).contains("i.locked >= :qty");
    }
}
