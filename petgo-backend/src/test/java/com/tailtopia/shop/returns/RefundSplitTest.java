package com.tailtopia.shop.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.returns.domain.RefundSplit;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：🔴🔴 按比例退款的整数累计法（Story 5.2，AD-2，<b>安全攸关</b>）。
 *
 * <p><b>少退是客诉，多退是缺口。</b>少退几盾用户会投诉；多退则是 FR-100A 规则 1（防套现）
 * 的缺口 —— 真钱段被多退意味着用户拿回的真钱超过了他投入的真钱。
 *
 * <p>AD-2 明列必须覆盖的三条，本类逐条对应：
 * ① 全额一次退 → Coin 段恰好等于 coinAmount；
 * ② 拆成 3 次不等额部分退至全额 → 累计 Coin 段恰好等于 coinAmount（零漂移）；
 * ③ coinAmount=1 / amount=300000 的极端比例下不出现负数或超退。
 */
class RefundSplitTest {

    // ---------- ① 全额一次退 ----------

    @Test
    @DisplayName("① 全额一次退 → Coin 段恰好等于 coinAmount")
    void fullRefundInOneGoReturnsExactCoinAmount() {
        RefundSplit s = RefundSplit.accumulate(305_000L, 60_000L, 0L, 0L, 305_000L);
        assertThat(s.thisCoin()).isEqualTo(60_000L);
        assertThat(s.thisCash()).isEqualTo(245_000L);
        assertThat(s.total()).isEqualTo(305_000L);
    }

    @Test
    @DisplayName("① 纯 PawCoin 单：全额退回全是 Coin，现金段为 0")
    void pureCoinOrder() {
        RefundSplit s = RefundSplit.accumulate(100_000L, 100_000L, 0L, 0L, 100_000L);
        assertThat(s.thisCoin()).isEqualTo(100_000L);
        assertThat(s.thisCash()).isZero();
    }

    @Test
    @DisplayName("① 纯 QRIS 单：全额退回全是现金，Coin 段为 0")
    void pureCashOrder() {
        RefundSplit s = RefundSplit.accumulate(285_000L, 0L, 0L, 0L, 285_000L);
        assertThat(s.thisCoin()).isZero();
        assertThat(s.thisCash()).isEqualTo(285_000L);
    }

    // ---------- ② 零漂移（AD-2 的核心） ----------

    @Test
    @DisplayName("🔴 ② 拆成 3 次【不等额】部分退至全额 → 累计 Coin 段恰好等于 coinAmount，零漂移")
    void threeUnequalPartialRefundsDriftToZero() {
        final long amount = 305_000L;
        final long coinAmount = 60_000L;
        // 刻意选除不尽的三段：单次取整的实现会在这里累出误差
        final long[] parts = {100_001L, 99_999L, 105_000L};

        long refundedTotal = 0;
        long refundedCoin = 0;
        for (long part : parts) {
            RefundSplit s = RefundSplit.accumulate(amount, coinAmount, refundedTotal,
                    refundedCoin, part);
            // 恒等式：两段之和 == 本次退款额
            assertThat(s.total()).as("两段之和必须等于本次退款额").isEqualTo(part);
            refundedTotal += part;
            refundedCoin += s.thisCoin();
        }

        assertThat(refundedTotal).isEqualTo(amount);
        assertThat(refundedCoin)
                .as("🔴 全额退完时累计 Coin 段必须恰好等于 coinAmount —— 少退是客诉，多退是资金缺口")
                .isEqualTo(coinAmount);
    }

    @Test
    @DisplayName("🔴 ② 单次取整会漂移，累计法不会 —— 用同一组数据把两种算法摆在一起对比")
    void perRefundRoundingWouldDriftButAccumulationDoesNot() {
        final long amount = 305_000L;
        final long coinAmount = 60_000L;
        final long[] parts = {100_001L, 99_999L, 105_000L};

        // 【错误实现】每次各自按比例取整
        long naiveCoinSum = 0;
        for (long part : parts) {
            naiveCoinSum += part * coinAmount / amount;
        }

        // 【正确实现】累计法
        long refundedTotal = 0;
        long accCoinSum = 0;
        for (long part : parts) {
            RefundSplit s =
                    RefundSplit.accumulate(amount, coinAmount, refundedTotal, accCoinSum, part);
            refundedTotal += part;
            accCoinSum += s.thisCoin();
        }

        assertThat(accCoinSum).isEqualTo(coinAmount);
        // 这一条是本 story 存在的全部理由：错误实现在这组数据上就是对不上。
        assertThat(naiveCoinSum)
                .as("若这一条不再成立，说明测试数据被改成了整除的，零漂移断言随之失去意义")
                .isNotEqualTo(coinAmount);
    }

    @Test
    @DisplayName("🔴 ② 逐件退（10 次每次 1/10）同样零漂移")
    void tenEqualPartialRefundsDriftToZero() {
        final long amount = 333_333L;
        final long coinAmount = 77_777L;
        long refundedTotal = 0;
        long refundedCoin = 0;
        for (int i = 0; i < 10; i++) {
            // 最后一次补齐余数，模拟真实的「退完剩下的」
            long part = (i == 9) ? amount - refundedTotal : amount / 10;
            RefundSplit s = RefundSplit.accumulate(amount, coinAmount, refundedTotal,
                    refundedCoin, part);
            assertThat(s.total()).isEqualTo(part);
            refundedTotal += part;
            refundedCoin += s.thisCoin();
        }
        assertThat(refundedTotal).isEqualTo(amount);
        assertThat(refundedCoin).isEqualTo(coinAmount);
    }

    // ---------- ③ 极端比例 ----------

    @Test
    @DisplayName("🔴 ③ coinAmount=1 / amount=300000：全程不出现负数或超退，最后一次才补上那 1 个币")
    void extremeRatioNeverGoesNegativeOrOver() {
        final long amount = 300_000L;
        final long coinAmount = 1L;
        long refundedTotal = 0;
        long refundedCoin = 0;
        for (int i = 0; i < 6; i++) {
            long part = 50_000L;
            RefundSplit s = RefundSplit.accumulate(amount, coinAmount, refundedTotal,
                    refundedCoin, part);
            assertThat(s.thisCoin()).as("Coin 段不得为负").isGreaterThanOrEqualTo(0L);
            assertThat(s.thisCash()).as("现金段不得为负").isGreaterThanOrEqualTo(0L);
            assertThat(s.total()).isEqualTo(part);
            refundedTotal += part;
            refundedCoin += s.thisCoin();
            assertThat(refundedCoin).as("任何时刻累计 Coin 都不得超过 coinAmount")
                    .isLessThanOrEqualTo(coinAmount);
        }
        assertThat(refundedCoin).isEqualTo(coinAmount);
    }

    @Test
    @DisplayName("③ 极端反向：coinAmount 只差 1 就等于全额")
    void extremeRatioTheOtherWay() {
        final long amount = 300_000L;
        final long coinAmount = 299_999L;
        long refundedTotal = 0;
        long refundedCoin = 0;
        for (int i = 0; i < 3; i++) {
            long part = 100_000L;
            RefundSplit s = RefundSplit.accumulate(amount, coinAmount, refundedTotal,
                    refundedCoin, part);
            assertThat(s.thisCash()).isGreaterThanOrEqualTo(0L);
            refundedTotal += part;
            refundedCoin += s.thisCoin();
        }
        assertThat(refundedCoin).isEqualTo(coinAmount);
    }

    // ---------- 恒等式与边界 ----------

    @Test
    @DisplayName("恒等式 thisCoin + thisCash == thisRefund 在一大批随机化组合上成立")
    void identityHoldsAcrossManyCombinations() {
        // 固定种子：可复现。随机不是为了「碰运气找 bug」，是为了覆盖手写用例想不到的比例组合。
        java.util.Random rnd = new java.util.Random(20260818L);
        for (int caseNo = 0; caseNo < 500; caseNo++) {
            long amount = 1_000L + rnd.nextInt(1_000_000);
            long coinAmount = (long) rnd.nextInt((int) Math.min(amount, Integer.MAX_VALUE));
            long refundedTotal = 0;
            long refundedCoin = 0;
            int slices = 1 + rnd.nextInt(5);
            for (int i = 0; i < slices; i++) {
                long remaining = amount - refundedTotal;
                if (remaining <= 0) {
                    break;
                }
                long part = (i == slices - 1) ? remaining : 1 + (long) rnd.nextInt((int) remaining);
                RefundSplit s = RefundSplit.accumulate(amount, coinAmount, refundedTotal,
                        refundedCoin, part);
                assertThat(s.total()).isEqualTo(part);
                assertThat(s.thisCoin()).isGreaterThanOrEqualTo(0L);
                assertThat(s.thisCash()).isGreaterThanOrEqualTo(0L);
                refundedTotal += part;
                refundedCoin += s.thisCoin();
            }
            if (refundedTotal == amount) {
                assertThat(refundedCoin)
                        .as("case %d: amount=%d coin=%d 退完后 Coin 段漂移了", caseNo, amount, coinAmount)
                        .isEqualTo(coinAmount);
            }
        }
    }

    @Test
    @DisplayName("🔴 超退当场拒绝，而不是截断 —— 截断会静默吞掉调用方的算错")
    void overRefundIsRejected() {
        assertThatThrownBy(
                () -> RefundSplit.accumulate(100_000L, 40_000L, 90_000L, 36_000L, 20_000L))
                .isInstanceOf(AppException.class).hasMessageContaining("超过订单金额");
    }

    @Test
    @DisplayName("参数非法当场拒绝（负额 / Coin 段超总额 / 总额非正）")
    void invalidInputsRejected() {
        assertThatThrownBy(() -> RefundSplit.accumulate(0L, 0L, 0L, 0L, 0L))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> RefundSplit.accumulate(100L, 200L, 0L, 0L, 50L))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> RefundSplit.accumulate(100L, 50L, 0L, 0L, -1L))
                .isInstanceOf(AppException.class);
    }

    // ---------- 🔒 能力缺席：coin_ratio 不可能参与计算 ----------

    @Test
    @DisplayName("🔒 AD-2：本类的入参里【根本没有 ratio】—— 是能力缺席，不是纪律要求")
    void ratioIsNotEvenAnInput() {
        Method[] methods = RefundSplit.class.getDeclaredMethods();
        List<Parameter> params = java.util.Arrays.stream(methods)
                .flatMap(m -> java.util.Arrays.stream(m.getParameters()))
                .toList();
        for (Parameter p : params) {
            assertThat(p.getType())
                    .as("参数 %s 不是整数类型 —— 比例/浮点一旦进入本类，AD-2 就形同虚设", p.getName())
                    .isIn(long.class, Long.class, RefundSplit.class, Object.class);
        }
        // 🔴 禁 double / float：0.1 + 0.2 那类误差在这里会直接变成钱
        for (Method m : methods) {
            for (Class<?> t : m.getParameterTypes()) {
                assertThat(t).isNotIn(double.class, float.class, Double.class, Float.class);
            }
            assertThat(m.getReturnType()).isNotIn(double.class, float.class, Double.class,
                    Float.class);
        }
        // 返回值两段也都是 long
        assertThat(RefundSplit.class.getRecordComponents()).allSatisfy(
                rc -> assertThat(rc.getType()).isEqualTo(long.class));
    }

    @Test
    @DisplayName("🔒 源码里不出现 coin_ratio / coinRatio —— 资金计算路径不得读它")
    void sourceNeverReadsCoinRatio() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/tailtopia/shop/returns/domain/RefundSplit.java"));
        // 只允许出现在注释里说明「不读它」；这里断言的是【代码】不读 —— 去掉注释后再看
        String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        assertThat(code).doesNotContain("coinRatio").doesNotContain("coin_ratio");
        assertThat(code).doesNotContain("double").doesNotContain("float");
    }
}
