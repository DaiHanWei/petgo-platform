package com.tailtopia.shop.shipping.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.domain.ShippingZone;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import com.tailtopia.shop.shipping.repository.ShippingZoneRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务范围与运费表的后台维护（Story 2.2，AB-11C）。
 *
 * <p>🔴 <b>界面与本类都不出现「配送方式」维度</b>（C-14）：运费表就是「Kecamatan → 运费」的平表。
 * <p>🔴 <b>不接承运商实时计费 API</b>（FR-99）——运费完全由本表决定。
 */
@Service
public class AdminShippingZoneService {

    private final ShippingZoneRepository zones;
    private final ShippingSettingsRepository settings;
    private final AdminAuditService audit;

    public AdminShippingZoneService(ShippingZoneRepository zones,
            ShippingSettingsRepository settings, AdminAuditService audit) {
        this.zones = zones;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ShippingZone> list() {
        return zones.findAllByOrderByProvinsiAscKotaKabupatenAscKecamatanAsc();
    }

    /** 新增或更新一个区域的运费（按 Kecamatan 唯一）。 */
    @Transactional
    public ShippingZone upsert(String kecamatan, String kotaKabupaten, String provinsi, long fee,
            long actorAccountId) {
        String k = require(kecamatan, "请填写 Kecamatan");
        String kota = require(kotaKabupaten, "请填写城市/县");
        String prov = require(provinsi, "请填写省份");
        if (fee < 0) {
            throw AppException.validation("运费不能为负");
        }
        ShippingZone z = zones.findByKecamatan(k).orElse(null);
        String action;
        if (z == null) {
            z = ShippingZone.create(k, kota, prov, fee);
            action = "新增";
        } else {
            z.apply(kota, prov, fee);
            action = "更新";
        }
        ShippingZone saved = zones.save(z);
        audit.record(actorAccountId, AuditActions.SHOP_SHIPPING_ZONE_UPDATED, "SHIPPING_ZONE", k,
                "%s配送区域 %s：运费 %d".formatted(action, k, fee));
        return saved;
    }

    /**
     * 启用/停用一个区域。
     *
     * <p>🔴 <b>停用用 active=false，不删行</b>：删了就丢掉「这个区域曾经可配送、运费是多少」，
     * 而历史订单的运费需要可追溯（AB-13D 对账）。
     */
    @Transactional
    public ShippingZone setActive(String kecamatan, boolean active, long actorAccountId) {
        ShippingZone z = zones.findByKecamatan(kecamatan)
                .orElseThrow(() -> AppException.notFound("配送区域不存在"));
        if (z.isActive() == active) {
            return z;
        }
        z.setActive(active);
        audit.record(actorAccountId, AuditActions.SHOP_SHIPPING_ZONE_UPDATED, "SHIPPING_ZONE",
                kecamatan, (active ? "启用" : "停用") + "配送区域 " + kecamatan);
        return zones.save(z);
    }

    /**
     * 配置<b>退货收件地址</b>（S-7，AB-11C 增配一项）。
     *
     * <p>🔴 用户<b>自寄</b>到这个地址；本版本<b>不做上门取件</b>（需承运商 API 与商务账号）。
     * 三项要么都填、要么都留空 —— 只填一半的地址寄不到，而寄不到的退货会变成
     * 「货在路上丢了、钱也没退」的双输。
     */
    @Transactional
    public ShippingSettings setReturnAddress(String addressText, String receiverName,
            String receiverPhone, long actorAccountId) {
        boolean anyFilled = notBlank(addressText) || notBlank(receiverName)
                || notBlank(receiverPhone);
        boolean allFilled = notBlank(addressText) && notBlank(receiverName)
                && notBlank(receiverPhone);
        if (anyFilled && !allFilled) {
            throw com.tailtopia.shared.error.AppException.validation(
                    "退货收件地址的收件人、电话、地址三项要么都填，要么都留空");
        }
        ShippingSettings s = settings.findAll().stream().findFirst()
                .orElseThrow(() -> com.tailtopia.shared.error.AppException.notFound("配送设置未初始化"));
        s.applyReturnAddress(addressText, receiverName, receiverPhone);
        // 🔒 审计只记「配置过」与是否清空，不记地址与电话本身（审计表永久保留）
        audit.record(actorAccountId, AuditActions.SHOP_SHIPPING_SETTINGS_UPDATED,
                "SHIPPING_SETTINGS", "1",
                allFilled ? "配置退货收件地址（内容不入审计）" : "清空退货收件地址");
        return settings.save(s);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /** 设置免运门槛。0 = 不做免运（不是「0 元即免运」）。 */
    @Transactional
    public ShippingSettings setFreeShippingThreshold(long value, long actorAccountId) {
        if (value < 0) {
            throw AppException.validation("免运门槛不能为负");
        }
        ShippingSettings s = settings.findById(ShippingSettings.SINGLETON_ID)
                .orElseThrow(() -> AppException.notFound("配送设置未初始化"));
        long old = s.getFreeShippingThreshold();
        s.applyThreshold(value);
        audit.record(actorAccountId, AuditActions.SHOP_SHIPPING_SETTINGS_UPDATED,
                "SHIPPING_SETTINGS", "1", "免运门槛：%d → %d".formatted(old, value));
        return settings.save(s);
    }

    private static String require(String v, String message) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) {
            throw AppException.validation(message);
        }
        return s;
    }
}
