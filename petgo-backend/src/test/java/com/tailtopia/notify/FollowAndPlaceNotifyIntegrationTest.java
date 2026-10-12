package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceComment;
import com.tailtopia.place.domain.PlaceCommentAttitude;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlaceCommentRepository;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.place.service.PlaceCommentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：新粉丝与场所评论两种新通知（V1.3.4 follow · Story 3.2 · AC5.2）—— 需 Docker postgres+redis。
 *
 * <p>关注走真实接口 {@code PUT/DELETE /api/v1/users/{token}/follow}；场所评论直接落 {@code UNDER_REVIEW} 行再调
 * {@link PlaceCommentService#approve}（绕开三方审核）。每例新建场所，去抖键 {@code notify:placecommentpush:{placeId}}
 * 天然隔离，<b>不 FLUSH Redis</b>。推送用 {@link MockitoBean} 计数。
 */
class FollowAndPlaceNotifyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private PlaceRepository places;

    @Autowired
    private PlaceCommentRepository placeComments;

    @Autowired
    private PlaceCommentService placeCommentService;

    @Autowired
    private PetProfileRepository petProfiles;

    @MockitoBean
    private NotificationPusher pusher;

    private void follow(User who, User target) throws Exception {
        mvc.perform(put("/api/v1/users/" + target.getPublicToken() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(who.getId())))
                .andExpect(status().isOk());
    }

    private void unfollow(User who, User target) throws Exception {
        mvc.perform(delete("/api/v1/users/" + target.getPublicToken() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(who.getId())))
                .andExpect(status().isOk());
    }

    private List<Map<String, Object>> notificationsOf(User recipient, String type) {
        return jdbc.queryForList("SELECT id, target_ref, is_read FROM notifications "
                + "WHERE recipient_user_id = ? AND type = ? ORDER BY id", recipient.getId(), type);
    }

    private List<Long> actorsOf(long notificationId) {
        return jdbc.queryForList("SELECT actor_user_id FROM notification_actors WHERE notification_id = ?",
                Long.class, notificationId);
    }

    private long badge(User u) {
        String v = redis.opsForValue().get(NotificationService.UNREAD_KEY_PREFIX + u.getId());
        return v == null ? 0 : Long.parseLong(v);
    }

    private Place newPlace(User creator) {
        return places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""),
                "Kopi " + SEQ.incrementAndGet(), PlaceType.CAFE, List.of(PlaceTag.PETS_ALLOWED_INSIDE),
                -6.2351, 106.8101, "Jl. Test", null, creator.getId(), "Jakarta"));
    }

    private long approvedComment(Place p, User author) {
        PlaceComment c = placeComments.save(PlaceComment.createUnderReview(p.getId(), author.getId(), "mantap",
                PlaceCommentAttitude.RECOMMEND));
        placeCommentService.approve(c.getId());
        return c.getId();
    }

    // ===== USER_FOLLOWED =====

    @Test
    void followNotifiesOnceAndUnfollowAndRefollowWithin24hDoNotAddMore() throws Exception {
        User a = newUser();
        User b = newUser();
        long before = badge(b);

        follow(a, b);

        List<Map<String, Object>> rows = notificationsOf(b, "USER_FOLLOWED");
        assertThat(rows).hasSize(1);
        long id = ((Number) rows.get(0).get("id")).longValue();
        assertThat(actorsOf(id)).containsExactly(a.getId());
        assertThat(rows.get(0).get("target_ref")).isEqualTo(String.valueOf(a.getId()));
        assertThat(badge(b) - before).isEqualTo(1);
        verify(pusher, times(1)).pushToUser(eq(b.getId()), anyString(), eq(""), eq("USER_FOLLOWED"), anyString(),
                eq(String.valueOf(a.getId())));

        unfollow(a, b);
        assertThat(notificationsOf(b, "USER_FOLLOWED")).as("取关不发").hasSize(1);
        follow(a, b);
        assertThat(notificationsOf(b, "USER_FOLLOWED")).as("24 小时内再关注不重复").hasSize(1);
    }

    /** D-4：B 拉黑 A 后 A 关注 B → 关注成功、B 粉丝数 +1，但 B 没有通知。 */
    @Test
    void followByUserTheRecipientBlockedSucceedsSilently() throws Exception {
        User a = newUser();
        User b = newUser();
        hideService.block(b.getId(), a.getId());

        follow(a, b);

        assertThat(jdbc.queryForObject("SELECT follower_count FROM users WHERE id = ?", Integer.class, b.getId()))
                .isEqualTo(1);
        assertThat(notificationsOf(b, "USER_FOLLOWED")).isEmpty();
    }

    // ===== PLACE_COMMENTED =====

    @Test
    void placeCommentsMergeWhileUnreadAndPushOnceWithinWindow() {
        User creator = newUser();
        Place p = newPlace(creator);
        User c1 = newUser();
        User c2 = newUser();

        approvedComment(p, c1);
        approvedComment(p, c2);

        List<Map<String, Object>> rows = notificationsOf(creator, "PLACE_COMMENTED");
        assertThat(rows).hasSize(1);
        long id = ((Number) rows.get(0).get("id")).longValue();
        assertThat(actorsOf(id)).containsExactlyInAnyOrder(c1.getId(), c2.getId());
        assertThat(rows.get(0).get("target_ref")).isEqualTo(p.getPublicToken());
        assertThat(jdbc.queryForObject("SELECT comment_id FROM notifications WHERE id = ?", Long.class, id))
                .as("comment_id 留空（place_comments 与 comments id 空间独立）").isNull();
        verify(pusher, times(1)).pushToUser(eq(creator.getId()), anyString(), eq(""), eq("PLACE_COMMENTED"),
                anyString(), eq(p.getPublicToken()));

        jdbc.update("UPDATE notifications SET is_read = true, read_at = now() WHERE id = ?", id);
        approvedComment(p, newUser());
        assertThat(notificationsOf(creator, "PLACE_COMMENTED")).as("已读后新起一条").hasSize(2);
    }

    @Test
    void creatorSelfCommentAndUnapprovedCommentDoNotNotify() {
        User creator = newUser();
        Place p = newPlace(creator);

        approvedComment(p, creator);
        placeComments.save(PlaceComment.createUnderReview(p.getId(), newUser().getId(), "tunggu",
                PlaceCommentAttitude.RECOMMEND)); // 停留 UNDER_REVIEW：不调 approve

        assertThat(notificationsOf(creator, "PLACE_COMMENTED")).isEmpty();
    }

    @Test
    void checkinDoesNotNotifyCreator() throws Exception {
        User creator = newUser();
        Place p = newPlace(creator);
        User visitor = newUser();
        PetProfile pet = petProfiles.save(PetProfile.create(visitor.getId(), PetType.CAT, "Momo", null, null, null,
                null, "TOK-" + SEQ.incrementAndGet()));

        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(visitor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":-6.2351,\"longitude\":106.8101,\"petIds\":[" + pet.getId() + "]}"))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ?",
                Long.class, creator.getId())).isZero();
    }
}
