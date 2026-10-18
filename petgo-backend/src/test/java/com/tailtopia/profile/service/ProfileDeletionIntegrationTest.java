package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.domain.MilestoneAutoEvent;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.dto.HealthRecordCreateRequest;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：删宠物档案级联（bug 20260702-237 / 决策 F18，需 Docker postgres）。
 *
 * <p>回归：宠物有绑定的 GROWTH_MOMENT 成长帖时，原 {@code petProfiles.delete(pet)} 撞
 * {@code content_posts.pet_id} 的外键 {@code fk_content_posts_pet}（无 ON DELETE）→
 * DataIntegrityViolationException（历史账号注销 deletionId=1 即栽在此，且 237 自助删档案同栽）。
 * 修复：删档案前先把引用该 pet 的 content_posts.pet_id 置 NULL —— 帖子本体保留（UGC 保留），仅解绑。
 */
class ProfileDeletionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IdCardRepository idCards;
    @Autowired
    private MilestoneService milestoneService;
    @Autowired
    private MilestoneCompletionService milestoneCompletion;
    @Autowired
    private HealthRecordService healthRecordService;

    @Test
    void deletingPetWithBoundGrowthPostDetachesNotBlocked() {
        User u = newUser();
        long uid = u.getId();
        long seq = SEQ.incrementAndGet();
        PetProfile pet = petProfiles.save(PetProfile.create(
                uid, PetType.CAT, "Momo", null, null, null, null, "TOK-" + seq));
        long petId = pet.getId();
        // 成长帖绑定该宠物（pet_id 非空）——修复前删档案会被此外键阻断。
        ContentPost growth = posts.save(
                ContentPost.publish(uid, ContentType.GROWTH_MOMENT, petId, "第一次洗澡", List.of()));

        profileDeletion.deleteAllForAccount(uid);

        // 档案已物理删。
        assertThat(petProfiles.findDefault(uid)).isEmpty();
        // 成长帖保留，仅解绑（pet_id 置空），不随宠物删除。
        ContentPost reloaded = posts.findById(growth.getId()).orElseThrow();
        assertThat(reloaded.getPetId()).isNull();
    }

    /**
     * V1.4.0 multi-pet Story 1.3 · AC1.4 / AC1.5：A、B 两只 + 两张身份证，经 {@code DELETE /me?petId=A} 删 A →
     * A 的派生数据删除、A 的 Diary 保留且 pet_id 置空；B 的数据与两张卡不变（profile_deleted_at 仍为 null）。
     * 再删最后一只 B：pet_status 不变，GET /me → 404、GET /me/list → []。
     */
    @Test
    void deletingOnePetLeavesTheOtherPetAndIdCardsUntouched() throws Exception {
        boolean dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
        List<Long> pets = new ArrayList<>();
        List<Long> cards = new ArrayList<>();
        try {
            User u = newUser();
            long uid = u.getId();
            String auth = userBearer(uid);
            Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
            long a = MultiPetDbFixture.insertPet(jdbc, uid, "Aaa", "CAT", t0);
            long b = MultiPetDbFixture.insertPet(jdbc, uid, "Bbb", "DOG", t0.plusSeconds(3600));
            pets.add(a);
            pets.add(b);
            for (long pet : new long[] {a, b}) {
                milestoneService.assignRoster(pet, pet == a ? PetType.CAT : PetType.DOG);
                milestoneCompletion.completeForPet(pet, MilestoneAutoEvent.PROFILE_CREATED,
                        MilestoneCompletionSource.SYSTEM_AUTO);
                healthRecordService.create(uid, pet, new HealthRecordCreateRequest(
                        "VACCINE", null, "Rabies", LocalDate.of(2026, 3, 1), null));
            }
            ContentPost diaryA = posts.save(
                    ContentPost.publish(uid, ContentType.GROWTH_MOMENT, a, "A 的日记", List.of()));
            ContentPost diaryB = posts.save(
                    ContentPost.publish(uid, ContentType.GROWTH_MOMENT, b, "B 的日记", List.of()));
            for (int i = 0; i < 2; i++) {
                IdCard c = idCards.save(IdCard.snapshot(uid, SEQ.incrementAndGet() + 910000000L,
                        "卡" + i, "DOG", null, null, null, null, "UNKNOWN", null, null, null, null,
                        null, null, "KTP", null, null, null));
                cards.add(c.getId());
            }
            String petStatusBefore = jdbc.queryForObject("SELECT pet_status FROM users WHERE id = ?",
                    String.class, uid);

            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/pet-profiles/me").param("petId", String.valueOf(a))
                            .header("Authorization", auth))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());

            assertThat(petProfiles.findById(a)).isEmpty();
            assertThat(petProfiles.findById(b)).isPresent();
            assertThat(count("SELECT count(*) FROM health_records WHERE pet_profile_id = ?", a)).isZero();
            assertThat(count("SELECT count(*) FROM pet_milestones WHERE pet_profile_id = ?", a)).isZero();
            assertThat(count("SELECT count(*) FROM health_records WHERE pet_profile_id = ?", b)).isEqualTo(1L);
            assertThat(count("SELECT count(*) FROM pet_milestones WHERE pet_profile_id = ?", b)).isPositive();
            assertThat(count("SELECT count(*) FROM milestone_completions WHERE pet_milestone_id IN"
                    + " (SELECT id FROM pet_milestones WHERE pet_profile_id = ?)", b)).isPositive();
            assertThat(posts.findById(diaryA.getId()).orElseThrow().getPetId()).isNull();
            assertThat(posts.findById(diaryB.getId()).orElseThrow().getPetId()).isEqualTo(b);
            for (Long cardId : cards) {
                assertThat(idCards.findById(cardId).orElseThrow().getProfileDeletedAt()).isNull();
            }

            // 删最后一只：沿用现状，pet_status 不改；之后 /me 404、/me/list 空。
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/pet-profiles/me").header("Authorization", auth))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
            assertThat(petProfiles.findById(b)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT pet_status FROM users WHERE id = ?", String.class, uid))
                    .isEqualTo(petStatusBefore);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/pet-profiles/me").header("Authorization", auth))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/pet-profiles/me/list").header("Authorization", auth))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.length()").value(0));
        } finally {
            for (Long cardId : cards) {
                idCards.deleteById(cardId);
            }
            MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, pets);
        }
    }

    private long count(String sql, long arg) {
        Long n = jdbc.queryForObject(sql, Long.class, arg);
        return n == null ? 0L : n;
    }
}
