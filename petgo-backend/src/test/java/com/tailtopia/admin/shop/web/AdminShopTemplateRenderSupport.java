package com.tailtopia.admin.shop.web;

import com.tailtopia.shared.i18n.AdminLocaleConfig;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * L0 模板直渲工具（V1.4.2 shop-admin）：不起 Spring 上下文、不连库，按给定 model 渲染某个片段，
 * 用来在云端挡住模板语法 / 表达式错误与缺 i18n key（{@code ??key??}）。页面级行为仍以 L1 测试为准。
 */
final class AdminShopTemplateRenderSupport {

    private static final SpringTemplateEngine ENGINE = build();

    private AdminShopTemplateRenderSupport() {
    }

    private static SpringTemplateEngine build() {
        ClassLoaderTemplateResolver files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        files.setOrder(1);
        files.setCheckExistence(true);
        org.thymeleaf.templateresolver.StringTemplateResolver strings = new org.thymeleaf.templateresolver.StringTemplateResolver();
        strings.setTemplateMode(TemplateMode.HTML);
        strings.setOrder(2);
        SpringTemplateEngine e = new SpringTemplateEngine();
        e.addTemplateResolver(files);
        e.addTemplateResolver(strings);
        e.setTemplateEngineMessageSource(new AdminLocaleConfig().messageSource());
        e.setLinkBuilder(new StandardLinkBuilder() {
            @Override
            protected String computeContextPath(IExpressionContext context, String base, Map<String, Object> parameters) {
                return "";
            }
        });
        try {
            e.addDialect(new SpringSecurityDialect());
        } catch (NoClassDefFoundError ignored) {
            // 未引入安全方言时跳过（片段不含 sec:）
        }
        return e;
    }

    static String render(String template, String fragment, Map<String, Object> model, Locale locale) {
        Context ctx = new Context(locale);
        model.forEach(ctx::setVariable);
        return ENGINE.process(template, Set.of(fragment), ctx);
    }

    /** 渲染一段内联标记（用于带参数的片段：{@code <div th:replace="~{… :: panel(true)}"></div>}）。 */
    static String renderInline(String markup, Map<String, Object> model, Locale locale) {
        Context ctx = new Context(locale);
        model.forEach(ctx::setVariable);
        return ENGINE.process(markup, ctx);
    }

    static String render(String template, String fragment, Map<String, Object> model) {
        return render(template, fragment, model, Locale.SIMPLIFIED_CHINESE);
    }
}
