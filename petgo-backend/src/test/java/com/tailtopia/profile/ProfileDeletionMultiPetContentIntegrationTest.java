package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.domain.DeletionStatus;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPetRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentPetQuery;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.support.ContentPetsTestFixture;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 multi-pet Story 3.1 · AC6 · L1（需 postgres + redis；云端不跑，待本地验收）：删除宠物只解除该宠物。
 *
 * <p>帖子关联 A、B，删 A → 只解除 A，{@code pet_id} 顺延为 B、B 的时间线照常；只关联 A 的帖 {@code pet_id} 置空、内容保留，
 * 仍在「我的发布」/ Feed；再注销账号不被外键挡住。
 */
class ProfileDeletionMultiPetContentIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ContentPetRepository contentPets;
    @Autowired
    private ContentPetQuery contentPetQuery;
    @Autowired
    private ContentService contentService;
    @Autowired
    private AccountDeletionRepository deletions;
    @Autowired
    private AccountDeletionService accountDeletion;

    @Test
    void deletingOnePetOnlyUnlinksThatPet() {
        boolean dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
        List<Long> pets = new ArrayList<>();
        try {
            User u = newUser();
            long uid = u.getId();
            Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
            long a = MultiPetDbFixture.insertPet(jdbc, uid, "Aaa", "CAT", t0);
            long b = MultiPetDbFixture.insertPet(jdbc, uid, "Bbb", "DOG", t0.plusSeconds(3600));
            long c = MultiPetDbFixture.insertPet(jdbc, uid, "Ccc", "CAT", t0.plusSeconds(7200));
            pets.add(a);
            pets.add(b);
            pets.add(c);
            LocalDate day = LocalDate.of(2026, 5, 1);
            // 双宠帖：A（第一只）+ C；另一条三宠帖 A + B + C，删 A 后应顺延为 B（剩余里最早）。
            ContentPost twoPets = ContentPetsTestFixture.link(contentPets, posts.save(
                    ContentPost.publish(uid, ContentType.GROWTH_MOMENT, a, "A+C", List.of(), day)));
            ContentPetsTestFixture.linkPet(jdbc, twoPets.getId(), c);
            ContentPost threePets = ContentPetsTestFixture.link(contentPets, posts.save(
                    ContentPost.publish(uid, ContentType.GROWTH_MOMENT, a, "A+B+C", List.of(), day)));
            ContentPetsTestFixture.linkPet(jdbc, threePets.getId(), c);
            ContentPetsTestFixture.linkPet(jdbc, threePets.getId(), b);
            ContentPost onlyA = ContentPetsTestFixture.link(contentPets, posts.save(
                    ContentPost.publish(uid, ContentType.GROWTH_MOMENT, a, "A saja", List.of(), day)));

            profileDeletion.deleteByPetId(uid, a);

            assertThat(posts.findById(twoPets.getId()).orElseThrow().getPetId()).isEqualTo(c);
            assertThat(posts.findById(threePets.getId()).orElseThrow().getPetId()).isEqualTo(b);
            assertThat(contentPetQuery.petsOf(threePets.getId())).containsExactly(b, c);
            // 只关联 A 的帖：pet_id 置空、内容保留、不再出现在任何 Diary Tab。
            ContentPost orphan = posts.findById(onlyA.getId()).orElseThrow();
            assertThat(orphan.getPetId()).isNull();
            assertThat(orphan.getDeletedAt()).isNull();
            assertThat(contentPetQuery.petsOf(onlyA.getId())).isEmpty();
            assertThat(contentService.findGrowthMomentsOnDate(uid, b, day)).extracting(g -> g.id())
                    .containsExactly(threePets.getId());
            assertThat(contentService.findGrowthMomentsOnDate(uid, c, day)).extracting(g -> g.id())
                    .containsExactlyInAnyOrder(twoPets.getId(), threePets.getId());
            // 作者「我的发布」照常有它。
            assertThat(posts.findMyPosts(uid, false, null, null, true, PageRequest.of(0, 50)))
                    .extracting(ContentPost::getId).contains(onlyA.getId(), twoPets.getId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM content_pets WHERE pet_profile_id = ?",
                    Long.class, a)).isZero();

            // 再注销账号：全部宠物删净、帖子全部无关联，不被外键挡住。
            AccountDeletion d = deletions.save(AccountDeletion.request(uid));
            accountDeletion.execute(d.getId());
            assertThat(deletions.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DeletionStatus.DONE);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM content_pets cp JOIN content_posts p ON p.id = cp.post_id"
                            + " WHERE p.author_id = ?", Long.class, uid)).isZero();
        } finally {
            MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, pets);
        }
    }
}
