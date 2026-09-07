package com.tailtopia.shop.returns.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.returns.domain.ReturnType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：凭证图张数（2026-09-02 产品拍板：<b>前端 2 张、后端 2 张</b>）。
 *
 * <p>此前只有前端在挡：App 对所有原因都要求 min 2，而服务端只在 {@code QUALITY_ISSUE} 时
 * 要求「非空」—— 1 张也过、换个调用方 0 张也过。
 * <b>挡在前端的那一档等于没挡</b>（MAX_EVIDENCE 那条注释当年说的是同一件事），
 * 而后果是后台审核时无图可看。
 */
class ReturnEvidenceCountTest {

    private static List<String> keys(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> "k" + i).toList();
    }

    private static void check(ReturnType t, int n) {
        ReturnRequestService.requireEvidenceCount(t, keys(n));
    }

    @Test
    @DisplayName("🔴 货在手上的退货：少于 2 张一律拒（含 0 张与 1 张）")
    void deliveredReturnsRequireTwo() {
        for (ReturnType t : List.of(ReturnType.QUALITY_ISSUE, ReturnType.NON_QUALITY_ISSUE)) {
            for (int n : new int[] {0, 1}) {
                assertThatThrownBy(() -> check(t, n))
                        .as("%s 传 %d 张", t, n)
                        .isInstanceOf(AppException.class);
            }
        }
        assertThatThrownBy(() -> ReturnRequestService.requireEvidenceCount(
                ReturnType.QUALITY_ISSUE, null)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("货在手上的退货：2~5 张放行")
    void deliveredReturnsAcceptTwoToFive() {
        for (int n = 2; n <= 5; n++) {
            final int k = n;
            assertThatCode(() -> check(ReturnType.NON_QUALITY_ISSUE, k)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("🔴 没见过货的退货：不要求凭证（否则这两条路直接走不通）")
    void undeliveredReturnsNeedNoEvidence() {
        // 拒收：货没离开承运商；发货前取消：无实物往返。用户拍不出任何东西。
        for (ReturnType t : List.of(
                ReturnType.REFUSED_ON_DELIVERY, ReturnType.CANCEL_BEFORE_SHIPMENT)) {
            assertThatCode(() -> check(t, 0)).as("%s", t).doesNotThrowAnyException();
            assertThatCode(() -> ReturnRequestService.requireEvidenceCount(t, null))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("上限 5 对所有类型都生效（包括没见过货的那两种）")
    void maxAppliesEverywhere() {
        for (ReturnType t : ReturnType.values()) {
            assertThatThrownBy(() -> check(t, 6)).as("%s 传 6 张", t)
                    .isInstanceOf(AppException.class);
        }
    }

    @Test
    @DisplayName("🔴 「要不要凭证」跟着 isUndelivered 走 —— 新增 ReturnType 时会在这里被拦下")
    void ruleFollowsUndeliveredFlag() {
        // 新增枚举值时本条会红：那是提醒去想「这一档拿不拿得到实物」，
        // 而不是让它默默继承某个默认值。
        for (ReturnType t : ReturnType.values()) {
            if (t.isUndelivered()) {
                assertThatCode(() -> check(t, 0)).as("%s", t).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> check(t, 0)).as("%s", t)
                        .isInstanceOf(AppException.class);
            }
        }
    }
}
