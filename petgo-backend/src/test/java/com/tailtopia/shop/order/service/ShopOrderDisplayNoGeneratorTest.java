package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：电商订单展示号生成器（Story 4-3 AC6）。
 *
 * <p>🎯 <b>变异靶子</b>：把 {@code SecureRandom} 换成递增序号（也就是退回旧算法），
 * {@link #everyPositionSpansTheAlphabet()} 与 {@link #consecutiveNumbersAreNotAdjacent()}
 * 必须变红。旧算法的序号段是自增主键零填充，任何用户拿自己的号就能推断平台当日单量、
 * 还能顺着序号试探别人的单。
 *
 * <p>⚠️ <b>教训</b>：最初这里只有「1 万次全不同」与「字符都在字母表内」两条，
 * 而它们<b>都抓不住</b>这次变异 —— 递增计数器同样次次不同，{@code %06d} 的数字
 * 也全在 Crockford 字母表里。被替换掉的性质是<b>不可预测</b>，不是唯一。
 * 上面两条方法是为此补的。
 */
class ShopOrderDisplayNoGeneratorTest {

    /** 🔴 权威正则：Crockford 去掉了 I / L / O / U，所以是 {@code A-HJKMNP-TV-Z} 而不是 {@code A-Z}。 */
    private static final String PATTERN = "^TOKO-\\d{8}-[0-9A-HJKMNP-TV-Z]{6}$";

    private final ShopOrderDisplayNoGenerator gen = new ShopOrderDisplayNoGenerator();

    private static final Instant NOON_UTC = Instant.parse("2026-09-16T05:00:00Z");

    // ---------- 格式与字母表 ----------

    @Test
    @DisplayName("号形如 TOKO-yyyyMMdd-XXXXXX，随机段恒为 6 位")
    void shapeMatchesTheContract() {
        String no = gen.generate(NOON_UTC);

        assertThat(no).matches(PATTERN);
        assertThat(no.substring(no.lastIndexOf('-') + 1)).hasSize(6);
    }

    @Test
    @DisplayName("🎯 随机段只用 Crockford 字母表")
    void randomSegmentUsesOnlyCrockfordAlphabet() {
        for (int i = 0; i < 2_000; i++) {
            assertThat(gen.generate(NOON_UTC))
                    .as("🎯 换成 id 拼接后这里会出现字母表外的字符（或根本没有随机段）")
                    .matches(PATTERN);
        }
    }

    @Test
    @DisplayName("🔴 号里绝不出现 I / L / O / U —— 用户要把它逐位念给客服")
    void neverContainsAmbiguousLetters() {
        // I 与 1、O 与 0 念出来分不清，认错一位就是查错单；U 是为了避免拼出脏词。
        for (int i = 0; i < 5_000; i++) {
            String segment = tail(gen.generate(NOON_UTC));
            assertThat(segment)
                    .as("易混字符会让「报单号给客服」这件事变成猜谜")
                    .doesNotContain("I")
                    .doesNotContain("L")
                    .doesNotContain("O")
                    .doesNotContain("U");
        }
    }

    @Test
    @DisplayName("🎯🎯 每一位都取遍字母表 —— 这条才分得出「随机」与「递增计数器」")
    void everyPositionSpansTheAlphabet() {
        // ⚠️ 这条是补上来的：光断言「1 万次全不同」**抓不住**退回旧算法这件事 ——
        //    一个递增计数器同样次次不同，且 %06d 的数字也全在 Crockford 字母表里。
        //    真正被替换掉的性质是**不可预测**，不是唯一。
        //    而顺序号的特征极其明显：高位几乎恒为 '0'。
        List<Set<Character>> perPosition = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            perPosition.add(new HashSet<>());
        }
        for (int i = 0; i < 5_000; i++) {
            String segment = tail(gen.generate(NOON_UTC));
            for (int pos = 0; pos < 6; pos++) {
                perPosition.get(pos).add(segment.charAt(pos));
            }
        }

        for (int pos = 0; pos < 6; pos++) {
            assertThat(perPosition.get(pos))
                    .as("🎯 第 %d 位只取到 %s —— 递增序号的高位恒为 '0'，这正是可枚举的样子",
                            pos, perPosition.get(pos))
                    .hasSizeGreaterThanOrEqualTo(30);
        }
    }

    @Test
    @DisplayName("🔴 相邻两次生成不是连号 —— 能从自己的号推出下一个，就等于可枚举")
    void consecutiveNumbersAreNotAdjacent() {
        // 用户拿自己的号推断平台单量、顺着序号试探别人的单，靠的就是「相邻可推」。
        int adjacent = 0;
        String prev = tail(gen.generate(NOON_UTC));
        for (int i = 0; i < 1_000; i++) {
            String cur = tail(gen.generate(NOON_UTC));
            if (differsOnlyInLastCharByOne(prev, cur)) {
                adjacent++;
            }
            prev = cur;
        }
        assertThat(adjacent).as("随机序列里这种情况应当罕见；顺序号则次次如此").isLessThan(50);
    }

    /** 两个段是否「只差最后一位、且差 1」——顺序号的典型特征。 */
    private static boolean differsOnlyInLastCharByOne(String a, String b) {
        if (!a.substring(0, 5).equals(b.substring(0, 5))) {
            return false;
        }
        return Math.abs(a.charAt(5) - b.charAt(5)) == 1;
    }

    @Test
    @DisplayName("1 万次生成的碰撞数落在生日悖论的容忍区间内（≤ 5）")
    void collisionCountStaysWithinTheBirthdayBound() {
        // ⚠️ 这条原来断言的是「1 万次**全不相同**」，那是一条 4.55% 概率红的用例：
        //    号空间 N = 32^6 = 1_073_741_824，抽 n = 10_000 次，
        //    期望碰撞数 λ = n(n-1)/(2N) = 99_990_000 / 2_147_483_648 ≈ 0.0466，
        //    至少撞一次的概率 = 1 − e^(−λ) ≈ 4.55% —— 约每 22 次 CI 就红一次，
        //    而红的时候生成器完全正常。**这是在用随机性惩罚随机性。**
        //
        //    也不能用固定种子把它做成确定性：被测性质就是「不可预测」，
        //    钉死种子等于把这条性质本身架空（退回递增序号照样能过）。
        //
        //    改成给碰撞数一个有统计意义的上界：碰撞数服从 Poisson(λ≈0.0466)，
        //    P(X > 5) ≈ λ⁶/6! · e^(−λ) ≈ 1.4e-11 —— 稳定到可以忽略。
        //    同时它仍然抓得住真正的故障：随机源退化（比如只剩几十种输出）
        //    会让碰撞数从 0 级直接跳到数千级，远远越过这个上界。
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(gen.generate(NOON_UTC));
        }

        int collisions = 10_000 - seen.size();
        assertThat(collisions)
                .as("期望碰撞 ≈ 0.047 次；撞到 %d 次说明随机源退化，不是运气差", collisions)
                .isLessThanOrEqualTo(5);
    }

    // ---------- 日期段：WIB，不是 UTC ----------

    @Test
    @DisplayName("🔴 日期段取 WIB 日期：UTC 16:00 之后已是雅加达的次日")
    void dateSegmentFollowsJakartaNotUtc() {
        // WIB = UTC+7。2026-09-16T17:00Z 在雅加达已经是 2026-09-17 00:00。
        // 用 UTC 日期会让「深夜下的单」号上写着前一天 —— 客服按日期对账时对不上。
        assertThat(gen.generate(Instant.parse("2026-09-16T17:00:00Z")))
                .startsWith("TOKO-20260917-");
        assertThat(gen.generate(Instant.parse("2026-09-16T16:59:59Z")))
                .startsWith("TOKO-20260916-");
    }

    @Test
    @DisplayName("前缀取 OrderDisplayNo.ECOMMERCE，不是本类里重写的字面量")
    void prefixComesFromTheSingleSourceOfTruth() {
        assertThat(gen.generate(NOON_UTC))
                .startsWith(com.tailtopia.order.dto.OrderDisplayNo.ECOMMERCE + "-");
    }

    // ---------- 冲突重试 ----------

    @Test
    @DisplayName("前 4 次判定已存在 → 第 5 次成功返回")
    void retriesUntilAFreeNumberIsFound() {
        AtomicInteger calls = new AtomicInteger();

        String no = gen.generateUnique(NOON_UTC, candidate -> calls.incrementAndGet() <= 4);

        assertThat(no).matches(PATTERN);
        assertThat(calls.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("第一次就没冲突 → 只查一次库")
    void happyPathQueriesOnce() {
        AtomicInteger calls = new AtomicInteger();

        gen.generateUnique(NOON_UTC, candidate -> {
            calls.incrementAndGet();
            return false;
        });

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 连续 5 次冲突 → 抛明确异常，不死循环")
    void givesUpAfterFiveCollisions() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> gen.generateUnique(NOON_UTC, c -> {
            calls.incrementAndGet();
            return true;   // 永远冲突
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5");

        assertThat(calls.get())
                .as("死循环比一次失败的下单难查得多")
                .isEqualTo(ShopOrderDisplayNoGenerator.MAX_TRIES);
    }

    @Test
    @DisplayName("🔒 异常信息里不出现候选号（它会进日志）")
    void exceptionNeverLeaksACandidate() {
        assertThatThrownBy(() -> gen.generateUnique(NOON_UTC, c -> true))
                .hasMessageNotContaining("TOKO-");
    }

    private static String tail(String displayNo) {
        return displayNo.substring(displayNo.lastIndexOf('-') + 1);
    }
}
