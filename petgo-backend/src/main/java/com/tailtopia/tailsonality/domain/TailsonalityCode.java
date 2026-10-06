package com.tailtopia.tailsonality.domain;

/**
 * 计分结果：四字母 {@code letters}（社交-探索-情绪-驱动）+ 能量后缀 {@code energy}（H / L）。
 */
public record TailsonalityCode(String letters, String energy) {

    /** 完整代号，如 {@code ENTJ-H}。 */
    public String full() {
        return letters + "-" + energy;
    }
}
