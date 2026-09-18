package com.tailtopia.mention.dto;

/**
 * 正文 / 评论里一处 @ 的**渲染投影**（V1.3.0 batch-b1 Story 3.3 · AC1/AC3/AC4）。
 *
 * <h2>🔴 可点与否由服务端判定，客户端只照做</h2>
 * 拉黑关系与注销状态都不该让客户端自己算（story Dev Notes / 同 NFR-2）：
 * 客户端判定只是"看不见"，而且两处判定迟早分叉。
 *
 * <h2>{@code nickname} 为 null 就是「这处 @ 不要高亮」</h2>
 * 不可点时**一律不下发昵称**，只留 id：
 * <ul>
 *   <li>拉黑（AC3）—— 下发当前昵称等于把「对方改名了」这件事告诉一个已拉黑他的人；</li>
 *   <li>注销（AC4）—— 注销后 nickname 本就是 null，匿名化口径不许再给身份。</li>
 * </ul>
 * 正文里那串写死的「@旧昵称」原样留着（它是历史文本，本 story 不改写 body），
 * 客户端把它当普通文字渲染 —— 不高亮、不可点。
 *
 * @param userId   被 @ 的人（存下来的那一份身份，AD-10 Rule 4）
 * @param nickname <b>当前</b>昵称（AC1：对方改名后自动跟着变）；不可点时为 null
 * @param tappable 能不能点进公开主页（AC2）
 */
public record MentionView(long userId, String nickname, boolean tappable) {

    /** 可点：带当前昵称。 */
    public static MentionView tappable(long userId, String nickname) {
        return new MentionView(userId, nickname, true);
    }

    /**
     * 不可点：只留 id，不带昵称（拉黑 AC3 / 注销 AC4）。
     *
     * <p>⚠️ 仍然下发这一条而不是整条省略：客户端据此知道「这里有一处 @，但你点不动」，
     * L0 契约测试也能直接断言 {@code tappable=false}（省略的话只能断言"不存在"，
     * 而"不存在"与"后端忘了算"长得一模一样）。
     */
    public static MentionView blocked(long userId) {
        return new MentionView(userId, null, false);
    }
}
