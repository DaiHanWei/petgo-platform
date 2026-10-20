package com.tailtopia.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.domain.DeletionStatus;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 multi-pet Story 1.3 · AC2 · L1（需 postgres + redis）：两只宠物的账号注销成功——全部宠物删净，
 * 不被 {@code fk_pet_profiles_owner}（V3，无级联）挡住；身份证按账号打标。
 */
class AccountDeletionMultiPetIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AccountDeletionRepository deletions;
    @Autowired
    private AccountDeletionService accountDeletion;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private IdCardRepository idCards;

    @Test
    void multiPetAccountDeletionRemovesAllPets() {
        boolean dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
        List<Long> pets = new ArrayList<>();
        try {
            User u = newUser();
            long uid = u.getId();
            Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
            long a = MultiPetDbFixture.insertPet(jdbc, uid, "Aaa", "CAT", t0);
            long b = MultiPetDbFixture.insertPet(jdbc, uid, "Bbb", "DOG", t0.plusSeconds(3600));
            pets.add(a);
            pets.add(b);
            posts.save(ContentPost.publish(uid, ContentType.GROWTH_MOMENT, a, "A", List.of()));
            posts.save(ContentPost.publish(uid, ContentType.GROWTH_MOMENT, b, "B", List.of()));
            idCards.save(IdCard.snapshot(uid, SEQ.incrementAndGet() + 920000000L,
                    "卡", "DOG", null, null, null, null, "UNKNOWN", null, null, null, null,
                    null, null, "KTP", null, null, null));

            AccountDeletion d = deletions.save(AccountDeletion.request(uid));
            accountDeletion.execute(d.getId());

            assertThat(deletions.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DeletionStatus.DONE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_profiles WHERE owner_id = ?", Long.class, uid))
                    .isZero();
            // 身份证：随账号级联删除，或仍在但已按账号打标——两种都不允许出现「未打标」的卡。
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM id_cards WHERE user_id = ? AND profile_deleted_at IS NULL", Long.class, uid))
                    .isZero();
        } finally {
            MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, pets);
        }
    }
}
