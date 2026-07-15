package com.tailtopia.admin.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.aiorder.dto.AiRevenueSummary;
import com.tailtopia.admin.aiorder.service.AdminAiOrderService;
import com.tailtopia.admin.dashboard.dto.OverviewMetrics;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.consult.domain.VetSettlement;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.VetSettlementRepository;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.vet.repository.VetAccountRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（Story 9.10）：四模块指标聚合组装。纯 Mockito。 */
class AdminDashboardServiceTest {

    @Test
    void overviewAggregatesFourModules() {
        UserRepository users = Mockito.mock(UserRepository.class);
        ConsultOrderRepository consultOrders = Mockito.mock(ConsultOrderRepository.class);
        AdminAiOrderService aiOrders = Mockito.mock(AdminAiOrderService.class);
        ContentPostRepository posts = Mockito.mock(ContentPostRepository.class);
        CommentRepository comments = Mockito.mock(CommentRepository.class);
        VetAccountRepository vets = Mockito.mock(VetAccountRepository.class);
        VetSettlementRepository settlements = Mockito.mock(VetSettlementRepository.class);

        when(users.count()).thenReturn(1000L);
        when(users.countByAccountType(AccountType.VIRTUAL)).thenReturn(20L);
        when(consultOrders.count()).thenReturn(300L);
        when(aiOrders.summary()).thenReturn(new AiRevenueSummary(500000L, 50L, 3L, 1L, 300000L, 200000L));
        when(posts.count()).thenReturn(207L);
        when(comments.count()).thenReturn(800L);
        when(vets.count()).thenReturn(12L);
        when(settlements.countByStatus(VetSettlement.PENDING_FINANCE)).thenReturn(4L);

        AdminDashboardService svc = new AdminDashboardService(users, consultOrders, aiOrders, posts,
                comments, vets, settlements);
        OverviewMetrics m = svc.overview();

        assertThat(m.totalUsers()).isEqualTo(1000L);
        assertThat(m.virtualAccounts()).isEqualTo(20L);
        assertThat(m.vetConsultOrders()).isEqualTo(300L);
        assertThat(m.aiCompletedOrders()).isEqualTo(50L);
        assertThat(m.aiRevenue()).isEqualTo(500000L);
        assertThat(m.posts()).isEqualTo(207L);
        assertThat(m.comments()).isEqualTo(800L);
        assertThat(m.totalVets()).isEqualTo(12L);
        assertThat(m.pendingSettlements()).isEqualTo(4L);
    }
}
