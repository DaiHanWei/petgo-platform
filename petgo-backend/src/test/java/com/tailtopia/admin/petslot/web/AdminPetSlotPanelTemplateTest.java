package com.tailtopia.admin.petslot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.petslot.service.AdminPetSlotTabService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.AdminLocaleConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

/**
 * L0：V1.4.0 multi-pet Story 4.3 · AC3 / AC4 / AC6 / AC8 —— 「宠物与名额」页签片段真渲染（无 Spring Security 方言：
 * {@code sec:authorize} 不求值，两张补发卡都出，只校验结构；权限分支由 L1 {@code AdminPetSlotIntegrationTest} 验）。
 * 另钉住地基契约：抽屉页签头 / {@code data-utab} 名不变、{@code petSlots} 容器换成本片段引用。
 */
class AdminPetSlotPanelTemplateTest {

    private static final Path DRAWER = Path.of("src", "main", "resources", "templates", "admin", "fragments",
            "drawer-users.html");

    private static SpringTemplateEngine engine;
    private static GenericApplicationContext app;

    @BeforeAll
    static void setUp() {
        ClassLoaderTemplateResolver files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        files.setOrder(1);
        files.setCheckExistence(true);
        StringTemplateResolver strings = new StringTemplateResolver();
        strings.setTemplateMode(TemplateMode.HTML);
        strings.setOrder(2);
        engine = new SpringTemplateEngine();
        engine.addTemplateResolver(files);
        engine.addTemplateResolver(strings);
        engine.setTemplateEngineMessageSource(new AdminLocaleConfig().messageSource());
        app = new GenericApplicationContext();
        app.refresh();
    }

    @AfterAll
    static void tearDown() {
        app.close();
    }

    private static String render(String template, Locale locale, Map<String, Object> vars) {
        MockServletContext sc = new MockServletContext();
        var exchange = JakartaServletWebApplication.buildApplication(sc)
                .buildExchange(new MockHttpServletRequest(sc), new MockHttpServletResponse());
        WebContext ctx = new WebContext(exchange, locale, new HashMap<>(vars));
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(app, null));
        return engine.process(template, ctx);
    }

    private static String panel(AdminPetSlotTabService.Tab tab, boolean visible, Locale locale, Boolean readOnly) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("petSlotsTab", tab);
        vars.put("petSlotGrantToken", "tok-uuid-1");
        vars.put("v", visible);
        if (readOnly != null) {
            vars.put("readOnly", readOnly);
        }
        return render("<div th:replace=\"~{admin/fragments/drawer-users-petslots :: panel(${v})}\"></div>", locale,
                vars);
    }

    private static AdminPetSlotTabService.Tab sample(boolean full) {
        return new AdminPetSlotTabService.Tab(10482L, false, false, full, full ? 5 : 3, 1, 1, 1, 3,
                List.of(new AdminPetSlotTabService.PetRow(5521L, "Orenji", "2026-06-12", "ACTIVE"),
                        new AdminPetSlotTabService.PetRow(6120L, "Boba", "2026-10-01", "READ_ONLY")),
                List.of(new AdminPetSlotTabService.RentalRow(1, "2026-10-01 10:12", "2026-10-31 10:12", true),
                        new AdminPetSlotTabService.RentalRow(2, "2026-08-01 10:12", "2026-08-31 10:12", false)),
                List.of(new AdminPetSlotTabService.PurchaseRow("2026-10-13 15:40", "ADMIN_GRANT", "PAID", "ops_lina",
                                7, 1, null, null, null, null),
                        new AdminPetSlotTabService.PurchaseRow("2026-10-01 10:12", "RENT", "PAID", null, 30, 1,
                                5000L, "PAWCOIN", null, null),
                        new AdminPetSlotTabService.PurchaseRow("2026-09-01 10:12", "RENT", "OVERPAID", null, 30, 1,
                                5000L, "QRIS", "pi_8f2c0d9e7b6a91", "pi_8f2…a91")),
                true, true);
    }

    @Test
    void panelRendersFiveBlocksInChinese() {
        String html = panel(sample(false), true, Locale.SIMPLIFIED_CHINESE, false);

        assertThat(html).contains("data-utab-panel=\"petSlots\"").contains("id=\"user-petslots-panel\"")
                .doesNotContain(" hidden");
        assertThat(html).contains("当前名额").contains("默认 / 等级").contains("1 / 1").contains("月租（生效中）")
                .contains("已建宠物");
        assertThat(html).contains("Orenji").contains("5521").contains("正常").contains("只读");
        assertThat(html).contains("月租名额 1").contains("生效中").contains("已到期");
        assertThat(html).contains("购买记录").contains("运营补发 · ops_lina · 7 天").contains("租用")
                .contains("Rp5.000").contains("PawCoin")
                .contains("已到账 · 超出 5 个名额上限未发放（待退款）").contains("仅显示最近 20 条");
        // intent 链接：新标签页打开支付抽屉（按对外 token）；只显示缩写
        assertThat(html).contains("href=\"/admin/payments/pi_8f2c0d9e7b6a91/drawer\"").contains("target=\"_blank\"")
                .contains("rel=\"noopener\"").contains(">pi_8f2…a91<");
        // 补发卡：表单、隐藏幂等 token、原生 confirm（{1} 原样保留给 admin-core.js 填天数）
        assertThat(html).contains("hx-post=\"/admin/users/10482/pet-slots/grant\"")
                .contains("hx-target=\"#user-drawer-err\"")
                .contains("name=\"grantToken\"").contains("value=\"tok-uuid-1\"")
                .contains("data-confirm-input=\"days\"")
                .contains("data-confirm=\"确认给该用户补发 1 个宠物名额（{1} 天）？补发后立即生效，到期照常提醒。\"")
                .contains("min=\"1\"").contains("max=\"365\"").contains("maxlength=\"200\"");
        // 无权限卡（sec 方言不在本测试里求值）：提示所缺权限
        assertThat(html).contains("需要「补发宠物名额」权限");
        assertThat(html).doesNotContain("??").doesNotContain("admin.v140.petSlots.");
    }

    @Test
    void hiddenWhenNotTheActiveTab() {
        assertThat(panel(sample(false), false, Locale.SIMPLIFIED_CHINESE, false))
                .containsPattern("data-utab-panel=\"petSlots\"[^>]*hidden|hidden[^>]*data-utab-panel=\"petSlots\"");
    }

    @Test
    void fullQuotaDisablesTheForm() {
        String html = panel(sample(true), true, Locale.SIMPLIFIED_CHINESE, false);
        assertThat(html).contains("已达 5 个名额上限，不能再补发");
        String form = html.substring(html.indexOf("data-ps-grant"), html.indexOf("data-ps-grant-denied"));
        assertThat(form).contains("name=\"days\"").containsPattern("name=\"days\"[^>]*disabled")
                .containsPattern("type=\"submit\"[^>]*disabled");
    }

    @Test
    void readOnlyDrawerRendersNoGrantCard() {
        String html = panel(sample(false), true, Locale.SIMPLIFIED_CHINESE, true);
        assertThat(html).doesNotContain("data-ps-grant").doesNotContain("pet-slots/grant");
        // 未传 readOnly（补发成功后单独渲染本片段）→ 照常渲染
        assertThat(panel(sample(false), true, Locale.SIMPLIFIED_CHINESE, null)).contains("data-ps-grant");
    }

    @Test
    void deletionPendingRendersNoGrantCard() {
        AdminPetSlotTabService.Tab t = sample(false);
        AdminPetSlotTabService.Tab pending = new AdminPetSlotTabService.Tab(t.userId(), false, true, false,
                t.quotaTotal(), t.defaultSlots(), t.levelSlots(), t.rentedActive(), t.petCount(), t.pets(),
                t.rentals(), t.purchases(), t.purchasesTruncated(), t.hasPurchaseExceptions());
        String html = panel(pending, true, Locale.SIMPLIFIED_CHINESE, null);
        assertThat(html).contains("当前名额").doesNotContain("data-ps-grant").doesNotContain("pet-slots/grant");
    }

    @Test
    void paymentLinkIsGatedByPaymentView() {
        // 本测试无 Spring Security 方言：sec:authorize 原样留在输出里，正好用来钉门控表达式
        String html = panel(sample(false), true, Locale.SIMPLIFIED_CHINESE, false);
        int a = html.indexOf("href=\"/admin/payments/pi_8f2c0d9e7b6a91/drawer\"");
        String anchor = html.substring(html.lastIndexOf("<a", a), html.indexOf("</a>", a));
        assertThat(anchor).contains("sec:authorize=\"hasRole('SUPER_ADMIN') or hasAuthority('payment.view')\"");
        assertThat(html).contains("sec:authorize=\"!(hasRole('SUPER_ADMIN') or hasAuthority('payment.view'))\"");
    }

    @Test
    void emptyStatesAndDeletedAccount() {
        AdminPetSlotTabService.Tab empty = new AdminPetSlotTabService.Tab(7L, false, false, false, 2, 1, 1, 0, 2, List.of(),
                List.of(), List.of(), false, false);
        String html = panel(empty, true, Locale.forLanguageTag("id"), false);
        assertThat(html).contains("Pengguna ini belum punya slot sewa").contains("Slot sewa 0")
                .doesNotContain("Riwayat pembelian");

        AdminPetSlotTabService.Tab deleted = new AdminPetSlotTabService.Tab(7L, true, false, false, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), false, false);
        String d = panel(deleted, true, Locale.ENGLISH, false);
        assertThat(d).contains("Account deleted").doesNotContain("removed").doesNotContain("data-ps-grant")
                .doesNotContain("Current slots");
    }

    @Test
    void englishAndIndonesianHaveNoMissingKeys() {
        for (Locale l : List.of(Locale.ENGLISH, Locale.forLanguageTag("id"))) {
            String html = panel(sample(false), true, l, false);
            assertThat(html).as(l.toString()).doesNotContain("??").doesNotContain("admin.v140.petSlots.")
                    .contains("{1}");
        }
        assertThat(panel(sample(false), true, Locale.ENGLISH, false)).contains("Ops grant · ops_lina · 7 days");
    }

    @Test
    void afterGrantSwapsPanelClearsErrorAndToasts() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("petSlotsTab", sample(false));
        vars.put("petSlotGrantToken", "tok-uuid-2");
        vars.put("toast", "已补发 1 个宠物名额（7 天）");
        String html = render("<div th:replace=\"~{admin/fragments/drawer-users-petslots :: afterGrant}\"></div>",
                Locale.SIMPLIFIED_CHINESE, vars);
        assertThat(html).contains("id=\"user-petslots-panel\"").contains("value=\"tok-uuid-2\"")
                .contains("id=\"user-drawer-err\" hx-swap-oob=\"innerHTML\"")
                .contains("hx-swap-oob=\"beforeend:#admin-toast-host\"").contains("已补发 1 个宠物名额（7 天）");
    }

    /** 🔴 地基契约：九个页签头与 data-utab 名不变；petSlots 空容器整段换成本片段引用。 */
    @Test
    void drawerTabHeadersUnchangedAndPetSlotsPanelIsTheFragment() throws IOException {
        String drawer = Files.readString(DRAWER, StandardCharsets.UTF_8);
        for (String tab : List.of("basic", "pets", "posts", "consults", "actions", "purchases", "growth", "petSlots",
                "gifts")) {
            assertThat(drawer).contains("data-utab=\"" + tab + "\"");
        }
        assertThat(drawer).contains("th:text=\"#{admin.v140.users.tab.petSlots}\">宠物与名额</button>")
                .contains("th:replace=\"~{admin/fragments/drawer-users-petslots :: panel(false)}\"")
                .doesNotContain("data-utab-panel=\"petSlots\" hidden");
    }

    @Test
    void parseDaysAcceptsOnlyOneTo365() {
        assertThat(AdminPetSlotController.parseDays(" 7 ")).isEqualTo(7);
        assertThat(AdminPetSlotController.parseDays("365")).isEqualTo(365);
        for (String bad : new String[] {null, "", "0", "366", "-3", "7.5", "abc"}) {
            assertThatThrownBy(() -> AdminPetSlotController.parseDays(bad)).as(String.valueOf(bad))
                    .isInstanceOf(AppException.class).extracting("messageCode")
                    .isEqualTo("admin.err.petSlot.daysInvalid");
        }
        assertThat(AdminPetSlotController.PET_SLOT_ADJUST_AUTH)
                .isEqualTo("hasRole('SUPER_ADMIN') or hasAuthority('user.pet_slot_adjust')");
    }
}
