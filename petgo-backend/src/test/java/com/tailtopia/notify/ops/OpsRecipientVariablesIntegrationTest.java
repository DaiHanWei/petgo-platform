package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.OpsAudienceQuery;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignStatus;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.domain.OpsRecipient;
import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.ops.service.OpsNotificationDispatcher;
import com.tailtopia.notify.ops.service.OpsNotificationScanner;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.ops.OpsTokenGenerator;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.DefaultPetPolicy;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1（需 Postgres + Redis，云端不跑，待本地验收）：运营通知变量替换与人群计算（V1.4.2 Story 7.1 · AB-37 · R-3）。
 *
 * <p>共享测试库有大量历史用户：人群断言一律只看「我造的人」，真正发送一律用 {@code IDS} 模式限定收件人。
 * 单独成类并 {@code @MockitoBean} 推送替身（同 {@code OpsPushGateIntegrationTest}），用来比对推送文案与通知行逐字一致。
 */
class OpsRecipientVariablesIntegrationTest extends ApiIntegrationTest {

    private static final String NICK = "{{nickname}}";
    private static final String PET = "{{petName}}";

    @MockitoBean
    private NotificationPusher pusher;

    @Autowired
    private OpsAudienceQuery audienceQuery;
    @Autowired
    private OpsNotificationCampaignRepository campaigns;
    @Autowired
    private OpsCampaignTargetDao targets;
    @Autowired
    private OpsNotificationDispatcher dispatcher;
    @Autowired
    private OpsNotificationScanner scanner;
    @Autowired
    private PetProfileRepository pets;
    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 造数 ----------

    private void addPet(long ownerId, String name, String petType, String status, int minutesAgo) {
        jdbc.update("INSERT INTO pet_profiles (owner_id, name, pet_type, card_token, status, created_at, updated_at, pet_species, milestone_track) "
                + "VALUES (?, ?, ?, ?, ?, now() - (? * interval '1 minute'), now(), ?, ?)", ownerId, name, petType,
                "ops-var-it-" + SEQ.incrementAndGet(), status, minutesAgo, petType, petType);
    }

    private User user(String nickname, String locale) {
        User u = newUser();
        jdbc.update("UPDATE users SET nickname = ?, locale = ? WHERE id = ?", nickname, locale, u.getId());
        return u;
    }

    private OpsNotificationCampaign saveIds(List<Long> ids, String titleId, String titleEn, String bodyId,
            String bodyEn, Instant scheduledAt) {
        OpsNotificationCampaign c = campaigns.save(OpsNotificationCampaign.create(OpsTokenGenerator.next(),
                titleId, titleEn, bodyId, bodyEn, OpsTargetType.NONE, null, AudienceSpec.ids().toJson(),
                scheduledAt, null));
        targets.insertAll(c.getId(), ids);
        return c;
    }

    private OpsNotificationCampaign awaitFinal(long id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            OpsNotificationCampaign c = campaigns.findById(id).orElseThrow();
            if (c.getStatus() == OpsCampaignStatus.DONE || c.getStatus() == OpsCampaignStatus.PARTIAL_FAILED) {
                return c;
            }
            Thread.sleep(100);
        }
        return campaigns.findById(id).orElseThrow();
    }

    private Map<String, Object> row(long userId, String campaignToken) {
        return jdbc.queryForMap("SELECT title, body FROM notifications WHERE recipient_user_id = ? "
                + "AND target_ref = ?", userId, campaignToken);
    }

    private OpsRecipient resolved(String json, long userId) {
        return audienceQuery.resolve(AudienceSpec.parse(json), 0L).stream()
                .filter(r -> r.userId() == userId).findFirst().orElse(null);
    }

    private void cancelForeignPending(long keep) {
        jdbc.update("UPDATE ops_notification_campaigns SET status = 'CANCELED', canceled_at = now(), "
                + "updated_at = now() WHERE status = 'PENDING' AND id <> ?", keep);
    }

    // ---------- 人群 SQL 取值 ----------

    @Test
    @DisplayName("多宠：最早只读 + 两只 ACTIVE → 取第二只；全只读 → 取最早；与 DefaultPetPolicy.pick 一致")
    void petPickMatchesDefaultPetPolicy() {
        User multi = newUser();
        addPet(multi.getId(), "OldRo", "DOG", "READ_ONLY", 30);
        addPet(multi.getId(), "FirstActive", "CAT", "ACTIVE", 20);
        addPet(multi.getId(), "SecondActive", "CAT", "ACTIVE", 10);
        User allRo = newUser();
        addPet(allRo.getId(), "RoEarly", "DOG", "READ_ONLY", 30);
        addPet(allRo.getId(), "RoLate", "DOG", "READ_ONLY", 10);
        User none = newUser();

        String all = "{\"mode\":\"ALL\"}";
        assertThat(resolved(all, multi.getId()).petName()).isEqualTo("FirstActive");
        assertThat(resolved(all, allRo.getId()).petName()).isEqualTo("RoEarly");
        assertThat(resolved(all, none.getId()).petName()).isNull();
        for (User u : List.of(multi, allRo)) {
            assertThat(resolved(all, u.getId()).petName()).isEqualTo(DefaultPetPolicy
                    .pick(pets.findAllByOwnerIdOrderByCreatedAtAscIdAsc(u.getId())).orElseThrow().getName());
        }
    }

    @Test
    @DisplayName("物种筛选：狗（更早）+ 猫 → 按猫取猫、按狗取狗；排除数 0")
    void speciesFilterLimitsLateral() {
        User u = newUser();
        addPet(u.getId(), "Doggo", "DOG", "ACTIVE", 30);
        addPet(u.getId(), "Kitty", "CAT", "ACTIVE", 10);
        assertThat(resolved("{\"mode\":\"FILTER\",\"species\":[\"CAT\"]}", u.getId()).petName()).isEqualTo("Kitty");
        assertThat(resolved("{\"mode\":\"FILTER\",\"species\":[\"DOG\"]}", u.getId()).petName()).isEqualTo("Doggo");
        OpsAudienceQuery.Reach r = audienceQuery.count(AudienceSpec.parse("{\"mode\":\"FILTER\",\"species\":[\"CAT\"]}"),
                null);
        assertThat(r.total()).isEqualTo(r.withPet());
    }

    @Test
    @DisplayName("空昵称：NULL 与空白 → 读出 null")
    void blankNicknameReadsNull() {
        User a = user(null, "id");
        User b = user("  ", "en");
        assertThat(resolved("{\"mode\":\"ALL\"}", a.getId()).nickname()).isNull();
        assertThat(resolved("{\"mode\":\"ALL\"}", b.getId()).nickname()).isNull();
    }

    // ---------- 发送 ----------

    @Test
    @DisplayName("IDS：不存在 ×1、已注销 ×1、无宠有效 ×1、有宠有效 ×2 + 宠物变量 → skipped 2 / noPet 1 / recipients 2 / planned 2；"
            + "替换值逐人、推送与通知行逐字相同；空昵称 ID Sobat / EN there")
    void idsWithPetVariable() throws InterruptedException {
        when(pusher.pushToUserNow(anyLong(), any(), any(), any(), any(), any())).thenReturn(true);
        User hex = user("hex", "en");
        addPet(hex.getId(), "tuotuo", "CAT", "ACTIVE", 5);
        User yenny = user(null, "id");
        addPet(yenny.getId(), "huahua", "DOG", "ACTIVE", 5);
        User noPet = user("nopet", "id");
        User deleted = newUser();
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", deleted.getId());

        OpsNotificationCampaign c = saveIds(List.of(hex.getId(), yenny.getId(), noPet.getId(), deleted.getId(),
                987_654_321_013L), "Halo " + NICK, "Hi " + NICK, PET + " rindu kamu", PET + " misses you", null);
        dispatcher.dispatch(c.getId());
        OpsNotificationCampaign done = awaitFinal(c.getId());

        assertThat(done.getStatus()).isEqualTo(OpsCampaignStatus.DONE);
        assertThat(done.getSkippedCount()).isEqualTo(2);
        assertThat(done.getSkippedNoPetCount()).isEqualTo(1);
        assertThat(done.getRecipientCount()).isEqualTo(2);
        assertThat(done.getPlannedCount()).isEqualTo(2);
        assertThat(row(hex.getId(), c.getPublicToken()))
                .containsEntry("title", "Hi hex").containsEntry("body", "tuotuo misses you");
        assertThat(row(yenny.getId(), c.getPublicToken()))
                .containsEntry("title", "Halo Sobat").containsEntry("body", "huahua rindu kamu");
        verify(pusher).pushToUserNow(eq(hex.getId()), eq("Hi hex"), eq("tuotuo misses you"), any(), any(), any());
        verify(pusher).pushToUserNow(eq(yenny.getId()), eq("Halo Sobat"), eq("huahua rindu kamu"), any(), any(),
                any());
        // 活动表存的是带令牌的原文
        assertThat(campaigns.findById(c.getId()).orElseThrow().getTitleEn()).isEqualTo("Hi " + NICK);
    }

    @Test
    @DisplayName("只用昵称变量：无宠用户照常收到，noPet 保持 0；EN 空昵称收 there")
    void nicknameOnlyKeepsUsersWithoutPet() throws InterruptedException {
        when(pusher.pushToUserNow(anyLong(), any(), any(), any(), any(), any())).thenReturn(true);
        User a = user("   ", "en");
        User b = user("budi", "id");
        OpsNotificationCampaign c = saveIds(List.of(a.getId(), b.getId()), "Halo " + NICK, "Hi " + NICK, "Isi",
                "Body", null);
        dispatcher.dispatch(c.getId());
        OpsNotificationCampaign done = awaitFinal(c.getId());
        assertThat(done.getRecipientCount()).isEqualTo(2);
        assertThat(done.getSkippedNoPetCount()).isZero();
        assertThat(row(a.getId(), c.getPublicToken())).containsEntry("title", "Hi there");
        assertThat(row(b.getId(), c.getPublicToken())).containsEntry("title", "Halo budi");
    }

    @Test
    @DisplayName("回归：无令牌活动文本与人数不变")
    void noTokensUnchanged() throws InterruptedException {
        when(pusher.pushToUserNow(anyLong(), any(), any(), any(), any(), any())).thenReturn(true);
        User a = user("hex", "en");
        OpsNotificationCampaign c = saveIds(List.of(a.getId()), "Promo akhir pekan", "Weekend promo", "Isi promo",
                "Promo body", null);
        dispatcher.dispatch(c.getId());
        OpsNotificationCampaign done = awaitFinal(c.getId());
        assertThat(done.getRecipientCount()).isEqualTo(1);
        assertThat(done.getSkippedNoPetCount()).isZero();
        assertThat(row(a.getId(), c.getPublicToken())).containsEntry("title", "Weekend promo")
                .containsEntry("body", "Promo body");
    }

    @Test
    @DisplayName("定时：建活动 → 改昵称 / 改宠物名 → 到点扫描发送 → 通知行是发送时刻的新值")
    void scheduledUsesValuesAtSendTime() throws InterruptedException {
        when(pusher.pushToUserNow(anyLong(), any(), any(), any(), any(), any())).thenReturn(true);
        User u = user("before", "en");
        addPet(u.getId(), "OldName", "CAT", "ACTIVE", 5);
        OpsNotificationCampaign c = saveIds(List.of(u.getId()), "Halo " + NICK, "Hi " + NICK + " & " + PET,
                "Isi", "Body", Instant.now().plusSeconds(1));
        jdbc.update("UPDATE users SET nickname = 'after' WHERE id = ?", u.getId());
        jdbc.update("UPDATE pet_profiles SET name = 'NewName' WHERE owner_id = ?", u.getId());
        jdbc.update("UPDATE ops_notification_campaigns SET scheduled_at = now() - interval '1 minute' WHERE id = ?",
                c.getId());
        cancelForeignPending(c.getId());
        scanner.scan();
        assertThat(awaitFinal(c.getId()).getStatus()).isEqualTo(OpsCampaignStatus.DONE);
        assertThat(row(u.getId(), c.getPublicToken())).containsEntry("title", "Hi after & NewName");
    }

    @Test
    @DisplayName("预览抽样：只在本次人群里抽；requirePet 只抽有宠的；excludeUserId 尽量避开，只剩他一人时仍返回他")
    void sampleFromAudience() {
        User a = user("a", "id");
        addPet(a.getId(), "PetA", "CAT", "ACTIVE", 5);
        User b = user("b", "id");
        AudienceSpec ids = AudienceSpec.ids();
        List<Long> both = List.of(a.getId(), b.getId());
        assertThat(audienceQuery.sample(ids, both, true, null)).hasValueSatisfying(r -> {
            assertThat(r.userId()).isEqualTo(a.getId());
            assertThat(r.petName()).isEqualTo("PetA");
        });
        assertThat(audienceQuery.sample(ids, both, true, a.getId()))
                .hasValueSatisfying(r -> assertThat(r.userId()).isEqualTo(a.getId()));
        assertThat(audienceQuery.sample(ids, both, false, a.getId()))
                .hasValueSatisfying(r -> assertThat(r.userId()).isEqualTo(b.getId()));
        assertThat(audienceQuery.sample(ids, List.of(b.getId()), true, null)).isEmpty();
        OpsAudienceQuery.Reach r = audienceQuery.count(ids, both);
        assertThat(r.total()).isEqualTo(2);
        assertThat(r.withPet()).isEqualTo(1);
    }
}
