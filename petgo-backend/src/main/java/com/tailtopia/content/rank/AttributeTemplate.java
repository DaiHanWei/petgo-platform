package com.tailtopia.content.rank;

import static com.tailtopia.content.rank.FeedAttribute.EDU;
import static com.tailtopia.content.rank.FeedAttribute.FUN;
import static com.tailtopia.content.rank.FeedAttribute.LIFE;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 属性穿插模板（V1.1.6 Story 16.2 · AC1）—— 每 10 条 FUN 5 / EDU 3 / LIFE 2，A/B 双模板交替。
 *
 * <pre>
 *   槽位    1    2     3    4     5    6    7    8     9    10
 *   A     FUN  EDU   FUN  LIFE  FUN  EDU  FUN  LIFE  FUN  EDU
 *   B     FUN  LIFE  FUN  EDU   FUN  EDU  FUN  LIFE  FUN  EDU
 * </pre>
 *
 * <p>🛡 <b>只有 A / B 两个模板，没有第三个。</b>
 * 早期曾为「计划养宠」用户准备过第三套（FUN 6 / EDU 4），<b>已整条废除</b> ——
 * 其前提（该状态用户不显示 LIFE）已被废止，且产品明确<b>不做任何用户类型区分</b>。
 * 保留它只会让后人以为还有第二套要实现。
 *
 * <p>🛡 跨窗口边界不得产生同属性相邻：A 末 EDU → B 首 FUN；B 末 EDU → A 首 FUN。
 * 两个模板都以 FUN 开头、EDU 结尾，所以边界天然安全 —— 但这是<b>字面量的巧合，不是不变量</b>，
 * 所以有一条测试专门钉它（改动模板时会立刻变红）。
 *
 * <p>🔴 <b>留给 16.4 的一处硬接缝</b>：这两个模板是<b>手写的字面量</b>。
 * 16.4 要把「属性配比 5/3/2」做成可调，但改那三个数<b>不会自动改变模板顺序</b> ——
 * 配置改了却不生效是最坏的一类 bug（不报错、不告警、只是节奏不对）。
 * 所以 16.4 必须二选一：要么同时给出「由配比生成模板」的规则，要么在配置校验里
 * 拒绝与模板不一致的配比（本类的 {@link #quotas()} 就是给那道校验用的）。
 */
public final class AttributeTemplate {

    /** 奇数窗口（0、2、4…）用的模板。 */
    public static final List<FeedAttribute> A =
            List.of(FUN, EDU, FUN, LIFE, FUN, EDU, FUN, LIFE, FUN, EDU);

    /** 偶数窗口（1、3、5…）用的模板。 */
    public static final List<FeedAttribute> B =
            List.of(FUN, LIFE, FUN, EDU, FUN, EDU, FUN, LIFE, FUN, EDU);

    /** 模板长度 = 窗口大小。 */
    public static final int WINDOW = 10;

    private AttributeTemplate() {
    }

    /** 第 {@code windowIndex} 个窗口（0 起）用哪个模板 —— 偶数 A、奇数 B，交替。 */
    public static List<FeedAttribute> forWindow(long windowIndex) {
        return (windowIndex % 2 == 0) ? A : B;
    }

    /** 全局槽位（0 起）该放什么属性。 */
    public static FeedAttribute at(long globalSlot) {
        return forWindow(globalSlot / WINDOW).get((int) (globalSlot % WINDOW));
    }

    /** 模板的属性构成（供 16.4 的配置校验比对，见类注释那条接缝）。 */
    public static Map<FeedAttribute, Long> quotas() {
        return A.stream().collect(Collectors.groupingBy(a -> a, Collectors.counting()));
    }
}
