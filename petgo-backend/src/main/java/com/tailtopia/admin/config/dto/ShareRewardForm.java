package com.tailtopia.admin.config.dto;

/**
 * 分享奖励配置表单（V1.1.6 Story 18.3 · AB-3M）。四项分两层：
 *
 * <ul>
 *   <li>全局：{@code shareRewardEnabled}（🔴 <b>总开关</b>）/ {@code shareRewardMonthlyCap}</li>
 *   <li>渠道①身份证：{@code idCardShareReward}（每次几枚）/ {@code idCardShareDailyCap}（每日次数）</li>
 *   <li>渠道②年龄卡：{@code ageCardShareReward} / {@code ageCardShareDailyCap}
 *       （V1.3.0 Story 5.3。⚠️ 本渠道**没有档案级去重**，日上限是它唯一的频次闸门）</li>
 * </ul>
 *
 * <p>🛡 与 {@link PawCoinForm} 分开是为了**权限**（AC5）：总开关存在的意义是
 * 「发现被刷要能立刻关掉」，而把它塞进 {@code config.edit} 就意味着
 * 想关开关的人必须同时握有改兽医单价与分成比例的权限 —— 那道门只有很少人过得去，
 * 于是"立刻"根本做不到。
 *
 * <p>🛡 落库仍是同一张 {@code pawcoin_config} 单行、同一个 PAWCOIN diff 审计组（AC1/AC6：
 * 挂既有配置组，不新建独立后台模块）。
 */
public record ShareRewardForm(boolean shareRewardEnabled, long shareRewardMonthlyCap,
        long idCardShareReward, int idCardShareDailyCap,
        long ageCardShareReward, int ageCardShareDailyCap) {
}
