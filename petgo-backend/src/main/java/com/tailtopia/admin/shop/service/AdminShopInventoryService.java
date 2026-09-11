package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.shop.dto.InventoryRowView;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.domain.SkuInventory;
import com.tailtopia.shop.domain.StockStatus;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.shop.service.InventoryService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B18 库存页的<b>只读</b>取数（V1.3.0 Story 10.4 · AC1）。
 *
 * <p>🔴 <b>只读</b>：库存的增减一律走 {@code InventoryMovementService} 的四条原语，本类一行都不写。
 * 本 story 是壳层重构，零后端功能改动。
 */
@Service
public class AdminShopInventoryService {

    /** AC1「每页 20」。 */
    public static final int PAGE_SIZE = 20;

    private final InventoryService inventory;
    private final SkuInventoryRepository inventoryRows;
    private final ShopSkuRepository skus;
    private final ShopProductRepository products;

    public AdminShopInventoryService(InventoryService inventory,
            SkuInventoryRepository inventoryRows, ShopSkuRepository skus,
            ShopProductRepository products) {
        this.inventory = inventory;
        this.inventoryRows = inventoryRows;
        this.skus = skus;
        this.products = products;
    }

    /**
     * 一页 SKU（AC1）。
     *
     * <p>⚠️ <b>排序必须稳定且与库存无关</b>：按 {@code productId, id} 升序。
     * 若按可售库存排序，一次报损就会让后面的行整体前移 —— 运营点开抽屉做完一笔，
     * 回到列表时下一行已经不是刚才看到的那一行了，而这是<b>无声</b>的。
     * 排序稳定也是本 story 的处置成功只换「被操作的那一行」的前提（见 done 片段）。
     */
    @Transactional(readOnly = true)
    public Page<InventoryRowView> page(int page) {
        Page<ShopSku> skuPage = skus.findAll(PageRequest.of(Math.max(page, 0), PAGE_SIZE,
                Sort.by(Sort.Direction.ASC, "productId", "id")));
        // ⚠️ 这两次批量查询必须在 map 之前做一次：写成 `map(sku -> row(sku, names(…), stocks(…)))`
        //    的话，lambda 每行都会重跑一遍两条查询 —— 20 行就是 40 次往返，而页面看起来完全正常。
        Map<Long, String> names = names(skuPage.getContent());
        Map<Long, long[]> stocks = stocks(skuPage.getContent());
        return skuPage.map(sku -> row(sku, names, stocks));
    }

    /** 单行（抽屉页签与处置成功后的行 oob 用同一条口径）。 */
    @Transactional(readOnly = true)
    public InventoryRowView row(long skuId) {
        ShopSku sku = skus.findById(skuId).orElseThrow(() ->
                AppException.notFound("SKU 不存在")
                        .code("admin.err.product.skuNotFound2"));
        List<ShopSku> one = List.of(sku);
        return row(sku, names(one), stocks(one));
    }

    private InventoryRowView row(ShopSku sku, Map<Long, String> names, Map<Long, long[]> stocks) {
        long[] s = stocks.getOrDefault(sku.getId(), new long[] {0L, 0L});
        long actual = s[0];
        long locked = s[1];
        return InventoryRowView.of(sku.getId(), sku.getPublicToken(),
                names.getOrDefault(sku.getProductId(), "-"), sku.getSpecName(),
                actual, locked, inventory.statusOf(actual - locked));
    }

    /** 只取本页用到的商品名，避免把整张 shop_products 拉回来（原实现是 {@code products.findAll()}）。 */
    private Map<Long, String> names(List<ShopSku> page) {
        List<Long> productIds = page.stream().map(ShopSku::getProductId).distinct().toList();
        return productIds.isEmpty() ? Map.of()
                : products.findAllById(productIds).stream()
                        .collect(Collectors.toMap(ShopProduct::getId, ShopProduct::getName, (a, b) -> a));
    }

    /** skuId → {actual, locked}。⚠️ 没有库存行的 SKU 取 0/0（= 售罄），与 {@code availableBySkuId} 同口径。 */
    private Map<Long, long[]> stocks(List<ShopSku> page) {
        List<Long> ids = page.stream().map(ShopSku::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return inventoryRows.findBySkuIdIn(ids).stream().collect(Collectors.toMap(
                SkuInventory::getSkuId, r -> new long[] {r.getActual(), r.getLocked()},
                (a, b) -> a));
    }

    /**
     * 摘要条三格（AC1）：售罄 SKU 数 · 低库存 SKU 数 · 锁定库存合计。
     *
     * <p>🔴 <b>分档判据只有一份</b>：这里逐行调 {@link InventoryService#statusOf}，
     * 而不是写一条 {@code count(*) FILTER (WHERE actual - locked <= :threshold)} 的聚合 SQL。
     * 阈值是配置项（{@code petgo.shop.low-stock-threshold}），聚合 SQL 等于把「什么叫低库存」
     * 抄第二遍 —— 阈值或分档规则一改，摘要条和状态列会给出<b>互相矛盾的两个答案</b>，
     * 而页面上完全看不出来（「低库存 0」配着一列黄色的「库存紧张」）。
     *
     * <p>⚠️ 代价是把 {@code sku_inventory} 整表拉回来算。V1 的 SKU 量级是几百，
     * 这条只在库存页首屏跑一次；真到需要聚合 SQL 的量级时，要连状态列一起改成同一条查询，
     * 不能只改这里。
     *
     * <p>🔴 <b>没有库存行的 SKU 也算售罄</b>：新建 SKU 到首次入库之间就是这个状态，
     * 而它恰恰是运营最需要看到的那一类（「上架了却一件都卖不出去」）。
     * 只对 {@code sku_inventory} 聚合会把这些 SKU 整个漏掉。
     */
    @Transactional(readOnly = true)
    public Summary summary() {
        long skuTotal = skus.count();
        List<SkuInventory> rows = inventoryRows.findAll();
        long out = 0;
        long low = 0;
        long lockedTotal = 0;
        for (SkuInventory r : rows) {
            StockStatus status = inventory.statusOf(r.getActual() - r.getLocked());
            if (status == StockStatus.OUT_OF_STOCK) {
                out++;
            } else if (status == StockStatus.LOW_STOCK) {
                low++;
            }
            lockedTotal += r.getLocked();
        }
        out += Math.max(0, skuTotal - rows.size());
        return new Summary(out, low, lockedTotal);
    }

    /** 摘要条三格（AC1）。 */
    public record Summary(long outOfStock, long lowStock, long lockedTotal) { }

    /** 抽屉上半的流水摘要条数（AC2「最近 N 条 + 查看全部流水」）。 */
    public static final int DRAWER_MOVEMENTS = 8;
}
