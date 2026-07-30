package com.tailtopia.content.moderation;

/**
 * 三方内容安全调用抽象（内容审核 Story 1，方案 §4）。隔离阿里云 green20220302，便于 stub/live 双模与降级封装。
 *
 * <p>两态实现：{@code StubContentSafetyClient}（{@code mode=stub}，默认，无凭证规则化打分，L0/L1 验状态机）、
 * {@code AliyunContentSafetyClient}（{@code mode=live}，真连阿里云 TextModeration/ImageModeration，L2）。
 *
 * <p>失败语义：任何「无法给出明确结论」（超时 / 4xx / 5xx / 配额）抛 {@link ModerationDegradedException}，
 * 由门面映射为 {@code DEGRADED}（fail-closed，绝不 PASS）。方法<b>无状态、可重入</b>（供 story 4/5 复用）。
 */
public interface ContentSafetyClient {

    /**
     * 文本审核，返回 0–1 风险评分。
     *
     * @param text 待审文本（调用方负责不落日志原文）
     * @return 归一化评分
     * @throws ModerationDegradedException 超时 / 非 2xx / 配额耗尽
     */
    TextScore scanText(String text);

    /**
     * 图像审核，返回分类置信度。
     *
     * @param imageUrl 公开桶图 URL
     * @return 分类 → 置信度
     * @throws ModerationDegradedException 超时 / 非 2xx / 配额耗尽
     */
    ImageScore scanImage(String imageUrl);
}
