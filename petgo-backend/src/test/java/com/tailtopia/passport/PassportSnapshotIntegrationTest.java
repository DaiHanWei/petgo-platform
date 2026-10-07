package com.tailtopia.passport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
import com.tailtopia.passport.service.PassportSnapshotGranter;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.service.KeepsakeGranterRegistry;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V1.3.2 batch-a Story 3.4 · L1（需 postgres + redis）：快照发起冻结 / 复用 / 409 / 422、到账发放、版本状态
 * （次数 / 新章 / 真实合并 / 下架）、已购列表与回看、删档级联 + 删档后到账 ORPHAN_PAID。
 */
class PassportSnapshotIntegrationTest extends ApiIntegrationTest {

    private static final String PASSPORT = "/api/v1/pet-profiles/me/passport";
    private static final String SNAPSHOTS = PASSPORT + "/snapshots";
    private static final double LAT = -6.2351;
    private static final double LNG = 106.8101;

    @MockitoBean
    private KeepsakeGranterRegistry granters;

    @Autowired
    private PassportSnapshotGranter granter;
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

    @BeforeEach
    void setUp() {
        when(granters.forSku(KeepsakeSku.PASSPORT_SNAP)).thenReturn(granter);
    }

    private Place newPlace() {
        return places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""),
                "Kopi " + SEQ.incrementAndGet(), PlaceType.CAFE, List.of(PlaceTag.PETS_ALLOWED_INSIDE),
                LAT, LNG, "Jl. Test", null, 1L, "Jakarta"));
    }

    private record Owner(User user, PetProfile pet) {
    }

    private Owner owner() {
        User u = newUser();
        PetProfile p = petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        wallet.credit(u.getId(), 100_000L, PawCoinTxnType.TOPUP, "TEST", null, "ps-topup:" + SEQ.incrementAndGet());
        return new Owner(u, p);
    }

    private void checkIn(Owner o, Place p) throws Exception {
        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(o.user().getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":" + LAT + ",\"longitude\":" + LNG + ",\"petIds\":[" + o.pet().getId() + "]}"))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions start(Owner o, String channel) throws Exception {
        return mvc.perform(post(SNAPSHOTS).header("Authorization", userBearer(o.user().getId()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"" + channel + "\"}"));
    }

    private boolean currentUnlocked(Owner o) throws Exception {
        String body = mvc.perform(get(PASSPORT).header("Authorization", userBearer(o.user().getId())))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("currentVersionUnlocked").asBoolean();
    }

    private long snapshotRows(Owner o) {
        return jdbc.queryForObject("SELECT count(*) FROM passport_snapshots WHERE pet_profile_id = ?", Long.class,
                o.pet().getId());
    }

    @Test
    void noStampsIs422AndPawcoinUnlocksAndSecondIs409() throws Exception {
        Owner o = owner();
        start(o, "PAWCOIN").andExpect(status().isUnprocessableEntity());
        checkIn(o, newPlace());
        checkIn(o, newPlace());
        start(o, "PAWCOIN").andExpect(status().isOk()).andExpect(jsonPath("$.unlocked").value(true))
                .andExpect(jsonPath("$.snapshotToken").exists());
        assertThat(currentUnlocked(o)).isTrue();
        start(o, "PAWCOIN").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/keepsake-already-unlocked"));
        mvc.perform(get(PASSPORT).header("Authorization", userBearer(o.user().getId())))
                .andExpect(jsonPath("$.purchasedVersionCount").value(1));
    }

    @Test
    void qrisReusesSnapshotNewStampMakesSecondAndPaidFrozenVersionIsGranted() throws Exception {
        Owner o = owner();
        Place a = newPlace();
        checkIn(o, a);
        String first = start(o, "QRIS").andExpect(jsonPath("$.unlocked").value(false))
                .andReturn().getResponse().getContentAsString();
        start(o, "QRIS").andExpect(status().isOk());
        assertThat(snapshotRows(o)).as("同集合复用同一行").isEqualTo(1);

        checkIn(o, newPlace()); // 付款期间盖了新章
        start(o, "QRIS").andExpect(status().isOk());
        assertThat(snapshotRows(o)).as("新集合新快照，两行并存").isEqualTo(2);

        String intent = json.readTree(first).path("payment").path("token").asText();
        paymentIntents.applyCallback(new PaymentCallback(intent, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID,
                Map.of()));
        assertThat(currentUnlocked(o)).as("按冻结内容发放：买到的是一章版，当前两章仍带水印").isFalse();
        String token = json.readTree(first).get("snapshotToken").asText();
        mvc.perform(get(SNAPSHOTS).header("Authorization", userBearer(o.user().getId())))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].snapshotToken").value(token))
                .andExpect(jsonPath("$.items[0].stampCount").value(1));
        mvc.perform(get(SNAPSHOTS + "/" + token).header("Authorization", userBearer(o.user().getId())))
                .andExpect(jsonPath("$.stamps.length()").value(1))
                .andExpect(jsonPath("$.stamps[0].placeToken").value(a.getPublicToken()))
                .andExpect(jsonPath("$.stamps[0].placeId").doesNotExist())
                .andExpect(jsonPath("$.passportNo").isNotEmpty());
        // 未付的第二行不出现在列表、不能回看。
        String unpaid = jdbc.queryForObject(
                "SELECT public_token FROM passport_snapshots WHERE pet_profile_id = ? AND paid_at IS NULL",
                String.class, o.pet().getId());
        mvc.perform(get(SNAPSHOTS + "/" + unpaid).header("Authorization", userBearer(o.user().getId())))
                .andExpect(status().isNotFound());
        Owner stranger = owner();
        mvc.perform(get(SNAPSHOTS + "/" + token).header("Authorization", userBearer(stranger.user().getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void realMergeKeepsBoughtVersionCurrent() throws Exception {
        Owner o = owner();
        Place a = newPlace();
        Place b = newPlace();
        checkIn(o, a);
        checkIn(o, b);
        start(o, "PAWCOIN").andExpect(jsonPath("$.unlocked").value(true));
        String email = "snap-" + SEQ.incrementAndGet() + "@tailtopia.test";
        adminAccounts.createAccount(email, "快照", AdminRole.CUSTOM, List.of(), 970000L + SEQ.incrementAndGet());
        long adminId = adminDetails.loadByEmail(email, false).getAdminAccountId();
        mergeService.merge(b.getId(), a.getId(), adminId); // B 并入 A → 当前章 {A}
        assertThat(currentUnlocked(o)).isTrue();
    }

    @Test
    void petDeletionRemovesSnapshotsKeepsPurchaseAndLatePaymentIsOrphan() throws Exception {
        Owner o = owner();
        checkIn(o, newPlace());
        String body = start(o, "QRIS").andReturn().getResponse().getContentAsString();
        String intent = json.readTree(body).path("payment").path("token").asText();
        new TransactionTemplate(txManager).executeWithoutResult(s -> profileDeletion.deleteByUserId(o.user().getId()));
        assertThat(snapshotRows(o)).isZero();
        paymentIntents.applyCallback(new PaymentCallback(intent, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID,
                Map.of()));
        assertThat(jdbc.queryForObject("SELECT status FROM keepsake_purchases WHERE user_id = ? AND sku = 'PASSPORT_SNAP'",
                String.class, o.user().getId())).isEqualTo("ORPHAN_PAID");
        assertThat(jdbc.queryForObject("SELECT pet_profile_id IS NULL FROM keepsake_purchases WHERE user_id = ?",
                Boolean.class, o.user().getId())).isTrue();
    }
}
