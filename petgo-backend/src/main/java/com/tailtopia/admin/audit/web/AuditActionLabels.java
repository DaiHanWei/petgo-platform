package com.tailtopia.admin.audit.web;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * 审计「动作说明」列的本地化文案（bug 20260923-548）。模板里用 {@code ${@auditActionLabels.label(r.actionType)}}。
 *
 * <p>审计 summary 落库后不可改（哈希链 append-only），历史摘要是什么语言就是什么语言；
 * 让页面跟随后台语言的办法是<b>按 action code 取三语文案</b>，另起一列展示，summary 原样保留。
 *
 * <p>键 = {@code admin.audit.action.<ACTION_CODE>}。<b>缺键回退显示 code 本身、不报错</b>：
 * 不能直接用模板的 {@code #{…}} —— messageSource 开了 {@code useCodeAsDefaultMessage}，缺键会露出
 * {@code admin.audit.action.XXX} 这种键名；这里显式把默认值给成 code。
 * 动态拼出来的 code（如 {@code CONFIG_UPDATE_*}）没有逐一配文案，走的就是这条回退。
 */
@Component("auditActionLabels")
public class AuditActionLabels {

    static final String PREFIX = "admin.audit.action.";

    private final MessageSource messageSource;

    public AuditActionLabels(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 当前请求语言下的动作说明；code 为空返回空串，缺文案返回 code。 */
    public String label(String actionCode) {
        return label(actionCode, LocaleContextHolder.getLocale());
    }

    String label(String actionCode, Locale locale) {
        if (actionCode == null || actionCode.isBlank()) {
            return "";
        }
        String v = messageSource.getMessage(PREFIX + actionCode, null, actionCode, locale);
        return v == null || v.isBlank() ? actionCode : v;
    }
}
