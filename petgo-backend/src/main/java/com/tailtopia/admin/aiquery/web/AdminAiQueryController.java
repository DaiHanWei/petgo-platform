package com.tailtopia.admin.aiquery.web;

import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.aiquery.domain.AiQueryLanguage;
import com.tailtopia.admin.aiquery.agent.AiQueryAgentClient;
import com.tailtopia.admin.aiquery.service.AiQueryFileGone;
import com.tailtopia.admin.aiquery.service.AiQueryFileRef;
import com.tailtopia.admin.aiquery.service.AiQueryNotFound;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import com.tailtopia.admin.aiquery.service.AiQueryQueries;
import com.tailtopia.admin.aiquery.service.AiQueryQueries.ConversationView;
import com.tailtopia.admin.aiquery.service.AiQueryRejection;
import com.tailtopia.admin.aiquery.service.AiQueryService;
import com.tailtopia.admin.aiquery.service.AiQueryTokens;
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
 * ops AI 查数页（主题 ops-ai-query，spec §5.2 / §5.3，UX A–E）。SSR，表单 PRG；
 * 轮询由 admin-ai-query.js 的 fetch 循环调用 {@code /poll}（Story 1.6），左栏滚动加载用 htmx q-more。
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
    private final AiQueryAgentClient agent;
    private final AdminAuditService audit;

    public AdminAiQueryController(AiQueryProperties props, AiQueryService service, AiQueryQueries queries,
            Messages msg, AiQueryAgentClient agent, AdminAuditService audit) {
        this.props = props;
        this.service = service;
        this.queries = queries;
        this.msg = msg;
        this.agent = agent;
        this.audit = audit;
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

    /** 重新查询 / 用原问题重试：在当前对话新增一轮，沿用某一轮的原问题。 */
    @PostMapping(ROUTE + "/{token}/requery")
    @PreAuthorize(AUTH)
    public String requery(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(value = "m", required = false) String messageToken,
            Locale locale, Model model, HttpServletResponse response) {
        try {
            service.requery(admin.getAdminAccountId(), token, messageToken);
            return "redirect:" + ROUTE + "/" + token;
        } catch (AiQueryRejection e) {
            populate(admin.getAdminAccountId(), queries.view(admin.getAdminAccountId(), token), locale, model);
            return rejected(e, null, model, response);
        }
    }

    /**
     * 下载答案里的导出文件（Story 2.1）：先校验归属（对话本人、消息在对话里、文件在清单里）再从 agent 流式取回。
     * 他人 / 不存在 → 404；文件名含路径分隔符等 → 400；超过保留期或 agent 已清除 → 410（C4）。
     * 取回成功才记 {@code AI_QUERY_DOWNLOAD} 审计，摘要只含 token 与文件名。
     */
    @GetMapping(ROUTE + "/{token}/files/{message}/{name}")
    @PreAuthorize(AUTH)
    public String download(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @PathVariable String message, @PathVariable String name, Model model, HttpServletResponse response) {
        if (!AiQueryQueries.isSafeFileName(name)) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return fileProblem(model, "admin.aiQuery.file.bad");
        }
        AiQueryFileRef ref;
        try {
            ref = queries.file(admin.getAdminAccountId(), token, message, name);
        } catch (AiQueryFileGone e) {
            response.setStatus(HttpStatus.GONE.value());
            return fileProblem(model, "admin.aiQuery.file.gone");
        }
        var outcome = agent.download(ref.messageToken(), ref.name(), (size, body) -> {
            audit.record(admin.getAdminAccountId(), AuditActions.AI_QUERY_DOWNLOAD, AiQueryService.AUDIT_TARGET,
                    ref.messageToken(), "conversation=" + ref.conversationToken() + " message=" + ref.messageToken()
                            + " file=" + ref.name());
            response.setStatus(HttpStatus.OK.value());
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                    .filename(ref.name(), StandardCharsets.UTF_8).build().toString());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Cache-Control", "no-store");
            if (size >= 0) {
                response.setContentLengthLong(size);
            }
            body.transferTo(response.getOutputStream());
            response.flushBuffer();
        });
        return switch (outcome) {
            // 已开始写出（含中途中断）：响应已提交，不再渲染页面
            case OK, ABORTED -> null;
            case GONE -> {
                response.setStatus(HttpStatus.GONE.value());
                yield fileProblem(model, "admin.aiQuery.file.gone");
            }
            case UNAVAILABLE -> {
                response.setStatus(HttpStatus.BAD_GATEWAY.value());
                yield fileProblem(model, "admin.aiQuery.file.unavailable");
            }
        };
    }

    private String fileProblem(Model model, String headingKey) {
        model.addAttribute("active", "ai-query");
        model.addAttribute("aiqGoneKey", headingKey);
        return VIEW_GONE;
    }

    /** 左栏滚动加载（E3，沿用工作台 q-more）：游标之后的一页行 + 下一页哨兵。 */
    @GetMapping(ROUTE + "/rows")
    @PreAuthorize(AUTH)
    public String rows(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam(value = "c", required = false) String current, Model model) {
        model.addAttribute("aiqList", queries.list(admin.getAdminAccountId(), after));
        model.addAttribute("aiqFirstPage", after == null);
        model.addAttribute("aiqCurrent", current);
        return "admin/fragments/ai-query-list :: rows";
    }

    /**
     * 页面轮询（UX-DR13，仅在有未完成任务时由 admin-ai-query.js 每 3 秒调用）：
     * {@code w} = 左栏里未完成的对话，{@code m} = 右栏里未完成的轮次，{@code c} = 当前对话。
     * 返回按元素 id 替换的片段：左栏行、右栏轮次；当前对话的未完成轮次结束时，连同输入框一起替换。
     * 只返回本人未过期的数据；查不到的 token 不返回任何内容（前端随即停止关注）。
     */
    @GetMapping(ROUTE + "/poll")
    @PreAuthorize(AUTH)
    public String poll(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "w", required = false) List<String> watchedConversations,
            @RequestParam(value = "m", required = false) List<String> watchedMessages,
            @RequestParam(value = "c", required = false) String current,
            Locale locale, Model model, HttpServletResponse response) {
        long me = admin.getAdminAccountId();
        List<String> w = validTokens(watchedConversations, POLL_MAX_CONVERSATIONS);
        List<String> m = validTokens(watchedMessages, POLL_MAX_MESSAGES);
        var rows = queries.rows(me, w);
        var msgs = queries.messages(me, current, m);
        ConversationView view = null;
        if (AiQueryTokens.isValid(current) && msgs.stream().anyMatch(x -> !x.isUnfinished())) {
            try {
                view = queries.view(me, current);
            } catch (AiQueryNotFound ignored) {
                view = null;
            }
        }
        populateComposer(view, locale, model);
        model.addAttribute("aiqRows", rows);
        model.addAttribute("aiqMessages", msgs);
        model.addAttribute("aiqComposerRefresh", view != null);
        model.addAttribute("aiqCurrent", current);
        response.setHeader("Cache-Control", "no-store");
        return "admin/fragments/ai-query-poll :: poll";
    }

    static final int POLL_MAX_CONVERSATIONS = 50;
    static final int POLL_MAX_MESSAGES = 10;

    private static List<String> validTokens(List<String> in, int max) {
        return in == null ? List.of()
                : in.stream().filter(AiQueryTokens::isValid).distinct().limit(max).toList();
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
        populateComposer(view, locale, model);
        model.addAttribute("aiqSamples", SAMPLE_KEYS);
        model.addAttribute("aiqList", queries.list(accountId, null));
        model.addAttribute("aiqFirstPage", true);
        model.addAttribute("aiqCurrent", view == null ? null : view.conversation().getToken());
    }

    private void populateComposer(ConversationView view, Locale locale, Model model) {
        // 答案页脚「重新查询」、失败卡「用原问题重试」只在能提交时显示（功能开启、对话不忙、未被授权失效锁住）
        model.addAttribute("aiqActions", props.isEnabled() && view != null && !view.busy() && !view.authLocked());
        model.addAttribute("aiqEnabled", props.isEnabled());
        model.addAttribute("aiqDefaultLang", AiQueryLanguage.fromLocale(locale).code());
        model.addAttribute("aiqLanguages", AiQueryLanguage.values());
        model.addAttribute("aiqView", view);
    }
}
