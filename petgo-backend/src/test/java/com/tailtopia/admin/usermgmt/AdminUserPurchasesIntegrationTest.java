package com.tailtopia.admin.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.usermgmt.dto.UserPurchasesView;
import com.tailtopia.admin.usermgmt.service.AdminUserPurchasesQuery;
import com.tailtopia.auth.domain.User;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;

/**
 * L1：用户详情抽屉第六页签「已购解锁」（V1.3.2 后台 AB-23 · 决策 D-24~D-26）。
 *
 * <p>钉四件事：① 读业务表解锁状态 —— PawCoin 付的也在；② KTP 解锁时间是真实付款时刻（不是 QRIS 下单时刻）；
 * ③ 快照只给已付款的编号、连续；④ 合并作废的登机牌照样列出并标「已合并」。外加页签与 600px 抽屉的渲染、只读无按钮。
 */
class AdminUserPurchasesIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminUserPurchasesQuery query;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PlaceRepository places;
    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant T0 = Instant.parse("2026-09-10T03:00:00Z");

    private PetProfile pet(User u) {
        return petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Mochi", null, "Anggora", null, null,
                "TOK-" + SEQ.incrementAndGet()));
    }

    private Place place(String name) {
        return places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""),
                name, PlaceType.PARK, List.of(PlaceTag.PETS_ALLOWED_INSIDE),
                -6.2351, 106.8101, "Jl. Test", null, 1L, "Jakarta"));
    }

    private static String tok() {
        return ("t" + SEQ.incrementAndGet() + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx").substring(0, 32);
    }

    private long idCard(long userId, long serial, boolean unlocked) {
        return jdbc.queryForObject("INSERT INTO id_cards (user_id, serial_id, name, hd_unlocked) VALUES (?, ?, 'Mochi', ?) RETURNING id",
                Long.class, userId, serial, unlocked);
    }

    /**
     * V1.3.4 ktp：指定卡种 / 配色 / 身份码的已解锁卡（CHECK：仅学生卡带配色）。
     * 身份码用 TX 前缀：发号器只出 TT 号，直插的号不会与别的用例经接口发出的号撞（绕过归属表登记，否则污染
     * CardNumberOwnershipIntegrationTest 的全库护栏）。
     */
    private long typedCard(long userId, long serial, String cardType, String cardVariant, String cardNo) {
        return jdbc.queryForObject("INSERT INTO id_cards (user_id, serial_id, name, hd_unlocked, card_type, card_variant, card_no) "
                        + "VALUES (?, ?, 'Mochi', true, ?, ?, ?) RETURNING id",
                Long.class, userId, serial, cardType, cardVariant, cardNo);
    }

    private void hdPurchase(long userId, long cardId, String channel, Long intentId, Instant at) {
        jdbc.update("INSERT INTO id_card_hd_purchases (user_id, card_id, pay_channel, payment_intent_id, purchased_at) VALUES (?, ?, ?, ?, ?)",
                userId, cardId, channel, intentId, Timestamp.from(at));
    }

    private PaymentIntent intent(long userId, boolean paid, Instant updatedAt) {
        PaymentIntent p = PaymentIntent.create(userId, PaymentPurpose.ID_HD, PayChannel.QRIS, 1000L, "IDR", tok());
        if (paid) {
            p.markPaid(Map.of());
        }
        p = intents.saveAndFlush(p);
        jdbc.update("UPDATE payment_intents SET updated_at = ? WHERE id = ?", Timestamp.from(updatedAt), p.getId());
        return p;
    }

    private void snapshot(long petId, int stamps, Instant paidAt) {
        jdbc.update("INSERT INTO passport_snapshots (public_token, pet_profile_id, stamps, stamp_count, place_set_hash, paid_at) "
                        + "VALUES (?, ?, '[]'::jsonb, ?, ?, ?)",
                tok(), petId, stamps, "0".repeat(64), paidAt == null ? null : Timestamp.from(paidAt));
    }

    private void boarding(long petId, long placeId, Instant unlockedAt, Instant supersededAt) {
        jdbc.update("INSERT INTO boarding_pass_unlocks (public_token, pet_profile_id, place_id, unlocked_at, superseded_at) VALUES (?, ?, ?, ?, ?)",
                tok(), petId, placeId, unlockedAt == null ? null : Timestamp.from(unlockedAt),
                supersededAt == null ? null : Timestamp.from(supersededAt));
    }

    private void tailsonality(long userId, long petId, String type, String energy, Instant unlockedAt) {
        jdbc.update("INSERT INTO tailsonality_results (public_token, pet_profile_id, user_id, question_set, answers, type_code, energy, "
                        + "content_version, unlocked_at) VALUES (?, ?, ?, 'CAT', '[]'::jsonb, ?, ?, 1, ?)",
                tok(), petId, userId, type, energy, unlockedAt == null ? null : Timestamp.from(unlockedAt));
    }

    @Test
    void ktpUnlockTimeIsTheRealPaymentMomentForBothChannels() {
        User u = newUser();
        long serialBase = 7_000_000L + SEQ.incrementAndGet() % 1_000_000;
        // QRIS：下单时刻 T0 插购买行，到账在 T0+2h —— 解锁时间必须是到账时刻，不是下单时刻
        long qrisCard = idCard(u.getId(), serialBase, true);
        PaymentIntent paid = intent(u.getId(), true, T0.plus(2, ChronoUnit.HOURS));
        hdPurchase(u.getId(), qrisCard, "QRIS", paid.getId(), T0);
        // PawCoin：当场扣币，购买记录时刻即付款时刻；同卡还挂着一笔放弃的 QRIS 单（PENDING，不算）
        long coinCard = idCard(u.getId(), serialBase + 1, true);
        PaymentIntent abandoned = intent(u.getId(), false, T0);
        hdPurchase(u.getId(), coinCard, "QRIS", abandoned.getId(), T0);
        hdPurchase(u.getId(), coinCard, "PAWCOIN", null, T0.plus(1, ChronoUnit.DAYS));
        // 未解锁的卡不出现
        idCard(u.getId(), serialBase + 2, false);

        UserPurchasesView v = query.forUser(u.getId());
        assertThat(v.ktpCards()).extracting(UserPurchasesView.KtpCard::serialId)
                .containsExactly(serialBase + 1, serialBase);
        assertThat(v.ktpCards().get(1).unlockedAt()).as("QRIS = 到账时刻").isEqualTo(T0.plus(2, ChronoUnit.HOURS));
        assertThat(v.ktpCards().get(0).unlockedAt()).as("PawCoin = 扣币时刻，放弃的 QRIS 单不算")
                .isEqualTo(T0.plus(1, ChronoUnit.DAYS));
    }

    @Test
    void snapshotsBoardingPassesAndTailsonalityFollowTheAgreedRules() {
        User u = newUser();
        PetProfile p = pet(u);
        snapshot(p.getId(), 5, T0);
        snapshot(p.getId(), 8, null); // 未付款：不列、不占号
        snapshot(p.getId(), 12, T0.plus(3, ChronoUnit.DAYS));

        Place a = place("Kopi Kalyan");
        Place b = place("Taman Suropati");
        Place c = place("Belum Dibuka");
        boarding(p.getId(), a.getId(), T0.plus(1, ChronoUnit.DAYS), null);
        boarding(p.getId(), b.getId(), T0.plus(2, ChronoUnit.DAYS), T0.plus(4, ChronoUnit.DAYS)); // 合并作废、已付款
        boarding(p.getId(), c.getId(), null, null); // 未解锁

        tailsonality(u.getId(), p.getId(), "ENTJ", "H", T0.plus(5, ChronoUnit.DAYS));
        tailsonality(u.getId(), p.getId(), "INFP", "L", null);

        UserPurchasesView v = query.forUser(u.getId());
        assertThat(v.snapshots()).extracting(UserPurchasesView.PassportSnapshot::version).as("新的在前，号连续")
                .containsExactly(2, 1);
        assertThat(v.snapshots()).extracting(UserPurchasesView.PassportSnapshot::stampCount).containsExactly(12, 5);
        assertThat(v.boardingPasses()).extracting(UserPurchasesView.BoardingPass::placeName)
                .containsExactly("Taman Suropati", "Kopi Kalyan");
        assertThat(v.boardingPasses().get(0).superseded()).isTrue();
        assertThat(v.hasSupersededBoardingPass()).isTrue();
        assertThat(v.tailsonality()).extracting(UserPurchasesView.TailsonalityResult::roleCode).containsExactly("ENTJ-H");
        assertThat(v.ktpCards()).isEmpty();
    }

    @Test
    void drawerRendersTheSixthTabReadOnlyAndTheEmptyState() throws Exception {
        User buyer = newUser();
        PetProfile p = pet(buyer);
        tailsonality(buyer.getId(), p.getId(), "ESTP", "L", T0);
        User nobody = newUser();

        String html = drawer(buyer.getId());
        assertThat(html).contains("data-utab=\"purchases\"").contains("已购解锁").contains("ESTP-L")
                .contains("data-utab-panel=\"purchases\"");
        String panel = html.substring(html.indexOf("data-utab-panel=\"purchases\""), html.indexOf("id=\"user-drawer-err\""));
        assertThat(panel).as("只读：不得出现任何按钮 / 表单").doesNotContain("<button").doesNotContain("<form");

        assertThat(drawer(nobody.getId())).contains("该用户还没有任何已购解锁");
    }

    /**
     * V1.3.4 ktp D-6：购买记录按「{卡种名} · No. {编号} · {宠物名}」显示 —— 护照显示「护照」而不是「KTP」，
     * 学生卡显示配色名，V95 前无身份码的老卡显示流水号补零。
     */
    @Test
    void ktpRowsShowCardStyleNameAndNumber() throws Exception {
        User u = newUser();
        long base = 8_000_000L + SEQ.incrementAndGet() % 1_000_000;
        long passport = typedCard(u.getId(), base, "PASSPORT", null, "TX600324020001");
        hdPurchase(u.getId(), passport, "PAWCOIN", null, T0);
        long student = typedCard(u.getId(), base + 1, "STUDENT", "CERIA", "TX600324020002");
        hdPurchase(u.getId(), student, "PAWCOIN", null, T0.plus(1, ChronoUnit.HOURS));
        long sim = typedCard(u.getId(), base + 2, "SIM", null, "TX600324020003");
        hdPurchase(u.getId(), sim, "PAWCOIN", null, T0.plus(2, ChronoUnit.HOURS));
        // V95 前老卡：无身份码，流水号补零到 4 位（如 42 → 0042）。流水号取库里空闲的 1–999（保留补零断言）——
        // 写死 42 会和同库其他测试建的卡撞 uq_id_cards_serial（CI 全量跑时稳定复现）。
        long legacySerial = jdbc.queryForObject("SELECT s FROM generate_series(1, 999) s "
                + "WHERE NOT EXISTS (SELECT 1 FROM id_cards c WHERE c.serial_id = s) LIMIT 1", Long.class);
        String legacyNo = String.format("%04d", legacySerial);
        long legacy = jdbc.queryForObject("INSERT INTO id_cards (user_id, serial_id, name, hd_unlocked) "
                + "VALUES (?, ?, 'Mochi', true) RETURNING id", Long.class, u.getId(), legacySerial);
        hdPurchase(u.getId(), legacy, "PAWCOIN", null, T0.plus(3, ChronoUnit.HOURS));

        UserPurchasesView v = query.forUser(u.getId());
        assertThat(v.ktpCards()).extracting(UserPurchasesView.KtpCard::styleKey)
                .containsExactly("KTP", "SIM", "STUDENT.CERIA", "PASSPORT");
        assertThat(v.ktpCards()).extracting(UserPurchasesView.KtpCard::displayNo)
                .containsExactly(legacyNo, "TX600324020003", "TX600324020002", "TX600324020001");

        String html = drawer(u.getId());
        assertThat(html).contains("护照 · No. TX600324020001 · Mochi")
                .contains("学生卡 Ceria · No. TX600324020002 · Mochi")
                .contains("SIM 驾照 · No. TX600324020003 · Mochi")
                .contains("KTP · No. " + legacyNo + " · Mochi")
                .doesNotContain("KTP #");
    }

    private String drawer(long userId) throws Exception {
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "purchases-" + SEQ.incrementAndGet() + "@tailtopia.test", "已购解锁测试员", "{bcrypt}x"));
        AdminUserDetails d = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(),
                AdminAccountType.SUPER_ADMIN);
        return mvc.perform(get("/admin/users/" + userId + "/drawer").param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(new TestingAuthenticationToken(d, null, new ArrayList<>(d.getAuthorities())))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
