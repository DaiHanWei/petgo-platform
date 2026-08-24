package com.tailtopia.content.species;

import com.tailtopia.profile.domain.PetType;
import java.util.List;

/**
 * 内容物种归属（V1.1.6 Story 14.1 · AB-3H · AC1）。
 *
 * <p>四值：{@code CAT} / {@code DOG} / {@code OTHER} / {@code GENERAL}。
 * 前三个与 {@link PetType} 对齐，{@code GENERAL} 是补的「通用」——
 * 不限物种的养宠知识（大量 Tips/科普内容都属于这一类）。
 *
 * <p>🛡 <b>纯算法输入，App 端不展示物种标签</b>（AC6）——
 * 本 story 不新增任何内容标签的用户可见展示。
 */
public final class ContentSpecies {

    public static final String CAT = "CAT";
    public static final String DOG = "DOG";
    public static final String OTHER = "OTHER";

    /** 通用（不限物种的养宠知识）。也是存量虚拟账号的读时默认值。 */
    public static final String GENERAL = "GENERAL";

    /** 四个合法取值。⚠️ 顺序即界面下拉顺序：GENERAL 放最前，它是最常用的那个。 */
    public static final List<String> ALL = List.of(GENERAL, CAT, DOG, OTHER);

    private ContentSpecies() {
    }

    public static boolean isValid(String value) {
        return value != null && ALL.contains(value);
    }

    /** 宠物档案的类型 → 物种值。 */
    public static String fromPetType(PetType petType) {
        return petType == null ? null : petType.name();
    }
}
