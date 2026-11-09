package com.tailtopia.admin.aiquery.web;

import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.aiquery.domain.AiQueryLanguage;
import java.util.List;
import java.util.Locale;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * ops AI 查数页（主题 ops-ai-query，spec §5.2 / §5.3，UX A1–A3）。SSR + htmx。
 *
 * <ul>
 * <li>权限：{@code ai.query}，不预授予任何角色；SUPER_ADMIN 隐式拥有。无权限走现有拒绝页（A3）。</li>
 * <li>{@code AI_QUERY_MODE=off}（默认）：提示未启用，输入框与发送禁用（A2）。</li>
 * </ul>
 */
@Controller
public class AdminAiQueryController {

    public static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('ai.query')";
    public static final String ROUTE = "/admin/ai-query";

    /** 空态示例问题（UX-DR6）：分类 key → 问题 key，文案三语。点击只填入输入框，不发送。 */
    static final List<String> SAMPLE_KEYS = List.of("users", "payments", "export", "logs");

    private final AiQueryProperties props;

    public AdminAiQueryController(AiQueryProperties props) {
        this.props = props;
    }

    @GetMapping(ROUTE)
    @PreAuthorize(AUTH)
    public String page(Locale locale, Model model) {
        model.addAttribute("active", "ai-query");
        model.addAttribute("aiqEnabled", props.isEnabled());
        model.addAttribute("aiqDefaultLang", AiQueryLanguage.fromLocale(locale).code());
        model.addAttribute("aiqLanguages", AiQueryLanguage.values());
        model.addAttribute("aiqSamples", SAMPLE_KEYS);
        return "admin/ai-query";
    }
}
