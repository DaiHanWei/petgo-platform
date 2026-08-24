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
 * <p>✅ <b>16.2 留的那处硬接缝已在 16.4 补上</b>：当时这两个模板是手写字面量，改配比不会改顺序。
 * 现在 {@link #forQuotas} 由配比生成排期（配比恰为 5/3/2 且窗口为 10 时原样返回下面这两张手写表，
 * 保证 16.2 AC1 钉死的默认行为一个字不变），{@link #rejectUnusableQuotas} 把不可用的配比
 * 挡在保存之前。
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

    /** 模板的属性构成（FUN 5 / EDU 3 / LIFE 2）。 */
    public static Map<FeedAttribute, Long> quotas() {
        return A.stream().collect(Collectors.groupingBy(a -> a, Collectors.counting()));
    }

    /** 手写的默认排期（5/3/2、窗口 10）。 */
    public static AttributeSchedule defaultSchedule() {
        return new AttributeSchedule(A, B);
    }

    /**
     * 按配比生成排期（V1.1.6 Story 16.4）。
     *
     * <p>🔴 <b>配比是 5/3/2 且窗口是 10 时，原样返回手写的 A/B</b> ——
     * Story 16.2 的 AC1 把那两张表逐槽位钉死了，默认行为一个字都不能变。
     * 生成器只在<b>运营真的改了配比</b>时上场。
     *
     * <p>生成规则：每一槽取「剩余配额最多、且与上一槽不同」的属性；平手时按变体各自的偏好序打破
     * （A 偏 EDU、B 偏 LIFE）—— 于是 5/3/2 下生成的 B 恰好等于手写的 B。
     *
     * <p>⚠️ 极端配比下相邻不可避免（比如 10 槽里 9 个 FUN），所以
     * {@link #rejectUnusableQuotas} 会先把那类配比挡在保存之前。
     */
    public static AttributeSchedule forQuotas(int fun, int edu, int life, int window) {
        if (fun == 5 && edu == 3 && life == 2 && window == WINDOW) {
            return defaultSchedule();
        }
        return new AttributeSchedule(
                generate(fun, edu, life, window, List.of(FUN, EDU, LIFE)),
                generate(fun, edu, life, window, List.of(FUN, LIFE, EDU)));
    }

    private static List<FeedAttribute> generate(int fun, int edu, int life, int window,
            List<FeedAttribute> preference) {
        Map<FeedAttribute, Integer> left = new java.util.EnumMap<>(FeedAttribute.class);
        left.put(FUN, fun);
        left.put(EDU, edu);
        left.put(LIFE, life);
        List<FeedAttribute> out = new java.util.ArrayList<>(window);
        FeedAttribute prev = null;
        for (int i = 0; i < window; i++) {
            FeedAttribute pick = pick(left, prev, preference);
            if (pick == null) {
                // 只剩上一槽那个属性了 —— 只能相邻（防扎堆规则会兜住，级别 1 补位也会）。
                pick = pick(left, null, preference);
            }
            if (pick == null) {
                break; // 配额用尽（之和 < window，业务层已校验，这里兜底不崩）
            }
            out.add(pick);
            left.merge(pick, -1, Integer::sum);
            prev = pick;
        }
        return List.copyOf(out);
    }

    private static FeedAttribute pick(Map<FeedAttribute, Integer> left, FeedAttribute exclude,
            List<FeedAttribute> preference) {
        FeedAttribute best = null;
        int bestLeft = 0;
        for (FeedAttribute a : preference) {
            int n = left.getOrDefault(a, 0);
            if (n <= 0 || a == exclude) {
                continue;
            }
            if (n > bestLeft) { // 严格大于 ⇒ 平手时保留 preference 里先出现的那个
                best = a;
                bestLeft = n;
            }
        }
        return best;
    }

    /**
     * 🛡 配比是否可用（供保存前校验）。
     *
     * <p>两条：
     * <ul>
     *   <li>三项之和 <b>必须等于</b>窗口大小 —— 不等就是窗口凑不满或溢出，
     *       而那<b>不会报错</b>，只会让首页节奏莫名其妙</li>
     *   <li>单项 <b>不得超过</b> {@code ceil(window/2)} —— 超过就必然出现同属性相邻，
     *       穿插这件事本身失去意义（5/10 正好在边界上，可行）</li>
     * </ul>
     *
     * @return 不可用的原因；{@code null} = 可用
     */
    public static String rejectUnusableQuotas(int fun, int edu, int life, int window) {
        if (window < 2) {
            return "窗口大小须 ≥ 2";
        }
        if (fun < 0 || edu < 0 || life < 0) {
            return "配比不可为负";
        }
        if (fun + edu + life != window) {
            return "属性配比之和（" + (fun + edu + life) + "）须等于窗口大小（" + window + "）";
        }
        int cap = (window + 1) / 2;
        int max = Math.max(fun, Math.max(edu, life));
        if (max > cap) {
            return "单一属性配额（" + max + "）不得超过窗口的一半（" + cap + "）—— 否则必然出现同属性相邻";
        }
        return null;
    }
}
