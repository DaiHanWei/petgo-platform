package com.tailtopia.content.rank;

import com.tailtopia.content.species.ContentSpecies;
import java.util.Set;

/**
 * 物种配比的三个桶（V1.1.6 Story 16.2 · AC2 / AC3）—— <b>相对于查看者的主物种</b>。
 *
 * <p>V1.4.0 multi-pet Story 3.2（AD-11）：查看者名下<b>所有</b>宠物的物种都算主物种（MAIN），
 * MAIN 配额再按宠物数比例拆给各物种（{@link SpeciesSlotAllocator}）；OTHER / GENERAL 不变。
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
        return ofOwned(species, mainSpecies == null ? Set.of() : Set.of(mainSpecies));
    }

    /**
     * 判桶（多宠，V1.4.0 multi-pet Story 3.2）。⚠️ 不与 {@link #of(String, String)} 同名重载：
     * 既有调用 {@code of(x, null)} 的 {@code null} 实参会二义。
     *
     * <p>内容物种 null / GENERAL → GENERAL；
     * {@code owned} 为空 → GENERAL（物种维度不生效）；∈ owned → MAIN；否则 OTHER。
     *
     * @param species 内容的物种归属；{@code null} = 推不出来 → GENERAL
     * @param owned   查看者名下宠物的物种集合；空 = 拿不到物种信号（无档案 / 游客）
     */
    public static SpeciesBucket ofOwned(String species, Set<String> owned) {
        String s = (species == null) ? ContentSpecies.GENERAL : species;
        if (ContentSpecies.GENERAL.equals(s)) {
            return GENERAL;
        }
        // 🔴 查看者无主物种时物种维度不生效（AC2）—— 这里返回 GENERAL 只是让判桶有个确定答案；
        // 引擎会在 owned 为空时整条跳过物种配额，不读这个返回值。
        if (owned == null || owned.isEmpty()) {
            return GENERAL;
        }
        return owned.contains(s) ? MAIN : OTHER;
    }
}
