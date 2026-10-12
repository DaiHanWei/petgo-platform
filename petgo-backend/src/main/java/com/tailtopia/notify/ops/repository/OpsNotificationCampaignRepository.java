package com.tailtopia.notify.ops.repository;

import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 运营通知发送活动仓储（V1.3.4 Story 6.1）。状态机条件更新方法由 Story 6.2 追加。
 */
public interface OpsNotificationCampaignRepository extends JpaRepository<OpsNotificationCampaign, Long> {

    Optional<OpsNotificationCampaign> findByPublicToken(String publicToken);

    /** 通知中心列表一页一次批量回查（不 N+1）。 */
    List<OpsNotificationCampaign> findByPublicTokenIn(Collection<String> publicTokens);
}
