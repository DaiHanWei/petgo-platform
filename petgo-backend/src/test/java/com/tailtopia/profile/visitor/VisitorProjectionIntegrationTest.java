package com.tailtopia.profile.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.HealthSourceType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.ArchiveStatsResponse;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.TimelineService;
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
 * L1：访客只读投影层的<b>行为</b>守卫（V1.1.6 Story 2.1 · AC2~AC6）。
 *
 * <p>姊妹文件 {@link VisitorProjectionFieldsTest} 守的是<b>形状</b>（这一层不许持有健康仓库、
 * 访客 DTO 里不许有健康字段），是不连数据库的反射断言。但形状对了不代表行为对 ——
 * 字段白名单再干净，取数时用错一个查询方法，照样能把下架内容发给全网。
 * <b>所以这两层测试缺一不可</b>：那边保证「装不下」，这边保证「真的没装」。
 *
 * <p>本类真跑 PostgreSQL：造出健康记录、问诊存档、下架内容、审核中内容、私密日记，
 * 再从<b>访客的角度</b>把页面拉下来，逐条断言什么该出现、什么绝不该出现。
 */
class VisitorProjectionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private VisitorProjectionService visitors;

    @Autowired
    private PetProfileRepository profiles;

    @Autowired
    private HealthRecordRepository healthRecords;

    @Autowired
    private HealthEventRepository healthEvents;

    @Autowired
    private TimelineService timelineService;

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

    /** 一条结构化健康记录（疫苗 / 驱虫这类）。归属键是 <b>petProfileId</b>，不是 ownerId。 */
    private void seedHealthRecord(PetProfile pet) {
        healthRecords.save(HealthRecord.create(pet.getId(), HealthRecordType.VACCINE,
                null, "Rabies-XYZ", LocalDate.now().minusDays(3), "catatan rahasia"));
    }

    /**
     * 一条<b>已存档</b>的问诊事件。
     *
     * <p>⚠️ 这是「问诊次数」真正数的东西（表 {@code health_events}，只数 {@code ARCHIVED}）。
     * {@code source_ref} 全表唯一，故用 {@code SEQ} 造。
     * {@code symptomSummary} 是<b>症状摘要 —— 健康数据本身</b>，下面的断言就盯它。
     */
    private void seedConsultArchive(PetProfile pet, String symptom) {
        healthEvents.save(HealthEvent.archived(pet.getId(), HealthSourceType.AI_TRIAGE,
                "ref-" + SEQ.incrementAndGet(), LocalDate.now().minusDays(2),
                symptom, "YELLOW", "saran dokter", List.of()));
    }

    /** 直插一条快乐时刻，可指定审核状态与可见性。 */
    private void seedMoment(long ownerId, long petId, String text, String status,
            String visibility, boolean softDeleted, int daysAgo) {
        jdbc.update("""
                INSERT INTO content_posts
                  (author_id, pet_id, type, text, image_urls, status, visibility,
                   event_date, deleted_at, created_at, updated_at)
                VALUES (?, ?, 'GROWTH_MOMENT', ?, '["https://cdn.test/x.jpg"]'::jsonb, ?, ?,
                        current_date - ?, ?, now(), now())
                """,
                ownerId, petId, text, status, visibility, daysAgo,
                softDeleted ? java.sql.Timestamp.from(Instant.now()) : null);
    }

    private String render(String token) throws Exception {
        return mvc.perform(get("/p/" + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private List<String> timelineTexts(PetProfile pet) {
        return visitors.timeline(pet, 50).stream().map(VisitorTimelineItem::text).toList();
    }

    // ===================== AC2：健康 / 问诊整类不下发 =====================

    /**
     * 🛡 <b>本类最要紧的一条</b>：健康记录与问诊存档<b>整类</b>不出现在访客视图。
     *
     * <p>作者态的时间线是<b>会</b>把这两类混进来的（那是作者自己的档案，理应看得到）。
     * 访客态若照搬那套取数，泄露的是<b>症状摘要、AI 分级、就诊建议</b> ——
     * 这是本 story 全部安全设计所围绕的那件事。
     *
     * <p>断言分两层：投影层的时间线里没有它们，<b>且</b>整页 HTML 里搜不到症状文字
     * （后者能兜住「投影层干净、但页面从别处又捞了一遍」这种绕过）。
     */
    @Test
    void healthRecordsAndConsultArchivesNeverReachVisitors() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedHealthRecord(pet);
        seedConsultArchive(pet, "muntah-dan-lemas-RAHASIA");
        seedMoment(owner.getId(), pet.getId(), "jalan-jalan pagi", "PUBLISHED", "PUBLIC", false, 1);

        assertThat(timelineTexts(pet))
                .as("访客时间线只应有快乐时刻，健康记录 / 问诊存档整类不得进入")
                .containsExactly("jalan-jalan pagi");

        String html = render(pet.getCardToken());
        assertThat(html)
                .as("🔴 症状摘要泄露到了对外 H5 页面 —— 这是健康数据本身")
                .doesNotContain("muntah-dan-lemas-RAHASIA")
                .doesNotContain("Rabies-XYZ")
                .doesNotContain("catatan rahasia")
                .doesNotContain("saran dokter");
        // AI 分级同样不得外泄（它能直接推断出宠物的健康严重程度）
        assertThat(html).doesNotContain("YELLOW");
    }

    // ===================== AC3：审核状态 =====================

    /**
     * 🛡 <b>被下架 / 审核中的内容不得出现在访客视图。</b>
     *
     * <p>作者自己<b>看得到</b>这些条目（得让他知道发生了什么），访客态若照搬作者态的查询，
     * 后果是：<b>违规内容被下架之后，仍能通过分享链接对全网可见</b> —— 下架就等于没下架。
     *
     * <p>⚠️ 这条守的是取数方法的选择：{@code ContentService} 里有好几个 {@code findGrowthMoments*}，
     * 有的只过滤了软删、<b>没过滤审核状态</b>。换成那些，本条立刻红。
     */
    @Test
    void takenDownAndUnderReviewContentIsInvisibleToVisitors() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedMoment(owner.getId(), pet.getId(), "normal", "PUBLISHED", "PUBLIC", false, 1);
        seedMoment(owner.getId(), pet.getId(), "sudah-dihapus", "PUBLISHED", "PUBLIC", true, 2);
        seedMoment(owner.getId(), pet.getId(), "sedang-ditinjau", "UNDER_REVIEW", "PUBLIC", false, 3);

        assertThat(timelineTexts(pet))
                .as("下架（软删）与审核中的条目泄露给了访客")
                .containsExactly("normal");
        assertThat(render(pet.getCardToken()))
                .doesNotContain("sudah-dihapus")
                .doesNotContain("sedang-ditinjau");
    }

    /**
     * ⚠️ <b>这条容易搞反，所以单独写一条钉住。</b>
     *
     * <p>作者关了同步的<b>私密</b>快乐时刻，在访客视图里<b>要出现</b>。
     * 依据是 PRD §2.9 §② 定稿：<b>分享宠物主页 = 授权访客查看该宠物完整 Diary</b>
     * （与 FR-83 对 H5 的拍板同口径）。「私密」管的是社区 Feed 不同步，不是这条分享链接。
     *
     * <p>如果哪天产品改口径，改的是这条测试和 PRD，<b>不要</b>在实现里悄悄加一个 visibility 过滤 ——
     * 那样两处口径就分叉了。
     */
    @Test
    void privateDiaryEntriesStayVisibleToVisitorsByDesign() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedMoment(owner.getId(), pet.getId(), "momen-privat", "PUBLISHED", "PRIVATE", false, 1);

        // 自证：这条确实落成了 PRIVATE。否则下面的断言会在「其实是 PUBLIC」的情况下空绿，
        // 而那正是本条最该防的失败方式 —— 一条永远通过的测试比没有测试更糟。
        // ⚠️ 必须按 pet_id 收窄：本类不回滚事务，反复跑会在库里留下同名的历史行。
        assertThat(jdbc.queryForObject(
                "SELECT visibility FROM content_posts WHERE pet_id = ? AND text = 'momen-privat'",
                String.class, pet.getId()))
                .isEqualTo("PRIVATE");

        assertThat(timelineTexts(pet))
                .as("私密快乐时刻应对访客可见（PRD §2.9 §② 定稿）—— 别顺手滤掉")
                .contains("momen-privat");
    }

    // ===================== AC5：统计口径 =====================

    /**
     * 统计三列与作者态<b>同源</b>，但 {@code healthRecordCount} 到投影层为止。
     *
     * <p>🛡 健康记录<b>条数</b>虽不是内容，却足以推断出「这只宠物有没有健康问题记录」，
     * 而 PRD §2.9 里健康记录整块都是 ❌。这里造了 2 条健康记录，
     * 断言这个数字<b>在访客侧根本不存在</b>（而不是「存在但没显示」）。
     */
    @Test
    void statsShareTheAuthorsNumbersButDropHealthRecordCount() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedHealthRecord(pet);
        seedHealthRecord(pet);
        seedConsultArchive(pet, "gatal");
        seedMoment(owner.getId(), pet.getId(), "m1", "PUBLISHED", "PUBLIC", false, 1);

        ArchiveStatsResponse author = timelineService.getStats(owner.getId());
        VisitorStats visitor = visitors.stats(pet);

        // 同源：三个数一一对上（AC5 —— 两个页面不该出现不一样的数字）
        assertThat(visitor.diaryCount()).isEqualTo(author.happyMomentCount());
        assertThat(visitor.consultCount()).isEqualTo(author.consultCount());
        assertThat(visitor.milestoneCompleted()).isEqualTo(author.milestoneCompleted());
        assertThat(visitor.milestoneTotal()).isEqualTo(author.milestoneTotal());

        // 作者侧确实数到了健康记录（证明上面的 fixture 真的落库了，不是空断言）
        assertThat(author.healthRecordCount()).isEqualTo(2);
        // 🛡 访客侧连这个字段都没有 —— 装不下就不可能填错
        assertThat(VisitorStats.class.getRecordComponents())
                .as("VisitorStats 多出了字段，检查是不是把 healthRecordCount 加回来了")
                .hasSize(4);
        assertThat(render(pet.getCardToken()))
                .as("页面上不得出现健康记录条数")
                .doesNotContain("Catatan kesehatan");
    }

    // ===================== AC6：失效判定 =====================

    /**
     * 🛡 token 不存在 / 账号注销 / 账号封号 → <b>完全一致</b>的失效响应。
     *
     * <p>三者的响应必须一个字都不差 —— 只要有任何差别（状态码、文案、长度），
     * 就等于给了枚举者一个信号：「这个 token 曾经存在过 / 这个人被封了」。
     * 拿一堆随机 token 扫一遍，就能扫出哪些是真实用户。
     *
     * <p>⚠️ 「封号也算失效」是 2026-08-17 产品追加的，且<b>可逆</b>：重新激活后应恢复可见。
     */
    @Test
    void unknownTokenDeactivatedAndDeletedAccountsAreIndistinguishable() throws Exception {
        // ① token 不存在
        String unknown = bodyOfGone("NO-SUCH-TOKEN-" + SEQ.incrementAndGet());

        // ② 账号被封号（可逆）
        User suspended = newUser();
        PetProfile suspendedPet = createProfile(suspended);
        User su = users.findById(suspended.getId()).orElseThrow();
        su.deactivate();
        users.save(su);
        String deactivated = bodyOfGone(suspendedPet.getCardToken());

        // ③ 账号已注销（不可逆）
        User deleted = newUser();
        PetProfile deletedPet = createProfile(deleted);
        User du = users.findById(deleted.getId()).orElseThrow();
        du.anonymizeForDeletion(Instant.now());
        users.save(du);
        String gone = bodyOfGone(deletedPet.getCardToken());

        assertThat(deactivated)
                .as("封号与「token 不存在」的响应不一致 —— 泄漏了「这个人被封了」")
                .isEqualTo(unknown);
        assertThat(gone)
                .as("注销与「token 不存在」的响应不一致 —— 泄漏了「这个 token 曾经存在」")
                .isEqualTo(unknown);

        // 可逆性：解封后照常可见（封号不是删除）
        su.reactivate();
        users.save(su);
        assertThat(render(suspendedPet.getCardToken())).contains("Momo");
    }

    /** 投影层层面的同一条：四种失效都是同一个 {@code Optional.empty()}，调用方无从区分。 */
    @Test
    void projectionCollapsesAllInvalidCasesIntoOneEmpty() throws Exception {
        assertThat(visitors.findVisibleProfile("NOPE-" + SEQ.incrementAndGet())).isEmpty();

        User owner = newUser();
        PetProfile pet = createProfile(owner);
        assertThat(visitors.findVisibleProfile(pet.getCardToken())).isPresent();

        User u = users.findById(owner.getId()).orElseThrow();
        u.deactivate();
        users.save(u);
        assertThat(visitors.findVisibleProfile(pet.getCardToken()))
                .as("封号后应判为失效（2026-08-17 产品追加）")
                .isEmpty();
    }

    /** 拉取失效页的响应体（顺带断言 404，防枚举的另一半）。 */
    private String bodyOfGone(String token) throws Exception {
        return mvc.perform(get("/p/" + token))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
    }

    // ===================== AD-1 Rule 9：不套拉黑过滤 =====================

    /**
     * ⚠️ <b>AD-1 Rule 9 的 L1 用例在本分支上写不出来，这是刻意留白，不是遗漏。</b>
     *
     * <p>规则本身是：已登录用户点开自己拉黑过的人的<b>宠物主页分享链接，照常看得到</b>
     * （2026-08-16 产品拍板）。V1.1.4 那条「拉黑后进不去对方主页」管的是<b>社区个人主页</b>，
     * 与本层不同口径，<b>发现不一致时不要「顺手对齐」</b>。
     *
     * <p>为什么写不出来：拉黑关系（分支 {@code hex/v1.1.4} 里叫「用户隐藏关系」
     * {@code user_hide_relations}，迁移 V101）<b>尚未合入 v1.1.6</b> ——
     * 本分支上根本造不出一条拉黑记录。
     *
     * <p>那么这条规则现在靠什么守：
     * <ol>
     *   <li><b>结构上</b>：本层不复用任何 Feed 查询，天然不继承那层过滤 ——
     *       {@code VisitorProjectionFieldsTest} 反射断言依赖清单里没有相关服务；</li>
     *   <li><b>形状上</b>：{@code visitorDtosLeakNoBlockSignal} 断言访客 DTO 里
     *       不得出现任何拉黑相关字段（否则等于向查看者暴露自己的拉黑名单在此处是否生效）。</li>
     * </ol>
     *
     * <p>🔴 <b>两分支合并时的待办</b>：合入后请回到本文件，把这条注释换成一条真的 L1 用例
     * —— 造一条拉黑关系，断言宠物主页分享链接<b>照常</b>返回 200 且内容完整。
     */
    @Test
    void blockRelationsAreNotFilteredHere_seeJavadocForWhyThisIsNotYetTestableAtL1() {
        // 能在本分支断言的只有「这一层没有任何拉黑相关的输入」这件事：
        // 投影层的公开方法签名里不接受任何「查看者身份」，因此它在结构上就无法按查看者做差异化过滤。
        boolean takesViewer = java.util.Arrays.stream(VisitorProjectionService.class.getMethods())
                .filter(m -> m.getDeclaringClass() == VisitorProjectionService.class)
                .flatMap(m -> java.util.Arrays.stream(m.getParameterTypes()))
                .anyMatch(t -> t.getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("viewer"));
        assertThat(takesViewer)
                .as("投影层出现了「查看者」参数 —— 一旦按查看者做差异化，AD-1 Rule 9 就可能被悄悄推翻，"
                        + "且这种泄露是静默的。若确需引入，请先回到架构 AD-1 改规则。")
                .isFalse();
    }
}
