package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.domain.OpsRecipient;
import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.ops.OpsTokenGenerator;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 Postgres + Redis，云端不跑）：运营通知数据面（V1.3.4 Story 6.1）。
 *
 * <p>上下文启动即验 Flyway {@code V20261012_1107} / {@code V20261012_1108} + {@code ddl-auto=validate}（AC1 / AC2 / AC4.6）。
 * 活动由实体工厂 + 仓储预置（本主题无写接口，D-2）。
 */
class OpsNotificationSchemaIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private OpsNotificationCampaignRepository campaigns;
    @Autowired
    private OpsCampaignTargetDao targets;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AccountDeletionRepository deletions;
    @Autowired
    private AccountDeletionService accountDeletion;

    private OpsNotificationCampaign saveCampaign(OpsTargetType type, String value, AudienceSpec spec) {
        return campaigns.save(OpsNotificationCampaign.create(OpsTokenGenerator.next(),
                "Promo akhir pekan", "Weekend promo", "Isi", "Body", type, value, spec.toJson(), null, null));
    }

    @Test
    @DisplayName("实体往返：JSONB 原文、默认计数 0、状态 PENDING")
    void entityRoundTrip() {
        OpsNotificationCampaign c = saveCampaign(OpsTargetType.EXTERNAL, "https://example.com/promo",
                AudienceSpec.parse("{\"mode\":\"FILTER\",\"hasPet\":true,\"species\":[\"CAT\"]}"));

        OpsNotificationCampaign back = campaigns.findByPublicToken(c.getPublicToken()).orElseThrow();
        assertThat(AudienceSpec.parse(back.getAudienceSpec()).hasPet()).isTrue();
        assertThat(back.getStatus().name()).isEqualTo("PENDING");
        assertThat(back.getRecipientCount()).isZero();
        assertThat(back.getCreatedAt()).isNotNull();
        assertThat(campaigns.findByPublicTokenIn(List.of(c.getPublicToken(), "nope"))).hasSize(1);
    }

    @Test
    @DisplayName("ck_notifications_type 接受 OPS_ANNOUNCEMENT（不触发 23514）")
    void opsAnnouncementAcceptedByCheck() {
        User u = newUser();
        jdbc.update("""
                INSERT INTO notifications (recipient_user_id, type, title, body, deep_link_type, deep_link_token, target_ref)
                VALUES (?, 'OPS_ANNOUNCEMENT', 't', 'b', 'OPS_ANNOUNCEMENT', ?, 'campTok')
                """, u.getId(), "tok" + SEQ.incrementAndGet());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'OPS_ANNOUNCEMENT'",
                Long.class, u.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("target_type = NONE 带 target_value 被 CHECK 拒")
    void noneWithValueRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ops_notification_campaigns (public_token, title_id, title_en, body_id, body_en,
                        target_type, target_value, audience_spec)
                VALUES (?, 'a', 'a', 'b', 'b', 'NONE', 'x', '{"mode":"ALL"}'::jsonb)
                """, "bad" + SEQ.incrementAndGet())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("audience_spec 缺 mode 被 CHECK 拒")
    void audienceWithoutModeRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ops_notification_campaigns (public_token, title_id, title_en, body_id, body_en,
                        audience_spec)
                VALUES (?, 'a', 'a', 'b', 'b', '{"hasPet":true}'::jsonb)
                """, "bad" + SEQ.incrementAndGet())).isInstanceOf(DataIntegrityViolationException.class);
        // mode 为 JSON null 同样被拒（->> 返回 NULL 的另一条路径）
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ops_notification_campaigns (public_token, title_id, title_en, body_id, body_en,
                        audience_spec)
                VALUES (?, 'a', 'a', 'b', 'b', '{"mode":null}'::jsonb)
                """, "bad" + SEQ.incrementAndGet())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("通知中心列表：运营通知含 opsTarget，点赞通知不含该键")
    void listCarriesOpsTargetOnlyForOpsAnnouncements() throws Exception {
        User u = newUser();
        OpsNotificationCampaign c = saveCampaign(OpsTargetType.PRODUCT, "prodTok", AudienceSpec.all());
        notificationService.sendWithoutPush(u.getId(), NotificationType.CONTENT_LIKED, "t", "b",
                NotificationType.CONTENT_LIKED.name(), "123");
        notificationService.writeOpsAnnouncements(c, List.of(new OpsRecipient(u.getId(), "en")));

        String body = mvc.perform(get("/api/v1/notifications").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode items = json.readTree(body).get("items");

        JsonNode ops = null;
        JsonNode liked = null;
        for (JsonNode n : items) {
            if ("OPS_ANNOUNCEMENT".equals(n.get("type").asText())) {
                ops = n;
            } else if ("CONTENT_LIKED".equals(n.get("type").asText())) {
                liked = n;
            }
        }
        assertThat(ops).isNotNull();
        assertThat(ops.get("title").asText()).isEqualTo("Weekend promo");
        assertThat(ops.get("targetRef").asText()).isEqualTo(c.getPublicToken());
        assertThat(ops.get("opsTarget").get("type").asText()).isEqualTo("PRODUCT");
        assertThat(ops.get("opsTarget").get("value").asText()).isEqualTo("prodTok");
        assertThat(liked).isNotNull();
        assertThat(liked.has("opsTarget")).isFalse();
    }

    @Test
    @DisplayName("注销后子表无该用户行；活动行仍在")
    void accountDeletionPurgesCampaignTargets() {
        User u = newUser();
        OpsNotificationCampaign c = saveCampaign(OpsTargetType.NONE, null, AudienceSpec.ids());
        targets.insertAll(c.getId(), List.of(u.getId(), u.getId(), 999_999_999L));
        assertThat(targets.countByCampaign(c.getId())).as("去重 + 无效 id 也落库").isEqualTo(2L);

        AccountDeletion d = deletions.save(AccountDeletion.request(u.getId()));
        accountDeletion.execute(d.getId());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ops_notification_campaign_targets WHERE user_id = ?", Long.class,
                u.getId())).isZero();
        assertThat(targets.countByCampaign(c.getId())).isEqualTo(1L);
        assertThat(campaigns.findById(c.getId())).isPresent();
    }
}
