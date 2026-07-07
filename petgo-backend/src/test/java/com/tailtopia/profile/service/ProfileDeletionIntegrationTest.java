package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

        profileDeletion.deleteByUserId(uid);

        // 档案已物理删。
        assertThat(petProfiles.findByOwnerId(uid)).isEmpty();
        // 成长帖保留，仅解绑（pet_id 置空），不随宠物删除。
        ContentPost reloaded = posts.findById(growth.getId()).orElseThrow();
        assertThat(reloaded.getPetId()).isNull();
    }
}
