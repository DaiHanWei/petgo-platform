package com.tailtopia.shop.repurchase.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 补货提醒卡（Story 6.4 区域①，FR-109）。
 *
 * <p>🔴 <b>文案给「估算依据」而非断言</b>：下发 {@code daysLeft} 让前端渲染成
 * 「预计 ~N 天后吃完」，<b>不写成确定事实</b> —— 档案体重不准或用户混喂时会有偏差，
 * 把估算说成事实会直接损伤信任。
 *
 * <p>⚠️ <b>本版本区域① 只有 FR-109 一个来源</b>（FR-108 已挪 1.2.0，C-11）。
 * 原型 `01-Toko首页-有复购触发.html` 画了两张卡（驱虫 + 粮量），🔴 <b>驱虫那张必须删掉</b>（UX-DR1）。
 */
public record RepurchaseCardView(
        long triggerId,
        String triggerType,
        String skuToken,
        String productToken,
        String productName,
        String petName,
        LocalDate estimatedDepletionDate,
        /** 距耗尽还有几天（可能为负 = 已过预估耗尽日）。前端据此渲染「~N 天」。 */
        long daysLeft,

        // ---- 推算依据（V1.4.0 · 设计文档 03 屏 1）----
        // 🔴 设计稿把「日均用量 · 剩余量 · 购买日期」列为**缺一不可**：
        //    它是用户信任这条推荐的唯一凭据，也是它区别于普通广告位的地方。
        //    三者**同时为 null 或同时有值**；任一算不出来就整组给 null，
        //    前端据此整卡不渲染 —— 与其给一条没有依据的推荐，不如不给。
        //    ⚠️ 只追加到 record 末尾，不动既有字段顺序（并行契约）。

        /** 日均用量（克/天）。 */
        Integer dailyGrams,
        /** 估算剩余量（克）。已吃超时为 0，**不给负数**。 */
        Integer remainingGrams,
        /** 购买（送达）日期。 */
        LocalDate purchasedOn) {

    /** 🔴 区域① 最多 2 张（FR-93）。⚠️ 超过 2 张时的排序规则 SPEC-16 未拍板。 */
    public static final int MAX_CARDS = 2;

    public static List<RepurchaseCardView> capped(List<RepurchaseCardView> all) {
        return all.size() <= MAX_CARDS ? all : all.subList(0, MAX_CARDS);
    }
}
