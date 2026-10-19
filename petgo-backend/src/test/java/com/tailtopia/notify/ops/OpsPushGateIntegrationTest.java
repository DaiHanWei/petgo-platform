package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignStatus;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.ops.service.OpsNotificationDispatcher;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.ops.OpsTokenGenerator;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1（需 Postgres + Redis，云端不跑）：V1.4.0 ④ Story 1.2 · AC2 —— 运营公告按批预取「活动与优惠」关闭名单（P-4）。
 *
 * <p>单独成类（不给 {@code OpsNotificationDispatchIntegrationTest} 加 {@code @MockitoBean}，免得改变它的上下文）。
 * 发送一律 IDS 模式；发送前把库里遗留 PENDING 活动置 CANCELED（同那个类的做法）。
 */
class OpsPushGateIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private OpsNotificationCampaignRepository campaigns;
    @Autowired
    private OpsCampaignTargetDao targets;
    @Autowired
    private OpsNotificationDispatcher dispatcher;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private NotificationPusher pusher;

    @Test
    @DisplayName("B 关 PROMO、C 未关 → 两人都进通知中心，只推 C；触达 2、失败 0、DONE")
    void mutedRecipientOnlyGetsNotificationCenter() throws Exception {
        jdbc.update("UPDATE ops_notification_campaigns SET status = 'CANCELED', canceled_at = now(), "
                + "updated_at = now() WHERE status = 'PENDING'");
        when(pusher.pushToUserNow(anyLong(), any(), any(), any(), any(), any())).thenReturn(true);
        User b = newUser();
        User c = newUser();
        mvc.perform(patch("/api/v1/me/push-settings").header(HttpHeaders.AUTHORIZATION, userBearer(b.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categories\":{\"PROMO\":false}}"))
                .andExpect(status().isOk());

        OpsNotificationCampaign campaign = campaigns.save(OpsNotificationCampaign.create(OpsTokenGenerator.next(),
                "Promo akhir pekan", "Weekend promo", "Isi promo", "Promo body", OpsTargetType.NONE, null,
                AudienceSpec.ids().toJson(), null, null));
        targets.insertAll(campaign.getId(), List.of(b.getId(), c.getId()));

        dispatcher.dispatch(campaign.getId());

        for (User u : List.of(b, c)) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'OPS_ANNOUNCEMENT' "
                    + "AND recipient_user_id = ? AND target_ref = ?", Long.class, u.getId(),
                    campaign.getPublicToken())).isEqualTo(1);
        }
        verify(pusher, times(1)).pushToUserNow(eq(c.getId()), any(), any(), any(), any(), any());
        verify(pusher, never()).pushToUserNow(eq(b.getId()), any(), any(), any(), any(), any());
        OpsNotificationCampaign done = campaigns.findById(campaign.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(OpsCampaignStatus.DONE);
        assertThat(done.getRecipientCount()).isEqualTo(2);
        assertThat(done.getFailedCount()).isZero();
    }
}
