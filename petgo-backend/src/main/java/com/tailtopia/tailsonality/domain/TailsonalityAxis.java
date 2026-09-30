package com.tailtopia.tailsonality.domain;

/**
 * 计分五轴（内容设计 §6.1 / §6.7）。每轴显式声明正分 / 负分字母。
 *
 * <p>🔴 <b>情绪轴 {@link #TF} 是唯一反向轴：正分是后写的 F，负分是 T。</b>
 * 禁止用「轴名第一个字母当正极」推导（{@code name().charAt(0)}）—— 那会让所有敏感型宠物被判成淡定型，
 * 16 个角色里 8 个整片对调，而且测试跑得通、不报错（内容设计 §6.7 红框）。
 */
public enum TailsonalityAxis {
    /** 社交取向：正 E 外向 / 负 I 内向。 */
    EI('E', 'I'),
    /** 探索倾向：正 N 好奇探索 / 负 S 务实守成。 */
    NS('N', 'S'),
    /** 情绪反应：<b>正 F</b> 敏感 / <b>负 T</b> 淡定（反向轴）。 */
    TF('F', 'T'),
    /** 驱动力：正 J 目标坚持 / 负 P 随性灵活。 */
    JP('J', 'P'),
    /** 能量水平（后缀）：正 H / 负 L。 */
    ENERGY('H', 'L');

    private final char positiveLetter;
    private final char negativeLetter;

    TailsonalityAxis(char positiveLetter, char negativeLetter) {
        this.positiveLetter = positiveLetter;
        this.negativeLetter = negativeLetter;
    }

    public char positiveLetter() {
        return positiveLetter;
    }

    public char negativeLetter() {
        return negativeLetter;
    }
}
