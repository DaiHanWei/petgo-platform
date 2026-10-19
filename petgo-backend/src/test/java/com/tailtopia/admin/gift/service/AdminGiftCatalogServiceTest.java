package com.tailtopia.admin.gift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.growth.service.GrowthDisplayLevelService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.gift.dto.GiftCatalogForm;
import com.tailtopia.admin.gift.dto.GiftCatalogRow;
import com.tailtopia.admin.gift.service.AdminGiftCatalogService.CatalogPage;
import com.tailtopia.admin.gift.service.AdminGiftCatalogService.Filter;
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
import com.tailtopia.shared.error.AppException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * L0：后台礼物目录与送礼 QRIS 最低金额（V1.4.0 gift Story 3.1 · AC3 / AC4 / AC6 / AC7 / AC8 / AC10）。
 *
 * <p>纯 Mockito，无 Spring / DB。每条 422 都断言<b>文案码</b>（后台按码本地化），不断言中文原文。
 */
class AdminGiftCatalogServiceTest {

    private static final long ADMIN = 7L;

    private GiftCatalogRepository catalog;
    private GiftOrderRepository orders;
    private AdminTagIconService icons;
    private PricingConfigRepository pricing;
    private ConfigChangeLogRepository changeLogs;
    private AdminAuditService audit;
    private GrowthDisplayLevelService growthLevels;
    /** ① seed 等级表最高级（Lv20）。 */
    private static final int MAX_LEVEL = 20;
    private AdminGiftCatalogService service;
    private PricingConfig pricingRow;

    @BeforeEach
    void setUp() throws Exception {
        catalog = mock(GiftCatalogRepository.class);
        orders = mock(GiftOrderRepository.class);
        icons = mock(AdminTagIconService.class);
        pricing = mock(PricingConfigRepository.class);
        changeLogs = mock(ConfigChangeLogRepository.class);
        audit = mock(AdminAuditService.class);
        GiftTokenGenerator tokens = new GiftTokenGenerator();
        when(catalog.save(any(GiftCatalogItem.class))).thenAnswer(inv -> {
            GiftCatalogItem g = inv.getArgument(0);
            if (g.getId() == null) {
                setField(g, "id", 99L);
            }
            return g;
        });
        when(icons.uploadOrKeep(any(), eq(IconPurpose.GIFT))).thenAnswer(inv -> {
            MultipartFile f = inv.getArgument(0);
            return f == null || f.isEmpty() ? null : "https://cdn.example/public/gift-icon/new.png";
        });
        pricingRow = newPricing(10_000L);
        when(pricing.findById(PricingConfig.SINGLETON_ID)).thenReturn(Optional.of(pricingRow));
        growthLevels = mock(GrowthDisplayLevelService.class);
        when(growthLevels.maxLevel()).thenReturn(MAX_LEVEL);
        service = new AdminGiftCatalogService(catalog, orders, tokens, icons, pricing, changeLogs, audit, growthLevels);
    }

    // ——————————————————— 夹具 ———————————————————

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static PricingConfig newPricing(long qrisMin) throws Exception {
        Constructor<PricingConfig> c = PricingConfig.class.getDeclaredConstructor();
        c.setAccessible(true);
        PricingConfig p = c.newInstance();
        p.setGiftQrisMinAmount(qrisMin);
        return p;
    }

    private static GiftCatalogItem gift(long id, long price, GiftKind kind, Integer minLevel, int sortOrder,
            GiftCatalogStatus status) throws Exception {
        GiftCatalogItem g = GiftCatalogItem.create("tok" + id, "Gift" + id, "Hadiah" + id,
                "https://cdn.example/icon" + id + ".png", price, false, false, false, kind, minLevel, sortOrder);
        setField(g, "id", id);
        if (status == GiftCatalogStatus.OFFLINE) {
            g.setStatus(GiftCatalogStatus.OFFLINE, null);
        }
        return g;
    }

    private static GiftCatalogForm form(String price, String kind, String minLevel, String sortOrder) {
        return new GiftCatalogForm("Bone", "Tulang", price, false, true, false, kind, minLevel, sortOrder);
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("iconFile", "a.png", "image/png", new byte[] {1, 2, 3});
    }

    private static String codeOf(Throwable t) {
        return ((AppException) t).getMessageCode();
    }

    private void assertCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(code));
    }

    // ——————————————————— AC4 校验 ———————————————————

    @Test
    void priceBoundsAreHundredToHundredMillion() {
        assertCode(() -> AdminGiftCatalogService.validate(form("99", "NORMAL", null, null), MAX_LEVEL),
                "admin.err.gift.priceRange");
        assertCode(() -> AdminGiftCatalogService.validate(form("100000001", "NORMAL", null, null), MAX_LEVEL),
                "admin.err.gift.priceRange");
        assertCode(() -> AdminGiftCatalogService.validate(form("12.5", "NORMAL", null, null), MAX_LEVEL),
                "admin.err.gift.priceRange");
        assertCode(() -> AdminGiftCatalogService.validate(form("", "NORMAL", null, null), MAX_LEVEL),
                "admin.err.gift.priceRange");
        assertThat(AdminGiftCatalogService.validate(form("100", "NORMAL", null, null), MAX_LEVEL).price()).isEqualTo(100L);
        assertThat(AdminGiftCatalogService.validate(form("100000000", "NORMAL", null, null), MAX_LEVEL).price())
                .isEqualTo(100_000_000L);
    }

    @Test
    void namesMustBePresentAndAtMostFortyChars() {
        assertCode(() -> AdminGiftCatalogService.validate(
                new GiftCatalogForm("  ", "Tulang", "1000", false, false, false, "NORMAL", null, null), MAX_LEVEL),
                "admin.err.gift.nameRequired");
        assertCode(() -> AdminGiftCatalogService.validate(
                new GiftCatalogForm("Bone", null, "1000", false, false, false, "NORMAL", null, null), MAX_LEVEL),
                "admin.err.gift.nameRequired");
        String n41 = "x".repeat(41);
        assertCode(() -> AdminGiftCatalogService.validate(
                new GiftCatalogForm(n41, "Tulang", "1000", false, false, false, "NORMAL", null, null), MAX_LEVEL),
                "admin.err.gift.nameTooLong");
        // trim 后计长：40 个字 + 两侧空白放行
        var ok = AdminGiftCatalogService.validate(
                new GiftCatalogForm("  " + "y".repeat(40) + " ", "Tulang", "1000", false, false, false, null, null,
                        null), MAX_LEVEL);
        assertThat(ok.nameEn()).hasSize(40);
        assertThat(ok.kind()).as("缺省类型 = 普通").isEqualTo(GiftKind.NORMAL);
    }

    /** gift Story 4.1 · AC7.4：上限来自 ① 等级表当前最高级（不再是常量 20），等级表追加后随之变长。 */
    @Test
    void minLevelUpperBoundComesFromGrowthLevelTable() {
        assertThat(service.maxMinLevel()).isEqualTo(MAX_LEVEL);
        when(growthLevels.maxLevel()).thenReturn(30);
        assertThat(service.maxMinLevel()).isEqualTo(30);
        assertThat(AdminGiftCatalogService.validate(form("1000", "EXCLUSIVE", "25", null), 30).minLevel()).isEqualTo(25);
        assertCode(() -> AdminGiftCatalogService.validate(form("1000", "EXCLUSIVE", "31", null), 30),
                "admin.err.gift.minLevelRange");
    }

    @Test
    void exclusiveNeedsMinLevelWithinOneToTwenty() {
        assertCode(() -> AdminGiftCatalogService.validate(form("1000", "EXCLUSIVE", null, null), MAX_LEVEL),
                "admin.err.gift.minLevelRange");
        assertCode(() -> AdminGiftCatalogService.validate(form("1000", "EXCLUSIVE", "0", null), MAX_LEVEL),
                "admin.err.gift.minLevelRange");
        assertCode(() -> AdminGiftCatalogService.validate(form("1000", "EXCLUSIVE", "21", null), MAX_LEVEL),
                "admin.err.gift.minLevelRange");
        assertThatThrownBy(() -> AdminGiftCatalogService.validate(form("1000", "EXCLUSIVE", "21", null), MAX_LEVEL))
                .satisfies(t -> assertThat(((AppException) t).getMessageArgs())
                        .as("上限作为参数带出").containsExactly(MAX_LEVEL));
        assertThat(AdminGiftCatalogService.validate(form("1000", "EXCLUSIVE", "20", null), MAX_LEVEL).minLevel()).isEqualTo(20);
        assertCode(() -> AdminGiftCatalogService.validate(form("1000", "SPECIAL", null, null), MAX_LEVEL),
                "admin.err.gift.kindInvalid");
    }

    @Test
    void normalGiftIgnoresSubmittedMinLevel() {
        assertThat(AdminGiftCatalogService.validate(form("1000", "NORMAL", "5", null), MAX_LEVEL).minLevel())
                .as("普通礼物落 NULL（满足 ck_gift_catalog_exclusive_level）").isNull();
    }

    @Test
    void sortOrderBlankMeansZeroAndMustBeWithinRange() {
        assertThat(AdminGiftCatalogService.validate(form("1000", "NORMAL", null, ""), MAX_LEVEL).sortOrder()).isZero();
        assertThat(AdminGiftCatalogService.validate(form("1000", "NORMAL", null, null), MAX_LEVEL).sortOrder()).isZero();
        assertThat(AdminGiftCatalogService.validate(form("1000", "NORMAL", null, "9999"), MAX_LEVEL).sortOrder()).isEqualTo(9999);
        assertCode(() -> AdminGiftCatalogService.validate(form("1000", "NORMAL", null, "10000"), MAX_LEVEL),
                "admin.err.gift.sortOrderRange");
        assertCode(() -> AdminGiftCatalogService.validate(form("1000", "NORMAL", null, "-1"), MAX_LEVEL),
                "admin.err.gift.sortOrderRange");
    }

    // ——————————————————— AC4 新建 ———————————————————

    @Test
    void createWithoutIconIs422AndUploadsNothing() {
        assertCode(() -> service.create(ADMIN, form("1000", "NORMAL", null, null), null),
                "admin.err.gift.iconRequired");
        assertCode(() -> service.create(ADMIN, form("1000", "NORMAL", null, null),
                new MockMultipartFile("iconFile", "a.png", "image/png", new byte[0])),
                "admin.err.gift.iconRequired");
        verify(catalog, never()).save(any());
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void invalidFormIsRejectedBeforeTheIconIsUploaded() {
        assertCode(() -> service.create(ADMIN, form("99", "NORMAL", null, null), png()), "admin.err.gift.priceRange");
        verify(icons, never()).uploadOrKeep(any(), any(IconPurpose.class));
    }

    @Test
    void createPersistsOnlineGiftWithTokenAndAudits() {
        long id = service.create(ADMIN, form("5000", "EXCLUSIVE", "5", "3"), png());
        assertThat(id).isEqualTo(99L);
        ArgumentCaptor<GiftCatalogItem> saved = ArgumentCaptor.forClass(GiftCatalogItem.class);
        verify(catalog).save(saved.capture());
        GiftCatalogItem g = saved.getValue();
        assertThat(g.getStatus()).isEqualTo(GiftCatalogStatus.ONLINE);
        assertThat(g.getPublicToken()).hasSize(32).matches("[0-9A-Za-z]{32}");
        assertThat(g.getIconUrl()).contains("/gift-icon/");
        assertThat(g.getPrice()).isEqualTo(5000L);
        assertThat(g.getKind()).isEqualTo(GiftKind.EXCLUSIVE);
        assertThat(g.getMinLevel()).isEqualTo(5);
        assertThat(g.getSortOrder()).isEqualTo(3);
        assertThat(g.isForDog()).isTrue();
        assertThat(g.getUpdatedBy()).isEqualTo(ADMIN);
        verify(icons).uploadOrKeep(any(), eq(IconPurpose.GIFT));
        verify(audit).record(eq(ADMIN), eq(AuditActions.GIFT_CATALOG_CREATED), eq("gift_catalog"), eq("99"),
                anyString());
    }

    // ——————————————————— AC6 编辑 ———————————————————

    @Test
    void editWithoutNewIconKeepsIconAndSummarisesBeforeAfter() throws Exception {
        GiftCatalogItem g = gift(5L, 25_000L, GiftKind.NORMAL, null, 0, GiftCatalogStatus.ONLINE);
        when(catalog.findById(5L)).thenReturn(Optional.of(g));
        String icon = g.getIconUrl();

        boolean changed = service.update(ADMIN, 5L,
                new GiftCatalogForm("Gift5", "Hadiah5", "30000", false, true, false, "EXCLUSIVE", "5", "0"), null);

        assertThat(changed).isTrue();
        assertThat(g.getIconUrl()).as("不传图 = 保留原图标").isEqualTo(icon);
        assertThat(g.getPrice()).isEqualTo(30_000L);
        assertThat(g.getMinLevel()).isEqualTo(5);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(ADMIN), eq(AuditActions.GIFT_CATALOG_UPDATED), eq("gift_catalog"), eq("5"),
                summary.capture());
        assertThat(summary.getValue())
                .contains("price 25000→30000")
                .contains("for_dog false→true")
                .contains("kind NORMAL→EXCLUSIVE")
                .contains("min_level null→5")
                .doesNotContain("icon");
    }

    @Test
    void editWithNewIconRecordsOnlyThatTheIconChanged() throws Exception {
        GiftCatalogItem g = gift(6L, 1000L, GiftKind.NORMAL, null, 0, GiftCatalogStatus.ONLINE);
        when(catalog.findById(6L)).thenReturn(Optional.of(g));
        service.update(ADMIN, 6L,
                new GiftCatalogForm("Gift6", "Hadiah6", "1000", false, false, false, "NORMAL", null, "0"), png());
        assertThat(g.getIconUrl()).isEqualTo("https://cdn.example/public/gift-icon/new.png");
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(ADMIN), eq(AuditActions.GIFT_CATALOG_UPDATED), anyString(), anyString(),
                summary.capture());
        assertThat(summary.getValue()).isEqualTo("icon changed").doesNotContain("https://");
    }

    @Test
    void editWithNoRealChangeWritesNothing() throws Exception {
        GiftCatalogItem g = gift(7L, 1000L, GiftKind.NORMAL, null, 2, GiftCatalogStatus.ONLINE);
        when(catalog.findById(7L)).thenReturn(Optional.of(g));
        boolean changed = service.update(ADMIN, 7L,
                new GiftCatalogForm(" Gift7 ", "Hadiah7", "1000", false, false, false, "NORMAL", "9", "2"), null);
        assertThat(changed).isFalse();
        verify(catalog, never()).save(any());
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void exclusiveToNormalClearsMinLevel() throws Exception {
        GiftCatalogItem g = gift(8L, 1000L, GiftKind.EXCLUSIVE, 3, 0, GiftCatalogStatus.ONLINE);
        when(catalog.findById(8L)).thenReturn(Optional.of(g));
        service.update(ADMIN, 8L,
                new GiftCatalogForm("Gift8", "Hadiah8", "1000", false, false, false, "NORMAL", "3", "0"), null);
        assertThat(g.getKind()).isEqualTo(GiftKind.NORMAL);
        assertThat(g.getMinLevel()).isNull();
    }

    @Test
    void missingGiftIs404() {
        when(catalog.findById(404L)).thenReturn(Optional.empty());
        assertCode(() -> service.detail(404L), "admin.err.gift.notFound");
        assertCode(() -> service.setOnline(ADMIN, 404L, false), "admin.err.gift.notFound");
    }

    // ——————————————————— AC7 上下线 ———————————————————

    @Test
    void offlineIsIdempotentAndAuditsOnlyRealChanges() throws Exception {
        GiftCatalogItem g = gift(9L, 1000L, GiftKind.NORMAL, null, 0, GiftCatalogStatus.ONLINE);
        when(catalog.findById(9L)).thenReturn(Optional.of(g));

        assertThat(service.setOnline(ADMIN, 9L, false)).isTrue();
        assertThat(g.getStatus()).isEqualTo(GiftCatalogStatus.OFFLINE);
        assertThat(service.setOnline(ADMIN, 9L, false)).as("已是目标状态 → 幂等").isFalse();
        verify(audit, times(1)).record(eq(ADMIN), eq(AuditActions.GIFT_CATALOG_OFFLINED), eq("gift_catalog"),
                eq("9"), anyString());

        assertThat(service.setOnline(ADMIN, 9L, true)).isTrue();
        verify(audit).record(eq(ADMIN), eq(AuditActions.GIFT_CATALOG_ONLINED), eq("gift_catalog"), eq("9"),
                anyString());
        verify(orders, never()).save(any());
    }

    // ——————————————————— AC3 列表 / AC6.4 统计 ———————————————————

    @Test
    void listSortsOfflineLastThenNormalBeforeExclusiveThenPriceSortId() throws Exception {
        List<GiftCatalogItem> all = new ArrayList<>(List.of(
                gift(1L, 5000L, GiftKind.NORMAL, null, 0, GiftCatalogStatus.OFFLINE),
                gift(2L, 1000L, GiftKind.EXCLUSIVE, 5, 0, GiftCatalogStatus.ONLINE),
                gift(3L, 5000L, GiftKind.NORMAL, null, 1, GiftCatalogStatus.ONLINE),
                gift(4L, 5000L, GiftKind.NORMAL, null, 0, GiftCatalogStatus.ONLINE),
                gift(5L, 1000L, GiftKind.NORMAL, null, 0, GiftCatalogStatus.ONLINE)));
        when(catalog.findAll()).thenReturn(all);

        CatalogPage page = service.page(Filter.NONE);
        assertThat(page.rows()).extracting(GiftCatalogRow::id).containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(page.summary().online()).isEqualTo(4);
        assertThat(page.summary().exclusive()).isEqualTo(1);
        assertThat(page.summary().offline()).isEqualTo(1);
        assertThat(page.priceOptions()).containsExactly(1000L, 5000L);
        assertThat(page.catalogEmpty()).isFalse();

        CatalogPage filtered = service.page(new Filter(5000L, GiftKind.NORMAL, GiftCatalogStatus.ONLINE));
        assertThat(filtered.rows()).extracting(GiftCatalogRow::id).containsExactly(4L, 3L);
        assertThat(filtered.summary()).as("摘要条全目录口径，不随筛选变").isEqualTo(page.summary());
        assertThat(filtered.priceOptions()).containsExactly(1000L, 5000L);
    }

    @Test
    void emptyCatalogIsFlagged() {
        when(catalog.findAll()).thenReturn(List.of());
        CatalogPage page = service.page(null);
        assertThat(page.rows()).isEmpty();
        assertThat(page.catalogEmpty()).isTrue();
    }

    @Test
    void detailCarriesDeliveredTotals() throws Exception {
        when(catalog.findById(11L)).thenReturn(
                Optional.of(gift(11L, 1000L, GiftKind.NORMAL, null, 0, GiftCatalogStatus.ONLINE)));
        List<Object[]> totals = new ArrayList<>();
        totals.add(new Object[] {12L, 4L});
        when(orders.deliveredTotalsOfGift(11L)).thenReturn(totals);
        var d = service.detail(11L);
        assertThat(d.received()).isEqualTo(12L);
        assertThat(d.sent()).isEqualTo(4L);
        assertThat(d.gift().code()).isEqualTo("gift-11");
    }

    // ——————————————————— AC8 QRIS 最低金额 ———————————————————

    @Test
    void qrisPreviewValidatesWithoutWriting() {
        var change = service.previewQrisMin("15000");
        assertThat(change.oldValue()).isEqualTo(10_000L);
        assertThat(change.newValue()).isEqualTo(15_000L);
        assertCode(() -> service.previewQrisMin("99"), "admin.err.gift.qrisMinRange");
        assertCode(() -> service.previewQrisMin("100000001"), "admin.err.gift.qrisMinRange");
        assertCode(() -> service.previewQrisMin("abc"), "admin.err.gift.qrisMinRange");
        assertCode(() -> service.previewQrisMin("10000"), "admin.err.gift.qrisMinNoChange");
        verify(pricing, never()).save(any());
        verify(changeLogs, never()).save(any());
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
        assertThat(pricingRow.getGiftQrisMinAmount()).isEqualTo(10_000L);
    }

    @Test
    void qrisSaveWritesPricingChangeLogAndOneAudit() {
        var change = service.updateQrisMin(ADMIN, "15000");
        assertThat(change.newValue()).isEqualTo(15_000L);
        assertThat(pricingRow.getGiftQrisMinAmount()).isEqualTo(15_000L);
        verify(pricing).save(pricingRow);
        ArgumentCaptor<ConfigChangeLog> log = ArgumentCaptor.forClass(ConfigChangeLog.class);
        verify(changeLogs).save(log.capture());
        assertThat(log.getValue().getConfigType()).isEqualTo(ConfigChangeLog.ConfigType.PRICING);
        assertThat(log.getValue().getField()).isEqualTo("gift_qris_min_amount");
        assertThat(log.getValue().getOldValue()).isEqualTo("10000");
        assertThat(log.getValue().getNewValue()).isEqualTo("15000");
        verify(audit, times(1)).record(eq(ADMIN), eq(AuditActions.GIFT_QRIS_MIN_UPDATED), eq("pricing_config"),
                eq("gift_qris_min_amount"), eq("gift_qris_min_amount 10000→15000"));
    }

    @Test
    void qrisSaveUsesTheSameValidationAsThePreview() {
        assertCode(() -> service.updateQrisMin(ADMIN, "10000"), "admin.err.gift.qrisMinNoChange");
        assertCode(() -> service.updateQrisMin(ADMIN, "50"), "admin.err.gift.qrisMinRange");
        verify(pricing, never()).save(any());
        verify(changeLogs, never()).save(any());
    }
}
