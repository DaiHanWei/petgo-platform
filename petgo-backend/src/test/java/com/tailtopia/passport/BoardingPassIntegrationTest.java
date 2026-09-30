package com.tailtopia.passport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.places.service.PlaceMergeService;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.passport.service.BoardingPassMergeService;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V1.3.2 batch-a Story 3.5 · L1（需 postgres + redis）：列表 / 详情 / 单张解锁 / 到账、再到访不产生新购买、
 * 真实合并四情形 + 改挂失败整体回滚、付款期间被合并的到账转移、删档级联。
 *
 * <p>三个发放口至此齐全（3.2 / 3.4 / 3.5），本 IT 不再顶掉注册表 —— 真实 Spring 上下文。
 */
class BoardingPassIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/pet-profiles/me/boarding-passes";
    private static final double LAT = -6.2351;
    private static final double LNG = 106.8101;

    @Autowired
    private PlaceRepository places;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private PlaceMergeService mergeService;
    @MockitoSpyBean
    private BoardingPassMergeService boardingMerge;
    @Autowired
    private AdminAccountService adminAccounts;
    @Autowired
    private AdminUserDetailsService adminDetails;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private JdbcTemplate jdbc;

    private record Owner(User user, PetProfile pet) {
    }

    private Owner owner() {
        User u = newUser();
        PetProfile p = petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, "Anggora", null, null,
                "TOK-" + SEQ.incrementAndGet()));
        wallet.credit(u.getId(), 100_000L, PawCoinTxnType.TOPUP, "TEST", null, "bp-topup:" + SEQ.incrementAndGet());
        return new Owner(u, p);
    }

    private Place newPlace() {
        return places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""),
                "Taman " + SEQ.incrementAndGet(), PlaceType.PARK, List.of(PlaceTag.PETS_ALLOWED_INSIDE),
                LAT, LNG, "Jl. Test", null, 1L, "Jakarta"));
    }

    private void checkIn(Owner o, Place p) throws Exception {
        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(o.user().getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":" + LAT + ",\"longitude\":" + LNG + ",\"petIds\":[" + o.pet().getId() + "]}"))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions unlock(Owner o, Place p, String channel)
            throws Exception {
        return mvc.perform(post(BASE + "/" + p.getPublicToken() + "/unlock")
                .header("Authorization", userBearer(o.user().getId()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"" + channel + "\"}"));
    }

    private long adminId() {
        String email = "bp-" + SEQ.incrementAndGet() + "@tailtopia.test";
        adminAccounts.createAccount(email, "登机牌", AdminRole.CUSTOM, List.of(), 960000L + SEQ.incrementAndGet());
        return adminDetails.loadByEmail(email, false).getAdminAccountId();
    }

    private Map<String, Object> rowOf(Owner o, Place p) {
        return jdbc.queryForMap("SELECT * FROM boarding_pass_unlocks WHERE pet_profile_id = ? AND place_id = ?",
                o.pet().getId(), p.getId());
    }

    @Test
    void listDetailUnlockAndRevisitNeverBuysAgain() throws Exception {
        Owner o = owner();
        mvc.perform(get(BASE).header("Authorization", userBearer(o.user().getId())))
                .andExpect(jsonPath("$.items.length()").value(0));
        Place a = newPlace();
        checkIn(o, a);
        mvc.perform(get(BASE).header("Authorization", userBearer(o.user().getId())))
                .andExpect(jsonPath("$.items[0].placeToken").value(a.getPublicToken()))
                .andExpect(jsonPath("$.items[0].unlocked").value(false))
                .andExpect(jsonPath("$.passportNo").isNotEmpty());
        mvc.perform(get(BASE + "/" + a.getPublicToken()).header("Authorization", userBearer(o.user().getId())))
                .andExpect(jsonPath("$.passenger").value("Momo"))
                .andExpect(jsonPath("$.breed").value("Anggora"))
                .andExpect(jsonPath("$.seat").isNotEmpty())
                .andExpect(jsonPath("$.city").value("Jakarta"))
                .andExpect(jsonPath("$.unlockToken").doesNotExist());

        unlock(o, a, "PAWCOIN").andExpect(status().isOk()).andExpect(jsonPath("$.unlocked").value(true));
        unlock(o, a, "PAWCOIN").andExpect(status().isConflict());
        long purchases = jdbc.queryForObject("SELECT count(*) FROM keepsake_purchases WHERE user_id = ?", Long.class,
                o.user().getId());
        // 解锁后再到访两次（跨天打卡由 1-1 口径限制；这里直接插打卡行模拟）→ 次数更新、不产生新购买。
        for (int i = 0; i < 2; i++) {
            String tok = java.util.UUID.randomUUID().toString().replace("-", "");
            Long checkin = jdbc.queryForObject("INSERT INTO place_checkins (user_id, place_id, origin_place_id, "
                    + "public_token, visit_date, checked_at) VALUES (?, ?, ?, ?, current_date - ?, "
                    + "now() - make_interval(days => ?)) RETURNING id", Long.class, o.user().getId(), a.getId(),
                    a.getId(), tok, i + 1, i + 1);
            jdbc.update("INSERT INTO place_checkin_pets (checkin_id, pet_profile_id, origin_place_id, visit_date) "
                    + "VALUES (?, ?, ?, current_date - ?)", checkin, o.pet().getId(), a.getId(), i + 1);
        }
        mvc.perform(get(BASE + "/" + a.getPublicToken()).header("Authorization", userBearer(o.user().getId())))
                .andExpect(jsonPath("$.visitCount").value(3))
                .andExpect(jsonPath("$.unlocked").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM keepsake_purchases WHERE user_id = ?", Long.class,
                o.user().getId())).isEqualTo(purchases);
    }

    @Test
    void mergeMatrixFourCases() throws Exception {
        long admin = adminId();
        // ① 保留方无行 → 改挂
        Owner o1 = owner();
        Place a1 = newPlace();
        Place b1 = newPlace();
        checkIn(o1, b1);
        unlock(o1, b1, "PAWCOIN").andExpect(jsonPath("$.unlocked").value(true));
        mergeService.merge(b1.getId(), a1.getId(), admin);
        assertThat(rowOf(o1, a1).get("unlocked_at")).isNotNull();
        mvc.perform(get(BASE).header("Authorization", userBearer(o1.user().getId())))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].unlocked").value(true));

        // ② 被并方未解锁、保留方有行 → supersede
        Owner o2 = owner();
        Place a2 = newPlace();
        Place b2 = newPlace();
        checkIn(o2, a2);
        checkIn(o2, b2);
        unlock(o2, a2, "QRIS");
        unlock(o2, b2, "QRIS");
        mergeService.merge(b2.getId(), a2.getId(), admin);
        assertThat(rowOf(o2, b2).get("superseded_at")).isNotNull();

        // ③ 被并方已解锁、保留方有行未解锁 → 解锁转给保留方，被并方清空
        Owner o3 = owner();
        Place a3 = newPlace();
        Place b3 = newPlace();
        checkIn(o3, a3);
        checkIn(o3, b3);
        unlock(o3, a3, "QRIS");
        unlock(o3, b3, "PAWCOIN");
        mergeService.merge(b3.getId(), a3.getId(), admin);
        assertThat(rowOf(o3, a3).get("unlocked_at")).isNotNull();
        assertThat(rowOf(o3, b3).get("unlocked_at")).isNull();
        assertThat(rowOf(o3, b3).get("superseded_at")).isNotNull();

        // ④ 两边都已解锁 → 被并方 supersede 且保留 unlocked_at（退款候选）
        Owner o4 = owner();
        Place a4 = newPlace();
        Place b4 = newPlace();
        checkIn(o4, a4);
        checkIn(o4, b4);
        unlock(o4, a4, "PAWCOIN");
        unlock(o4, b4, "PAWCOIN");
        mergeService.merge(b4.getId(), a4.getId(), admin);
        assertThat(rowOf(o4, b4).get("unlocked_at")).isNotNull();
        assertThat(rowOf(o4, b4).get("superseded_at")).isNotNull();
    }

    @Test
    void paymentArrivingAfterMergeGoesToKeeperRow() throws Exception {
        long admin = adminId();
        Owner o = owner();
        Place a = newPlace();
        Place b = newPlace();
        checkIn(o, a);
        checkIn(o, b);
        unlock(o, a, "QRIS");
        String body = unlock(o, b, "QRIS").andReturn().getResponse().getContentAsString();
        String intent = json.readTree(body).path("payment").path("token").asText();
        mergeService.merge(b.getId(), a.getId(), admin); // b 行被 supersede
        paymentIntents.applyCallback(new PaymentCallback(intent, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID,
                Map.of()));
        assertThat(rowOf(o, a).get("unlocked_at")).as("到账转给保留方").isNotNull();
        mvc.perform(get(BASE + "/" + b.getPublicToken()).header("Authorization", userBearer(o.user().getId())))
                .andExpect(jsonPath("$.placeToken").value(a.getPublicToken()))
                .andExpect(jsonPath("$.unlocked").value(true));
    }

    @Test
    void reassignFailureRollsBackWholeMerge() throws Exception {
        long admin = adminId();
        Owner o = owner();
        Place a = newPlace();
        Place b = newPlace();
        checkIn(o, b);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(boardingMerge)
                .reassignForMerge(b.getId(), a.getId());
        assertThatThrownBy(() -> mergeService.merge(b.getId(), a.getId(), admin)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM place_checkins WHERE place_id = ?", Long.class, b.getId()))
                .as("打卡未改挂").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM places WHERE id = ?", String.class, b.getId()))
                .isNotEqualTo("MERGED");
    }

    @Test
    void petDeletionCascadesAndLatePaymentIsOrphan() throws Exception {
        Owner o = owner();
        Place a = newPlace();
        checkIn(o, a);
        String body = unlock(o, a, "QRIS").andReturn().getResponse().getContentAsString();
        String intent = json.readTree(body).path("payment").path("token").asText();
        new TransactionTemplate(txManager).executeWithoutResult(s -> profileDeletion.deleteByUserId(o.user().getId()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM boarding_pass_unlocks WHERE pet_profile_id = ?",
                Long.class, o.pet().getId())).isZero();
        paymentIntents.applyCallback(new PaymentCallback(intent, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID,
                Map.of()));
        assertThat(jdbc.queryForObject("SELECT status FROM keepsake_purchases WHERE user_id = ? AND sku = 'BOARDING_PASS'",
                String.class, o.user().getId())).isEqualTo("ORPHAN_PAID");
    }
}
