package com.tailtopia.shop.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.dto.ShopProductDetailView;
import com.tailtopia.shop.dto.ShopProductPageResponse;
import com.tailtopia.shop.dto.ShopProductSummaryView;
import com.tailtopia.shop.dto.ShopSkuView;
import com.tailtopia.shop.domain.StockStatus;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品只读查询（Story 1.1，FR-94 / FR-94A）。
 *
 * <p><b>只读</b>：写入（创建/编辑/上下架/库存）属 Story 1.3 / 1.5，本 Story 不提供任何写方法。
 *
 * <p>🔴 <b>对外一律按 {@code publicToken} 寻址</b>，自增 id 不出现在任何返回体中（NFR-3）。
 * 未知 token 返回 <b>404 而非 403</b>——与 {@code HealthRecordController} 同范式，防枚举探测。
 *
 * <p>🔴 <b>不引入任何缓存</b>（NFR-1 禁通用缓存层）。SKU 上限 30（C-7），直查即可。
 */
@Service
public class ShopProductQueryService {

    private final ShopProductRepository products;
    private final ShopSkuRepository skus;
    private final InventoryService inventory;
    private final ShopImageUrlResolver imageUrls;

    public ShopProductQueryService(ShopProductRepository products, ShopSkuRepository skus,
            InventoryService inventory, ShopImageUrlResolver imageUrls) {
        this.products = products;
        this.skus = skus;
        this.inventory = inventory;
        this.imageUrls = imageUrls;
    }

    /**
     * 商品列表（FR-93 区域③④）。仅返回已上架商品；{@code category} 为 null 时不筛选。
     *
     * <p>排序为「运营权重降序 + id 降序兜底」，同权重时顺序稳定。
     */
    @Transactional(readOnly = true)
    public List<ShopProductSummaryView> list(ProductCategory category) {
        return list(category, null);
    }

    /**
     * 商品列表 + 关键词搜索（2026-08-31）。
     *
     * <p>{@code query} 命中 <b>name 或 brand</b>，忽略大小写；为空/全空白时行为与
     * {@link #list(ProductCategory)} <b>逐字相同</b>——搜索框清空必须原样回到列表，
     * 而不是变成「搜了个空串」的另一条分支。
     *
     * <p>🔴 与 {@code category} <b>是与关系，不是互斥</b>：选了 Makanan 再搜「royal」
     * 只在 Makanan 里搜。做成互斥（一搜就清掉品类）会让用户以为筛选没生效。
     */
    @Transactional(readOnly = true)
    public List<ShopProductSummaryView> list(ProductCategory category, String query) {
        String pattern = likePattern(query);
        List<ShopProduct> rows;
        if (pattern == null) {
            rows = category == null
                    ? products.findByActiveTrueOrderBySortWeightDescIdDesc()
                    : products.findByActiveTrueAndCategoryOrderBySortWeightDescIdDesc(category);
        } else {
            rows = category == null
                    ? products.searchActive(pattern)
                    : products.searchActiveByCategory(pattern, category);
        }
        return toViews(rows);
    }

    /**
     * 行 → 视图（含最低价批量查，避免 N+1）。
     *
     * <p>🔴 <b>全量与分页共用这一处组装</b>：两处各拼一遍，迟早出现「不翻页时有图、
     * 翻页后没图」这种只在某一条路径上出现的差异。
     */
    private List<ShopProductSummaryView> toViews(List<ShopProduct> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> minPriceByProduct = minPriceByProduct(rows);
        List<ShopProductSummaryView> out = new ArrayList<>(rows.size());
        for (ShopProduct p : rows) {
            out.add(new ShopProductSummaryView(
                    p.getPublicToken(),
                    p.getName(),
                    p.getBrand(),
                    p.getCategory(),
                    p.getMainImageKey(),
                    imageUrls.publicUrl(p.getMainImageKey()),
                    p.getMainImageW(),
                    p.getMainImageH(),
                    p.getSpecies(),
                    minPriceByProduct.get(p.getId())));
        }
        return out;
    }

    /** 每页默认条数（SHOP-NFR-03）。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 页长上限 —— 客户端传个 10000 就等于退化成全量查询，那正是本 story 要消除的东西。 */
    static final int MAX_PAGE_SIZE = 100;

    /**
     * 商品列表 · 游标分页（Story 4-5，SHOP-FR-13 / SHOP-NFR-03）。
     *
     * <p>🔴 <b>{@code category} 与 {@code query} 的语义与两个全量重载逐字相同</b>：
     * 空白 {@code query} 等同不传、与 {@code category} 是与关系。
     * 这里<b>复用同一个 {@link #likePattern} 与同一套排序</b> ——
     * 分页另起一套过滤或排序，就会出现「不翻页看到的列表」和「翻页看到的列表」不是同一个。
     *
     * <p>🔴 <b>keyset 而非 OFFSET</b>：运营在用户翻页期间调了权重，OFFSET 会让同一件商品
     * 在两页里出现两次，或者一次都不出现。
     *
     * @param cursor 上一页末条的游标；null / 空白 = 从头开始（{@link ShopProductCursor#START}）
     * @param size   页长；null 或非正取默认 20，上限 {@value #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true)
    public ShopProductPageResponse page(ProductCategory category, String query, String cursor,
            Integer size) {
        int limit = normalizeSize(size);
        ShopProductCursor from = ShopProductCursor.decode(cursor);
        String pattern = likePattern(query);

        // 多取一条用来判断 hasMore —— 比再打一次 count 查询便宜，且不会因为两次查询
        // 之间有商品上下架而自相矛盾。
        Pageable probe = PageRequest.of(0, limit + 1);
        List<ShopProduct> rows;
        if (pattern == null) {
            rows = category == null
                    ? products.pageActive(from.sortWeight(), from.id(), probe)
                    : products.pageActiveByCategory(category, from.sortWeight(), from.id(), probe);
        } else {
            rows = category == null
                    ? products.pageSearchActive(pattern, from.sortWeight(), from.id(), probe)
                    : products.pageSearchActiveByCategory(pattern, category, from.sortWeight(),
                            from.id(), probe);
        }

        boolean hasMore = rows.size() > limit;
        List<ShopProduct> page = hasMore ? rows.subList(0, limit) : rows;
        // 🔴 无结果 → 空 items，不是 404：「这个品类下暂时没有商品」是一个正常答案。
        if (page.isEmpty()) {
            return new ShopProductPageResponse(List.of(), null, false);
        }
        ShopProduct last = page.get(page.size() - 1);
        return new ShopProductPageResponse(toViews(page),
                // 末页 nextCursor 为 null → NON_NULL 下整键省略（与 Feed 信封同款）
                hasMore ? new ShopProductCursor(last.getSortWeight(), last.getId()).encode() : null,
                hasMore);
    }

    private static int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 商品详情 + 其 SKU 列表（FR-94 / FR-94A）。
     *
     * @throws AppException 未上架或不存在 → {@code notFound}（404，非 403 —— 防枚举）
     */
    @Transactional(readOnly = true)
    public ShopProductDetailView detail(String publicToken) {
        ShopProduct p = products.findByPublicTokenAndActiveTrue(publicToken)
                .orElseThrow(() -> AppException.notFound("商品不存在"));
        List<ShopSku> rows = skus.findByProductIdOrderByIdAsc(p.getId());
        // 批量取库存，避免 N+1；无库存行的 SKU 视为可售 0（售罄）
        Map<Long, Long> availableBySku =
                inventory.availableBySkuId(rows.stream().map(ShopSku::getId).toList());
        List<ShopSkuView> skuViews = rows.stream()
                .map(s -> {
                    long available = availableBySku.getOrDefault(s.getId(), 0L);
                    StockStatus status = inventory.statusOf(available);
                    return new ShopSkuView(
                            s.getPublicToken(),
                            s.getSpecName(),
                            s.getPrice(),
                            s.getNetWeightG(),
                            // SKU 级为空时继承商品级，前端直接展示（FR-94A / FR-104 第 1 处明示）
                            s.effectiveReturnPolicy(p.getReturnPolicy()),
                            status,
                            // 🔴 仅低库存时给真实剩余数；售罄/充足给 null（不虚构、不泄露经营数据）
                            status == StockStatus.LOW_STOCK ? available : null);
                })
                .toList();
        return new ShopProductDetailView(
                p.getPublicToken(),
                p.getName(),
                p.getBrand(),
                p.getCategory(),
                p.getMainImageKey(),
                imageUrls.publicUrl(p.getMainImageKey()),
                p.getGalleryKeys(),
                imageUrls.publicUrls(p.getGalleryKeys()),
                p.getSpecies(),
                p.getBodySize(),
                p.getAgeStage(),
                p.getDetailHtml(),
                p.getFeedingGuide(),
                p.getShelfLifeNote(),
                p.getReturnPolicy(),
                skuViews);
    }

    /** 一次取回本页商品的全部 SKU，按商品聚出最低价——避免 N+1。 */
    private Map<Long, Long> minPriceByProduct(List<ShopProduct> rows) {
        List<Long> ids = rows.stream().map(ShopProduct::getId).toList();
        return skus.findByProductIdInOrderByIdAsc(ids).stream()
                .collect(Collectors.toMap(
                        ShopSku::getProductId,
                        ShopSku::getPrice,
                        Math::min));
    }

    /**
     * 把用户输入变成一条可安全用于 {@code like} 的 pattern；空输入返回 {@code null}（= 不搜）。
     *
     * <p>🔴 <b>必须转义 {@code \ % _}</b>：不转的话用户敲一个 {@code %} 就是「匹配全部」，
     * 敲 {@code _} 就成了通配单字——搜索结果会莫名其妙地多出东西，而且没人能从
     * 输入框里看出为什么。转义字符与仓储 {@code escape '\'} 声明的必须是同一个。
     *
     * <p>⚠️ 顺序要紧：反斜杠<b>必须先转</b>，否则会把后面刚加上去的转义符再转一遍。
     *
     * <p>大小写用 {@link java.util.Locale#ROOT} 归一——印尼语无特殊大小写映射，
     * 但用默认 locale 会让结果随服务器区域设置漂移（土耳其语 I/ı 是经典坑）。
     */
    static String likePattern(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String escaped = raw.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
