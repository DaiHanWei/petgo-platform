package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.event.GrowthLevelChangedEvent;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：V1.4.0 multi-pet Story 2.1 · AC1 / AC3 / AC4 / AC5 / AC6 / AC8 —— 迁移 1604 落库形态、AC13c 四只降级 + 改选、
 * 改选端点鉴权与 ProblemDetail、两线程并发 reconcile 串行、删除后只读恢复、等级事件触发恢复（AC9 降级不转只读）。
 *
 * <p>名额经真实 {@link PetSlotService#quotaOf}，等级用 {@link MockitoBean} 的 {@link GrowthQueryService} 设定
 * （Lv5 = 名额 2；Lv10 = 3）。宠物用 {@link MultiPetDbFixture#insertPet} 直插（status 走列默认 ACTIVE）。
 */
class PetSlotReconcileIntegrationTest extends ApiIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @MockitoBean
    private GrowthQueryService growth;

    @Autowired
    private PetSlotStateService state;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    private void level(long userId, int lv) {
        when(growth.getLevel(userId)).thenReturn(GrowthLevel.of(lv, GrowthTier.NEWBIE));
    }

    private List<Long> fourPets(long owner) {
        List<Long> ids = new ArrayList<>();
        int i = 0;
        for (String n : List.of("A", "B", "C", "D")) {
            ids.add(MultiPetDbFixture.insertPet(jdbc, owner, n, "CAT", T0.plusSeconds(60L * i++)));
        }
        return ids;
    }

    private String statusOf(long petId) {
        return jdbc.queryForObject("SELECT status FROM pet_profiles WHERE id = ?", String.class, petId);
    }

    @Test
    void migrationShape() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'ck_pet_profiles_status'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_pet_profiles_owner_status'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns"
                + " WHERE table_name = 'pet_profiles' AND column_name = 'keep_active'", String.class)).isEqualTo("false");
        User u = newUser();
        long id = MultiPetDbFixture.insertPet(jdbc, u.getId(), "A", "CAT", T0);
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
    }

    /** AC13c：A、B、C、D、名额 2 → C、D 只读；改选 A、D → B、C 只读；列表与默认宠物随之变化。 */
    @Test
    void fourPetsExpiryThenSelection() throws Exception {
        User u = newUser();
        level(u.getId(), 5);
        List<Long> ids = fourPets(u.getId());

        ReconcileResult r = state.reconcile(u.getId(), PetSlotTrigger.RENTAL_EXPIRED);
        assertThat(r.demoted()).extracting(ReconcileResult.PetRef::id).containsExactly(ids.get(2), ids.get(3));
        assertThat(List.of(statusOf(ids.get(0)), statusOf(ids.get(1)))).containsOnly("ACTIVE");
        assertThat(List.of(statusOf(ids.get(2)), statusOf(ids.get(3)))).containsOnly("READ_ONLY");

        mvc.perform(put("/api/v1/pet-profiles/me/active-selection")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"petIds\":[" + ids.get(0) + "," + ids.get(3) + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].keepActive").value(true))
                .andExpect(jsonPath("$[1].status").value("READ_ONLY"))
                .andExpect(jsonPath("$[2].status").value("READ_ONLY"))
                .andExpect(jsonPath("$[3].status").value("ACTIVE"))
                .andExpect(jsonPath("$[3].keepActive").value(true));
    }

    @Test
    void selectionErrors() throws Exception {
        User u = newUser();
        level(u.getId(), 5);
        List<Long> ids = fourPets(u.getId());
        String auth = userBearer(u.getId());
        mvc.perform(put("/api/v1/pet-profiles/me/active-selection").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"petIds\":[" + ids.get(0) + "]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/validation"));
        User other = newUser();
        long foreign = MultiPetDbFixture.insertPet(jdbc, other.getId(), "X", "CAT", T0);
        mvc.perform(put("/api/v1/pet-profiles/me/active-selection").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"petIds\":[" + ids.get(0) + "," + foreign + "]}"))
                .andExpect(status().isNotFound());
        User single = newUser();
        long only = MultiPetDbFixture.insertPet(jdbc, single.getId(), "S", "CAT", T0);
        mvc.perform(put("/api/v1/pet-profiles/me/active-selection")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(single.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"petIds\":[" + only + "]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/pet-selection-not-needed"));
    }

    @Test
    void selectionEndpointRejectsGuestAndVet() throws Exception {
        mvc.perform(put("/api/v1/pet-profiles/me/active-selection")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"petIds\":[]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/pet-profiles/me/active-selection").header(HttpHeaders.AUTHORIZATION, vetBearer(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"petIds\":[]}"))
                .andExpect(status().isForbidden());
    }

    /** AC3.8：同一用户并发两次 reconcile 串行、终态一致。 */
    @Test
    void concurrentReconcileIsSerialized() throws Exception {
        User u = newUser();
        when(growth.getLevel(anyLong())).thenReturn(GrowthLevel.of(5, GrowthTier.NEWBIE));
        List<Long> ids = fourPets(u.getId());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    return state.reconcile(u.getId(), PetSlotTrigger.RENTAL_EXPIRED).demoted().size();
                }));
            }
            start.countDown();
            List<Integer> demoted = new ArrayList<>();
            for (Future<Integer> f : fs) {
                demoted.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(demoted).containsExactlyInAnyOrder(2, 0);
            assertThat(List.of(statusOf(ids.get(2)), statusOf(ids.get(3)))).containsOnly("READ_ONLY");
            assertThat(List.of(statusOf(ids.get(0)), statusOf(ids.get(1)))).containsOnly("ACTIVE");
        } finally {
            pool.shutdownNow();
        }
    }

    /** AC6：名额 2、A/B 正常、C 只读，删 A → C 恢复正常。 */
    @Test
    void deletingActivePetRestoresReadOnly() throws Exception {
        User u = newUser();
        level(u.getId(), 5);
        long a = MultiPetDbFixture.insertPet(jdbc, u.getId(), "A", "CAT", T0);
        MultiPetDbFixture.insertPet(jdbc, u.getId(), "B", "CAT", T0.plusSeconds(60));
        long c = MultiPetDbFixture.insertPet(jdbc, u.getId(), "C", "CAT", T0.plusSeconds(120));
        state.reconcile(u.getId(), PetSlotTrigger.RENTAL_EXPIRED);
        assertThat(statusOf(c)).isEqualTo("READ_ONLY");

        mvc.perform(delete("/api/v1/pet-profiles/me").param("petId", String.valueOf(a))
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().is2xxSuccessful());

        assertThat(statusOf(c)).isEqualTo("ACTIVE");
    }

    /** AC5 / AC9：升级事件 → 只读按序恢复；降级事件 → 不转只读。 */
    @Test
    void levelEventsRestoreButNeverDemote() throws Exception {
        User u = newUser();
        level(u.getId(), 5);
        List<Long> ids = fourPets(u.getId());
        state.reconcile(u.getId(), PetSlotTrigger.RENTAL_EXPIRED);

        level(u.getId(), 10);
        events.publishEvent(new GrowthLevelChangedEvent(u.getId(), 5, 10));
        awaitStatus(ids.get(2), "ACTIVE");
        assertThat(statusOf(ids.get(3))).isEqualTo("READ_ONLY");

        level(u.getId(), 1);
        events.publishEvent(new GrowthLevelChangedEvent(u.getId(), 10, 1));
        Thread.sleep(1000);
        assertThat(List.of(statusOf(ids.get(0)), statusOf(ids.get(1)), statusOf(ids.get(2)))).containsOnly("ACTIVE");

        mvc.perform(get("/api/v1/pet-profiles/me/list").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[3].status").value("READ_ONLY"));
    }

    private void awaitStatus(long petId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(statusOf(petId))) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(statusOf(petId)).isEqualTo(expected);
    }
}
