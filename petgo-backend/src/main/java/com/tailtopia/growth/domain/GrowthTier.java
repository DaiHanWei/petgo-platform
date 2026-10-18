package com.tailtopia.growth.domain;

/**
 * Tailrent Level 段位（V1.4.0 ① FR-143，Story 0.1 地基定义）。
 *
 * <p>本枚举只给段位起名；<b>段位与等级边界的权威来源是 ① 的等级配置表</b>（等级可向上追加），不要在业务代码里按数字硬编码边界。
 * 上线初始边界：Lv1–4 / Lv5–9 / Lv10–14 / Lv15+。
 */
public enum GrowthTier {
    /** 灰 · Newbie / Pemula。 */
    NEWBIE,
    /** 绿 · Buddy / Sahabat。 */
    BUDDY,
    /** 蓝 · Guardian / Penjaga。 */
    GUARDIAN,
    /** 紫金 · Star / Bintang。 */
    STAR
}
