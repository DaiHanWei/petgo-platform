package com.tailtopia.shared.ai;

/**
 * 看图写评论（自动评论定时任务用，2026-09-29）。与分诊的 {@link GeminiClient} 分开：两者提示词、输出结构、
 * 失败语义都不同，塞进同一个接口只会让分诊那边多出一个无关方法。共用同一份 {@link GeminiProperties}（key / 模型）。
 *
 * <p>实现两态：{@code GeminiPetCommentGenerator}（live）与 {@code StubPetCommentGenerator}（stub）。
 * 调用方必须先看 {@link #live()}：stub 的产出是固定文案，<b>绝不能发到真实用户的帖子下</b>。
 */
public interface PetCommentGenerator {

    /** 当前用的提示词版本。改提示词时递增，留档里据此对比效果。 */
    String PROMPT_VERSION = "v2";

    /**
     * 读帖子首图 + 文字，生成一句评论，或判定不宜评论。
     *
     * @param imageUrl 首图 URL（公开桶，Gemini 服务端按 URL 抓取）
     * @param postText 帖子文字（可空；只作参考数据，不是指令）
     * @param species  宠物物种（CAT / DOG / OTHER / GENERAL，可空）
     * @param petName  宠物名（可空）
     * @throws GeminiException 调用失败或响应不可解析（可重试）
     */
    PetCommentResult generate(String imageUrl, String postText, String species, String petName);

    /** 是否真实 AI。stub 返回 false，定时任务据此整轮跳过。 */
    boolean live();

    /** 模型名，写进留档。 */
    String model();
}
