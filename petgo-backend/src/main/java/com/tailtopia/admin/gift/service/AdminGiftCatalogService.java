package com.tailtopia.admin.gift.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.gift.dto.GiftCatalogForm;
import com.tailtopia.admin.gift.dto.GiftCatalogRow;
import com.tailtopia.admin.gift.dto.GiftCatalogSummary;
import com.tailtopia.admin.tagicon.AdminTagIconService;
import com.tailtopia.admin.tagicon.IconPurpose;
import com.tailtopia.config.domain.ConfigChangeLog;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftCatalogStatus;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.gift.service.GiftTokenGenerator;
import com.tailtopia.growth.service.GrowthDisplayLevelService;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 后台礼物目录与送礼 QRIS 最低金额（V1.4.0 gift Story 3.1 · AB-35，AD-1 / AD-4 / AD-11）。
 *
 * <ul>
 *   <li><b>只下线不物理删除</b>（订单与礼物墙 FK 指向目录行）：本类没有删除方法。</li>
 *   <li><b>快照语义</b>（AD-2 / AD-3）：改价 / 改名 / 换图 / 下线只动 {@code gift_catalog} 一行，
 *       不碰任何 {@code gift_orders} / {@code gift_pet_wall}；已发起未付款的 QRIS 订单按订单快照交付。</li>
 *   <li><b>QRIS 最低金额只对送礼生效</b>（AD-4）：值只被送礼发起校验读取，本类不碰通用 {@code createIntent}。
 *       刻意不写进 {@code AdminConfigService}（避免与 ② 在那里加月租价格的改动撞车），
 *       但校验写法与 {@code config_change_logs} 口径与它一致。</li>
 *   <li>审计与业务写入同一事务；无真变化不写库、不写审计。</li>
 * </ul>
 */
@Service
public class AdminGiftCatalogService {

    /** 筛选条件（宽松解析后的结果；null = 不限）。 */
    public record Filter(Long price, GiftKind kind, GiftCatalogStatus status) {
        public static final Filter NONE = new Filter(null, null, null);
    }

    /** 列表页一次取数：表格行（已筛选、已排序）+ 全目录摘要 + 价格下拉选项 + 目录是否为空。 */
    public record CatalogPage(List<GiftCatalogRow> rows, GiftCatalogSummary summary, List<Long> priceOptions,
            boolean catalogEmpty) {
    }

    /** 抽屉：礼物 + 收送统计（{@code received} = DELIVERED 订单数量之和，{@code sent} = DELIVERED 订单数）。 */
    public record GiftDetail(GiftCatalogRow gift, long received, long sent) {
    }

    /** QRIS 最低金额预检结果（弹层复述用；旧值读库里当前值，不信任页面）。 */
    public record QrisMinChange(long oldValue, long newValue) {
    }

    /** 校验通过的礼物字段。 */
    record Parsed(String nameEn, String nameId, long price, boolean forCat, boolean forDog, boolean forOther,
            GiftKind kind, Integer minLevel, int sortOrder) {
    }

    /** 列表排序：下线的最后 → 普通先于专属 → 价格 → 排序值 → id（AC3.2）。 */
    static final Comparator<GiftCatalogItem> ADMIN_ORDER = Comparator
            .<GiftCatalogItem>comparingInt(g -> g.getStatus() == GiftCatalogStatus.OFFLINE ? 1 : 0)
            .thenComparingInt(g -> g.getKind() == GiftKind.EXCLUSIVE ? 1 : 0)
            .thenComparingLong(GiftCatalogItem::getPrice)
            .thenComparingInt(GiftCatalogItem::getSortOrder)
            .thenComparing(GiftCatalogItem::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    static final String FIELD_QRIS_MIN = "gift_qris_min_amount";

    private final GiftCatalogRepository catalog;
    private final GiftOrderRepository orders;
    private final GiftTokenGenerator tokens;
    private final AdminTagIconService icons;
    private final PricingConfigRepository pricing;
    private final ConfigChangeLogRepository changeLogs;
    private final AdminAuditService audit;
    private final GrowthDisplayLevelService growthLevels;

    public AdminGiftCatalogService(GiftCatalogRepository catalog, GiftOrderRepository orders,
            GiftTokenGenerator tokens, AdminTagIconService icons, PricingConfigRepository pricing,
            ConfigChangeLogRepository changeLogs, AdminAuditService audit, GrowthDisplayLevelService growthLevels) {
        this.catalog = catalog;
        this.orders = orders;
        this.tokens = tokens;
        this.icons = icons;
        this.pricing = pricing;
        this.changeLogs = changeLogs;
        this.audit = audit;
        this.growthLevels = growthLevels;
    }

    /**
     * 专属礼物「最低等级」的上限 = ① 等级表当前最高级（gift Story 4.1 · AC7.4，取代 3.1 的常量 20）。
     * 只读 ① 的公开读口 {@link GrowthDisplayLevelService#maxLevel}，等级表追加后上限自动变长。
     */
    @Transactional(readOnly = true)
    public int maxMinLevel() {
        return growthLevels.maxLevel();
    }

    // ——————————————————— 读 ———————————————————

    /**
     * 列表页（AC3）。目录是低基数数据（百级），一次取全表：摘要条（全目录口径）、价格下拉（全目录出现过的价格）
     * 与筛选后的表格出自同一次读取，三处数字不会互相对不上。
     */
    @Transactional(readOnly = true)
    public CatalogPage page(Filter filter) {
        Filter f = filter == null ? Filter.NONE : filter;
        List<GiftCatalogItem> all = catalog.findAll();
        long online = 0;
        long exclusive = 0;
        long offline = 0;
        for (GiftCatalogItem g : all) {
            if (g.getStatus() == GiftCatalogStatus.ONLINE) {
                online++;
                if (g.getKind() == GiftKind.EXCLUSIVE) {
                    exclusive++;
                }
            } else {
                offline++;
            }
        }
        List<Long> prices = all.stream().map(GiftCatalogItem::getPrice).distinct().sorted().toList();
        List<GiftCatalogRow> rows = all.stream()
                .filter(g -> f.price() == null || g.getPrice() == f.price())
                .filter(g -> f.kind() == null || g.getKind() == f.kind())
                .filter(g -> f.status() == null || g.getStatus() == f.status())
                .sorted(ADMIN_ORDER)
                .map(GiftCatalogRow::of)
                .toList();
        return new CatalogPage(rows, new GiftCatalogSummary(online, exclusive, offline), prices, all.isEmpty());
    }

    /** 抽屉（AC6.1 / AC6.4）。不存在 → 404。 */
    @Transactional(readOnly = true)
    public GiftDetail detail(long id) {
        GiftCatalogItem g = require(id);
        long received = 0;
        long sent = 0;
        List<Object[]> totals = orders.deliveredTotalsOfGift(id);
        if (totals != null && !totals.isEmpty() && totals.get(0) != null) {
            Object[] row = totals.get(0);
            received = toLong(row.length > 0 ? row[0] : null);
            sent = toLong(row.length > 1 ? row[1] : null);
        }
        return new GiftDetail(GiftCatalogRow.of(g), received, sent);
    }

    /** 当前送礼 QRIS 最低金额（每次读库，无缓存）。 */
    @Transactional(readOnly = true)
    public long qrisMin() {
        return pricingRow().getGiftQrisMinAmount();
    }

    // ——————————————————— 礼物写入 ———————————————————

    /**
     * 新建礼物（AC4）。先校验表单（不合规不上传，免得留下孤儿图标），再按礼物用途校验并上传图标；
     * 新建必须有图标。返回新礼物 id。
     */
    @Transactional
    public long create(long adminId, GiftCatalogForm form, MultipartFile iconFile) {
        Parsed p = validate(form, maxMinLevel());
        if (iconFile == null || iconFile.isEmpty()) {
            throw AppException.validation("新建礼物必须上传图标。").code("admin.err.gift.iconRequired");
        }
        String iconUrl = icons.uploadOrKeep(iconFile, IconPurpose.GIFT);
        if (iconUrl == null) {
            throw AppException.validation("新建礼物必须上传图标。").code("admin.err.gift.iconRequired");
        }
        GiftCatalogItem g = GiftCatalogItem.create(tokens.generate(), p.nameEn(), p.nameId(), iconUrl, p.price(),
                p.forCat(), p.forDog(), p.forOther(), p.kind(), p.minLevel(), p.sortOrder());
        g.setUpdatedBy(adminId);
        g = catalog.save(g);
        audit.record(adminId, AuditActions.GIFT_CATALOG_CREATED, "gift_catalog", String.valueOf(g.getId()),
                "name_en=" + p.nameEn() + "; name_id=" + p.nameId() + "; price=" + p.price()
                        + "; kind=" + p.kind() + "; min_level=" + p.minLevel()
                        + "; for_cat=" + p.forCat() + "; for_dog=" + p.forDog() + "; for_other=" + p.forOther()
                        + "; sort_order=" + p.sortOrder());
        return g.getId();
    }

    /**
     * 编辑礼物（AC6）。不选文件 = 保留原图标。只改 {@code gift_catalog} 这一行 —— 订单与礼物墙按下单快照，不受影响。
     *
     * @return 是否真的有改动（无改动不写库、不写审计；页面 toast 照样「已保存」）
     */
    @Transactional
    public boolean update(long adminId, long id, GiftCatalogForm form, MultipartFile iconFile) {
        GiftCatalogItem g = require(id);
        Parsed p = validate(form, maxMinLevel());
        String newIcon = icons.uploadOrKeep(iconFile, IconPurpose.GIFT);

        List<String> changes = new ArrayList<>();
        diff(changes, "name_en", g.getNameEn(), p.nameEn());
        diff(changes, "name_id", g.getNameId(), p.nameId());
        diff(changes, "price", g.getPrice(), p.price());
        diff(changes, "for_cat", g.isForCat(), p.forCat());
        diff(changes, "for_dog", g.isForDog(), p.forDog());
        diff(changes, "for_other", g.isForOther(), p.forOther());
        diff(changes, "kind", g.getKind(), p.kind());
        diff(changes, "min_level", g.getMinLevel(), p.minLevel());
        diff(changes, "sort_order", g.getSortOrder(), p.sortOrder());
        if (newIcon != null && !newIcon.equals(g.getIconUrl())) {
            // 🔒 换图只记一句，不记 URL。
            changes.add("icon changed");
        }
        if (changes.isEmpty()) {
            return false;
        }
        g.edit(p.nameEn(), p.nameId(), newIcon, p.price(), p.forCat(), p.forDog(), p.forOther(), p.kind(),
                p.minLevel(), p.sortOrder(), adminId);
        catalog.save(g);
        audit.record(adminId, AuditActions.GIFT_CATALOG_UPDATED, "gift_catalog", String.valueOf(id),
                String.join("; ", changes));
        return true;
    }

    /**
     * 上 / 下线（AC7）。已是目标状态 → 幂等成功、不写审计。下线后 App 目录不再返回、新发起被拒；
     * 已收到的照常展示，已发起未付款的 QRIS 订单照常交付（交付不校验 ONLINE，AD-7）。
     *
     * @return 是否真的改了状态
     */
    @Transactional
    public boolean setOnline(long adminId, long id, boolean online) {
        GiftCatalogItem g = require(id);
        GiftCatalogStatus target = online ? GiftCatalogStatus.ONLINE : GiftCatalogStatus.OFFLINE;
        if (g.getStatus() == target) {
            return false;
        }
        GiftCatalogStatus before = g.getStatus();
        g.setStatus(target, adminId);
        catalog.save(g);
        audit.record(adminId, online ? AuditActions.GIFT_CATALOG_ONLINED : AuditActions.GIFT_CATALOG_OFFLINED,
                "gift_catalog", String.valueOf(id), "status " + before + "→" + target);
        return true;
    }

    // ——————————————————— QRIS 最低金额 ———————————————————

    /** 预检（AC8.2）：只校验不写库；通过则返回新旧值供弹层复述。 */
    @Transactional(readOnly = true)
    public QrisMinChange previewQrisMin(String amount) {
        return checkQrisMin(pricingRow(), amount);
    }

    /**
     * 保存（AC8.5）：同一事务更新 {@code pricing_config.gift_qris_min_amount} + 一条 {@code config_change_logs}
     * （{@code PRICING}）+ 审计。校验与预检是同一方法 —— 绕过弹层直接 POST 也一样被拦。
     */
    @Transactional
    public QrisMinChange updateQrisMin(long adminId, String amount) {
        PricingConfig cfg = pricingRow();
        QrisMinChange change = checkQrisMin(cfg, amount);
        cfg.setGiftQrisMinAmount(change.newValue());
        pricing.save(cfg);
        changeLogs.save(ConfigChangeLog.of(ConfigChangeLog.ConfigType.PRICING, FIELD_QRIS_MIN,
                String.valueOf(change.oldValue()), String.valueOf(change.newValue()), adminId));
        audit.record(adminId, AuditActions.GIFT_QRIS_MIN_UPDATED, "pricing_config", FIELD_QRIS_MIN,
                FIELD_QRIS_MIN + " " + change.oldValue() + "→" + change.newValue());
        return change;
    }

    private QrisMinChange checkQrisMin(PricingConfig cfg, String amount) {
        Long v = parseLong(amount);
        require(v != null && v >= GiftCatalogRules.MIN_QRIS_AMOUNT && v <= GiftCatalogRules.MAX_QRIS_AMOUNT,
                "金额须为 100 ~ 100,000,000 的整数（Rp）", "admin.err.gift.qrisMinRange");
        long old = cfg.getGiftQrisMinAmount();
        require(v != old, "金额没有改动", "admin.err.gift.qrisMinNoChange");
        return new QrisMinChange(old, v);
    }

    // ——————————————————— 校验 ———————————————————

    /** 礼物字段校验（AC4.3）：全部抛 422 + 文案码，抽屉不关、错误落行内槽。 */
    static Parsed validate(GiftCatalogForm form, int maxLevel) {
        String nameEn = trim(form.nameEn());
        String nameId = trim(form.nameId());
        require(!nameEn.isEmpty() && !nameId.isEmpty(), "请填写 EN 与 ID 名称", "admin.err.gift.nameRequired");
        require(nameEn.length() <= GiftCatalogRules.NAME_MAX && nameId.length() <= GiftCatalogRules.NAME_MAX,
                "名称最多 40 个字", "admin.err.gift.nameTooLong");

        Long price = parseLong(form.price());
        require(price != null && price >= GiftCatalogRules.MIN_PRICE && price <= GiftCatalogRules.MAX_PRICE,
                "价格须为 100 ~ 100,000,000 的整数（Rp）", "admin.err.gift.priceRange");

        GiftKind kind;
        String rawKind = trim(form.kind());
        if (rawKind.isEmpty()) {
            kind = GiftKind.NORMAL;
        } else {
            try {
                kind = GiftKind.valueOf(rawKind.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw AppException.validation("类型取值无效").code("admin.err.gift.kindInvalid");
            }
        }

        Integer minLevel = null;
        if (kind == GiftKind.EXCLUSIVE) {
            Long lv = parseLong(form.minLevel());
            require(lv != null && lv >= 1 && lv <= maxLevel,
                    "专属礼物须填写最低等级（Lv1 ~ Lv" + maxLevel + "）",
                    "admin.err.gift.minLevelRange", maxLevel);
            minLevel = lv.intValue();
        }
        // NORMAL：忽略提交的 minLevel，落 NULL（满足 ck_gift_catalog_exclusive_level）。

        int sortOrder = 0;
        String rawSort = trim(form.sortOrder());
        if (!rawSort.isEmpty()) {
            Long so = parseLong(rawSort);
            require(so != null && so >= 0 && so <= GiftCatalogRules.SORT_ORDER_MAX,
                    "排序须为 0 ~ 9999 的整数", "admin.err.gift.sortOrderRange");
            sortOrder = so.intValue();
        }
        return new Parsed(nameEn, nameId, price, form.forCat(), form.forDog(), form.forOther(), kind, minLevel,
                sortOrder);
    }

    // ——————————————————— 工具 ———————————————————

    private GiftCatalogItem require(long id) {
        return catalog.findById(id).orElseThrow(() -> AppException.notFound("礼物不存在")
                .code("admin.err.gift.notFound"));
    }

    private PricingConfig pricingRow() {
        return pricing.findById(PricingConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("pricing_config 单行缺失（V78 种子）"));
    }

    private static void diff(List<String> out, String field, Object oldVal, Object newVal) {
        if (!Objects.equals(oldVal, newVal)) {
            out.add(field + " " + oldVal + "→" + newVal);
        }
    }

    /** 校验失败即抛（中文原文 + 后台本地化文案码；写法同 {@code AdminConfigService.require}）。 */
    private static void require(boolean cond, String msg, String code, Object... args) {
        if (!cond) {
            throw AppException.validation(msg).code(code, args);
        }
    }

    /** 严格整数解析：空、带小数、带非数字字符 → null（由调用方报对应的 422）。 */
    static Long parseLong(String raw) {
        String s = trim(raw);
        if (s.isEmpty() || !s.matches("-?\\d{1,18}")) {
            return null;
        }
        return Long.parseLong(s);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
