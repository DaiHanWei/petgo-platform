package com.tailtopia.content.rank;

import java.util.List;

/**
 * 属性穿插排期（V1.1.6 Story 16.4）—— A/B 两个模板 + 窗口大小，打包成引擎的一个入参。
 *
 * <p>🔴 <b>它的存在是为了填 Story 16.2 留下的那处硬接缝</b>：那时 A/B 模板是<b>手写字面量</b>，
 * 而 16.4 要把「属性配比 5/3/2」做成可调 —— 改那三个数<b>不会自动改变模板顺序</b>。
 * 配置改了却不生效是最坏的一类 bug：不报错、不告警、只是节奏不对，且极难被想到去查配置。
 *
 * <p>现在配比与模板<b>由同一处产生</b>（{@link AttributeTemplate#forQuotas}），接缝消失。
 *
 * @param variantA 奇数窗口（0、2、4…）用的序列
 * @param variantB 偶数窗口（1、3、5…）用的序列
 */
public record AttributeSchedule(List<FeedAttribute> variantA, List<FeedAttribute> variantB) {

    public AttributeSchedule {
        if (variantA == null || variantB == null || variantA.isEmpty()
                || variantA.size() != variantB.size()) {
            throw new IllegalArgumentException("两个模板必须非空且等长");
        }
    }

    /** 窗口大小 = 模板长度。 */
    public int window() {
        return variantA.size();
    }

    /** 第 {@code windowIndex} 个窗口（0 起）用哪个模板 —— 偶数 A、奇数 B，交替。 */
    public List<FeedAttribute> forWindow(long windowIndex) {
        return (windowIndex % 2 == 0) ? variantA : variantB;
    }

    /** 全局槽位（0 起）该放什么属性。 */
    public FeedAttribute at(long globalSlot) {
        int w = window();
        return forWindow(globalSlot / w).get((int) (globalSlot % w));
    }
}
