package com.tailtopia.profile.domain;

/**
 * 里程碑「自动完成」的触发事件枚举（V1.3.0 批次 A · Story 1.1 · AD-A4）。
 *
 * <p>这一层存在的唯一理由：把「发生了什么」与「点亮哪一条」彻底分开。改造前两者是同一个字符串
 * （语义后缀 {@code "M3"}），于是「录疫苗」这件事被迫假设三张清单同号位含义相同 —— 而通用清单
 * 只有 16 项、猫狗各 31 项，同号位大面积错位，一次造出五处线上错误（两处错点亮 + 三处静默失效）。
 *
 * <p>事件枚举不带任何编号语义，映射表 {@link MilestoneAutoCompleteMap} 按物种逐条显式列举完整 code，
 * 缺节点就是缺（映射到 {@code null}），不再靠「查不到就 no-op」把错位掩盖过去。
 */
public enum MilestoneAutoEvent {

    /** 宠物档案创建完成。 */
    PROFILE_CREATED,
    /** 第一张照片进成长日历（成长日历记录数 ≥ 1）。 */
    GROWTH_MOMENT_FIRST,
    /** 成长日历记录数满 10 条。 */
    GROWTH_MOMENT_10,
    /** 成长日历记录数满 30 条。 */
    GROWTH_MOMENT_30,
    /** 第一次分享宠物名片。 */
    CARD_SHARED,
    /** 第一次保存兽医问诊结论（存档）。 */
    CONSULT_ARCHIVED,
    /** 第一次发布对外可见的平台帖子。 */
    PLATFORM_POST,
    /** 真人兽医咨询结束（AI 分诊不发此事件，模块隔离天然满足）。 */
    CONSULT_CLOSED,
    /** 内容第一次被他人评论。 */
    FIRST_COMMENT,
    /** 内容第一次收到点赞。 */
    FIRST_LIKE,
    /** 录入 {@code VACCINE} 类型健康记录。 */
    HEALTH_RECORD_VACCINE,
    /** 录入 {@code DEWORM} 类型健康记录。 */
    HEALTH_RECORD_DEWORM,
    /** 录入 {@code NEUTER} 类型健康记录。 */
    HEALTH_RECORD_NEUTER,
    /** 陪伴满 30 天（每日定时扫描）。 */
    COMPANION_30_DAYS,
    /** 第一个生日当天发布成长日历记录。 */
    FIRST_BIRTHDAY,
    /** 陪伴满 100 天后发布成长日历记录。 */
    COMPANION_100_DAYS,
    /** 陪伴满 365 天后发布成长日历记录。 */
    COMPANION_365_DAYS,
}
