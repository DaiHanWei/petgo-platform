package com.tailtopia.content.rank;

import com.tailtopia.content.species.ContentSpecies;

/**
 * 物种配比的三个桶（V1.1.6 Story 16.2 · AC2 / AC3）—— <b>相对于查看者的主物种</b>。
 *
 * <p>同一条内容对养猫的人是 {@code MAIN}、对养狗的人是 {@code OTHER}，
 * 所以这个桶<b>不能预先算好存起来</b>，必须在排序时按查看者算。
 */
public enum SpeciesBucket {

    /** 与查看者主物种一致。窗口内配额最高（6/10）。 */
    MAIN,

    /** 其他具体物种（养猫的人看到的狗内容）。配额 2/10 —— 让首页不至于只有一种动物。 */
    OTHER,

    /** 通用（不限物种的养宠知识）。配额 2/10。 */
    GENERAL;

    /**
     * 判桶。
     *
     * <p>🔴 <b>物种「推不出来」（{@code null}）归入 {@link #GENERAL}</b> —— 这是 2026-08-24 产品拍板的口径。
     *
     * <p>背景：Story 14.1 已交付的 {@code ContentSpeciesResolver} 对无信号的内容返回<b>空</b>，
     * 而补充 PRD 的优先级链写的是「无档案 → GENERAL」。两个消费方要的不是一回事：
     * <ul>
     *   <li><b>后台自查列</b>要「推不出来」—— 否则运营分不出「真的配了通用」与「压根没信号」，
     *       而那一列存在的目的就是让他自查还有多少没配</li>
     *   <li><b>算法</b>要 {@code GENERAL} —— 它是一个<b>有 2/10 配额的桶</b>，
     *       空值会让这些内容不属于任何桶、于是永远排不进去</li>
     * </ul>
     * ⚠️ 所以<b>不改 14.1 的 resolver</b>，只在这里（引擎消费时）映射。
     *
     * @param species     内容的物种归属；{@code null} = 推不出来 → GENERAL
     * @param mainSpecies 查看者主物种；{@code null} = 拿不到物种信号（无档案 / 游客）
     */
    public static SpeciesBucket of(String species, String mainSpecies) {
        String s = (species == null) ? ContentSpecies.GENERAL : species;
        if (ContentSpecies.GENERAL.equals(s)) {
            return GENERAL;
        }
        // 🔴 查看者无主物种时物种维度不生效（AC2）—— 这里返回 GENERAL 只是让判桶有个确定答案；
        // 引擎会在 mainSpecies == null 时整条跳过物种配额，不读这个返回值。
        if (mainSpecies == null) {
            return GENERAL;
        }
        return s.equals(mainSpecies) ? MAIN : OTHER;
    }
}
