package com.tailtopia.content.species;

/**
 * 推导结果（V1.1.6 Story 14.1）。
 *
 * @param species 物种值；{@code null} = 推不出来
 * @param source  从哪推出来的（AC5 要在界面上显示并可筛选）
 */
public record ResolvedSpecies(String species, SpeciesSource source) {

    public static final ResolvedSpecies NONE = new ResolvedSpecies(null, SpeciesSource.NONE);

    public boolean known() {
        return species != null;
    }
}
