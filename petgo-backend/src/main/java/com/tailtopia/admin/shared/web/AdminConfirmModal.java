package com.tailtopia.admin.shared.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端渲染的通用确认弹层（V1.3.4 后台返工清单 §2；模板 {@code admin/fragments/confirm-modal :: modal}）。
 *
 * <p>两种形态：
 * <ul>
 *   <li><b>普通确认</b>（UI 稿 G3）：标题 + 逐字段「旧值 → 新值」+ 提醒语 +「取消 / 确认」；</li>
 *   <li><b>输入值确认</b>（UI 稿 G4，AB-31 I8 复用）：再加红色警示条 + 输入框，输入值与 {@link #typedExpected}
 *       完全一致前确认钮禁用（admin-core.js {@code [data-typed-confirm]}）。输入框以 {@link #typedName} 提交，
 *       <b>服务端必须自己再比一次</b>——前端禁用只是体验。</li>
 * </ul>
 *
 * <p>弹层里的表单 {@code hx-post} 到 {@link #actionPath}，带上 {@link #hidden} 里的全部字段；{@code hx-target} 指向
 * 弹层内部错误槽 {@code #confirm-modal-err}（422 / 403 时弹层不消失、看得见原因）。成功路径由端点发
 * {@code admin:confirm-close}（{@link AdminHxEvents#CONFIRM_CLOSE}）收起弹层，并自行 HX-Retarget 到要刷新的区块。
 * 宿主为页面上的 {@code <div id="confirm-host">}，与运营发布身份页同一套。
 *
 * @param title         标题
 * @param danger        红色警示条（null = 不显示）
 * @param diffs         逐字段改动（可空列表）
 * @param note          改动下方的提醒语（null = 不显示）
 * @param typedPromptHtml 输入确认的提示语，<b>已转义的 HTML</b>（期望值加粗；由调用方用 {@code HtmlUtils.htmlEscape} 组装）；null = 普通确认
 * @param typedName     输入框的参数名（输入确认时必填）
 * @param typedExpected 输入框须完全一致的值（输入确认时必填）
 * @param actionPath    确认后提交的端点（上下文相对路径，模板里走 @{...}）
 * @param hidden        随确认一起提交的字段（保持顺序）
 * @param confirmLabel  确认钮文案
 * @param dangerButton  确认钮用红色（G4 / I10）还是品牌色（G3 / I8）
 * @param warnBanner    警示条用黄色（UI 稿 I8「这是一次大范围发送」）而非红色（G4）
 */
public record AdminConfirmModal(String title, String danger, List<DiffLine> diffs, String note,
        String typedPromptHtml, String typedName, String typedExpected,
        String actionPath, Map<String, String> hidden, String confirmLabel, boolean dangerButton,
        boolean warnBanner) {

    /** 红色警示条（G3 / G4 / H8–H10 的既有形态）。 */
    public AdminConfirmModal(String title, String danger, List<DiffLine> diffs, String note,
            String typedPromptHtml, String typedName, String typedExpected,
            String actionPath, Map<String, String> hidden, String confirmLabel, boolean dangerButton) {
        this(title, danger, diffs, note, typedPromptHtml, typedName, typedExpected, actionPath, hidden,
                confirmLabel, dangerButton, false);
    }

    /** 一行「标签：旧值 → 新值」；值为空串时页面显示「—」。 */
    public record DiffLine(String label, String oldValue, String newValue) {
    }

    public AdminConfirmModal {
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
        hidden = hidden == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(hidden));
        if (typedPromptHtml != null && (typedName == null || typedExpected == null)) {
            throw new IllegalArgumentException("输入确认须同时给出 typedName 与 typedExpected");
        }
    }

    /** 是否为输入值确认（G4 / I8）。 */
    public boolean typed() {
        return typedPromptHtml != null;
    }
}
