package com.tailtopia.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.auth.service.AuthAccountDeletionService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：账号注销「就地匿名化」（决策 D1/A）。
 *
 * <p>回归：用户发过帖时，原物理 {@code users.delete()} 撞 {@code content_posts.author_id} 的
 * NOT NULL + RESTRICT 外键 → DataIntegrityViolationException，注销作业 FAILED。改为软删 + 擦 PII 后，
 * user 行保留（google_sub 置墓碑防复登/唯一冲突）、UGC 保留并由 {@link AccountQueryService} 解析为「已注销」。
 */
class AccountDeletionAnonymizeIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AuthAccountDeletionService authDeletion;
    @Autowired
    private UserRepository users;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private AccountQueryService accountQuery;

    @Test
    void deletingUserWithContentAnonymizesInPlaceAndRetainsUgc() {
        User u = newUser();
        long uid = u.getId();
        ContentPost post = posts.save(ContentPost.publish(uid, ContentType.DAILY, null, "我的帖子", List.of()));

        // 修复前：此调用因 content_posts 外键抛 DataIntegrityViolationException。
        authDeletion.deleteByUserId(uid);

        User reloaded = users.findById(uid).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();            // 软删标记
        assertThat(reloaded.getEmail()).isNull();                   // PII 擦除
        assertThat(reloaded.getNickname()).isNull();
        assertThat(reloaded.getDisplayName()).isNull();
        assertThat(reloaded.getAvatarUrl()).isNull();
        assertThat(reloaded.getGoogleSub()).startsWith("deleted:"); // 墓碑：防复登 + 唯一约束
        // UGC 保留
        assertThat(posts.findById(post.getId())).isPresent();
        // 应用层解析为「已注销」
        AuthorView av = accountQuery.findAuthorViews(List.of(uid)).get(uid);
        assertThat(av.deleted()).isTrue();
    }
}
