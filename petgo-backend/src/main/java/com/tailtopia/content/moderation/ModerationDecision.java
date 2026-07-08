package com.tailtopia.content.moderation;

/**
 * 运营处置决策字段（内容审核补充规范 §5.2，跨 story 契约 CM8）。承载「判定依据（违规类别）+ 备注」，
 * 由运营控制台处置表单采集，供人工审核拒绝 / 名称重置 / 头像重置在<b>同事务</b>写入 append-only 审计
 * （{@code AdminAuditService.record} 的 summary）。
 *
 * <p>放中性包 {@code content.moderation}（与 {@link ModerationOutcome} 并列）：admin 侧人工审核、
 * name/avatar 侧处置服务均已依赖 {@code content.*}，此处不引入新的跨包耦合方向。
 *
 * <p>护栏（§5.5）：{@code category} 为<b>受控枚举值</b>（违法/仇恨/色情/政治敏感/广告引流/骚扰/其他，取值校验在
 * 表单/控制器层）；{@code note} 为运营备注。二者均为审计元数据，<b>绝不复制被审内容原文明文 / 名称原文 / 图片 URL</b>——
 * 审核证据留受控业务库，审计仅引用。
 *
 * @param category 违规类别 / 判定依据（可空 → 归「未分类」）
 * @param note     运营备注（可空）
 */
public record ModerationDecision(String category, String note) {

    /** 空决策（无依据无备注）——占位入口 / 无表单场景。 */
    public static ModerationDecision none() {
        return new ModerationDecision(null, null);
    }

    /** 归一后的类别（空 → 「未分类」），用于审计 summary 与记录列。 */
    public String categoryOrDefault() {
        return category == null || category.isBlank() ? "未分类" : category.trim();
    }

    /**
     * 审计 summary 片段：「依据=<category>；备注=<note>」（备注为空则省略）。
     * <b>仅描述性判定依据，不含被审原文</b>（§5.5）。
     */
    public String auditFragment() {
        String base = "依据=" + categoryOrDefault();
        if (note != null && !note.isBlank()) {
            return base + "；备注=" + note.trim();
        }
        return base;
    }
}
