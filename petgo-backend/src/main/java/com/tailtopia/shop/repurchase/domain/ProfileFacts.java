package com.tailtopia.shop.repurchase.domain;

import com.tailtopia.shop.domain.AgeStage;
import com.tailtopia.shop.domain.BodySize;
import com.tailtopia.shop.domain.Species;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;

/**
 * 从宠物档案推出的推荐输入（Story 6.2，FR-107）。
 *
 * <p>🔴 <b>规则式，不引入个性化算法</b>：SKU 上限 30，规则式已足够；
 * 引入算法要为冷启动、可解释性与调参长期负责，而这个规模下它带不来相应的收益。
 *
 * <p>🔴 <b>单账号单宠物是硬约束</b>（L-11，{@code ProfileService} 以 {@code existsByOwnerId} → 409）。
 * 故这里直接用该用户的唯一宠物，<b>不做宠物选择器</b>。
 * ⚠️ <b>1.3.0 多宠物后启用</b>选择器时，本类需要接收一个 petProfileId 而不是 ownerId。
 */
public record ProfileFacts(Species species, AgeStage ageStage, BodySize bodySize,
        BigDecimal weightKg, String petName) {

    /** 幼年 / 成年 / 老年的年龄阈值（岁）。🔴 犬猫阈值可后台配置 —— 这里是默认值。 */
    public static final int DEFAULT_PUPPY_MAX_YEARS = 1;
    public static final int DEFAULT_ADULT_MAX_YEARS = 7;

    /** 体型阈值（kg）。犬猫共用一套：小型 <10、中型 10–25、大型 >25。 */
    public static final int SMALL_MAX_KG = 10;
    public static final int MEDIUM_MAX_KG = 25;

    /** 年龄未知 → {@link AgeStage} 为 null（不是 UNIVERSAL）。两者含义不同，见 {@link #isComplete}。 */
    public static AgeStage ageStageOf(LocalDate birthday, LocalDate today, int puppyMax,
            int adultMax) {
        if (birthday == null || today == null) {
            return null;
        }
        int years = Period.between(birthday, today).getYears();
        if (years < puppyMax) {
            return AgeStage.PUPPY;
        }
        return years <= adultMax ? AgeStage.ADULT : AgeStage.SENIOR;
    }

    /** 体重未知 → 体型为 null。 */
    public static BodySize bodySizeOf(BigDecimal weightKg) {
        if (weightKg == null) {
            return null;
        }
        int kg = weightKg.intValue();
        if (kg < SMALL_MAX_KG) {
            return BodySize.SMALL;
        }
        return kg <= MEDIUM_MAX_KG ? BodySize.MEDIUM : BodySize.LARGE;
    }

    /** 🔴 档案完整 = 年龄与体重都有。缺任一即降级为按物种推荐。 */
    public boolean isComplete() {
        return ageStage != null && bodySize != null;
    }

    /** 缺了什么 —— 引导卡据此决定提示补哪一项。 */
    public String missing() {
        if (ageStage == null && bodySize == null) {
            return "BOTH";
        }
        if (bodySize == null) {
            return "WEIGHT";
        }
        return ageStage == null ? "AGE" : "NONE";
    }
}
