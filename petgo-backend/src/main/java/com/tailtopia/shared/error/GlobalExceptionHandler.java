package com.tailtopia.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理 —— 所有异常统一输出 RFC 9457 ProblemDetail。
 * 字段：type / title / status / detail / instance / traceId（校验错误附 errors）。
 * 强制护栏：绝不外泄堆栈；5xx 不回显内部细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final com.tailtopia.shared.i18n.Messages messages;

    public GlobalExceptionHandler(com.tailtopia.shared.i18n.Messages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ProblemDetail> handleApp(AppException ex, HttpServletRequest req) {
        // 挂了文案码的按当前请求 locale 取（后台页面用），没挂码的原样输出。
        // api 链请求没有后台 locale cookie，回落 zh_CN —— 与外化前逐字相同，App 侧无感知。
        ProblemDetail pd = base(ex.getStatus(), ex.getType(), titleFor(ex.getStatus()),
                messages.resolve(ex), req);
        // RFC 9457 §3.2 扩展成员：少数错误光有一句 detail 不够用（如下单被库存挡住时的逐行明细）。
        // 信封仍在这里统一产出 —— 控制器自拼会漏掉 traceId/instance，而排障最先看的就是 traceId。
        if (ex instanceof ProblemExtensions pe) {
            pe.problemExtensions().forEach(pd::setProperty);
        }
        return ResponseEntity.status(ex.getStatus()).body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        ProblemDetail pd = base(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.VALIDATION, "Validation Failed",
                "请求参数校验未通过", req);
        List<FieldErrorEntry> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorEntry(fe.getField(), fe.getDefaultMessage()))
                .toList();
        pd.setProperty("errors", errors);
        return ResponseEntity.unprocessableEntity().body(pd);
    }

    /** 未匹配任何路由/静态资源 → 404（而非落到 catch-all 误报 500）。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        // ⚠️ H5 路径上的「找不到」是**终局**（链接错了/没了），不是「暂时性故障」——
        // 故走失效页而不是那张带「再试一次」的加载失败页。给一个重试按钮只会让人白点。
        // 语义划分见视觉稿 E3（终局）与 E4（暂时）。
        Object page = h5PageOrNull(req, HttpStatus.NOT_FOUND, "card_gone");
        if (page != null) {
            return page;
        }
        ProblemDetail pd = base(HttpStatus.NOT_FOUND, ErrorTypes.NOT_FOUND, "Not Found", "请求的资源不存在", req);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    /** 缺必填参数 / 类型不匹配 / 请求体不可读 / 缺 multipart 部件 → 400（而非 catch-all 误报 500）。 */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception ex, HttpServletRequest req) {
        ProblemDetail pd = base(HttpStatus.BAD_REQUEST, ErrorTypes.VALIDATION, "Bad Request",
                "请求格式不正确", req);
        return ResponseEntity.badRequest().body(pd);
    }

    /**
     * 方法级 {@code @PreAuthorize} 拒绝（{@code AuthorizationDeniedException} 亦为其子类）：
     * 原样重抛，交还 Spring Security 的 ExceptionTranslationFilter 按各链 accessDeniedHandler 处理为 403
     * （api 链 → ProblemDetail；admin 链 → 「权限不足」页）。否则会落入下方 catch-all 被误报成 500。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public void handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public Object handleUnexpected(Exception ex, HttpServletRequest req) {
        // 5xx：服务端记录完整堆栈，对外仅给通用文案 + traceId，绝不外泄内部细节
        String traceId = currentTraceId();
        log.error("Unhandled exception [traceId={}]", traceId, ex);
        // ⚠️ H5 对外分享页出错时，访客拿到一坨 JSON 是没法看的 —— 给它一张能看懂、带重试的页面。
        Object page = h5PageOrNull(req, HttpStatus.INTERNAL_SERVER_ERROR, "card_error");
        if (page != null) {
            return page;
        }
        ProblemDetail pd = base(HttpStatus.INTERNAL_SERVER_ERROR, ErrorTypes.INTERNAL, "Internal Server Error",
                "服务暂时不可用，请稍后重试", req, traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }

    /**
     * H5 对外页（{@code /p/**} 宠物名片、{@code /m/**} 里程碑分享）且访客要的是 HTML
     * → 返回指定的剪贴簿状态页（V1.1.6 Story 1.3 · AC3/AC4）；否则返回 {@code null}，
     * 调用方照常走 ProblemDetail JSON。
     *
     * <p>调用方按语义选视图：<b>{@code card_gone} = 终局</b>（链接错了/没了，重试无用）、
     * <b>{@code card_error} = 暂时</b>（服务异常，可重试）。两者不可混用 ——
     * 给一个「找不到」的页面配重试按钮，只会让人白点。
     *
     * <p>🛡 <b>三条红线，各有测试守着</b>（{@code H5ErrorControllerIntegrationTest}）：
     * ① API 出错仍回 JSON —— 客户端拿到 HTML 只会解析失败；
     * ② 运营后台不受影响 —— 后台报错不该显示宠物剪贴簿页；
     * ③ 状态码原样透传 —— 渲染了页面不代表请求成功。
     *
     * <p>⚠️ <b>为什么这段必须放在这里、而不是只靠 {@code H5ErrorController}</b>：
     * 本类的 {@code @ExceptionHandler(Exception.class)} 兜住了<b>所有</b>异常，
     * 请求根本到不了容器的 {@code /error} 出口。只写 ErrorController 的话，
     * H5 出错时访客看到的仍然是一坨 JSON —— 页面做了却永远不显示。
     * 两者是互补的：这里接控制器抛出的，ErrorController 接过滤器层与容器级的。
     */
    private static Object h5PageOrNull(HttpServletRequest req, HttpStatus status, String view) {
        if (req == null || !isH5Path(req.getRequestURI()) || !prefersHtml(req)) {
            return null;
        }
        org.springframework.web.servlet.ModelAndView mav =
                new org.springframework.web.servlet.ModelAndView(view);
        mav.setStatus(status); // 🛡 不改成 200
        return mav;
    }

    private static boolean isH5Path(String uri) {
        return uri != null && (uri.startsWith("/p/") || uri.startsWith("/m/"));
    }

    /**
     * 客户端是否更想要 HTML。
     * ⚠️ 明确要 JSON 的一律不给 HTML —— 哪怕路径在 {@code /p/} 下（红线①）。
     */
    private static boolean prefersHtml(HttpServletRequest req) {
        String accept = req.getHeader(org.springframework.http.HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank()) {
            return false;
        }
        if (accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return false;
        }
        return accept.contains(MediaType.TEXT_HTML_VALUE) || accept.contains("*/*");
    }

    private ProblemDetail base(HttpStatus status, URI type, String title, String detail, HttpServletRequest req) {
        return base(status, type, title, detail, req, currentTraceId());
    }

    private ProblemDetail base(HttpStatus status, URI type, String title, String detail, HttpServletRequest req,
            String traceId) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(type);
        pd.setTitle(title);
        if (req != null) {
            pd.setInstance(URI.create(req.getRequestURI()));
        }
        pd.setProperty("traceId", traceId);
        return pd;
    }

    private static String currentTraceId() {
        String fromMdc = MDC.get("traceId");
        return fromMdc != null ? fromMdc : UUID.randomUUID().toString();
    }

    private static String titleFor(HttpStatus status) {
        return switch (status) {
            case UNPROCESSABLE_ENTITY, BAD_REQUEST -> "Validation Failed";
            case UNAUTHORIZED -> "Unauthorized";
            case FORBIDDEN -> "Forbidden";
            case NOT_FOUND -> "Not Found";
            case CONFLICT -> "Conflict";
            case TOO_MANY_REQUESTS -> "Rate Limited";
            default -> status.getReasonPhrase();
        };
    }

    /** 校验错误的字段条目（camelCase JSON）。 */
    public record FieldErrorEntry(String field, String message) {
    }
}
