package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.seed.dto.RowError;
import java.util.List;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * 模板里渲染批量内容行错误（bug 20260924-565）：{@code ${@seedErr.lines(r.errorMessage)}} /
 * {@code ${@seedErr.text(e)}}。
 *
 * <p>按<b>当前后台语言</b>（{@code LocaleContextHolder}，与 {@code Messages} 同源）取文案；
 * 🛡 历史中文原文（无 {@code i18n:} 前缀）原样显示。缺键时 MessageSource 回落为键名本身
 * （{@code useCodeAsDefaultMessage}），不抛异常打断页面渲染。
 */
@Component("seedErr")
public class SeedRowErrorRenderer {

    /** 多条合在一格里显示时的分隔（列表列宽有限，不换行）。 */
    static final String JOINER = " · ";

    private final MessageSource messages;

    public SeedRowErrorRenderer(MessageSource messages) {
        this.messages = messages;
    }

    /** 一条错误的本地化文案。 */
    public String text(RowError e) {
        if (e == null) {
            return "";
        }
        if (e.isRaw()) {
            return e.rawText();
        }
        return messages.getMessage(e.key(), e.args().length == 0 ? null : e.args(), e.key(),
                LocaleContextHolder.getLocale());
    }

    /** 存储串 → 逐条本地化文案（工作台 / 抽屉逐条一行展示）。 */
    public List<String> lines(String stored) {
        return SeedRowErrors.decode(stored).stream().map(this::text).toList();
    }

    /** 存储串 → 合成一段（表格单元格 / title 悬浮）；空 ⇒ {@code null}，便于模板 {@code ?:} 兜底。 */
    public String render(String stored) {
        List<String> ls = lines(stored);
        return ls.isEmpty() ? null : String.join(JOINER, ls);
    }
}
