package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.shop.dto.ShopProductForm;
import com.tailtopia.admin.shop.dto.ShopSkuForm;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.FeedingGuideEntry;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品与 SKU 后台维护（Story 1.3，AB-10A / AB-10B）。
 *
 * <p><b>校验一律在 service 层</b>（照 {@code VetQualificationService.requireFullInput} 范式），
 * 不只依赖前端——后台表单同样可被绕过。
 *
 * <p>🔒 <b>进货价的权限门控是结构性的</b>：本类的写方法<b>不接收进货价</b>，
 * 它走单独的 {@link #updateCostPrice}，由控制器在校验 {@code shop.cost_edit} 后才调用。
 * 这样「忘记判权限」就不再是可能发生的失误。
 */
@Service
public class AdminShopProductService {

    private final ShopProductRepository products;
    private final ShopSkuRepository skus;
    private final InventoryService inventory;
    private final ShopTokenGenerator tokens;
    private final AdminAuditService audit;

    public AdminShopProductService(ShopProductRepository products, ShopSkuRepository skus,
            InventoryService inventory, ShopTokenGenerator tokens, AdminAuditService audit) {
        this.products = products;
        this.skus = skus;
        this.inventory = inventory;
        this.tokens = tokens;
        this.audit = audit;
    }

    // ---------- 商品 ----------

    @Transactional
    public ShopProduct create(ShopProductForm form, long actorAccountId) {
        validate(form);
        ShopProduct p = ShopProduct.create(tokens.generate(), form.getName().trim(),
                form.getBrand().trim(), form.getCategory(), form.getMainImageKey().trim(),
                form.galleryKeys(), form.getSpecies(), form.getBodySize(), form.getAgeStage(),
                form.getDetailHtml(), form.feedingGuide(), form.getShelfLifeNote().trim(),
                form.getReturnPolicy(), form.getSortWeight());
        products.save(p);
        audit.record(actorAccountId, AuditActions.SHOP_PRODUCT_CREATED,
                "SHOP_PRODUCT", p.getPublicToken(), "创建商品：" + p.getName());
        return p;
    }

    @Transactional
    public ShopProduct update(long productId, ShopProductForm form, long actorAccountId) {
        validate(form);
        ShopProduct p = products.findById(productId)
                .orElseThrow(() -> AppException.notFound("商品不存在").code("admin.err.product.notFound"));
        p.apply(form.getName().trim(), form.getBrand().trim(), form.getCategory(),
                form.getMainImageKey().trim(), form.galleryKeys(), form.getSpecies(),
                form.getBodySize(), form.getAgeStage(), form.getDetailHtml(),
                form.feedingGuide(), form.getShelfLifeNote().trim(), form.getReturnPolicy(),
                form.getSortWeight());
        products.save(p);
        audit.record(actorAccountId, AuditActions.SHOP_PRODUCT_UPDATED,
                "SHOP_PRODUCT", p.getPublicToken(), "编辑商品：" + p.getName());
        return p;
    }

    // ---------- SKU ----------

    /**
     * 新建或更新 SKU。🔒 <b>本方法不接收进货价</b>——见类注释。
     *
     * <p>新建时同步建库存行（{@code actual=0}），使 1.2 的原语在下单时不会因缺行而失败。
     */
    @Transactional
    public ShopSku upsertSku(long productId, ShopSkuForm form, long actorAccountId) {
        validateSku(form);
        products.findById(productId).orElseThrow(() -> AppException.notFound("商品不存在").code("admin.err.product.notFound"));
        ShopSku sku;
        boolean created = false;
        if (form.getId() == null) {
            sku = ShopSku.create(tokens.generate(), productId, form.getSpecName().trim(),
                    form.getPrice(), form.getNetWeightG(), form.getReturnPolicy());
            created = true;
        } else {
            sku = skus.findById(form.getId())
                    .orElseThrow(() -> AppException.notFound("规格不存在").code("admin.err.product.skuNotFound"));
            if (!sku.getProductId().equals(productId)) {
                // 越权改他商品的 SKU：按 404 处理，不泄露存在性
                throw AppException.notFound("规格不存在").code("admin.err.product.skuNotFound");
            }
            sku.apply(form.getSpecName().trim(), form.getPrice(), form.getNetWeightG(),
                    form.getReturnPolicy());
        }
        skus.save(sku);
        if (created) {
            // 建库存行：下单锁定（1.2 lock）在无行时会返回影响 0 行 = 售罄，故必须先建
            inventory.ensureRow(sku.getId());
        }
        audit.record(actorAccountId, AuditActions.SHOP_SKU_UPSERTED,
                "SHOP_SKU", sku.getPublicToken(),
                (created ? "新建规格：" : "更新规格：") + sku.getSpecName());
        return sku;
    }

    /**
     * 🔒 单独更新进货价 —— 调用方必须先校验 {@code shop.cost_edit}。
     *
     * <p>🔴 <b>审计详情不写数值</b>：审计日志页的可见范围与进货价权限不同，
     * 写进去等于绕过权限位。
     */
    @Transactional
    public void updateCostPrice(long skuId, Long costPrice, long actorAccountId) {
        if (costPrice != null && costPrice < 0) {
            throw AppException.validation("进货价不能为负").code("admin.err.product.costNegative");
        }
        ShopSku sku = skus.findById(skuId)
                .orElseThrow(() -> AppException.notFound("规格不存在").code("admin.err.product.skuNotFound"));
        sku.applyCostPrice(costPrice);
        skus.save(sku);
        audit.record(actorAccountId, AuditActions.SHOP_PRODUCT_COST_UPDATED,
                "SHOP_SKU", sku.getPublicToken(), "更新了进货价");
    }

    // ---------- 校验 ----------

    /** FR-94 必填项 + 喂量结构。违规抛 {@code validation}，页面回显且保留已填内容。 */
    void validate(ShopProductForm f) {
        requireText(f.getName(), "商品名称", "admin.err.product.nameRequired");
        if (f.getName().trim().length() > 60) {
            throw AppException.validation("商品名称不得超过 60 字符").code("admin.err.product.nameTooLong");
        }
        requireText(f.getBrand(), "品牌", "admin.err.product.brandRequired");
        if (f.getCategory() == null) {
            throw AppException.validation("请选择品类").code("admin.err.product.categoryRequired");
        }
        requireText(f.getMainImageKey(), "主图", "admin.err.product.mainImageRequired");
        if (f.getSpecies() == null) {
            throw AppException.validation("请选择适用物种").code("admin.err.product.speciesRequired");
        }
        requireText(f.getDetailHtml(), "商品详情", "admin.err.product.detailRequired");
        requireText(f.getShelfLifeNote(), "保质期说明", "admin.err.product.shelfLifeRequired");
        if (f.getReturnPolicy() == null) {
            throw AppException.validation("请选择退货规则").code("admin.err.product.returnPolicyRequired");
        }
        if (f.galleryKeys().size() > 8) {
            throw AppException.validation("图集最多 8 张").code("admin.err.product.galleryMax");
        }
        validateFeedingGuide(f.feedingGuide());
    }

    /**
     * 喂量结构校验（AC2）。
     *
     * <p>🔴 <b>Makanan 品类留空不阻断</b>——数据依赖 DEP-6（Rendy 未交付），强制必填会直接
     * 卡死商品录入，而商品录不进去整个 Epic 1 就白做了。留空的后果由 {@link #feedingGuideWarning}
     * 在页面上显著提示。<b>这是有意识的偏离，待 DEP-6 到位后收紧。</b>
     */
    void validateFeedingGuide(List<FeedingGuideEntry> guide) {
        if (guide.isEmpty()) {
            return;
        }
        for (FeedingGuideEntry e : guide) {
            if (e.weightMinKg() <= 0 || e.weightMaxKg() <= 0 || e.gramsPerDay() <= 0) {
                throw AppException.validation("每日建议喂量的体重与克数必须为正整数").code("admin.err.product.feedingPositive");
            }
            if (e.weightMinKg() >= e.weightMaxKg()) {
                throw AppException.validation("体重区间的下限必须小于上限").code("admin.err.product.feedingRangeOrder");
            }
        }
        List<FeedingGuideEntry> sorted = guide.stream()
                .sorted(Comparator.comparingInt(FeedingGuideEntry::weightMinKg)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).weightMinKg() < sorted.get(i - 1).weightMaxKg()) {
                throw AppException.validation("体重区间不得重叠").code("admin.err.product.feedingRangeOverlap");
            }
        }
    }

    /**
     * Makanan 品类未填喂量时的警告文案（AC2）。返回 null 表示无需警告。
     *
     * <p>🔴 这句话必须出现在页面上——它是 FR-109 能否成立的唯一提醒点。
     */
    public String feedingGuideWarning(ProductCategory category, List<FeedingGuideEntry> guide) {
        if (category == ProductCategory.MAKANAN && (guide == null || guide.isEmpty())) {
            return "此商品为 Makanan 但未填写每日建议喂量。该字段是粮量见底预估（复购提醒）的"
                    + "唯一计算依据，留空将导致该商品永远不会触发补货提醒。";
        }
        return null;
    }

    void validateSku(ShopSkuForm f) {
        requireText(f.getSpecName(), "规格名", "admin.err.product.specNameRequired");
        if (f.getPrice() == null || f.getPrice() < 0) {
            throw AppException.validation("价格必须为非负整数").code("admin.err.product.priceNonNegative");
        }
        if (f.getNetWeightG() != null && f.getNetWeightG() <= 0) {
            throw AppException.validation("净含量必须为正").code("admin.err.product.netWeightPositive");
        }
    }

    /**
     * 必填校验。{@code label} 只作日志/兜底原文，真正展示给运营的是 {@code code} 对应的三语文案——
     * 刻意一个字段一个码，而不是把字段名当参数塞进一条通用文案：后者要求解析时再解析一层嵌套 key，
     * 而印尼语/英语里「XX 不能为空」的语序也未必能用同一个模板套出来。
     */
    private static void requireText(String v, String label, String code) {
        if (v == null || v.isBlank()) {
            throw AppException.validation(label + "不能为空").code(code);
        }
    }
}
