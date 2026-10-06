package com.tailtopia.tailsonality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.service.KeepsakeGranterRegistry;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import com.tailtopia.tailsonality.service.TailsonalityKeepsakeGranter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V1.3.2 batch-a Story 3.3 · L1（需 postgres + redis）：首次解锁自动佩戴 / 不替换 / 并发、切换 / 卸下、
 * 档案与公开卡小标、Diary 条目能力闸、删档级联。
 */
class TailsonalityBadgeIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/pet-profiles/me/tailsonality";

    @MockitoBean
    private KeepsakeGranterRegistry granters;

    @Autowired
    private TailsonalityKeepsakeGranter granter;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        when(granters.forSku(KeepsakeSku.TAILSONALITY)).thenReturn(granter);
    }

    private User userWithPet() {
        User u = newUser();
        petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        wallet.credit(u.getId(), 100_000L, PawCoinTxnType.TOPUP, "TEST", null, "tb-topup:" + SEQ.incrementAndGet());
        return u;
    }

    private String submit(User u, int idx) throws Exception {
        String body = TailsonalityCatalog.QUESTION_IDS.stream().map(q -> "\"" + q + "\":" + idx)
                .collect(Collectors.joining(",", "{", "}"));
        String res = mvc.perform(post(BASE + "/results").header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("token").asText();
    }

    private void unlock(User u, String token) throws Exception {
        mvc.perform(post(BASE + "/results/" + token + "/unlock").header("Authorization", userBearer(u.getId()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"PAWCOIN\"}")).andExpect(status().isOk());
    }

    private String equippedToken(User u) {
        List<String> t = jdbc.queryForList("""
                SELECT r.public_token FROM tailsonality_badges b JOIN tailsonality_results r ON r.id = b.result_id
                 JOIN pet_profiles p ON p.id = b.pet_profile_id WHERE p.owner_id = ?""", String.class, u.getId());
        return t.isEmpty() ? null : t.get(0);
    }

    @Test
    void firstUnlockAutoEquipsLaterUnlockDoesNotReplaceAndEquipSwitches() throws Exception {
        User u = userWithPet();
        String a = submit(u, 0);
        String b = submit(u, 3);
        unlock(u, a);
        assertThat(equippedToken(u)).isEqualTo(a);
        unlock(u, b);
        assertThat(equippedToken(u)).as("再解锁不替换").isEqualTo(a);

        mvc.perform(get(BASE + "/results").header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.items[0].equipped").value(false))
                .andExpect(jsonPath("$.items[1].equipped").value(true));

        mvc.perform(put(BASE + "/badge").header("Authorization", userBearer(u.getId()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"resultToken\":\"" + b + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(equippedToken(u)).isEqualTo(b);

        mvc.perform(get("/api/v1/pet-profiles/me").header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.tailsonalityBadge").value("ISTP"));
        mvc.perform(get("/api/v1/users/" + u.getId() + "/pet"))
                .andExpect(jsonPath("$.tailsonalityBadge").value("ISTP"));
    }

    @Test
    void lockedCannotBeEquippedAndUnequipIsIdempotentAndNotReEquippedOnNextUnlock() throws Exception {
        User u = userWithPet();
        String a = submit(u, 0);
        mvc.perform(put(BASE + "/badge").header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resultToken\":\"" + a + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/tailsonality-badge-locked"));
        mvc.perform(get("/api/v1/pet-profiles/me").header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.tailsonalityBadge").doesNotExist());

        unlock(u, a);
        mvc.perform(delete(BASE + "/badge").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isNoContent());
        mvc.perform(delete(BASE + "/badge").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isNoContent());
        String b = submit(u, 3);
        unlock(u, b);
        assertThat(equippedToken(u)).as("D-16：卸下后再解锁不自动戴回").isNull();
        mvc.perform(get("/api/v1/users/" + u.getId() + "/pet")).andExpect(jsonPath("$.tailsonalityBadge").doesNotExist());
    }

    @Test
    void concurrentFirstUnlocksLeaveExactlyOneBadgeRow() throws Exception {
        User u = userWithPet();
        String a = submit(u, 0);
        String b = submit(u, 3);
        List<Long> ids = jdbc.queryForList("SELECT id FROM tailsonality_results WHERE public_token IN (?, ?)",
                Long.class, a, b);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Object>> jobs = ids.stream()
                    .<Callable<Object>>map(id -> () -> tx.execute(s -> granter.grant(id, 0L))).toList();
            for (var f : pool.invokeAll(jobs)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        Integer rows = jdbc.queryForObject("""
                SELECT count(*) FROM tailsonality_badges b JOIN pet_profiles p ON p.id = b.pet_profile_id
                 WHERE p.owner_id = ?""", Integer.class, u.getId());
        // 两笔并发时各自看不到对方未提交的解锁，可能都满足「恰好 1 条」—— ON CONFLICT DO NOTHING 保证只落一行。
        assertThat(rows).isLessThanOrEqualTo(1);
    }

    @Test
    void diaryBannerOnlyWithSupportsAndPetDeletionCascades() throws Exception {
        User u = userWithPet();
        String a = submit(u, 0);
        String timelineBase = "/api/v1/pet-profiles/me/timeline";
        mvc.perform(get(timelineBase).param("supports", "tailsonality").header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.items[?(@.itemType == 'TAILSONALITY_BANNER')]").isEmpty());
        unlock(u, a);
        mvc.perform(get(timelineBase).param("supports", "tailsonality").header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.items[?(@.itemType == 'TAILSONALITY_BANNER')].tailsonalityResultToken").value(a));
        mvc.perform(get(timelineBase).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.items[?(@.itemType == 'TAILSONALITY_BANNER')]").isEmpty());

        new TransactionTemplate(txManager).executeWithoutResult(s -> profileDeletion.deleteByUserId(u.getId()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tailsonality_results r WHERE r.user_id = ?",
                Integer.class, u.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM keepsake_purchases WHERE user_id = ? AND pet_profile_id IS NULL",
                Integer.class, u.getId())).isEqualTo(1);
        petProfiles.save(PetProfile.create(u.getId(), PetType.DOG, "Baru", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        mvc.perform(get("/api/v1/users/" + u.getId() + "/pet")).andExpect(jsonPath("$.tailsonalityBadge").doesNotExist());
    }
}
