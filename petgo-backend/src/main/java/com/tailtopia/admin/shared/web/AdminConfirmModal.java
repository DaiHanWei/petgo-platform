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
 * @param facts         「标签：值」事实行（V1.4.0 ①）
 * @param amounts       金额明细块（V1.4.2 shop-admin Story 1.2 · AD-3：标签左、金额右、灰字附注、合计行）；空 = 不渲染
 * @param choices       单选组（AD-3）；null = 不渲染
 * @param select        下拉（AD-3）；null = 不渲染
 * @param successTarget 成功（2xx 且响应未自带 HX-Retarget）时把响应换进哪个选择器 —— 用来替换原生 {@code data-confirm}：
 *                      原表单的 hx-target 搬到这里，错误（422 / 403）仍落弹层内错误槽；null = 现状（只靠 admin:confirm-close）
 * @param successSwap   成功时的 swap 方式（原表单的 hx-swap；null = innerHTML）
 * @param nativePost    true = 弹层表单按原生 POST 整页提交（原表单本就是非 htmx 的 POST + 重定向）
 */
public record AdminConfirmModal(String title, String danger, List<DiffLine> diffs, String note,
        String typedPromptHtml, String typedName, String typedExpected,
        String actionPath, Map<String, String> hidden, String confirmLabel, boolean dangerButton,
        boolean warnBanner, List<Fact> facts, List<AmountLine> amounts, ChoiceGroup choices, SelectField select,
        String successTarget, String successSwap, boolean nativePost) {

    /** 13 参（V1.4.0 ① facts）。V1.4.2 新增的可选块一律不渲染，形态与之前逐字一致。 */
    public AdminConfirmModal(String title, String danger, List<DiffLine> diffs, String note,
            String typedPromptHtml, String typedName, String typedExpected,
            String actionPath, Map<String, String> hidden, String confirmLabel, boolean dangerButton,
            boolean warnBanner, List<Fact> facts) {
        this(title, danger, diffs, note, typedPromptHtml, typedName, typedExpected, actionPath, hidden,
                confirmLabel, dangerButton, warnBanner, facts, List.of(), null, null, null, null, false);
    }

    /** 12 参（V1.3.4 黄色警示条），无 facts。 */
    public AdminConfirmModal(String title, String danger, List<DiffLine> diffs, String note,
            String typedPromptHtml, String typedName, String typedExpected,
            String actionPath, Map<String, String> hidden, String confirmLabel, boolean dangerButton,
            boolean warnBanner) {
        this(title, danger, diffs, note, typedPromptHtml, typedName, typedExpected, actionPath, hidden,
                confirmLabel, dangerButton, warnBanner, List.of());
    }

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

    /**
     * 一行「标签：值」（V1.4.0 ① Story 4.1，如 G6「升级 N 人」）；渲染在 diffs 上方。4.2 / 4.3 复用。
     */
    public record Fact(String label, String value) {
    }

    /**
     * 金额明细一行（AD-3 / UI 稿 D3、E3）：标签左、金额右（金额已由调用方格式化，如 {@code Rp 120.000}），
     * {@code note} 为金额下的灰字附注（可空），{@code total} = 合计行（上边线 + 加粗）。
     */
    public record AmountLine(String label, String amount, String note, boolean total) {
        public static AmountLine of(String label, String amount) {
            return new AmountLine(label, amount, null, false);
        }

        public static AmountLine total(String label, String amount) {
            return new AmountLine(label, amount, null, true);
        }
    }

    /** 单选项；{@code hint} 为选项下灰字（可空）。 */
    public record Choice(String value, String label, String hint, boolean checked) {
    }

    /** 单选组（以 {@code name} 提交；{@code label} 为组标题，可空）。 */
    public record ChoiceGroup(String name, String label, List<Choice> options) {
        public ChoiceGroup {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** 下拉项。 */
    public record Option(String value, String label, boolean selected) {
    }

    /** 下拉（以 {@code name} 提交；{@code required} 时首项为空占位且必选）。 */
    public record SelectField(String name, String label, List<Option> options, boolean required) {
        public SelectField {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public AdminConfirmModal {
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
        facts = facts == null ? List.of() : List.copyOf(facts);
        amounts = amounts == null ? List.of() : List.copyOf(amounts);
        hidden = hidden == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(hidden));
        if (typedPromptHtml != null && (typedName == null || typedExpected == null)) {
            throw new IllegalArgumentException("输入确认须同时给出 typedName 与 typedExpected");
        }
    }

    /** 是否为输入值确认（G4 / I8）。 */
    public boolean typed() {
        return typedPromptHtml != null;
    }

    /** 换掉金额明细块（其余不变）。 */
    public AdminConfirmModal withAmounts(List<AmountLine> lines) {
        return new AdminConfirmModal(title, danger, diffs, note, typedPromptHtml, typedName, typedExpected, actionPath,
                hidden, confirmLabel, dangerButton, warnBanner, facts, lines, choices, select, successTarget,
                successSwap, nativePost);
    }

    /** 加单选组。 */
    public AdminConfirmModal withChoices(ChoiceGroup group) {
        return new AdminConfirmModal(title, danger, diffs, note, typedPromptHtml, typedName, typedExpected, actionPath,
                hidden, confirmLabel, dangerButton, warnBanner, facts, amounts, group, select, successTarget,
                successSwap, nativePost);
    }

    /** 加下拉。 */
    public AdminConfirmModal withSelect(SelectField field) {
        return new AdminConfirmModal(title, danger, diffs, note, typedPromptHtml, typedName, typedExpected, actionPath,
                hidden, confirmLabel, dangerButton, warnBanner, facts, amounts, choices, field, successTarget,
                successSwap, nativePost);
    }

    /** 成功后把响应换进原表单的目标（替换 {@code data-confirm} 用；错误仍落弹层内）。 */
    public AdminConfirmModal withSuccessTarget(String target, String swap) {
        return new AdminConfirmModal(title, danger, diffs, note, typedPromptHtml, typedName, typedExpected, actionPath,
                hidden, confirmLabel, dangerButton, warnBanner, facts, amounts, choices, select, target, swap,
                false);
    }

    /** 弹层表单改为原生整页 POST（原表单是非 htmx 的 POST + 重定向）。 */
    public AdminConfirmModal asNativePost() {
        return new AdminConfirmModal(title, danger, diffs, note, typedPromptHtml, typedName, typedExpected, actionPath,
                hidden, confirmLabel, dangerButton, warnBanner, facts, amounts, choices, select, null, null, true);
    }

    /** 简单确认（替换原生 {@code data-confirm}）：标题 + 说明 + 确认钮，带原表单字段。 */
    public static AdminConfirmModal simple(String title, String note, String actionPath, Map<String, String> hidden,
            String confirmLabel, boolean dangerButton) {
        return new AdminConfirmModal(title, null, List.of(), note, null, null, null, actionPath, hidden, confirmLabel,
                dangerButton, false, List.of());
    }

    /** 弹层表单的 swap 方式（模板用）。 */
    public String formSwap() {
        return successSwap == null || successSwap.isBlank() ? "innerHTML" : successSwap;
    }
}
