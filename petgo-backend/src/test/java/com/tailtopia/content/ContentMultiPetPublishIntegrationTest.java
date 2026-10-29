package com.tailtopia.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentPetQuery;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 multi-pet Story 3.1 · AC3 / AC5 / AC8 · L1（需 postgres + redis；云端不跑，待本地验收）。
 *
 * <p>经真实发布接口发 {@code petIds=[A, B]}：落 {@code pet_id = A} + 关联两行；A、B 各自的时间线 / 日历 / 当天 /
 * 统计 / 访客 / 名片最近内容都有这条；Feed 只一条；详情 {@code pets} 两项；只读 → 409；Moment 带 petIds → 无关联；
 * 推荐池两只各计一次、封面两只都可取到。
 */
class ContentMultiPetPublishIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ContentService contentService;
    @Autowired
    private ContentPetQuery contentPetQuery;
    @Autowired
    private ContentPostRepository posts;

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
        petB = MultiPetDbFixture.insertPet(jdbc, owner, "Bbb", "DOG", t0.plusSeconds(3600));
        extraPets.add(petA);
        extraPets.add(petB);
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    private long publish(String body) throws Exception {
        String resp = mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private long publishDiary(String petIdsJson, String text) throws Exception {
        return publish("{\"type\":\"GROWTH_MOMENT\",\"petIds\":" + petIdsJson + ",\"text\":\"" + text
                + "\",\"imageUrls\":[\"https://cdn.test/" + text + ".jpg\"]}");
    }

    private static List<Long> ids(List<GrowthMomentView> views) {
        return views.stream().map(GrowthMomentView::id).toList();
    }

    @Test
    void diaryLinkedToTwoPetsShowsUpForEachPetButOnceInFeed() throws Exception {
        long postId = publishDiary("[" + petB + "," + petA + "]", "berdua");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));

        // 落库：pet_id = 第一只（A 先建档），关联两行按创建顺序。
        assertThat(posts.findById(postId).orElseThrow().getPetId()).isEqualTo(petA);
        assertThat(contentPetQuery.petsOf(postId)).containsExactly(petA, petB);

        for (long pet : new long[] {petA, petB}) {
            // 作者自看：时间线 / 日历 / 当天 / 统计
            assertThat(ids(contentService.findGrowthMomentsBeforeAnchor(owner, pet, today.plusDays(1),
                    Instant.now(), Instant.now().plusSeconds(60), 50))).contains(postId);
            assertThat(ids(contentService.findGrowthMomentsInMonth(owner, pet, today.withDayOfMonth(1),
                    today.withDayOfMonth(today.lengthOfMonth())))).contains(postId);
            assertThat(ids(contentService.findGrowthMomentsOnDate(owner, pet, today))).contains(postId);
            assertThat(contentService.countGrowthMoments(owner, pet)).isEqualTo(1L);
            assertThat(contentService.countPublishedGrowthMomentsOfPet(owner, pet)).isEqualTo(1L);
            // 访客 / 名片
            assertThat(ids(contentService.findPublishedGrowthMomentsInMonth(owner, pet, today.withDayOfMonth(1),
                    today.withDayOfMonth(today.lengthOfMonth())))).contains(postId);
            assertThat(ids(contentService.findPublishedGrowthMomentsOnDate(owner, pet, today))).contains(postId);
            assertThat(ids(contentService.findRecentGrowthMomentsByEventDate(owner, pet, 10))).contains(postId);
            // 真实接口：/me/timeline?petId=
            String tl = mvc.perform(get("/api/v1/pet-profiles/me/timeline").param("petId", String.valueOf(pet))
                            .header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<Long> tlPosts = new ArrayList<>();
            for (JsonNode item : json.readTree(tl).get("items")) {
                if (item.hasNonNull("postId")) {
                    tlPosts.add(item.get("postId").asLong());
                }
            }
            assertThat(tlPosts).as("宠物 %s 的时间线", pet).containsOnlyOnce(postId);
            // 访客时间线
            mvc.perform(get("/api/v1/pets/" + pet + "/visitor/timeline").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isOk());
        }

        // Feed（不按宠物过滤）只出现一次。
        long inFeed = posts.findFeed(ContentType.GROWTH_MOMENT, true, false, null, false, null, null,
                        false, null, PageRequest.of(0, 200)).stream()
                .filter(p -> p.getId() == postId).count();
        assertThat(inFeed).isEqualTo(1L);

        // 详情 pets 两项，按创建顺序。
        mvc.perform(get("/api/v1/content-posts/" + postId).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pets.length()").value(2))
                .andExpect(jsonPath("$.pets[0].petId").value(petA))
                .andExpect(jsonPath("$.pets[1].petId").value(petB))
                .andExpect(jsonPath("$.pets[1].petType").value("DOG"));
    }

    @Test
    void legacyPetIdOnlyStillLinksOnePet() throws Exception {
        long postId = publish("{\"type\":\"GROWTH_MOMENT\",\"petId\":" + petB + ",\"text\":\"lama\"}");
        assertThat(contentPetQuery.petsOf(postId)).containsExactly(petB);
        assertThat(contentService.countGrowthMoments(owner, petA)).isZero();
    }

    @Test
    void readOnlyPetIs409AndNothingIsSaved() throws Exception {
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", petB);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM content_posts WHERE author_id = ?",
                Integer.class, owner);

        mvc.perform(post("/api/v1/content-posts").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"GROWTH_MOMENT\",\"petIds\":[" + petA + "," + petB + "],\"text\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.petId").value(petB));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_posts WHERE author_id = ?",
                Integer.class, owner)).isEqualTo(before);
    }

    @Test
    void foreignPetIs404() throws Exception {
        User other = newUser();
        long foreign = MultiPetDbFixture.insertPet(jdbc, other.getId(), "Zzz", "CAT", Instant.now());
        extraPets.add(foreign);

        mvc.perform(post("/api/v1/content-posts").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"GROWTH_MOMENT\",\"petIds\":[" + petA + "," + foreign + "],\"text\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void momentWithPetIdsHasNoLinks() throws Exception {
        long postId = publish("{\"type\":\"DAILY\",\"petIds\":[" + petA + "],\"text\":\"moment\"}");
        assertThat(posts.findById(postId).orElseThrow().getPetId()).isNull();
        assertThat(contentPetQuery.petsOf(postId)).isEmpty();
        mvc.perform(get("/api/v1/content-posts/" + postId).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pets").doesNotExist());
    }

    @Test
    void recommendationPoolCountsTwoPetPostForBothPets() throws Exception {
        for (int i = 0; i < 3; i++) {
            publishDiary("[" + petA + "," + petB + "]", "rek" + i);
        }
        var pool = contentService.findRecommendablePets(Instant.now().minusSeconds(14 * 86400L), 3, 500, null);
        var a = pool.stream().filter(p -> p.petId() == petA).findFirst();
        var b = pool.stream().filter(p -> p.petId() == petB).findFirst();
        assertThat(a).isPresent();
        assertThat(b).isPresent();
        assertThat(a.get().publicRecords()).isEqualTo(3L);
        assertThat(b.get().publicRecords()).isEqualTo(3L);

        var covers = contentService.findLatestPublicCovers(List.of(petA, petB));
        assertThat(covers).containsKeys(petA, petB);
        assertThat(covers.get(petA)).isEqualTo(covers.get(petB)).isEqualTo("https://cdn.test/rek2.jpg");
    }
}
