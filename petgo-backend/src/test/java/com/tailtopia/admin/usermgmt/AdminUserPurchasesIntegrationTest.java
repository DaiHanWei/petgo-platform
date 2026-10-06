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
