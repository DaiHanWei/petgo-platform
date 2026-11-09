package com.tailtopia.admin.aiquery.web;

import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.aiquery.domain.AiQueryLanguage;
import com.tailtopia.admin.aiquery.service.AiQueryNotFound;
import com.tailtopia.admin.aiquery.service.AiQueryQueries;
import com.tailtopia.admin.aiquery.service.AiQueryQueries.ConversationView;
import com.tailtopia.admin.aiquery.service.AiQueryRejection;
import com.tailtopia.admin.aiquery.service.AiQueryService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ops AI 查数页（主题 ops-ai-query，spec §5.2 / §5.3，UX A–E）。SSR，表单 PRG；轮询走 htmx（Story 1.6）。
 *
 * <ul>
 * <li>权限：{@code ai.query}，不预授予任何角色；SUPER_ADMIN 隐式拥有。无权限走现有拒绝页（A3）。</li>
 * <li>{@code AI_QUERY_MODE=off}（默认）：提示未启用，输入框与发送禁用（A2）。</li>
 * <li>对话只对本人可见（含超管）；不存在 / 属于他人 / 已过期一律 404（E1）。</li>
 * <li>提交只写 {@code PENDING} 并重定向回对话页，不同步调用 agent。被拒时整页重渲染，错误紧贴输入框、保留内容（B2 / B6）。</li>
 * </ul>
 */
@Controller
public class AdminAiQueryController {

    public static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('ai.query')";
    public static final String ROUTE = "/admin/ai-query";
    static final String VIEW = "admin/ai-query";
    static final String VIEW_GONE = "admin/ai-query-gone";

    /** 空态示例问题（UX-DR6）：分类 key → 问题 key，文案三语。点击只填入输入框，不发送。 */
    static final List<String> SAMPLE_KEYS = List.of("users", "payments", "export", "logs");

    private final AiQueryProperties props;
    private final AiQueryService service;
    private final AiQueryQueries queries;
    private final Messages msg;

    public AdminAiQueryController(AiQueryProperties props, AiQueryService service, AiQueryQueries queries,
            Messages msg) {
        this.props = props;
        this.service = service;
        this.queries = queries;
        this.msg = msg;
    }

    /** 新对话（左栏本人对话 + 右栏空态）。 */
    @GetMapping(ROUTE)
    @PreAuthorize(AUTH)
    public String page(@AuthenticationPrincipal AdminUserDetails admin, Locale locale, Model model) {
        populate(admin.getAdminAccountId(), null, locale, model);
        return VIEW;
    }

    /** 某个对话。 */
    @GetMapping(ROUTE + "/{token}")
    @PreAuthorize(AUTH)
    public String conversation(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            Locale locale, Model model) {
        populate(admin.getAdminAccountId(), queries.view(admin.getAdminAccountId(), token), locale, model);
        return VIEW;
    }

    /**
     * 新建对话并提问。回答语言字段名用 {@code answerLang}：{@code lang} 是后台全局切换界面语言的参数
     * （LocaleChangeInterceptor），同名会让提交顺带改掉界面语言。
     */
    @PostMapping(ROUTE)
    @PreAuthorize(AUTH)
    public String start(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "question", required = false) String question,
            @RequestParam(value = "answerLang", required = false) String lang,
            Locale locale, Model model, HttpServletResponse response) {
        try {
            var r = service.startConversation(admin.getAdminAccountId(), question, lang);
            return "redirect:" + ROUTE + "/" + r.conversationToken();
        } catch (AiQueryRejection e) {
            populate(admin.getAdminAccountId(), null, locale, model);
            AiQueryLanguage.fromCode(lang).ifPresent(l -> model.addAttribute("aiqDefaultLang", l.code()));
            return rejected(e, question, model, response);
        }
    }

    /** 在对话里追问（语言沿用对话）。 */
    @PostMapping(ROUTE + "/{token}/messages")
    @PreAuthorize(AUTH)
    public String followUp(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(value = "question", required = false) String question,
            Locale locale, Model model, HttpServletResponse response) {
        try {
            service.followUp(admin.getAdminAccountId(), token, question);
            return "redirect:" + ROUTE + "/" + token;
        } catch (AiQueryRejection e) {
            populate(admin.getAdminAccountId(), queries.view(admin.getAdminAccountId(), token), locale, model);
            return rejected(e, question, model, response);
        }
    }

    /** E1：不存在 / 属于他人 / 已过期，同一文案、同一状态码。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(AiQueryNotFound.class)
    public String notFound(Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        model.addAttribute("active", "ai-query");
        return VIEW_GONE;
    }

    private String rejected(AiQueryRejection e, String question, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        model.addAttribute("aiqDraft", question);
        model.addAttribute("aiqError", msg.get(e.messageKey(), e.args()));
        return VIEW;
    }

    private void populate(long accountId, ConversationView view, Locale locale, Model model) {
        model.addAttribute("active", "ai-query");
        model.addAttribute("aiqEnabled", props.isEnabled());
        model.addAttribute("aiqDefaultLang", AiQueryLanguage.fromLocale(locale).code());
        model.addAttribute("aiqLanguages", AiQueryLanguage.values());
        model.addAttribute("aiqSamples", SAMPLE_KEYS);
        model.addAttribute("aiqList", queries.list(accountId, 0));
        model.addAttribute("aiqView", view);
    }
}
