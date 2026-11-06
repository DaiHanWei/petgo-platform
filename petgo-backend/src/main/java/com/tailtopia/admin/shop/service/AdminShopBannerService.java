package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.shop.dto.ShopBannerForm;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponStatus;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.domain.BannerTargetType;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.repository.ShopBannerRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.service.ShopBannerQueryService;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Toko 顶部 banner 的后台维护（2026-08-27）。
 *
 * <p>🔴 <b>每一次变更都写审计</b>：banner 是所有用户进 Toko 第一眼看到的东西，
 * 误配的影响面比改一个商品大得多 —— 出问题时要能立刻回答「谁把哪张推上去的」。
 */
@Service
public class AdminShopBannerService {

    private final ShopBannerRepository banners;
    private final AdminAuditService audit;
    private final ShopProductRepository products;
    private final ShopCouponRepository coupons;
    private final ShopBannerQueryService liveQuery;

    public AdminShopBannerService(ShopBannerRepository banners, AdminAuditService audit,
            ShopProductRepository products, ShopCouponRepository coupons, ShopBannerQueryService liveQuery) {
        this.banners = banners;
        this.audit = audit;
        this.products = products;
        this.coupons = coupons;
        this.liveQuery = liveQuery;
    }

    // ---------- V1.3.0 Story 10.3：三档状态（AC3） ----------

    /** 后台列表：全部 banner，按 App 的取用顺序排列（权重降序、同权重取后建的）。 */
    @Transactional(readOnly = true)
    public java.util.List<ShopBanner> all() {
        return banners.findAllByOrderBySortWeightDescIdDesc();
    }

    /**
     * 当前 App 轮播里的第一张（{@code null} = 一张生效的都没有）；旧版 App 单张端点取的也是它。
     *
     * <p>🔴 <b>刻意调 App 端那个取用方法</b>（v1.4.2 起为 {@code ShopBannerQueryService.liveBanners}），
     * 而不是在后台列表里自己「取第一条 active」：那是两份各自演化的判据 —— v1.4.2 正好加了排期与目标有效过滤，
     * 后台若自己挑，「生效中」标就会指错一张，而<b>界面上完全看不出来</b>。AC3「与 App 端取图规则同源」取的就是
     * 「同一个方法」这个更强的解释。
     */
    @Transactional(readOnly = true)
    public Long liveId() {
        return liveQuery.liveBanners(Instant.now()).stream()
                .map(l -> l.banner().getId()).findFirst().orElse(null);
    }

    /**
     * 全部生效张的 id（v1.4.2 Story 6.1 AC7.3，轮播最多 6 张，按取用顺序）。同源于 {@code liveBanners}。
     * 后台页面升级为多张「生效中」由后台线完成。
     */
    @Transactional(readOnly = true)
    public Set<Long> liveIds() {
        Set<Long> ids = new LinkedHashSet<>();
        liveQuery.liveBanners(Instant.now()).forEach(l -> ids.add(l.banner().getId()));
        return ids;
    }

    /**
     * 三档状态（AC3）：{@code live} 生效中 · {@code activeNotLive} 已上架但被更高权重压住 · {@code inactive} 未上架。
     *
     * <p>只显示启用 / 停用两档会让运营反复怀疑「我明明上架了为什么没显示」——
     * App 同一时间只展示一张，被压住的那些和没上架的在效果上完全一样，但处置动作完全不同。
     */
    public static String stateOf(ShopBanner b, Long liveId) {
        if (!b.isActive()) {
            return "inactive";
        }
        return b.getId().equals(liveId) ? "live" : "activeNotLive";
    }

    @Transactional
    public ShopBanner create(ShopBannerForm form, long actorAccountId) {
        validate(form);
        Target target = target(form);
        ShopBanner b = ShopBanner.create(form.getImageKey().trim(), form.getImageW(),
                form.getImageH(), form.getSortWeight());
        b.retarget(target.type(), target.value());
        b.schedule(form.getStartAt(), form.getEndAt());
        banners.save(b);
        audit.record(actorAccountId, AuditActions.SHOP_BANNER_CREATED, "SHOP_BANNER",
                String.valueOf(b.getId()), "新建 banner（默认未上架）");
        return b;
    }

    @Transactional
    public void update(long id, ShopBannerForm form, long actorAccountId) {
        validate(form);
        Target target = target(form);
        ShopBanner b = require(id);
        b.apply(form.getImageKey().trim(), form.getImageW(), form.getImageH(),
                form.getSortWeight());
        b.retarget(target.type(), target.value());
        b.schedule(form.getStartAt(), form.getEndAt());
        banners.save(b);
        audit.record(actorAccountId, AuditActions.SHOP_BANNER_UPDATED, "SHOP_BANNER",
                String.valueOf(id), "编辑 banner");
    }

    /**
     * 上架。
     *
     * <p>⚠️ <b>不自动下架其他 banner</b>：产品口径是「同一时间只展示一张」，而这条口径
     * 由读取端保证（取权重最高的那一条），不是靠写入端维持"全表只有一条 active"。
     * 若在这里顺手把别的下架掉，运营调权重试排序时会不断丢掉上一次的上架状态 ——
     * 那种"我明明上架了怎么又没了"的困惑，比多几条 active 行难查得多。
     */
    @Transactional
    public void activate(long id, long actorAccountId) {
        ShopBanner b = require(id);
        b.activate();
        banners.save(b);
        audit.record(actorAccountId, AuditActions.SHOP_BANNER_ACTIVATED, "SHOP_BANNER",
                String.valueOf(id), "上架 banner");
    }

    @Transactional
    public void deactivate(long id, long actorAccountId) {
        ShopBanner b = require(id);
        b.deactivate();
        banners.save(b);
        audit.record(actorAccountId, AuditActions.SHOP_BANNER_DEACTIVATED, "SHOP_BANNER",
                String.valueOf(id), "下架 banner");
    }

    /**
     * 删除。
     *
     * <p>🔴 <b>已上架的不允许直接删</b>：删一条正在首屏展示的 banner 是不可撤销的，
     * 而它与「下架」在运营眼里长得很像。强制先下架 —— 多一步，但那一步会让人
     * 看到首屏确实换掉了，再决定要不要永久删除。
     */
    @Transactional
    public void delete(long id, long actorAccountId) {
        ShopBanner b = require(id);
        if (b.isActive()) {
            throw AppException.validation("请先下架再删除")
                    .code("admin.err.banner.deleteActive");
        }
        banners.delete(b);
        audit.record(actorAccountId, AuditActions.SHOP_BANNER_DELETED, "SHOP_BANNER",
                String.valueOf(id), "删除 banner");
    }

    /** 单条（写入路径与 V1.3.0 Story 10.3 的抽屉编辑态回填共用同一条「不存在即 404」口径）。 */
    @Transactional(readOnly = true)
    public ShopBanner require(long id) {
        return banners.findById(id)
                .orElseThrow(() -> AppException.notFound("banner 不存在")
                        .code("admin.err.banner.notFound"));
    }

    record Target(BannerTargetType type, String value) {
    }

    /**
     * 点击跳转校验（V1.4.1）。🔴 每类都校验到「App 点下去一定能落地」：
     * 商品须存在且已上架（下架商品点进去是空页）；品类须是合法字面量；外链只允许 https（不让运营配出
     * http / javascript: / 自定义 scheme —— App 会把它交给系统浏览器打开）；券须为 ACTIVE 的 CLAIM 类且未过发放期止（v1.4.2）。
     */
    Target target(ShopBannerForm f) {
        String raw = f.getTargetType() == null ? "" : f.getTargetType().trim();
        BannerTargetType type;
        try {
            type = raw.isEmpty() ? BannerTargetType.NONE : BannerTargetType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw AppException.validation("跳转类型不合法").code("admin.err.banner.targetInvalid");
        }
        String value = f.getTargetValue() == null ? "" : f.getTargetValue().trim();
        switch (type) {
            case NONE -> {
                return new Target(BannerTargetType.NONE, null);
            }
            case PRODUCT -> {
                if (value.isEmpty() || products.findByPublicTokenAndActiveTrue(value).isEmpty()) {
                    throw AppException.validation("商品不存在或未上架")
                            .code("admin.err.banner.targetProduct");
                }
            }
            case CATEGORY -> {
                try {
                    value = ProductCategory.valueOf(value).name();
                } catch (IllegalArgumentException e) {
                    throw AppException.validation("品类不合法").code("admin.err.banner.targetCategory");
                }
            }
            case EXTERNAL -> {
                if (!isHttpsUrl(value)) {
                    throw AppException.validation("外部链接须为 https 地址")
                            .code("admin.err.banner.targetUrl");
                }
            }
            case COUPON -> {
                // v1.4.2 Story 6.1 AC7.2：banner 券 = 可领取券的一个入口（PRD 不做「只在 banner 里领」的券），
                // NEWBIE / CODE 类不可绑；已停用或已过发放期止的不可绑（发放期还没开始的允许，排期可以先配）。
                ShopCoupon c = value.isEmpty() ? null : coupons.findByPublicToken(value).orElse(null);
                if (c == null || c.getStatus() != CouponStatus.ACTIVE
                        || c.getAcquireType() != CouponAcquireType.CLAIM
                        || (c.getIssueEndAt() != null && !c.getIssueEndAt().isAfter(Instant.now()))) {
                    throw AppException.validation("券不存在、已停用、不是领取类或已过发放期")
                            .code("admin.err.banner.targetCoupon");
                }
            }
        }
        return new Target(type, value);
    }

    static boolean isHttpsUrl(String v) {
        if (v == null || v.isBlank() || v.length() > 512) {
            return false;
        }
        try {
            java.net.URI u = new java.net.URI(v);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isBlank();
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    /** 🔴 校验在 service 层，与商品同范式 —— 模板里的 required 绕得过，这里绕不过。 */
    private void validate(ShopBannerForm f) {
        if (f.getImageKey() == null || f.getImageKey().isBlank()) {
            throw AppException.validation("请上传 banner 图")
                    .code("admin.err.banner.imageRequired");
        }
    }
}
