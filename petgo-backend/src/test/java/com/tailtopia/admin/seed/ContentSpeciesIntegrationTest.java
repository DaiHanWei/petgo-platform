package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.moderation.service.AdminContentManageService;
import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.service.AccountSpeciesDefaultReader;
import com.tailtopia.admin.seed.service.SeedBatchPublishService;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.species.ContentSpecies;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.content.species.SpeciesSource;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：种子内容物种归属（V1.1.6 Story 14.1 · AB-3H）。
 *
 * <h2>缺口是什么</h2>
 * 算法要 join 作者的宠物档案推导内容物种，但**虚拟账号创建时只填昵称+头像、不建宠物档案**
 * ⇒ 全部种子内容的物种推导结果都是空；而 Tips/科普类主要由虚拟号发布
 * ⇒ 算法的「教育类」槽位几乎完全无法做物种个性化。
 *
 * <h2>🔴 本类最重要的一条</h2>
 * {@link #changingTheAccountTagInstantlyRetagsAllHistoricalContent()} ——
 * 内容物种是**读时 join 推导**（不是发布时快照），所以改完账号定位，
 * 该号名下<b>全部历史内容</b>的物种归属立即生效、**零回填**。
 * 这是本 story 杠杆最高的地方；快照方案每次改定位都要跑历史回填，收益尽失。
 */
class ContentSpeciesIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentSpeciesResolver resolver;

    @Autowired
    private AdminVirtualAccountService virtualAccounts;

    @Autowired
    private AdminContentManageService contentManage;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private PetProfileRepository pets;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private SeedBatchService batchService;

    @Autowired
    private SeedBatchPublishService publishing;

    @Autowired
    private AccountSpeciesDefaultReader speciesReader;

    private long adminId() {
        long n = SEQ.incrementAndGet();
        return adminAccounts.save(AdminAccount.newSuperAdmin(
                "sp-" + n + "@tailtopia.test", "物种测试员", "{bcrypt}x")).getId();
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "spview-" + n + "@tailtopia.test", "物种查看员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));
    }

    /** ⚠️ 昵称 ≤20 字；SEQ 是 nanoTime 种子的大整数。 */
    private long virtualAccount(String species) {
        long id = virtualAccounts.create("物种号" + (SEQ.incrementAndGet() % 100000), null,
                species, adminId());
        return id;
    }

    private ContentPost postBy(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.KNOWLEDGE, null, text, List.of()));
    }

    private List<ContentPost> postsOf(long authorId) {
        return posts.findAll().stream()
                // ⚠️ Objects.equals：getAuthorId() 是装箱 Long，`==` 比引用、id 一大就恒 false。
                .filter(p -> Objects.equals(p.getAuthorId(), authorId)).toList();
    }

    // ——————————————————— 🔴 AC2 读时推导、零回填 ———————————————————

    /**
     * 🔴 <b>改账号定位 → 该号全部历史内容的物种立即变化</b>（读时推导、零回填）。
     *
     * <p>这是本 story 杠杆最高的地方。快照方案（发布时把物种写死进内容行）
     * 每次改定位都要跑历史回填 —— 而运营改定位这件事本身就是
     * "我发现这个号其实一直在发猫内容"，需要立刻对历史生效才有意义。
     */
    @Test
    void changingTheAccountTagInstantlyRetagsAllHistoricalContent() {
        long virtualId = virtualAccount(ContentSpecies.GENERAL);
        ContentPost a = postBy(virtualId, "老内容 A-" + SEQ.incrementAndGet());
        ContentPost b = postBy(virtualId, "老内容 B-" + SEQ.incrementAndGet());

        assertThat(resolver.resolve(null, virtualId).species()).isEqualTo(ContentSpecies.GENERAL);

        virtualAccounts.setAccountSpecies(virtualId, ContentSpecies.CAT, adminId());

        // ✅ 两条历史内容的物种立即变了 —— 没有任何回填作业。
        for (ContentPost p : List.of(a, b)) {
            var r = resolver.resolve(p.getSpeciesOverride(), p.getAuthorId());
            assertThat(r.species()).isEqualTo(ContentSpecies.CAT);
            assertThat(r.source()).isEqualTo(SpeciesSource.ACCOUNT_SPECIES);
        }
    }

    /**
     * 🔴 <b>存量虚拟账号读作 GENERAL，无需任何回填作业</b>（AC2）。
     *
     * <p>用读时默认而不是一条 UPDATE：那条 UPDATE 不只是跑一次的代价 ——
     * 它会让"这个号到底配过没有"永远分不出来。
     */
    @Test
    void legacyVirtualAccountsWithoutATagReadAsGeneral() {
        long virtualId = virtualAccounts.create("存量号" + (SEQ.incrementAndGet() % 100000),
                null, adminId());
        assertThat(userRepo.findById(virtualId).orElseThrow().getAccountSpecies())
                .as("库里确实是空的 —— 没有回填").isNull();

        assertThat(resolver.resolve(null, virtualId).species()).isEqualTo(ContentSpecies.GENERAL);
    }

    // ——————————————————— T6 优先级链 ———————————————————

    /** 行级覆写优先于账号定位。 */
    @Test
    void theRowOverrideBeatsTheAccountTag() {
        long virtualId = virtualAccount(ContentSpecies.CAT);

        var r = resolver.resolve(ContentSpecies.DOG, virtualId);

        assertThat(r.species()).isEqualTo(ContentSpecies.DOG);
        assertThat(r.source()).isEqualTo(SpeciesSource.ROW_OVERRIDE);
    }

    /**
     * 真实账号走**作者宠物档案**，不走账号定位。
     *
     * <p>🔴 刻意如此：真实账号有真实档案，让算法读档案比让运营给账号贴标签准确 ——
     * 而且档案会随主人换宠物而变，账号标签不会。
     */
    @Test
    void realAccountsDeriveFromTheirPetProfile() {
        User real = newUser();
        pets.save(PetProfile.create(real.getId(), PetType.DOG,
                "旺财" + (SEQ.incrementAndGet() % 10000), null, null,
                LocalDate.of(2025, 1, 1), null, "tok" + SEQ.incrementAndGet()));

        var r = resolver.resolve(null, real.getId());

        assertThat(r.species()).isEqualTo(ContentSpecies.DOG);
        assertThat(r.source()).isEqualTo(SpeciesSource.PET_PROFILE);
    }

    /** 真实账号没建档案 ⇒ 推不出来（而不是硬套一个 GENERAL）。 */
    @Test
    void aRealAccountWithoutAPetProfileYieldsNothing() {
        User real = newUser();

        var r = resolver.resolve(null, real.getId());

        assertThat(r.known()).isFalse();
        assertThat(r.source()).isEqualTo(SpeciesSource.NONE);
    }

    // ——————————————————— 🔴 13-3 的接线点已换掉 ———————————————————

    /**
     * 📌 <b>13-3 留的那个恒空实现已被换掉。</b>
     *
     * <p>13-3 的注释里写着：「字段一建好就要立刻换掉本实现，否则这个"正确的空"会变成
     * "等着变错的硬编码" —— 表现是运营在虚拟账号上配了猫/狗定位，
     * 批量发出去的内容物种却全是空」。这条用例就是钉住它真的被换了。
     */
    @Test
    void theBatchInheritanceReaderNowSeesRealAccountTags() {
        long virtualId = virtualAccount(ContentSpecies.CAT);

        assertThat(speciesReader.speciesOf(virtualId)).contains(ContentSpecies.CAT);
    }

    /** 🔴 运营真实账号**无此字段** ⇒ 继承读不到值（留空由档案推导）。 */
    @Test
    void realAccountsHaveNoInheritableAccountTag() {
        User real = newUser();

        assertThat(speciesReader.speciesOf(real.getId())).isEmpty();
    }

    /** 批量录入时留空 ⇒ 继承账号定位（13-3 的继承规则 + 14-1 的数据源）。 */
    @Test
    void aBatchRowWithoutSpeciesInheritsTheAccountTag() {
        long admin = adminId();
        long virtualId = virtualAccount(ContentSpecies.DOG);
        long batchId = batchService.openBatch(SeedBatch.Source.EXCEL, admin).getId();

        SeedBatchRow row = batchService.addDraft(batchId, 1, virtualId, ContentType.DAILY, null,
                "留空继承-" + SEQ.incrementAndGet(), null, null,
                com.tailtopia.admin.seed.service.SeedRowDefaults.species(null,
                        userRepo.findById(virtualId).orElseThrow(), speciesReader));

        assertThat(row.getSpecies()).isEqualTo(ContentSpecies.DOG);
    }

    // ——————————————————— 🔴 AC5 存量修正入口 ———————————————————

    /** 种子内容可以设置行级覆写。 */
    @Test
    void seedContentCanGetARowOverride() {
        long admin = adminId();
        long virtualId = virtualAccount(ContentSpecies.GENERAL);
        ContentPost p = postBy(virtualId, "要改物种的-" + SEQ.incrementAndGet());

        int changed = contentManage.setSpeciesOverride(List.of(p.getId()),
                ContentSpecies.CAT, admin);

        assertThat(changed).isEqualTo(1);
        assertThat(posts.findById(p.getId()).orElseThrow().getSpeciesOverride())
                .isEqualTo(ContentSpecies.CAT);
    }

    /** 清除覆写 ⇒ 回落到账号定位。 */
    @Test
    void clearingTheOverrideFallsBackToTheAccountTag() {
        long admin = adminId();
        long virtualId = virtualAccount(ContentSpecies.CAT);
        ContentPost p = postBy(virtualId, "清覆写-" + SEQ.incrementAndGet());
        contentManage.setSpeciesOverride(List.of(p.getId()), ContentSpecies.DOG, admin);

        contentManage.setSpeciesOverride(List.of(p.getId()), null, admin);

        ContentPost after = posts.findById(p.getId()).orElseThrow();
        assertThat(after.getSpeciesOverride()).isNull();
        assertThat(resolver.resolve(after.getSpeciesOverride(), after.getAuthorId()).species())
                .isEqualTo(ContentSpecies.CAT);
    }

    /**
     * 🛡 <b>真实用户内容只读</b>（AC5）——其物种由作者宠物档案决定，运营不应手工干预。
     *
     * <p>⚠️ 服务层的校验是**权威的**：界面不给按钮只是体验，改个请求参数就能绕过。
     */
    @Test
    void ordinaryUserContentCannotBeOverridden() {
        long admin = adminId();
        User ordinary = newUser();
        ContentPost p = postBy(ordinary.getId(), "普通用户的内容-" + SEQ.incrementAndGet());

        int changed = contentManage.setSpeciesOverride(List.of(p.getId()),
                ContentSpecies.CAT, admin);

        assertThat(changed).as("🛡 普通用户内容不许改").isZero();
        assertThat(posts.findById(p.getId()).orElseThrow().getSpeciesOverride()).isNull();
    }

    /**
     * 🛡 批量里混进一条真实用户内容 ⇒ **静默跳过那一条**，其余照改。
     *
     * <p>整批毙掉的话运营还得自己找出是哪条。
     */
    @Test
    void aMixedBatchSkipsTheReadOnlyOnesAndAppliesTheRest() {
        long admin = adminId();
        long virtualId = virtualAccount(ContentSpecies.GENERAL);
        ContentPost seed = postBy(virtualId, "种子的-" + SEQ.incrementAndGet());
        ContentPost ordinary = postBy(newUser().getId(), "普通的-" + SEQ.incrementAndGet());

        int changed = contentManage.setSpeciesOverride(
                List.of(seed.getId(), ordinary.getId()), ContentSpecies.DOG, admin);

        assertThat(changed).isEqualTo(1);
        assertThat(posts.findById(seed.getId()).orElseThrow().getSpeciesOverride())
                .isEqualTo(ContentSpecies.DOG);
        assertThat(posts.findById(ordinary.getId()).orElseThrow().getSpeciesOverride()).isNull();
    }

    /** 非法取值被拒（AC1 的四值枚举）。 */
    @Test
    void anInvalidSpeciesValueIsRejected() {
        long admin = adminId();
        long virtualId = virtualAccount(ContentSpecies.GENERAL);
        ContentPost p = postBy(virtualId, "非法值-" + SEQ.incrementAndGet());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        contentManage.setSpeciesOverride(List.of(p.getId()), "BIRD", admin))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
    }

    /** 内容列表带物种列与来源，且能按来源筛（AC5 的典型用法靠它）。 */
    @Test
    void theContentListCarriesSpeciesAndItsSource() {
        long virtualId = virtualAccount(ContentSpecies.CAT);
        String marker = "列表里的-" + SEQ.incrementAndGet();
        postBy(virtualId, marker);

        var rows = contentManage.browseWithSpecies(null, virtualId, null, null, null, null, 0,
                null, SpeciesSource.ACCOUNT_SPECIES.name());

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.species().source()).isEqualTo(SpeciesSource.ACCOUNT_SPECIES);
            assertThat(r.species().species()).isEqualTo(ContentSpecies.CAT);
            assertThat(r.editable()).as("虚拟账号的内容运营可以改").isTrue();
        });
    }

    /** 🛡 普通用户的内容在列表上标为不可编辑。 */
    @Test
    void ordinaryUserRowsAreMarkedReadOnlyInTheList() {
        User ordinary = newUser();
        postBy(ordinary.getId(), "只读的-" + SEQ.incrementAndGet());

        var rows = contentManage.browseWithSpecies(null, ordinary.getId(), null, null, null, null,
                0, null, null);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(r.editable()).isFalse());
    }

    // ——————————————————— 🛡 AC6 App 端不外露 ———————————————————

    /**
     * 🛡 <b>物种归属是纯算法输入，App 端不展示、也不下发</b>（AC6 最后一条）。
     *
     * <p>本 story 不新增任何内容标签的用户可见展示。判据：Feed 与详情两个接口的响应体里
     * 都不该出现物种字段名。
     */
    @Test
    void theAppFacingApisNeverExposeSpecies() throws Exception {
        User author = newUser();
        long virtualId = virtualAccount(ContentSpecies.CAT);
        ContentPost seed = postBy(virtualId, "算法用的-" + SEQ.incrementAndGet());
        contentManage.setSpeciesOverride(List.of(seed.getId()), ContentSpecies.DOG, adminId());

        String feed = mvc.perform(get("/api/v1/content-posts")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION,
                                userBearer(author.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String detail = mvc.perform(get("/api/v1/content-posts/" + seed.getId())
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION,
                                userBearer(author.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String body : List.of(feed, detail)) {
            assertThat(body).doesNotContain("speciesOverride");
            assertThat(body).doesNotContain("\"species\"");
            assertThat(body).doesNotContain("accountSpecies");
        }
    }

    // ——————————————————— AC2 界面 ———————————————————

    /** 虚拟账号页有账号物种定位这一列，且可改。 */
    @Test
    void theVirtualAccountPageExposesTheAccountTag() throws Exception {
        long virtualId = virtualAccount(ContentSpecies.DOG);

        String html = mvc.perform(get("/admin/virtual-accounts")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("/admin/virtual-accounts/" + virtualId + "/species");
    }

    /** 改账号定位走 HTTP 也通。 */
    @Test
    void theAccountTagCanBeChangedThroughHttp() throws Exception {
        long virtualId = virtualAccount(ContentSpecies.GENERAL);

        mvc.perform(post("/admin/virtual-accounts/" + virtualId + "/species")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("accountSpecies", ContentSpecies.OTHER))
                .andExpect(status().is3xxRedirection());

        assertThat(userRepo.findById(virtualId).orElseThrow().getAccountSpecies())
                .isEqualTo(ContentSpecies.OTHER);
    }

    /** 🔴 真实账号没有这个字段 —— 试着给它设会被拒。 */
    @Test
    void realAccountsCannotBeGivenAnAccountTag() throws Exception {
        User real = newUser();

        mvc.perform(post("/admin/virtual-accounts/" + real.getId() + "/species")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("accountSpecies", ContentSpecies.CAT))
                .andExpect(status().is3xxRedirection());

        assertThat(userRepo.findById(real.getId()).orElseThrow().getAccountSpecies()).isNull();
    }

    /** 单条发布带物种 ⇒ 落成行级覆写（触点③）。 */
    @Test
    void singlePublishWithSpeciesWritesARowOverride() throws Exception {
        long virtualId = virtualAccount(ContentSpecies.GENERAL);
        String marker = "单条带物种-" + SEQ.incrementAndGet();

        mvc.perform(post("/admin/seed-post").with(authentication(superAdmin())).with(csrf())
                        .param("authorUserId", String.valueOf(virtualId))
                        .param("type", ContentType.KNOWLEDGE.name())
                        .param("text", marker)
                        .param("species", ContentSpecies.CAT))
                .andExpect(status().isOk());

        ContentPost saved = postsOf(virtualId).stream()
                .filter(p -> marker.equals(p.getText())).findFirst().orElseThrow();
        assertThat(saved.getSpeciesOverride()).isEqualTo(ContentSpecies.CAT);
    }

    /** 批量发布把行上的物种落成覆写。 */
    @Test
    void batchPublishCarriesTheRowSpeciesIntoTheOverride() {
        long admin = adminId();
        long virtualId = virtualAccount(ContentSpecies.GENERAL);
        long batchId = batchService.openBatch(SeedBatch.Source.EXCEL, admin).getId();
        String marker = "批量带物种-" + SEQ.incrementAndGet();
        batchService.addDraft(batchId, 1, virtualId, ContentType.DAILY, null, marker, null, null,
                ContentSpecies.DOG);

        publishing.confirm(batchId, admin, false);

        ContentPost saved = postsOf(virtualId).stream()
                .filter(p -> marker.equals(p.getText())).findFirst().orElseThrow();
        assertThat(saved.getSpeciesOverride()).isEqualTo(ContentSpecies.DOG);
    }
}
