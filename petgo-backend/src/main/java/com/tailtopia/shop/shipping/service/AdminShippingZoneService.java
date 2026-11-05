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

    // ---------- v1.4.2 shop Story 1.1：发货地 / 免运封顶 / 保价费率 / 区域邮编（后台线 S-2 用） ----------

    /**
     * 配置发货地（Biteship 询价起点 + 5.1 下运单发件人）。
     *
     * <p>🔒 审计只记邮编前 2 位与是否清空，不记联系人 / 电话 / 地址（审计表永久保留）。
     */
    @Transactional
    public ShippingSettings setOrigin(String postalCode, String contactName, String contactPhone,
            String address, long actorAccountId) {
        // 长度上限与列宽一致（V20261105_1605）：超长在 service 层拦成 422，不让 DB 报 500
        requireMaxLength(contactName, ORIGIN_CONTACT_NAME_MAX, "发货联系人不能超过 %d 个字符");
        requireMaxLength(contactPhone, ORIGIN_CONTACT_PHONE_MAX, "发货联系电话不能超过 %d 个字符");
        requireMaxLength(address, ORIGIN_ADDRESS_MAX, "发货地址不能超过 %d 个字符");
        ShippingSettings s = singleton();
        try {
            s.applyOrigin(postalCode, contactName, contactPhone, address);
        } catch (IllegalArgumentException e) {
            throw AppException.validation("发货地邮编须为 5 位数字且首位不为 0");
        }
        String pc = s.getOriginPostalCode();
        audit.record(actorAccountId, AuditActions.SHOP_SHIPPING_SETTINGS_UPDATED,
                "SHIPPING_SETTINGS", "1",
                pc == null ? "清空发货地邮编" : "配置发货地（邮编 " + pc.substring(0, 2) + "***，其余内容不入审计）");
        return settings.save(s);
    }

    /** 设置免运减免封顶；{@code null} 或 0 = 不封顶。 */
    @Transactional
    public ShippingSettings setFreeShippingCap(Long cap, long actorAccountId) {
        if (cap != null && cap < 0) {
            throw AppException.validation("免运封顶不能为负");
        }
        ShippingSettings s = singleton();
        Long old = s.getFreeShippingCap();
        s.applyFreeShippingCap(cap);
        audit.record(actorAccountId, AuditActions.SHOP_SHIPPING_SETTINGS_UPDATED,
                "SHIPPING_SETTINGS", "1", "免运封顶：%s → %s".formatted(old, cap));
        return settings.save(s);
    }

    /** 设置保价费率（基点，0 ~ 10000；50 = 0.5%）。 */
    @Transactional
    public ShippingSettings setInsuranceRateBp(int bp, long actorAccountId) {
        if (bp < 0 || bp > 10000) {
            throw AppException.validation("保价费率须在 0 ~ 10000 基点之间");
        }
        ShippingSettings s = singleton();
        int old = s.getInsuranceRateBp();
        s.applyInsuranceRateBp(bp);
        audit.record(actorAccountId, AuditActions.SHOP_SHIPPING_SETTINGS_UPDATED,
                "SHIPPING_SETTINGS", "1", "保价费率(bp)：%d → %d".formatted(old, bp));
        return settings.save(s);
    }

    /** 设置区域代表邮编（游客按地区询价用）；空 = 清空（该区游客询价按不支持配送）。 */
    @Transactional
    public ShippingZone setZonePostalCode(String kecamatan, String postalCode,
            long actorAccountId) {
        ShippingZone z = zones.findByKecamatan(kecamatan)
                .orElseThrow(() -> AppException.notFound("配送区域不存在"));
        try {
            z.applyPostalCode(postalCode);
        } catch (IllegalArgumentException e) {
            throw AppException.validation("邮编须为 5 位数字且首位不为 0");
        }
        audit.record(actorAccountId, AuditActions.SHOP_SHIPPING_ZONE_UPDATED, "SHIPPING_ZONE",
                kecamatan, "配送区域 %s 代表邮编：%s".formatted(kecamatan,
                        z.getPostalCode() == null ? "清空" : z.getPostalCode()));
        return zones.save(z);
    }

    static final int ORIGIN_CONTACT_NAME_MAX = 60;
    static final int ORIGIN_CONTACT_PHONE_MAX = 20;
    static final int ORIGIN_ADDRESS_MAX = 300;

    /** 按去首尾空白后的长度校验（与 {@code ShippingSettings.blankToNull} 落库口径一致）。 */
    private static void requireMaxLength(String v, int max, String messagePattern) {
        if (v != null && v.trim().length() > max) {
            throw AppException.validation(messagePattern.formatted(max));
        }
    }

    private ShippingSettings singleton() {
        return settings.findById(ShippingSettings.SINGLETON_ID)
                .orElseThrow(() -> AppException.notFound("配送设置未初始化"));
    }

    private static String require(String v, String message) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) {
            throw AppException.validation(message);
        }
        return s;
    }
}
