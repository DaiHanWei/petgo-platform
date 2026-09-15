package com.tailtopia.place.domain;

/**
 * 场所评论自带的二元态度（V1.3.0 batch-b1 Story 1.7 · AC3 · B1-D3）。
 *
 * <h2>🔴 只有两个值，"没表态"是 null 而不是第三个枚举值</h2>
 * 多一个 {@code NEUTRAL} 会让计数多出一档谁也不看的数字，而界面上只有两个 chip
 * （👍 Rekomen / 👎 Tidak），不选就是不选。
 *
 * <h2>🔴 这是**评论自带的**态度，不是对场所的独立投票</h2>
 * 不写评论就不能表态（B1-D3，UI 稿 A4 的修订理由）。所以它是 {@link PlaceComment}
 * 的一个可空列，**不是**一张独立的投票表 —— 独立表等于允许"只投票不评论"，那个形态已被否。
 */
public enum PlaceCommentAttitude {
    RECOMMEND,
    NOT_RECOMMEND
}
