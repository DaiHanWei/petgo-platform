package com.tailtopia.shared.i18n;

import com.tailtopia.shared.error.AppException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * 按<b>当前请求 locale</b> 取文案。管理后台的动态文案（操作提示、报错）经此本地化——
 * 模板里的静态文案走 Thymeleaf 的 {@code #{...}}，不经这里。
 *
 * <p>locale 来自 {@code LocaleContextHolder}，由 DispatcherServlet 依
 * {@link AdminLocaleConfig#localeResolver()} 在每个请求开始时填入。因此：
 * <ul>
 *   <li>后台请求 → 用户在顶栏选的语言（zh_CN / en / id）；</li>
 *   <li>App 的 api 请求（无后台 locale cookie）→ 回落默认语言 zh_CN，
 *       与外化之前的固定中文<b>逐字相同</b>，不产生行为变化。</li>
 *   <li>{@code @Async} / 定时任务等无请求上下文处 → 同样回落 zh_CN。</li>
 * </ul>
 */
@Component
public class Messages {

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * 取本地化文案。缺键时返回 code 本身（{@code useCodeAsDefaultMessage}，见
     * {@link AdminLocaleConfig#messageSource()}）——绝不抛异常打断业务流程；
     * 漏键由 L0 键集对齐测试拦截，不靠运行时炸出来。
     */
    public String get(String code, Object... args) {
        return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    /**
     * 解析业务异常的展示文案：带 {@link AppException#getMessageCode() 文案码} 的按当前语言取，
     * 否则退回异常自带的原文。
     *
     * <p>这个「有码用码、无码用原文」的双轨是刻意的：后端有 500 多处 {@code AppException}，
     * 其中绝大多数走 api 链面向 App（App 有自己的 .arb 本地化）。逐个改造它们既无必要也有回归风险，
     * 所以只给<b>会出现在后台界面上</b>的那些挂码，其余原样不动。
     */
    public String resolve(AppException e) {
        return e.getMessageCode() == null ? e.getMessage() : get(e.getMessageCode(), e.getMessageArgs());
    }
}
