package com.tailtopia.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.tailtopia.auth.domain.User;
import com.tailtopia.moderation.domain.AccountReportReason;
import com.tailtopia.moderation.repository.AccountReportRepository;
import com.tailtopia.moderation.service.AccountReportService;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * L1：账号举报的<b>事务边界</b>（Story 2.1 AC5）—— 需 Docker postgres。
 *
 * <p>「写工单 / 写明细 / 写隐藏关系」必须在<b>同一个事务</b>里，任一失败整体回滚。
 * 破了这条会出现两种都很难查的线上现象：
 * <ul>
 *   <li><b>有工单没隐藏</b>：用户举报完了，那个人的内容还天天出现在他首页；</li>
 *   <li><b>有隐藏没工单</b>：内容确实消失了，但运营队列里根本看不到这条举报，永远无人处置。</li>
 * </ul>
 *
 * <p>这里用 spy 让隐藏关系那一步抛异常 —— 单独开一个测试类是因为 bean override 会另起一个
 * Spring 上下文，不该拖累主用例集。
 */
class AccountReportTransactionIntegrationTest extends ApiIntegrationTest {

    @MockitoSpyBean
    private UserHideRelationService hideRelations;

    @Autowired
    private AccountReportService accountReportService;

    @Autowired
    private AccountReportRepository reports;

    @Test
    void ac5_hideFailureRollsBackTheWholeTicket() {
        User target = newUser();
        User reporter = newUser();
        doThrow(new IllegalStateException("模拟隐藏关系写入失败"))
                .when(hideRelations).hideByReport(anyLong(), anyLong());

        assertThatThrownBy(() -> accountReportService.submit(
                reporter.getId(), target.getId(), AccountReportReason.SPAM, null))
                .isInstanceOf(IllegalStateException.class);

        // 工单没留下来 —— 明细外键指向工单，工单没了它也不可能存在。
        // ⚠️ 这里不能断言 `entries.count() == 0`：L1 打的是同一个真实库，别的用例写的行还在。
        assertThat(reports.findByTargetUserId(target.getId())).isEmpty();
    }
}
