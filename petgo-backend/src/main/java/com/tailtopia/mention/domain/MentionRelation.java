package com.tailtopia.mention.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * @ 候选与我的关系（V1.4.2 social-topic Story 5.1 · D-15 · P-5）。
 *
 * <p>🔴 <b>声明顺序 = 优先级</b>：同一个人命中多类时取靠前的那一类
 * （我关注的 &gt; 关注我的 &gt; 互动过的）。{@link #rank()} 与 {@code MentionScopeDao} SQL 里的
 * {@code rk} 一一对应（1 / 2 / 3），改顺序必须同时改 SQL。
 *
 * <p>wire 值小写（P-5），App 5.2 埋点 {@code mention_inserted.relation} 直接取它。
 */
public enum MentionRelation {

    /** 我关注的人（{@code user_follows.follower_id = 我}，不分 USER / SYSTEM 来源）。 */
    FOLLOWING("following"),
    /** 关注我的人（只在带 {@code q} 的查询里进范围）。 */
    FOLLOWER("follower"),
    /** 互动候选（{@code mention_candidates}）。 */
    INTERACTED("interacted");

    private final String wire;

    MentionRelation(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** SQL 里的 {@code rk}：1 起，与声明顺序一致。 */
    public int rank() {
        return ordinal() + 1;
    }

    /** {@code rk} → 关系；越界即 SQL 与枚举不同步，直接炸而不是悄悄归类。 */
    public static MentionRelation ofRank(int rank) {
        MentionRelation[] all = values();
        if (rank < 1 || rank > all.length) {
            throw new IllegalStateException("未知的 @ 候选关系 rk=" + rank);
        }
        return all[rank - 1];
    }
}
