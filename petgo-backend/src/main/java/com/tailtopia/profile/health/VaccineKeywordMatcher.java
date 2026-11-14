package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 存量 / 旧版自由文本疫苗名 → 预设（V1.5.0 Story 1.2，架构增量 §3.5，PRD FR-168 Free-text vaccines）。纯函数。
 *
 * <p>按物种过滤，大小写不敏感，按「词」切（非字母数字分隔）：整词 {@code tricat|f3} → F3、{@code tetracat|f4} → F4、
 * {@code rabies} → RABIES、{@code felv} → FELV、{@code kennel} → KENNEL_COUGH；词以 {@code lepto} 开头 → LEPTO；
 * 词以 {@code dhpp} 开头 → COMBO_DHPPI_L。有 Combo 时丢弃 LEPTO。剩余恰好 1 个 → 该预设；0 个或 ≥2 个 → OTHER。
 * 只用于匹配，<b>不改存储的原文</b>。
 */
public final class VaccineKeywordMatcher {

    private VaccineKeywordMatcher() {
    }

    /** @return CAT / DOG → 预设或 {@code OTHER}；其他物种 → null（不存预设）。 */
    public static VaccinePreset match(String text, PetType petType) {
        if (petType != PetType.CAT && petType != PetType.DOG) {
            return null;
        }
        Set<VaccinePreset> hits = EnumSet.noneOf(VaccinePreset.class);
        if (text != null) {
            for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                VaccinePreset p = presetOf(word);
                if (p != null && p.appliesTo(petType)) {
                    hits.add(p);
                }
            }
        }
        if (hits.contains(VaccinePreset.COMBO_DHPPI_L)) {
            hits.remove(VaccinePreset.LEPTO);
        }
        return hits.size() == 1 ? hits.iterator().next() : VaccinePreset.OTHER;
    }

    private static VaccinePreset presetOf(String word) {
        if (word.isEmpty()) {
            return null;
        }
        switch (word) {
            case "tricat", "f3":
                return VaccinePreset.F3;
            case "tetracat", "f4":
                return VaccinePreset.F4;
            case "rabies":
                return VaccinePreset.RABIES;
            case "felv":
                return VaccinePreset.FELV;
            case "kennel":
                return VaccinePreset.KENNEL_COUGH;
            default:
                break;
        }
        if (word.startsWith("lepto")) {
            return VaccinePreset.LEPTO;
        }
        if (word.startsWith("dhpp")) {
            return VaccinePreset.COMBO_DHPPI_L;
        }
        return null;
    }
}
