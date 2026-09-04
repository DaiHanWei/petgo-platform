package com.tailtopia.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

    /** 与 {@code spring.servlet.multipart.max-file-size} 同源——文案里的数字不另写死，避免两处对不上。 */
    private final DataSize maxFileSize;

    public GlobalExceptionHandler(com.tailtopia.shared.i18n.Messages messages,
            @Value("${spring.servlet.multipart.max-file-size:1MB}") DataSize maxFileSize) {
        this.messages = messages;
        this.maxFileSize = maxFileSize;
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

    /**
     * 乐观锁撞车（{@code shop_orders.version} 等）：两个事务同时改同一行，输家整体回滚。
     * 这是「被别人抢先了」，不是服务端故障 —— 给 409 让客户端/网关重试，而不是 500。
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(
            org.springframework.dao.OptimisticLockingFailureException ex, HttpServletRequest req) {
        return handleApp(AppException.conflict("操作与另一笔更新冲突，请重试"), req);
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
     * 上传超出 multipart 上限 → 413，并给出**具体 MB 数**。
     *
     * <p>🔴 这一段是在 controller <b>之前</b>发生的：Tomcat 解析 multipart 时就抛，
     * 所以 {@code AdminSeedImageService} 里那道 10MB 校验根本轮不到执行。
     * 不单独接住它，就会掉进最后的 catch-all → 500「服务暂时不可用」，
     * 运营看到的是一句与体积毫无关系的话，只会反复重传同一张图。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleUploadTooLarge(MaxUploadSizeExceededException ex,
            HttpServletRequest req) {
        return uploadTooLarge(req);
    }

    private ResponseEntity<ProblemDetail> uploadTooLarge(HttpServletRequest req) {
        ProblemDetail pd = base(HttpStatus.PAYLOAD_TOO_LARGE, ErrorTypes.VALIDATION,
                "Payload Too Large",
                messages.get("admin.seed.upload.tooLarge", maxFileSize.toMegabytes()), req);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(pd);
    }

    /**
     * 这次失败到底是不是「上传超限」——<b>按异常链判，不按类型判</b>。
     *
     * <p>🔴 2026-09-03 stag 回归实测：10.2MB 图 POST 到 {@code /admin/shop/banners/images}
     * 回的是 <b>500</b>「服务暂时不可用」，而不是上面那个 413。日志里是本类
     * {@code handleUnexpected} 打的「Unhandled exception」+
     * {@code org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException}。
     *
     * <p>成因：把 Tomcat 的原始异常包成 Spring 的 {@link MaxUploadSizeExceededException} 的，
     * 只有 {@code StandardServletMultipartResolver.resolveMultipart}（DispatcherServlet 内部）。
     * 而 {@code /admin/**} 这条链<b>带 CSRF 过滤器</b>，它会提前读请求参数、就地触发 multipart 解析
     * —— 那一刻 DispatcherServlet 还没接手，抛出来的是 Tomcat 自己的
     * {@code FileSizeLimitExceededException}，它继承 {@code IOException}，
     * <b>与 Spring 的 multipart 异常体系毫无血缘</b>，于是上面那个 {@code @ExceptionHandler} 接不到，
     * 一路掉进 catch-all。运营看到的是一句与体积毫无关系的「服务暂时不可用」，只会反复重传同一张图。
     *
     * <p>⚠️ 为什么按<b>类名</b>判而不是 import Tomcat 的类：那是容器内部实现
     * （{@code org.apache.tomcat.util.http.fileupload.impl.*}），换 Jetty/Undertow 或 Tomcat
     * 挪包就编不过或静默失效。类名后缀 + 异常链遍历对三种容器都成立，且不给本模块添依赖。
     */
    private static boolean isUploadTooLarge(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.web.multipart.MultipartException) {
                return true;
            }
            String name = t.getClass().getSimpleName();
            // Tomcat: FileSizeLimitExceededException（单文件超限）/ SizeLimitExceededException（整请求超限）
            if (name.endsWith("SizeLimitExceededException")) {
                return true;
            }
            if (t.getCause() == t) {
                break; // 自环，防死循环
            }
        }
        return false;
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
        // 🔴 先认「上传超限」：它可能以 Tomcat 的原始异常形态到这里，与 5xx 不是一回事。
        //    见 isUploadTooLarge 的说明 —— 漏了这一步，运营拿到的是 500 通用文案。
        if (isUploadTooLarge(ex)) {
            return uploadTooLarge(req);
        }
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
