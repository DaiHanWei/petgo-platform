package com.tailtopia.profile.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.HealthSourceType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：访客<b>日历</b>与<b>某天详情</b>（V1.1.6 Story 2.2 · AC1~AC7）。
 *
 * <p>Story 2.1 守住了访客<b>时间线</b>，本类守的是同一批规则在另外两个视图上是否也成立 ——
 * 这不是重复：日历与某天详情走的是<b>另一对查询</b>，而作者态的那对
 * <b>只过滤了删除、没过滤审核状态</b>。也就是说，如果实现时顺手复用了作者态那对，
 * <b>被下架的违规内容会经分享链接继续对全网可见</b>，而时间线那边的测试<b>一条都不会红</b>。
 *
 * <p>为避免跨月边界导致的偶发失败，所有日期都固定在一个确定的历史月份。
 */
class VisitorCalendarIntegrationTest extends ApiIntegrationTest {

    /** 固定月份 —— 不用 "N 天前" 是因为那会在月初跨月，让断言时灵时不灵。 */
    private static final int Y = 2026;
    private static final int M = 5;

    private static LocalDate day(int d) {
        return LocalDate.of(Y, M, d);
    }

    @Autowired
    private VisitorProjectionService visitors;

    @Autowired
    private PetProfileRepository profiles;

    @Autowired
    private HealthRecordRepository healthRecords;

    @Autowired
    private HealthEventRepository healthEvents;

    @Autowired
    private JdbcTemplate jdbc;

    // ===================== fixture =====================

    private PetProfile createProfile(User owner) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"petType":"CAT","name":"Momo","breed":"Kucing Domestik",
                                 "birthday":"2024-03-10","intro":"Suka tidur"}
                                """))
                .andExpect(status().isCreated());
        return profiles.findByOwnerId(owner.getId()).orElseThrow();
    }

    /** 直插一条快乐时刻到指定日期，可指定审核状态、可见性、是否软删、有没有配图。 */
    private void seedMoment(PetProfile pet, LocalDate date, String text, String status,
            String visibility, boolean softDeleted, String imageUrl) {
        jdbc.update("""
                INSERT INTO content_posts
                  (author_id, pet_id, type, text, image_urls, status, visibility,
                   event_date, deleted_at, created_at, updated_at)
                VALUES (?, ?, 'GROWTH_MOMENT', ?, ?::jsonb, ?, ?, ?, ?, now(), now())
                """,
                pet.getOwnerId(), pet.getId(), text,
                imageUrl == null ? null : "[\"" + imageUrl + "\"]",
                status, visibility, java.sql.Date.valueOf(date),
                softDeleted ? java.sql.Timestamp.from(Instant.now()) : null);
    }

    /** 一条公开、已发布、带图的快乐时刻（最常见的那种）。 */
    private void seedPublicMoment(PetProfile pet, LocalDate date, String text) {
        seedMoment(pet, date, text, "PUBLISHED", "PUBLIC", false, "https://cdn.test/x.jpg");
    }

    private void seedHealthRecord(PetProfile pet, LocalDate date) {
        healthRecords.save(HealthRecord.create(pet.getId(), HealthRecordType.VACCINE,
                null, "Rabies-XYZ", date, "catatan rahasia"));
    }

    /**
     * 一条已存档的问诊事件。
     *
     * <p>⚠️ <b>作者态的日历与某天详情是按 {@code created_at} 把问诊存档落格的</b>
     * （{@code healthEventsInRange} 查的是 created_at 区间），而结构化健康记录是按
     * {@code event_date} 落格 —— <b>两类数据的落格口径本就不一致</b>（作者态既有行为，非本 story 引入）。
     * 所以这里存完必须把 {@code created_at} 显式回写到目标日期，否则它会落在「今天」那一格，
     * 断言就会莫名其妙地对不上。
     */
    private void seedConsultArchive(PetProfile pet, LocalDate date, String symptom) {
        HealthEvent saved = healthEvents.save(HealthEvent.archived(pet.getId(), HealthSourceType.AI_TRIAGE,
                "ref-" + SEQ.incrementAndGet(), date,
                symptom, "YELLOW", "saran dokter", List.of()));
        jdbc.update("UPDATE health_events SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(date.atStartOfDay(java.time.ZoneOffset.UTC)
                        .plusHours(12).toInstant()),
                saved.getId());
    }

    private List<Integer> visitorDays(PetProfile pet) {
        return visitors.calendarMonth(pet, Y, M).days().stream()
                .map(VisitorDayCell::day).toList();
    }

    private String getVisitorCalendar(String token) throws Exception {
        return mvc.perform(get("/api/v1/public/shared-pets/" + token + "/calendar")
                        .param("year", String.valueOf(Y)).param("month", String.valueOf(M)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String getVisitorDay(String token, LocalDate date) throws Exception {
        return mvc.perform(get("/api/v1/public/shared-pets/" + token + "/day")
                        .param("date", date.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String getAuthorCalendar(User owner) throws Exception {
        return mvc.perform(get("/api/v1/pet-profiles/me/calendar")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .param("year", String.valueOf(Y)).param("month", String.valueOf(M)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ===================== AC2 / AC3：同一天两类记录的分叉 =====================

    /**
     * 🛡 同一天既有 Diary 又有健康记录 → <b>作者看到两类标记，访客只看到 Diary 标记</b>。
     *
     * <p>这条同时是 T1 新增查询的<b>回归</b>：作者态那边必须<b>照旧</b>看得到健康标记 ——
     * 新增查询是「另加一条」而不是「改掉原来那条」，改错了这里会红。
     */
    @Test
    void sameDayShowsBothMarkersToAuthorButOnlyDiaryToVisitor() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedPublicMoment(pet, day(10), "jalan pagi");
        seedHealthRecord(pet, day(10));
        seedConsultArchive(pet, day(10), "muntah-RAHASIA");

        // 作者侧：两类标记俱全（回归）
        String authorJson = getAuthorCalendar(owner);
        assertThat(authorJson)
                .as("作者态日历应照旧带健康标记 —— 新增访客查询不得改动作者口径")
                .contains("\"hasHealthEvent\":true")
                .contains("\"healthRecordType\":\"VACCINE\"");

        // 访客侧：只有 Diary，且健康三字段在结构上就不存在
        String visitorJson = getVisitorCalendar(pet.getCardToken());
        assertThat(visitorJson).contains("\"hasHappyMoment\":true");
        assertThat(visitorJson)
                .as("🔴 访客日历下发了健康相关字段 —— 哪怕只是一个布尔值，"
                        + "也等于告诉陌生人「这只宠物这天看过病」")
                .doesNotContain("hasHealthEvent")
                .doesNotContain("healthRecordType")
                .doesNotContain("healthRecordCount");
    }

    /**
     * 🛡 某天<b>只有</b>健康记录、没有 Diary → 该天在访客日历里<b>整格不出现</b>。
     *
     * <p>⚠️ 不是「出现一个没有标记的空格子」—— 空格子是<b>能被数出来</b>的：
     * 访客会看到「这天有个格子但什么都没有」，从而推断出「这天发生了什么，只是不给我看」。
     * 无记录日与有记录但不给看的日子，必须长得一模一样。
     */
    @Test
    void dayWithOnlyHealthDataIsAbsentFromVisitorCalendar() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedPublicMoment(pet, day(3), "ada diary");
        seedHealthRecord(pet, day(20));          // 20 号只有健康记录
        seedConsultArchive(pet, day(21), "gatal"); // 21 号只有问诊存档

        assertThat(visitorDays(pet))
                .as("只有健康数据的日子不该出现在访客日历里（连空格子都不行）")
                .containsExactly(3);

        // 作者侧这两天是有格子的 —— 证明数据确实落库了，上面不是空断言
        assertThat(getAuthorCalendar(owner))
                .contains("\"day\":20")
                .contains("\"day\":21");
    }

    // ===================== AC4：审核状态（本类最要紧的一条）=====================

    /**
     * 🛡 <b>被下架 / 审核中的 Diary 不得出现在访客的日历与某天详情里。</b>
     *
     * <p>🔴 这是本 story 最容易踩的坑：作者态的日历与某天详情用的查询<b>只过滤了删除、
     * 没过滤审核状态</b>（作者要看得到自己被下架的帖，那是刻意的）。
     * 实现时若顺手复用了那对方法，<b>违规内容被下架之后仍能通过分享链接对全网可见</b> ——
     * 而 Story 2.1 那批时间线测试<b>一条都不会红</b>，因为时间线走的是另一个已带过滤的查询。
     */
    @Test
    void takenDownAndUnderReviewMomentsAreInvisibleToVisitors() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedPublicMoment(pet, day(5), "normal");
        seedMoment(pet, day(6), "sudah-dihapus", "PUBLISHED", "PUBLIC", true, "https://cdn.test/a.jpg");
        seedMoment(pet, day(7), "sedang-ditinjau", "UNDER_REVIEW", "PUBLIC", false, "https://cdn.test/b.jpg");

        assertThat(visitorDays(pet))
                .as("下架（软删）或审核中的内容让那一天出现在了访客日历里")
                .containsExactly(5);

        // 某天详情同样不给
        assertThat(getVisitorDay(pet.getCardToken(), day(6))).doesNotContain("sudah-dihapus");
        assertThat(getVisitorDay(pet.getCardToken(), day(7))).doesNotContain("sedang-ditinjau");

        // 作者侧看得到审核中的那条 —— 证明数据落库了，且作者口径未被改动
        assertThat(getAuthorCalendar(owner)).contains("\"day\":7");
    }

    // ===================== AC5：私密条目照常可见 =====================

    /**
     * ⚠️ <b>这条容易搞反</b>：作者关闭同步的<b>私密</b> Diary，在访客日历与某天详情里<b>要出现</b>。
     *
     * <p>依据 PRD §2.9 §② 定稿：分享宠物主页 = 授权访客查看该宠物<b>完整</b> Diary。
     * 「私密」管的是社区 Feed 不同步，不是这条分享链接。与 Story 2.1 的时间线同口径。
     */
    @Test
    void privateMomentsStayVisibleInCalendarAndDayDetail() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedMoment(pet, day(12), "momen-privat", "PUBLISHED", "PRIVATE", false, "https://cdn.test/p.jpg");

        // 自证夹具：这条确实落成了 PRIVATE，否则下面是空绿
        assertThat(jdbc.queryForObject(
                "SELECT visibility FROM content_posts WHERE pet_id = ? AND text = 'momen-privat'",
                String.class, pet.getId()))
                .isEqualTo("PRIVATE");

        assertThat(visitorDays(pet))
                .as("私密 Diary 应对访客可见（PRD §2.9 §② 定稿）—— 别顺手加 visibility 过滤")
                .containsExactly(12);
        assertThat(getVisitorDay(pet.getCardToken(), day(12))).contains("momen-privat");
    }

    // ===================== AC2：某天详情不含类④ =====================

    /** 🛡 访客某天详情<b>不含</b>健康记录与问诊存档，响应里搜不到症状摘要。 */
    @Test
    void dayDetailCarriesNoHealthOrConsultItems() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedPublicMoment(pet, day(15), "main bola");
        seedHealthRecord(pet, day(15));
        seedConsultArchive(pet, day(15), "batuk-RAHASIA");

        String json = getVisitorDay(pet.getCardToken(), day(15));
        assertThat(json).contains("main bola");
        assertThat(json)
                .as("🔴 症状摘要 / AI 分级 / 医嘱泄露到了访客某天详情")
                .doesNotContain("batuk-RAHASIA")
                .doesNotContain("Rabies-XYZ")
                .doesNotContain("catatan rahasia")
                .doesNotContain("saran dokter")
                .doesNotContain("YELLOW")
                .doesNotContain("symptomSummary")
                .doesNotContain("aiLevel");
        // 访客侧永远不产出类④
        assertThat(json).doesNotContain("HEALTH_RECORD");

        // 作者侧同一天是看得到问诊条目的 —— 证明数据落库了
        assertThat(mvc.perform(get("/api/v1/pet-profiles/me/day")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .param("date", day(15).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .contains("batuk-RAHASIA");
    }

    // ===================== AC7：去 EXIF =====================

    /**
     * 🛡 日历首图必须去 EXIF。
     *
     * <p>⚠️ 作者态的首图是<b>原样下发</b>的（自己看自己的图，无需脱敏），照抄就会把
     * 拍摄地点等元数据随图发给陌生人。
     */
    @Test
    void calendarFirstImageIsExifStripped() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedPublicMoment(pet, day(8), "foto");

        assertThat(getVisitorCalendar(pet.getCardToken()))
                .as("访客日历首图未经去 EXIF 分发 —— 拍摄地点会随图外泄")
                .contains("x-oss-process=image/");
    }

    // ===================== AC6：失效防枚举 =====================

    /**
     * 🛡 token 不存在 / 账号封号 / 账号注销 → 同一个失效响应，<b>无从区分</b>。
     *
     * <p>比较前会剔除 {@code instance}（回显你自己请求的路径）与 {@code traceId}（随机排查号）——
     * 见 {@link #normalizeProblem}。剩下的 status / title / type / detail 必须完全一致。
     */
    @Test
    void unknownSuspendedAndDeletedTokensAreIndistinguishable() throws Exception {
        String unknown = goneBody("NO-SUCH-TOKEN-" + SEQ.incrementAndGet());

        User suspended = newUser();
        PetProfile suspendedPet = createProfile(suspended);
        User su = users.findById(suspended.getId()).orElseThrow();
        su.deactivate();
        users.save(su);

        User deleted = newUser();
        PetProfile deletedPet = createProfile(deleted);
        User du = users.findById(deleted.getId()).orElseThrow();
        du.anonymizeForDeletion(Instant.now());
        users.save(du);

        assertThat(goneBody(suspendedPet.getCardToken()))
                .as("封号与「token 不存在」的响应不一致 —— 泄漏了「这个人被封了」")
                .isEqualTo(unknown);
        assertThat(goneBody(deletedPet.getCardToken()))
                .as("注销与「token 不存在」的响应不一致 —— 泄漏了「这个 token 曾经存在」")
                .isEqualTo(unknown);

        // 某天详情走同一条判定，同样不可区分
        assertThat(goneDayBody(suspendedPet.getCardToken()))
                .isEqualTo(goneDayBody("NO-SUCH-TOKEN-" + SEQ.incrementAndGet()));
    }

    /**
     * 归一化 ProblemDetail：剔除 {@code instance} 与 {@code traceId} 再比。
     *
     * <p>⚠️ 这两项<b>必然</b>每次不同，且<b>都不泄漏任何东西</b>：
     * {@code instance} 只是把你自己请求的那个 token 原样回显（你本来就知道它），
     * {@code traceId} 是随机排查号。真正会泄漏的是 <b>status / title / type / detail</b> ——
     * 只要这四项在四种失效下完全一致，扫描者就无法区分「这个 token 不存在」和「这个人被封了」。
     */
    private static String normalizeProblem(String body) {
        return body.replaceAll("\"instance\":\"[^\"]*\",?", "")
                .replaceAll(",?\"traceId\":\"[^\"]*\"", "");
    }

    private String goneBody(String token) throws Exception {
        return normalizeProblem(mvc.perform(get("/api/v1/public/shared-pets/" + token + "/calendar")
                        .param("year", String.valueOf(Y)).param("month", String.valueOf(M)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());
    }

    private String goneDayBody(String token) throws Exception {
        return normalizeProblem(mvc.perform(get("/api/v1/public/shared-pets/" + token + "/day")
                        .param("date", day(1).toString()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());
    }

    // ===================== AC1：未登录可访问 =====================

    /**
     * 🛡 <b>不带任何登录凭证</b>也能访问。
     *
     * <p>同一个链接在浏览器里无需登录即可看完整 Diary；App 内若要求登录，
     * 只会把用户推回浏览器（FR-92 §④）。上面所有用例本来就没带 Authorization 头，
     * 这条把它<b>写成一条显式断言</b> —— 否则将来有人给这段路径加上鉴权，
     * 上面的用例会集体变红却看不出根因。
     */
    @Test
    void endpointsAreReachableWithoutAnyCredentials() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedPublicMoment(pet, day(2), "halo");

        mvc.perform(get("/api/v1/public/shared-pets/" + pet.getCardToken() + "/calendar")
                        .param("year", String.valueOf(Y)).param("month", String.valueOf(M)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/shared-pets/" + pet.getCardToken() + "/day")
                        .param("date", day(2).toString()))
                .andExpect(status().isOk());
    }
}
