package com.tailtopia.shared.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.autoconfigure.error.AbstractErrorController;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * H5 对外页的错误落点（V1.1.6 Story 1.3 · FR-92 §①）。
 *
 * <p><b>解决的问题</b>：分享页出错时访客看到的是 Spring 的默认错误页 —— 一段英文技术信息，
 * 对着它没人知道该干什么。本控制器让 <b>H5 两条链路</b>（{@code /p/**} 宠物名片、
 * {@code /m/**} 里程碑分享）的错误落到一张能看懂、且带「再试一次」的页面上。
 *
 * <h2>⚠️ 为什么不直接放一个 {@code templates/error.html}</h2>
 * 那是最省事、也最错的做法：Spring Boot 会<b>全局</b>采用它，于是<b>运营后台</b>报错时
 * 也会显示一张宠物剪贴簿页。错误页必须按受众分流，故本控制器<b>只认 H5 那两条路径</b>，
 * 其余一律交回默认行为。
 *
 * <h2>⚠️ 为什么不在两个页面控制器里 try-catch</h2>
 * 那接不住<b>控制器之外</b>的异常 —— 模板渲染失败、过滤器抛错、无路由 404 ——
 * 而「服务器出错」最常见的恰恰是这几种。{@code /error} 是容器的统一出口，接得住。
 *
 * <h2>三条红线（各有测试守着）</h2>
 * <ol>
 *   <li><b>API 的错误响应不受影响</b>：{@code Accept} 不含 HTML（或含 {@code application/json}）
 *       一律回 JSON。注：受控异常本就先被 {@link GlobalExceptionHandler} 的
 *       {@code @ExceptionHandler} 接走、<b>根本到不了这里</b>；本控制器只兜它没覆盖的那部分。</li>
 *   <li><b>运营后台不受影响</b>：{@code /admin/**} 不在分流名单里。</li>
 *   <li><b>状态码不变</b>：渲染了页面不等于请求成功，5xx 仍回 5xx、404 仍回 404。
 *       改成 200 会让监控与爬虫都误判。</li>
 * </ol>
 */
@Controller
public class H5ErrorController extends AbstractErrorController {

    /** 只有这两条对外 H5 链路走剪贴簿错误页；其余保持默认。 */
    private static final String[] H5_PREFIXES = {"/p/", "/m/"};

    public H5ErrorController(ErrorAttributes errorAttributes) {
        super(errorAttributes);
    }

    /**
     * HTML 请求：H5 两条链路 → 剪贴簿错误页；其余 → 交回默认（返回 {@code null} 让
     * Spring 继续走它自己的那套）。
     */
    @RequestMapping(value = "${server.error.path:${error.path:/error}}", produces = MediaType.TEXT_HTML_VALUE)
    public ModelAndView errorHtml(HttpServletRequest request) {
        HttpStatus status = getStatus(request);
        if (!isH5Path(originalPath(request))) {
            return null; // 非 H5 → 保持既有行为，不接管
        }
        ModelAndView mav = new ModelAndView("card_error");
        // 🛡 状态码原样透传：渲染了页面不代表请求成功。
        mav.setStatus(status);
        return mav;
    }

    /**
     * 非 HTML 请求（API、抓取器等）：一律回 JSON，形态与既有 ProblemDetail 路径保持一致。
     * 🛡 这条是红线 —— API 出错绝不能收到一张 HTML 页。
     */
    @RequestMapping(value = "${server.error.path:${error.path:/error}}")
    public ResponseEntity<java.util.Map<String, Object>> error(HttpServletRequest request) {
        HttpStatus status = getStatus(request);
        if (status == HttpStatus.NO_CONTENT) {
            return new ResponseEntity<>(status);
        }
        return new ResponseEntity<>(
                getErrorAttributes(request, ErrorAttributeOptions.defaults()), status);
    }

    /**
     * 取<b>原始</b>请求路径。
     *
     * <p>⚠️ 此刻 {@code request.getRequestURI()} 已经是 {@code /error} 了 —— 容器做过一次内部转发。
     * 原始路径在 {@link RequestDispatcher#FORWARD_REQUEST_URI} 属性里。取错了会导致本控制器
     * 对谁都不生效（永远匹配不到 {@code /p/}），且这种错<b>不会报错、只会静默失效</b>。
     */
    private static String originalPath(HttpServletRequest request) {
        Object forwarded = request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        if (forwarded instanceof String s && !s.isBlank()) {
            return s;
        }
        Object errorUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (errorUri instanceof String s && !s.isBlank()) {
            return s;
        }
        return request.getRequestURI();
    }

    private static boolean isH5Path(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : H5_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
