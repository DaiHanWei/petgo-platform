package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.shop.dto.ShopBannerForm;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.repository.ShopBannerRepository;
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

    public AdminShopBannerService(ShopBannerRepository banners, AdminAuditService audit) {
        this.banners = banners;
        this.audit = audit;
    }

    @Transactional
    public ShopBanner create(ShopBannerForm form, long actorAccountId) {
        validate(form);
        ShopBanner b = ShopBanner.create(form.getImageKey().trim(), form.getImageW(),
                form.getImageH(), form.getSortWeight());
        banners.save(b);
        audit.record(actorAccountId, AuditActions.SHOP_BANNER_CREATED, "SHOP_BANNER",
                String.valueOf(b.getId()), "新建 banner（默认未上架）");
        return b;
    }

    @Transactional
    public void update(long id, ShopBannerForm form, long actorAccountId) {
        validate(form);
        ShopBanner b = require(id);
        b.apply(form.getImageKey().trim(), form.getImageW(), form.getImageH(),
                form.getSortWeight());
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

    private ShopBanner require(long id) {
        return banners.findById(id)
                .orElseThrow(() -> AppException.notFound("banner 不存在")
                        .code("admin.err.banner.notFound"));
    }

    /** 🔴 校验在 service 层，与商品同范式 —— 模板里的 required 绕得过，这里绕不过。 */
    private void validate(ShopBannerForm f) {
        if (f.getImageKey() == null || f.getImageKey().isBlank()) {
            throw AppException.validation("请上传 banner 图")
                    .code("admin.err.banner.imageRequired");
        }
    }
}
