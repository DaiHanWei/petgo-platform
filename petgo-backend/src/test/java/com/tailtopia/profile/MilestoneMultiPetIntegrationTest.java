package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.event.HealthArchivedEvent;
import com.tailtopia.profile.service.MilestoneAutoCompleteListener;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 multi-pet Story 1.3 · AC5 / AC6 · L1（需 postgres + redis）：里程碑按宠物完成。
 *
 * <p>A 发 Diary 只点亮 A 的「首条 Diary」；B 存档问诊只点亮 B 的 C-S4；里程碑打卡跨宠物（帖子关联 A、用 B 打卡）拒绝。
 * 监听方法是 {@code @Async}：经代理直接调用后轮询完成表（有限过程，5 秒兜底）。
 */
class MilestoneMultiPetIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MilestoneService milestoneService;
    @Autowired
    private MilestoneAutoCompleteListener listener;
    @Autowired
    private ContentPostRepository posts;
    /** V1.4.0 multi-pet Story 3.1：直接造的 Diary 同步写 content_pets（计数 / 打卡校验读关联表）。 */
    @Autowired
    private com.tailtopia.content.repository.ContentPetRepository contentPets;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();
    private long owner;
    private long petA;
    private long petB;
    private String auth;

    @BeforeEach
    void setUpPets() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
        User u = newUser();
        owner = u.getId();
        auth = userBearer(owner);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        petA = MultiPetDbFixture.insertPet(jdbc, owner, "Aaa", "CAT", t0);
        petB = MultiPetDbFixture.insertPet(jdbc, owner, "Bbb", "CAT", t0.plusSeconds(3600));
        extraPets.add(petA);
        extraPets.add(petB);
        milestoneService.assignRoster(petA, PetType.CAT);
        milestoneService.assignRoster(petB, PetType.CAT);
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    private boolean completed(long petId, String code) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM milestone_completions c JOIN pet_milestones m"
                + " ON m.id = c.pet_milestone_id WHERE m.pet_profile_id = ? AND m.code = ?", Long.class, petId, code);
        return n != null && n > 0;
    }

    private void awaitCompleted(long petId, String code) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && !completed(petId, code)) {
            Thread.sleep(100);
        }
    }

    @Test
    void diaryOfALightsOnlyA() throws Exception {
        ContentPost diary = com.tailtopia.content.support.ContentPetsTestFixture.link(contentPets,
                posts.save(ContentPost.publish(owner, ContentType.GROWTH_MOMENT, petA, "A", List.of())));

        listener.onContentPublished(new ContentPublishedEvent(diary.getId(), owner, ContentType.GROWTH_MOMENT,
                petA, 1L, ContentVisibility.PRIVATE, Instant.now()));
        awaitCompleted(petA, "C-S2");

        assertThat(completed(petA, "C-S2")).isTrue();
        assertThat(completed(petB, "C-S2")).isFalse();
    }

    @Test
    void healthArchivedOfBLightsOnlyB() throws Exception {
        listener.onHealthArchived(new HealthArchivedEvent(owner, petB, Instant.now()));
        awaitCompleted(petB, "C-S4");

        assertThat(completed(petB, "C-S4")).isTrue();
        assertThat(completed(petA, "C-S4")).isFalse();
    }

    @Test
    void checkInWithPostOfAnotherPetIsRejected() throws Exception {
        ContentPost diaryOfA = com.tailtopia.content.support.ContentPetsTestFixture.link(contentPets, posts.save(
                ContentPost.publish(owner, ContentType.GROWTH_MOMENT, petA, "A", List.of())));
        String body = "{\"contentId\":" + diaryOfA.getId() + "}";

        mvc.perform(post("/api/v1/pet-profiles/me/milestones/C-S6/check-in")
                        .param("petId", String.valueOf(petB))
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
        assertThat(completed(petB, "C-S6")).isFalse();

        mvc.perform(post("/api/v1/pet-profiles/me/milestones/C-S6/check-in")
                        .param("petId", String.valueOf(petA))
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful());
        assertThat(completed(petA, "C-S6")).isTrue();
    }
}
