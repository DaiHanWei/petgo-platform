package com.tailtopia.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.event.ContentCollectedEvent;
import com.tailtopia.content.event.ContentUncollectedEvent;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.gift.event.GiftSentEvent;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V1.4.0 ① Story 1.1 · AC9 / AC11 / AC15 / AC16（L1，需 Docker postgres + redis）：经真实接口与事件的端到端入账。
 * 异步监听用短轮询等待（照 {@code WarmReplyEnqueueIntegrationTest}）。
 */
class GrowthActivityIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate tx;

    private long sum(long userId, String rule) {
        return jdbc.queryForObject("SELECT COALESCE(sum(delta), 0) FROM growth_ledger WHERE user_id = ? AND rule_code = ?",
                Long.class, userId, rule);
    }

    private int count(long userId, String rule) {
        return jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ? AND rule_code = ?",
                Integer.class, userId, rule);
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        Thread.sleep(150);
    }

    private long postBy(long authorId) {
        return posts.saveAndFlush(ContentPost.publish(authorId, ContentType.DAILY, null, "x", null)).getId();
    }

    @Test
    void likeUnlikeLikeEndsAtPlusOneMinusOne() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = postBy(b.getId());

        mvc.perform(post("/api/v1/content-posts/" + postId + "/like").header("Authorization", userBearer(a.getId())))
                .andExpect(status().isOk());
        await(() -> count(b.getId(), "LIKE_RECEIVED") == 1);
        mvc.perform(delete("/api/v1/content-posts/" + postId + "/like").header("Authorization", userBearer(a.getId())))
                .andExpect(status().isOk());
        await(() -> count(b.getId(), "LIKE_RECEIVED") == 2);
        mvc.perform(post("/api/v1/content-posts/" + postId + "/like").header("Authorization", userBearer(a.getId())))
                .andExpect(status().isOk());
        await(() -> false);

        assertThat(count(a.getId(), "LIKE_GIVEN")).isEqualTo(2);
        assertThat(sum(a.getId(), "LIKE_GIVEN")).isZero();
        assertThat(count(b.getId(), "LIKE_RECEIVED")).isEqualTo(2);
        assertThat(sum(b.getId(), "LIKE_RECEIVED")).isZero();
    }

    @Test
    void collectUncollectAndSelfCollect() throws Exception {
        User author = newUser();
        User fan = newUser();
        long postId = postBy(author.getId());

        tx.executeWithoutResult(s -> events.publishEvent(new ContentCollectedEvent(postId, fan.getId(), author.getId())));
        await(() -> count(author.getId(), "COLLECT_RECEIVED") == 1);
        tx.executeWithoutResult(s -> events.publishEvent(new ContentUncollectedEvent(postId, fan.getId(), author.getId())));
        await(() -> count(author.getId(), "COLLECT_RECEIVED") == 2);
        tx.executeWithoutResult(s -> events.publishEvent(new ContentCollectedEvent(postId, fan.getId(), author.getId())));
        tx.executeWithoutResult(s -> events.publishEvent(new ContentCollectedEvent(postId, author.getId(), author.getId())));
        await(() -> false);

        assertThat(sum(author.getId(), "COLLECT_RECEIVED")).isZero();
        assertThat(count(author.getId(), "COLLECT_RECEIVED")).isEqualTo(2);
    }

    @Test
    void giftAccruesBothSidesByAmount() throws Exception {
        User sender = newUser();
        User owner = newUser();
        long order = 9_000_000L + sender.getId();

        tx.executeWithoutResult(s -> events.publishEvent(new GiftSentEvent(order, sender.getId(), owner.getId(), 1L, 12_500)));
        await(() -> count(owner.getId(), "GIFT_RECEIVED") == 1);

        assertThat(sum(sender.getId(), "GIFT_SENT")).isEqualTo(63);
        assertThat(sum(owner.getId(), "GIFT_RECEIVED")).isEqualTo(50);
    }

    @Test
    void virtualAuthorGetsNothingButRealLikerDoes() throws Exception {
        User liker = newUser();
        User virtual = users.saveAndFlush(User.newVirtual("virtual:it-" + SEQ.incrementAndGet(), "v", null,
                adminActorId()));
        long postId = postBy(virtual.getId());

        mvc.perform(post("/api/v1/content-posts/" + postId + "/like").header("Authorization", userBearer(liker.getId())))
                .andExpect(status().isOk());
        await(() -> count(liker.getId(), "LIKE_GIVEN") == 1);

        assertThat(count(liker.getId(), "LIKE_GIVEN")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ?", Integer.class,
                virtual.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_accounts WHERE user_id = ?", Integer.class,
                virtual.getId())).isZero();
    }
}
