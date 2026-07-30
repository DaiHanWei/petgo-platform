package com.tailtopia.admin.dashboard.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营概览看板聚合（Story 9.10，AB-1.1-01）。**复用各模块 repo count，不建独立统计系统**（护栏）。
 * ≤500 DAU 直算即可，无缓存/物化视图。AI 收入口径复用 9-4（仅 COMPLETED 计收入）。
 */
@Service
public class AdminDashboardService {

    private final UserRepository users;
    private final ConsultOrderRepository consultOrders;
    private final AdminAiOrderService aiOrders;
    private final ContentPostRepository posts;
    private final CommentRepository comments;
    private final VetAccountRepository vets;
    private final VetSettlementRepository settlements;

    public AdminDashboardService(UserRepository users, ConsultOrderRepository consultOrders,
            AdminAiOrderService aiOrders, ContentPostRepository posts, CommentRepository comments,
            VetAccountRepository vets, VetSettlementRepository settlements) {
        this.users = users;
        this.consultOrders = consultOrders;
        this.aiOrders = aiOrders;
        this.posts = posts;
        this.comments = comments;
        this.vets = vets;
        this.settlements = settlements;
    }

    @Transactional(readOnly = true)
    public OverviewMetrics overview() {
        AiRevenueSummary ai = aiOrders.summary();
        return new OverviewMetrics(
                users.count(),
                users.countByAccountType(AccountType.VIRTUAL),
                consultOrders.count(),
                ai.completedCount(),
                ai.totalRevenue(),
                posts.count(),
                comments.count(),
                vets.count(),
                settlements.countByStatus(VetSettlement.PENDING_FINANCE));
    }
}
