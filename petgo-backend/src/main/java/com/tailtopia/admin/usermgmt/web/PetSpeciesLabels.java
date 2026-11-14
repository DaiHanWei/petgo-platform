package com.tailtopia.admin.usermgmt.web;

import com.tailtopia.profile.domain.PetSpecies;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 后台宠物类型显示（V1.5.0 pet-species Story 2.1 · G1 / G2）：emoji、文案 key、代改下拉顺序。
 *
 * <p>emoji 同 App（PRD 3.1，2026-11-13 定）：🐱 🐶 🐰 🐹 🐦 🐔 🐟 🦎 🐢 🐾 🐍 🦆 🐸 🐾；「其他」组物种与 OTHER 均 🐾。
 * 模板经 bean {@code @petSpeciesLabels.emojiOf(p.species())} / {@code keyOf(...)} 取（同 {@code @adminTime}），不在模板里写映射。
 */
@org.springframework.stereotype.Component("petSpeciesLabels")
public class PetSpeciesLabels {

    /** 文案 key 前缀：{@code admin.petSpecies.<SPECIES>}。 */
    public static final String KEY_PREFIX = "admin.petSpecies.";

    private static final String PAW = "🐾";

    private static final Map<PetSpecies, String> EMOJI = new EnumMap<>(PetSpecies.class);

    static {
        EMOJI.put(PetSpecies.CAT, "🐱");
        EMOJI.put(PetSpecies.DOG, "🐶");
        EMOJI.put(PetSpecies.RABBIT, "🐰");
        EMOJI.put(PetSpecies.HAMSTER, "🐹");
        EMOJI.put(PetSpecies.BIRD, "🐦");
        EMOJI.put(PetSpecies.CHICKEN, "🐔");
        EMOJI.put(PetSpecies.FISH, "🐟");
        EMOJI.put(PetSpecies.LIZARD, "🦎");
        EMOJI.put(PetSpecies.TURTLE, "🐢");
        EMOJI.put(PetSpecies.SUGAR_GLIDER, PAW);
        EMOJI.put(PetSpecies.SNAKE, "🐍");
        EMOJI.put(PetSpecies.DUCK, "🦆");
        EMOJI.put(PetSpecies.FROG, "🐸");
        EMOJI.put(PetSpecies.GUINEA_PIG, PAW);
        for (PetSpecies s : PetSpecies.values()) {
            EMOJI.putIfAbsent(s, PAW); // 「其他」组 12 个物种 + OTHER
        }
    }

    /** 模板用：头像 emoji。 */
    public String emojiOf(String species) {
        return emoji(species);
    }

    /** 模板用：类型名文案 key。 */
    public String keyOf(String species) {
        return key(species);
    }

    /** 头像 emoji；null / 不认识 → 🐾。 */
    public static String emoji(String species) {
        return PetSpecies.parse(species).map(EMOJI::get).orElse(PAW);
    }

    /** 文案 key；null / 不认识 → OTHER 的 key。 */
    public static String key(String species) {
        return KEY_PREFIX + PetSpecies.parse(species).orElse(PetSpecies.OTHER).name();
    }

    /** 代改下拉的平铺部分：14 个独立类型 + 「其他」（= OTHER），顺序同 PRD 3.1。 */
    public static List<PetSpecies> pickerTypes() {
        List<PetSpecies> out = new ArrayList<>();
        for (PetSpecies s : PetSpecies.values()) {
            if (s.pickerType() == s && s != PetSpecies.OTHER) {
                out.add(s);
            }
        }
        out.add(PetSpecies.OTHER);
        return out;
    }

    /** 代改下拉「其他」分组下的 12 个物种（附录 B 顺序 = 枚举顺序）。 */
    public static List<PetSpecies> otherGroupMembers() {
        return java.util.Arrays.stream(PetSpecies.values()).filter(PetSpecies::isOtherGroupMember).toList();
    }
}
