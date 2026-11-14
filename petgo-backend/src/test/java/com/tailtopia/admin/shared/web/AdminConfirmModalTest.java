package com.tailtopia.admin.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shared.i18n.AdminLocaleConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * L0（V1.4.2 shop-admin Story 1.2 · AD-3）：confirm-modal 新增的金额明细 / 单选组 / 下拉 / 成功目标 / 原生提交五项可选能力。
 * 未传时渲染与现状一致（不出新块、仍 hx-post 到弹层内错误槽）。直接用模板引擎渲染片段，无需 Web 上下文。
 */
class AdminConfirmModalTest {

    private static SpringTemplateEngine engine;

    @BeforeAll
    static void setUp() {
        ClassLoaderTemplateResolver files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        engine = new SpringTemplateEngine();
        engine.addTemplateResolver(files);
        engine.setTemplateEngineMessageSource(new AdminLocaleConfig().messageSource());
        // 模板里用 @{...}：非 Web 上下文下把 context path 当成空串
        engine.setLinkBuilder(new StandardLinkBuilder() {
            @Override
            protected String computeContextPath(IExpressionContext context, String base, Map<String, Object> parameters) {
                return "";
            }
        });
    }

    private static String render(AdminConfirmModal cm) {
        Context ctx = new Context(Locale.SIMPLIFIED_CHINESE);
        ctx.setVariable("cm", cm);
        return engine.process("admin/fragments/confirm-modal", java.util.Set.of("modal"), ctx);
    }

    private static AdminConfirmModal base() {
        Map<String, String> hidden = new LinkedHashMap<>();
        hidden.put("reason", "缺货");
        return AdminConfirmModal.simple("请确认", "说明", "/admin/x", hidden, "确认", true);
    }

    @Test
    void withoutNewBlocksRendersLikeBefore() {
        String html = render(base());
        assertThat(html).contains("hx-post=\"/admin/x\"").contains("hx-target=\"#confirm-modal-err\"")
                .contains("hx-swap=\"innerHTML\"").contains("name=\"reason\"").contains("btn btn-danger");
        assertThat(html).doesNotContain("data-confirm-amounts").doesNotContain("data-confirm-choices")
                .doesNotContain("data-confirm-select").doesNotContain("data-cm-success-target")
                .doesNotContain("method=\"post\"");
        assertThat(html.split("<form", -1)).as("只有一个表单").hasSize(2);
    }

    @Test
    void amountsRenderLabelLeftAmountRightWithTotalRow() {
        String html = render(base().withAmounts(List.of(
                new AdminConfirmModal.AmountLine("PawCoin 段", "Rp 20.000", "即时退回", false),
                AdminConfirmModal.AmountLine.of("现金段", "Rp 100.000"),
                AdminConfirmModal.AmountLine.total("退款合计", "Rp 120.000"))));
        assertThat(html).contains("data-confirm-amounts").contains("kv amt");
        assertThat(html).contains("PawCoin 段").contains("Rp 20.000").contains("即时退回").contains("class=\"nt\"");
        assertThat(html).contains("退款合计").contains("Rp 120.000");
        assertThat(html).containsPattern("class=\"tl\"[^>]*>退款合计|退款合计");
        assertThat(html).contains("tl");
    }

    @Test
    void choicesAndSelectRenderAsFormFields() {
        String html = render(base()
                .withChoices(new AdminConfirmModal.ChoiceGroup("voidUnused", "已领未用", List.of(
                        new AdminConfirmModal.Choice("false", "继续可用", null, true),
                        new AdminConfirmModal.Choice("true", "停用并作废", "已占用的不作废", false))))
                .withSelect(new AdminConfirmModal.SelectField("category", "原因类别", List.of(
                        new AdminConfirmModal.Option("OUT_OF_STOCK", "缺货", false),
                        new AdminConfirmModal.Option("PICKUP_FAILED", "揽收失败", true)), true)));
        assertThat(html).contains("data-confirm-choices").contains("type=\"radio\"").contains("name=\"voidUnused\"")
                .contains("继续可用").contains("已占用的不作废").contains("checked");
        assertThat(html).contains("data-confirm-select").contains("name=\"category\"").contains("required")
                .contains("value=\"PICKUP_FAILED\" selected");
    }

    @Test
    void successTargetIsCarriedForTheCoreScript() {
        String html = render(base().withSuccessTarget(".wb-detail-body", "innerHTML"));
        assertThat(html).contains("data-cm-success-target=\".wb-detail-body\"").contains("hx-target=\"#confirm-modal-err\"");
    }

    @Test
    void nativePostRendersAPlainForm() {
        String html = render(base().asNativePost());
        assertThat(html).contains("method=\"post\"").contains("action=\"/admin/x\"").doesNotContain("hx-post");
    }

    @Test
    void legacyConstructorsKeepNewBlocksEmpty() {
        AdminConfirmModal m = new AdminConfirmModal("t", null, List.of(), null, null, null, null, "/x", Map.of(), "ok",
                false, false, List.of());
        assertThat(m.amounts()).isEmpty();
        assertThat(m.choices()).isNull();
        assertThat(m.select()).isNull();
        assertThat(m.successTarget()).isNull();
        assertThat(m.nativePost()).isFalse();
        assertThat(m.formSwap()).isEqualTo("innerHTML");
    }

    /** V1.5.0 pet-species Story 2.1 · G2：下拉分组（optgroup）与多行输入只追加，不传时不出块。 */
    @Test
    void optionGroupsAndTextAreaRender() {
        assertThat(render(base())).doesNotContain("data-confirm-textarea").doesNotContain("<optgroup");
        AdminConfirmModal cm = base()
                .withSelect(new AdminConfirmModal.SelectField("species", "新类型 *", List.of(
                        new AdminConfirmModal.Option("RABBIT", "兔", true)), true,
                        List.of(new AdminConfirmModal.OptionGroup("其他",
                                List.of(new AdminConfirmModal.Option("CIVET", "麝香猫", false))))))
                .withTextArea(new AdminConfirmModal.TextAreaField("reason", "修改原因 *", "写清为什么改", 200, true));
        String html = render(cm);
        assertThat(html).contains("<optgroup label=\"其他\"").contains("value=\"CIVET\"")
                .contains("value=\"RABBIT\" selected");
        assertThat(html).contains("data-confirm-textarea").contains("<textarea").contains("name=\"reason\"")
                .contains("maxlength=\"200\"").contains("placeholder=\"写清为什么改\"");
        // 其他 with* 不丢多行输入。
        assertThat(cm.withSuccessTarget("#x", null).textArea()).isNotNull();
        assertThat(cm.asNativePost().textArea()).isNotNull();
        assertThat(cm.withAmounts(List.of()).textArea()).isNotNull();
        assertThat(new AdminConfirmModal.SelectField("s", "l", List.of(), true).groups()).isEmpty();
    }
}
