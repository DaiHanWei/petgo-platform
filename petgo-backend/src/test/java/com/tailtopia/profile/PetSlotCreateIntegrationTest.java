package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.profile.dto.PetProfileCreateRequest;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.profile.slot.PetSlotFullException;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1：V1.4.0 multi-pet Story 1.4 · AC2 / AC4 —— 经真实 {@code POST /pet-profiles} 的名额校验、ProblemDetail 扩展成员、
 * 并发建档按账号串行化。{@link GrowthQueryService} 用 {@link MockitoBean} 设定等级（① 合入前真实实现恒 Lv1）。
 */
class PetSlotCreateIntegrationTest extends ApiIntegrationTest {

    @MockitoBean
    private GrowthQueryService growth;

    @Autowired
    private PetProfileRepository profiles;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void level(long userId, int lv) {
        when(growth.getLevel(userId)).thenReturn(GrowthLevel.of(lv, GrowthTier.NEWBIE));
    }

    private ResultActions create(User owner, String name) throws Exception {
        return mvc.perform(post("/api/v1/pet-profiles")
                .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                .header("X-App-Version", "1.4.0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"%s","petType":"DOG","birthday":"2022-01-01"}
                        """.formatted(name)));
    }

    @Test
    void lv1SecondPetIsSlotFullWithExtensions() throws Exception {
        User owner = newUser();
        level(owner.getId(), 1);
        create(owner, "A").andExpect(status().isCreated());
        create(owner, "B").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/pet-slot-full"))
                .andExpect(jsonPath("$.slotTotal").value(1))
                .andExpect(jsonPath("$.petCount").value(1))
                .andExpect(jsonPath("$.canRent").value(true))
                .andExpect(jsonPath("$.nextLevelSlot").value(5))
                .andExpect(jsonPath("$.unitPrice").value(5000))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void lv5AllowsTwoThenSlotFullWithNextLevel10() throws Exception {
        User owner = newUser();
        level(owner.getId(), 5);
        create(owner, "A").andExpect(status().isCreated());
        create(owner, "B").andExpect(status().isCreated());
        create(owner, "C").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/pet-slot-full"))
                .andExpect(jsonPath("$.nextLevelSlot").value(10));
        assertThat(profiles.countByOwnerId(owner.getId())).isEqualTo(2);
    }

    @Test
    void lv10ThreeThenDowngradeKeepsPetsButBlocksFourth() throws Exception {
        User owner = newUser();
        level(owner.getId(), 10);
        create(owner, "A").andExpect(status().isCreated());
        create(owner, "B").andExpect(status().isCreated());
        create(owner, "C").andExpect(status().isCreated());
        level(owner.getId(), 9);
        create(owner, "D").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/pet-slot-full"));
        mvc.perform(get("/api/v1/pet-profiles/me/list")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void sixthPetIsLimitReached() throws Exception {
        User owner = newUser();
        // 名额最多 3（Lv10），月租在 2.2 才有 → 以 Lv10 建 3 只后直插 2 只凑满 5 只。
        level(owner.getId(), 10);
        for (String n : List.of("A", "B", "C")) {
            create(owner, n).andExpect(status().isCreated());
        }
        for (String n : List.of("D", "E")) {
            com.tailtopia.profile.support.MultiPetDbFixture.insertPet(jdbc, owner.getId(), n, "CAT",
                    java.time.Instant.now());
        }
        create(owner, "F").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/pet-limit-reached"));
    }

    /** AC4.3：名额只剩 1，同一账号两个线程同时建档 → 恰好一只成功，另一个 pet-slot-full。 */
    @Test
    void concurrentCreatesWithOneSlotLeftOnlyOneSucceeds() throws Exception {
        User owner = newUser();
        when(growth.getLevel(anyLong())).thenReturn(GrowthLevel.of(1, GrowthTier.NEWBIE));
        long ownerId = owner.getId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (String name : List.of("X", "Y")) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        profileService.create(ownerId, new PetProfileCreateRequest(
                                null, "CAT", name, null, LocalDate.of(2022, 1, 1), null));
                        return "OK";
                    } catch (PetSlotFullException e) {
                        return "FULL";
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "FULL");
            assertThat(profiles.countByOwnerId(ownerId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
