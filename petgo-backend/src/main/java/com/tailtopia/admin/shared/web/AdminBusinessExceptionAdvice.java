package com.tailtopia.admin.shared.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.GlobalExceptionHandler;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;

/**
 * htmx 请求的 4xx fragment 出口（V1.3.0 Story 2.3a，AD-9）。<b>只在 {@code HX-Request} 头存在时接管</b>：
 * <ul>
 *   <li>{@link AppException}（422/400/404/409）→ 原状态（非 4xx 归 422）+ {@code tpl-shared :: inline-error}，
 *       响应头 {@code HX-Reswap: innerHTML} + {@code HX-Retarget}（优先 {@code HX-Target}，其次 {@code #admin-inline-error}）；</li>
 *   <li>表单校验（{@link BindException} / MethodArgumentNotValid）→ 422 inline-error，文案取第一个 field error；</li>
 *   <li>{@link AccessDeniedException}（{@code @PreAuthorize} 拒绝）→ 403 {@code tpl-shared :: forbidden}，文案
 *       「需要「{permission}」权限」（D-37：固定显示所缺权限名），权限名从 HandlerMethod 的 {@code @PreAuthorize} 表达式
 *       里取第一个 {@code hasAuthority('x')} 的三语显示名（{@code perm.x}），拿不到则通用文案。</li>
 * </ul>
 * 非 htmx 请求<b>维持现状</b>：AppException / 校验异常委托 {@link GlobalExceptionHandler}（RFC 9457 ProblemDetail），
 * AccessDenied 原样重抛交回 Security 链 forward {@code /admin/denied}。
 * 只覆盖 {@code com.tailtopia.admin} 包；{@code /api/v1} 永远不进。
 *
 * <p>htmx 请求下各页 Controller 的分支<b>不再 try/catch</b>，直接让 AppException 冒出来；整页 PRG 分支照旧。
 * 浏览器端 422/403 能 swap 依赖 Story 2.2 在 admin-core.js 加的 {@code htmx:beforeSwap} 放行。
 */
// namemoderation.web：名称审核的处置端点被统一复核工作台以 htmx 调用（Story 2.4），须同样出 422/403 fragment。
@ControllerAdvice(basePackages = {"com.tailtopia.admin", "com.tailtopia.namemoderation.web"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminBusinessExceptionAdvice {

    static final String VIEW_INLINE_ERROR = "admin/fragments/tpl-shared :: inline-error";
    static final String VIEW_FORBIDDEN = "admin/fragments/tpl-shared :: forbidden";
    static final String DEFAULT_TARGET = "#admin-inline-error";
    private static final Pattern HAS_AUTHORITY = Pattern.compile("hasAuthority\\('([A-Za-z0-9_.]+)'\\)");

    private final Messages messages;
    private final GlobalExceptionHandler fallback;

    public AdminBusinessExceptionAdvice(Messages messages, GlobalExceptionHandler fallback) {
        this.messages = messages;
        this.fallback = fallback;
    }

    @ExceptionHandler(AppException.class)
    public Object handleApp(AppException ex, HttpServletRequest req, HttpServletResponse resp) {
        HxRequest hx = HxRequest.of(req);
        if (!hx.isHtmx()) {
            return fallback.handleApp(ex, req);
        }
        HttpStatus status = ex.getStatus() != null && ex.getStatus().is4xxClientError()
                ? ex.getStatus() : HttpStatus.UNPROCESSABLE_ENTITY;
        return inlineError(status, messages.resolve(ex), ex.getMessageCode(), hx, resp);
    }

    @ExceptionHandler(BindException.class)
    public Object handleBind(BindException ex, HttpServletRequest req, HttpServletResponse resp) {
        HxRequest hx = HxRequest.of(req);
        if (!hx.isHtmx()) {
            if (ex instanceof org.springframework.web.bind.MethodArgumentNotValidException manv) {
                return fallback.handleValidation(manv, req);
            }
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "请求参数校验未通过");
            return ResponseEntity.unprocessableEntity().body(pd);
        }
        FieldError fe = ex.getBindingResult().getFieldError();
        String message = fe == null ? "请求参数校验未通过"
                : (fe.getField() + ": " + (fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()));
        return inlineError(HttpStatus.UNPROCESSABLE_ENTITY, message, null, hx, resp);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ModelAndView handleAccessDenied(AccessDeniedException ex, HttpServletRequest req,
            HttpServletResponse resp, HandlerMethod handler) {
        HxRequest hx = HxRequest.of(req);
        if (!hx.isHtmx()) {
            throw ex; // 非 htmx：交回 Security 链 forward /admin/denied（与 GlobalExceptionHandler 现状一致）
        }
        String code = missingPermission(handler);
        String permission = code == null ? null : messages.get("perm." + code);
        ModelAndView mav = new ModelAndView(VIEW_FORBIDDEN, HttpStatus.FORBIDDEN);
        mav.addObject("permission", permission);
        mav.addObject("permissionCode", code);
        mav.addObject("message", permission == null
                ? messages.get("admin.v130.err.forbiddenGeneric")
                : messages.get("admin.v130.err.forbidden", permission));
        resp.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
        resp.setHeader(AdminFragmentResponses.HEADER_RETARGET, hx.target() != null ? "#" + stripHash(hx.target()) : DEFAULT_TARGET);
        return mav;
    }

    private ModelAndView inlineError(HttpStatus status, String message, String code, HxRequest hx,
            HttpServletResponse resp) {
        ModelAndView mav = new ModelAndView(VIEW_INLINE_ERROR, status);
        mav.addObject("message", message);
        mav.addObject("code", code);
        resp.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
        resp.setHeader(AdminFragmentResponses.HEADER_RETARGET, hx.target() != null ? "#" + stripHash(hx.target()) : DEFAULT_TARGET);
        return mav;
    }

    private static String stripHash(String target) {
        return target.startsWith("#") ? target.substring(1) : target;
    }

    /** 尽力而为：方法级、其次类级 {@code @PreAuthorize} 里第一个 {@code hasAuthority('x')} 的 x。 */
    static String missingPermission(HandlerMethod handler) {
        if (handler == null) {
            return null;
        }
        PreAuthorize pre = handler.getMethodAnnotation(PreAuthorize.class);
        if (pre == null) {
            pre = handler.getBeanType().getAnnotation(PreAuthorize.class);
        }
        if (pre == null) {
            return null;
        }
        Matcher m = HAS_AUTHORITY.matcher(pre.value());
        return m.find() ? m.group(1) : null;
    }
}
