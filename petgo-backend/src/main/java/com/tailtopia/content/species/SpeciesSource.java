package com.tailtopia.content.species;

/**
 * 物种归属是**怎么推出来的**（V1.1.6 Story 14.1 · AC5）。
 *
 * <p>🔴 <b>为什么这个来源必须给运营看见</b>：AC5 举的典型用法就是靠它 ——
 * 把某个号的定位由 {@code GENERAL} 改成 {@code CAT} 之后，
 * 用「推导来源 = 账号定位」+「物种 = 猫」筛出**被批量套上去**的内容，
 * 再把其中实际是狗内容的少数几条改掉。
 *
 * <p>没有来源这一列，运营只能看到一堆"猫"，分不出哪些是他自己逐条标的、
 * 哪些是账号定位顺带套上的。
 */
public enum SpeciesSource {

    /** 运营在内容列表上手工改的（优先级最高）。 */
    ROW_OVERRIDE,

    /** 来自作者（虚拟账号）的账号物种定位。 */
    ACCOUNT_SPECIES,

    /** 来自作者的宠物档案（真实账号走这条）。 */
    PET_PROFILE,

    /** 推不出来。 */
    NONE
}
