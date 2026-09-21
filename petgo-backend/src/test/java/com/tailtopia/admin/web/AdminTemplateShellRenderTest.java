package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shared.i18n.AdminLocaleConfig;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * L0：五套模板壳（Story 2.3b AC1）用 SpringTemplateEngine 直渲——槽位内容落到对应容器；缺槽位（null）渲染为空 / 默认空态，不抛异常。
 * 壳内不用 @{...}，故无需 Web 上下文。
 */
class AdminTemplateShellRenderTest {

    private static SpringTemplateEngine engine;

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
    }

    private static String render(String template) {
        Context ctx = new Context(Locale.SIMPLIFIED_CHINESE);
        return engine.process(template, ctx);
    }

    @Test
    void workbenchShellPlacesSlotsAndDefaultsEmptyStates() {
        String html = render("""
                <div th:replace="~{admin/fragments/tpl-a-workbench :: workbench(~{::t}, ~{::f}, ~{::q}, null, ~{::a})}">
                  <div th:fragment="t" class="tabs">TABS</div>
                  <form th:fragment="f">FILTERS</form>
                  <ul th:fragment="q" class="q-list"><li class="q-row" data-id="1">ROW1</li></ul>
                  <div th:fragment="a">ACTIONS</div>
                </div>""");
        assertThat(html).contains("data-workbench").contains("TABS").contains("FILTERS").contains("ROW1").contains("ACTIONS");
        assertThat(html).as("detail 缺槽位 → 默认空态").contains("从左侧队列选一条开始处理");
        assertThat(html).contains("data-pane=\"queue\"").contains("data-pane=\"detail\"").contains("id=\"admin-inline-error\"");
        assertThat(html).doesNotContain("ArrowUp"); // D-14
    }

    @Test
    void workbenchQueueEmptyDefaultsToCleared() {
        String html = render("<div th:replace=\"~{admin/fragments/tpl-a-workbench :: workbench(null, null, null, null, null)}\"></div>");
        assertThat(html).contains("队列已清空");
    }

    @Test
    void listShellRendersDrawerWithResIdAndMask() {
        String html = render("""
                <div th:with="res='order'">
                <div th:replace="~{admin/fragments/tpl-b-list :: list(~{::f}, ~{::s}, ~{::t}, ~{::d})}">
                  <form th:fragment="f">FILTERS</form>
                  <div th:fragment="s" class="sum">SUM</div>
                  <table th:fragment="t"><tr data-id="9" data-drawer-url="/x">ROW</tr></table>
                  <span th:fragment="d">TITLE</span>
                </div></div>""");
        assertThat(html).contains("id=\"order-drawer\"").contains("class=\"drawer\"").contains("data-drawer-mask")
                .contains("data-drawer-close").contains("FILTERS").contains("SUM").contains("ROW").contains("TITLE");
        String bare = render("<div th:replace=\"~{admin/fragments/tpl-b-list :: list(null, null, null, null)}\"></div>");
        assertThat(bare).contains("id=\"item-drawer\"").contains("没有匹配的结果");
    }

    @Test
    void reportShellIsReadonly() {
        String html = render("""
                <div th:replace="~{admin/fragments/tpl-c-report :: report(~{::r}, ~{::c}, null)}">
                  <form th:fragment="r">RANGE</form>
                  <div th:fragment="c" class="cards">CARDS</div>
                </div>""");
        assertThat(html).contains("data-readonly").contains("RANGE").contains("CARDS").contains("只读");
    }

    @Test
    void configCardHasDisabledSaveAndDirtyFlag() {
        String html = render("""
                <div th:with="action='/admin/x'">
                <div th:replace="~{admin/fragments/tpl-d-config-card :: configCard(~{::t}, ~{::f}, '保存定价')}">
                  <span th:fragment="t">TITLE</span>
                  <div th:fragment="f"><input name="a"/></div>
                </div></div>""");
        assertThat(html).contains("data-config-card").contains("action=\"/admin/x\"").contains("TITLE")
                .contains("name=\"a\"").contains("data-save").contains("disabled").contains("保存定价").contains("data-dirty-flag");
        String bare = render("<div th:replace=\"~{admin/fragments/tpl-d-config-card :: configCard(null, null, null)}\"></div>");
        assertThat(bare).contains(">保存<");
    }

    @Test
    void stepsShellHasStepBarAndStickyFooter() {
        String html = render("""
                <div th:replace="~{admin/fragments/tpl-e-steps :: steps(~{::s}, ~{::b}, ~{::f})}">
                  <ol th:fragment="s" class="step-bar"><li class="step step--on">1</li></ol>
                  <div th:fragment="b">BODY</div>
                  <div th:fragment="f">FOOTER</div>
                </div>""");
        assertThat(html).contains("data-steps").contains("step--on").contains("BODY").contains("sticky-footer").contains("FOOTER");
        assertThat(render("<div th:replace=\"~{admin/fragments/tpl-e-steps :: steps(null, null, null)}\"></div>")).contains("sticky-footer");
    }

    @Test
    void sharedFragmentsRender() {
        String html = render("<div th:replace=\"~{admin/fragments/tpl-shared :: inline-error('boom')}\"></div>"
                + "<div th:replace=\"~{admin/fragments/tpl-shared :: forbidden('需要「x」权限')}\"></div>"
                + "<div th:replace=\"~{admin/fragments/tpl-shared :: toast('done')}\"></div>");
        assertThat(html).contains("role=\"alert\"").contains("boom").contains("forbidden").contains("class=\"toast\"");
    }
}
