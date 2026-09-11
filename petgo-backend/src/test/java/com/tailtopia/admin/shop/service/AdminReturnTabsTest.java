package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.returns.domain.ReturnStatus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * L0：A7 五页签必须<b>恰好覆盖</b>九个退货状态（V1.3.0 Story 10.1 · AC1）。
 *
 * <h2>为什么这条值得单独钉</h2>
 * 列表页时代有个「全部」页签兜底，看漏了状态也看得见；工作台<b>没有兜底页签</b> ——
 * 一个状态没被任何页签收进去，处于该状态的退货申请就在整个后台里<b>彻底不可见</b>：
 * 队列里没有、计数里没有、右栏也开不出来（左栏没有那一行）。
 * 用户那边照常显示「处理中」，运营这边永远看不到它。
 *
 * <p>而这种漏，编译不报错、渲染不报错、任何端点测试都不会红 ——
 * {@code ReturnStatus} 将来加一个状态（例如「仲裁中」），也不会有任何东西提醒你回来改页签映射。
 *
 * <p>反向也要钉：一个状态被<b>两个</b>页签同时收，那条申请会在两个队列里各出现一次，
 * 两边的运营各处置一遍（第二遍撞状态机报错，但人已经白忙）。
 *
 * <p>纯枚举比对，无 Spring / 无 DB。
 */
class AdminReturnTabsTest {

    @Test
    void everyReturnStatusBelongsToExactlyOneTab() {
        List<ReturnStatus> tabbed = new ArrayList<>(AdminReturnService.ALL_TABBED);

        assertThat(new LinkedHashSet<>(tabbed))
                .as("同一个状态被两个页签收了 —— 那条申请会在两个队列里各出现一次")
                .hasSameSizeAs(tabbed);
        assertThat(tabbed)
                .as("有状态没被任何页签收进去 —— 处于该状态的退货申请在整个后台里彻底不可见："
                        + "队列没有、计数没有、右栏也开不出来")
                .containsExactlyInAnyOrder(ReturnStatus.values());
    }

    /** 页签参数是 URL 的一部分（{@code ?tab=…}），解析必须宽松：手改 URL 不该让整页 500。 */
    @Test
    void unknownTabParamFallsBackToTheDefaultTabInsteadOfBlowingUp() {
        assertThat(AdminReturnService.Tab.of("nope")).isEqualTo(AdminReturnService.Tab.PENDING);
        assertThat(AdminReturnService.Tab.of((String) null)).isEqualTo(AdminReturnService.Tab.PENDING);
        assertThat(AdminReturnService.Tab.of("  REFUND ")).isEqualTo(AdminReturnService.Tab.REFUND);
    }

    /**
     * 状态 → 页签的映射必须与 <b>AC1 逐字一致</b>。
     *
     * <p>🔴 <b>期望值写死在这里，不能从 {@code Tab.statuses()} 推</b>：
     * {@code Tab.of(status)} 的实现就是「返回第一个 statuses 里含该状态的页签」，
     * 于是 {@code assertThat(Tab.of(s).statuses()).contains(s)} 这种写法<b>由构造方式保证为真</b> ——
     * 把 {@code REFUND_FAILED} 从「待退款」挪到「已完结·已驳回」，它照样绿
     * （上面那条 {@code everyReturnStatusBelongsToExactlyOneTab} 也绿：九个状态仍然互不重复）。
     * 而那个改动的真实后果是：<b>退款失败的单子只出现在终态队列里</b>，
     * 一个运营根本不会去翻的地方 —— 右栏「执行退款」按钮照渲染，却没人找得到它。
     *
     * <p>所以这里必须是一份独立写下的期望表：AC1 说「待退款 = REFUNDING / REFUND_FAILED」，
     * 那就在测试里再写一遍，让实现与需求两处对账。
     */
    @Test
    void everyStatusLandsOnTheTabThatAc1Says() {
        record Expected(ReturnStatus status, AdminReturnService.Tab tab) {
        }
        List<Expected> ac1 = List.of(
                new Expected(ReturnStatus.PENDING_REVIEW, AdminReturnService.Tab.PENDING),
                new Expected(ReturnStatus.AWAIT_SHIPBACK, AdminReturnService.Tab.SHIPBACK),
                new Expected(ReturnStatus.INSPECTING, AdminReturnService.Tab.INSPECT),
                new Expected(ReturnStatus.REFUNDING, AdminReturnService.Tab.REFUND),
                new Expected(ReturnStatus.REFUND_FAILED, AdminReturnService.Tab.REFUND),
                new Expected(ReturnStatus.REFUNDED, AdminReturnService.Tab.CLOSED),
                new Expected(ReturnStatus.CLOSED, AdminReturnService.Tab.CLOSED),
                new Expected(ReturnStatus.REJECTED, AdminReturnService.Tab.CLOSED),
                new Expected(ReturnStatus.WITHDRAWN, AdminReturnService.Tab.CLOSED));

        assertThat(ac1).as("期望表漏了状态 —— 它自己就得是全集，否则新状态照样能溜过去")
                .hasSize(ReturnStatus.values().length);
        List<String> wrong = new ArrayList<>();
        for (Expected e : ac1) {
            AdminReturnService.Tab actual = AdminReturnService.Tab.of(e.status());
            if (actual != e.tab()) {
                wrong.add(e.status() + "：AC1 说在「" + e.tab() + "」，实际落在「" + actual + "」");
            }
        }
        assertThat(wrong).as("页签映射与 AC1 不符 —— 这类错的后果是「单据还在，但运营找不到」").isEmpty();
    }
}
