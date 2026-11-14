package com.tailtopia.notify.lifecycle;

/**
 * 生命周期推送用户分层（V1.5.0 onboarding-recall Story 6.1 · OR-22）：发送当时按账号判定，自上而下取第一个。
 */
public enum LifecycleLayer {
    /** 没宠物：无宠物行且 {@code petStatus != HAS_PET}（含为空）。 */
    A,
    /** 待建档：无宠物行但注册时选了「已养」（{@code HAS_PET}）。 */
    B,
    /** 待发帖：有宠物、{@code publishedCount == 0}。 */
    C,
    /** 待互动：发过帖、新手 ③④⑤（{@code FIRST_FOLLOW / FIRST_LIKE_GIVEN / FIRST_COMMENT}）都没有。 */
    D,
    /** 已互动：发过帖、③④⑤ 至少一个。 */
    E
}
