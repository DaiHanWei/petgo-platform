package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.support.ApiIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * L1 集成：单条发布整改（V1.1.6 Story 12.2 · AB-3J）。
 *
 * <p><b>本 story 落地 V1.1.0 AB-1.1-03 那两条从未交付的要求</b>：发布账号可选、图片可直传。
 * 顺带修好一件事 —— <b>成长日历（{@code GROWTH_MOMENT}）此前实际发不出来</b>：
 * 它必须绑一份宠物档案，而作者被写死为"登录后台账号所关联的官方作者身份"，那个账号没有档案。
 *
 * <h2>🛡 三条不得弱化的断言</h2>
 * <ul>
 *   <li><b>超比例图不被阻止，但必须给出带数字的警告</b>（A-13：后台不做裁剪框）——
 *       问题不是运营不能裁，而是<b>不知道会被裁</b>。</li>
 *   <li><b>HEIC 被拒，且理由要说清"是 HEIC"</b>（A-12）—— 只说"格式不支持"运营会反复重试同一张图。</li>
 *   <li><b>"不信任客户端 author" 换了守法但没放弃</b>：作者来自表单，服务端校验它在身份池内。</li>
 * </ul>
 */
@Import(AdminSeedPostIntegrationTest.StubOss.class)
class AdminSeedPostIntegrationTest extends ApiIntegrationTest {

    /**
     * 假对象存储：本地跑测试没有阿里云凭证。
     *
     * <p>🛡 只替掉"把字节送出去"这一步 —— 校验、量宽高、算裁切量全部走真实代码。
     */
    @TestConfiguration
    static class StubOss {
        @Bean
        @Primary
        AliyunOssClient stubOssClient(com.tailtopia.shared.media.MediaProperties props) {
            return new AliyunOssClient(props) {
                @Override
                public String putPublicObject(String objectKey, byte[] bytes, String contentType) {
                    return "https://cdn.test/" + objectKey;
                }
            };
        }
    }

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private PetProfileRepository pets;

    @Autowired
    private AdminVirtualAccountService virtualAccounts;

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "seed-" + n + "@tailtopia.test", "单条发布测试员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new TestingAuthenticationToken(principal, null,
                    new java.util.ArrayList<>(principal.getAuthorities()));
        }
        List<GrantedAuthority> auths = new java.util.ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
    }

    private Authentication superAdmin() {
        return auth(AdminAccountType.SUPER_ADMIN);
    }

    /** 池内一个真实账号 + 它名下的宠物档案（成长日历必须绑档案）。 */
    private User realAccountWithPet() throws Exception {
        User u = newUser();
        mvc.perform(post("/admin/publish-identities").with(authentication(superAdmin())).with(csrf())
                        .param("userId", String.valueOf(u.getId()))
                        .param("authorizationNote", "IP 号，用于成长日历"))
                .andExpect(status().is3xxRedirection());
        pets.save(PetProfile.create(u.getId(), com.tailtopia.profile.domain.PetType.CAT,
                "小花-" + SEQ.incrementAndGet(), null, null, LocalDate.of(2025, 1, 1), null,
                "tok" + SEQ.incrementAndGet()));
        return u;
    }

    /** 造一张指定像素的真 PNG —— 宽高要真的能被读出来，塞随机字节是量不出尺寸的。 */
    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private List<ContentPost> postsOf(User u) {
        return posts.findAll().stream()
                // ⚠️ Objects.equals：getAuthorId() 是装箱 Long，`==` 比引用、id 一大就恒 false。
                .filter(p -> Objects.equals(p.getAuthorId(), u.getId())).toList();
    }

    // ——————————————————— 🛡 AC3 裁切警告 ———————————————————

    /**
     * 🛡 <b>超比例图不被阻止，但警告必须带具体数字与方向。</b>
     *
     * <p>笼统一句"会被裁切"运营看了也不知道要不要重裁。这里钉住三样：
     * 上传成功（200）、有 warning、且 warning 里含百分比数字。
     */
    @Test
    void wideImageIsAcceptedButWarnedWithConcreteNumbers() throws Exception {
        String body = mvc.perform(multipart("/admin/seed-post/images")
                        .file(new MockMultipartFile("file", "wide.png", "image/png", png(1920, 1080)))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var node = json.readTree(body);
        assertThat(node.get("url").asText()).isNotBlank();
        assertThat(node.get("w").asInt()).isEqualTo(1920);
        assertThat(node.get("h").asInt()).isEqualTo(1080);
        String warning = node.get("warning").asText();
        // 16:9 ⇒ 共裁约 25%、每侧约 12%。两个数都要在文案里，只给一个必然被读错。
        assertThat(warning).contains("25").contains("12");
    }

    /** 区间内的图不该有警告 —— 乱报会让运营去裁一张本来没问题的图。 */
    @Test
    void squareImageGetsNoWarning() throws Exception {
        String body = mvc.perform(multipart("/admin/seed-post/images")
                        .file(new MockMultipartFile("file", "sq.png", "image/png", png(1000, 1000)))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // ⚠️ 无警告时该字段**根本不出现**（NON_NULL 序列化），不是 `"warning": null` ——
        //    先按 `get("warning").isNull()` 断言，结果 get 返回 Java null 直接 NPE。
        assertThat(json.readTree(body).hasNonNull("warning")).isFalse();
    }

    // ——————————————————— 🛡 AC2 格式与大小 ———————————————————

    /** 🛡 HEIC 被拒，且**理由要说清是 HEIC**（A-12）。 */
    @Test
    void heicIsRejectedWithAReasonThatNamesTheFormat() throws Exception {
        String body = mvc.perform(multipart("/admin/seed-post/images")
                        .file(new MockMultipartFile("file", "IMG_1234.HEIC", "image/heic",
                                new byte[] {1, 2, 3}))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("error").asText())
                .as("只说「格式不支持」运营会反复重试同一张图")
                .containsIgnoringCase("HEIC");
    }

    /**
     * ⚠️ 浏览器给 HEIC 的 content-type 并不统一（有时是 {@code application/octet-stream}）——
     * 所以顺带看文件名。这条钉住"靠扩展名也能认出来"。
     */
    @Test
    void heicIsAlsoCaughtByFileExtensionWhenTheBrowserLies() throws Exception {
        String body = mvc.perform(multipart("/admin/seed-post/images")
                        .file(new MockMultipartFile("file", "photo.heif", "application/octet-stream",
                                new byte[] {1, 2, 3}))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("error").asText()).containsIgnoringCase("HEIC");
    }

    /** 🛡 上传失败一律 400 + {@code error}，**不是 500** —— 这些都是预期内的用户输入。 */
    @Test
    void oversizedImageIsRejectedWithFourHundredNotFiveHundred() throws Exception {
        byte[] tooBig = new byte[11 * 1024 * 1024];
        mvc.perform(multipart("/admin/seed-post/images")
                        .file(new MockMultipartFile("file", "big.jpg", "image/jpeg", tooBig))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // ——————————————————— 🛡 AC1 发布账号来自表单 ———————————————————

    /**
     * 🔴 <b>成长日历现在真的发得出来了</b>（AC4 后半条）。
     *
     * <p>此前这条路必然失败：{@code GROWTH_MOMENT} 要绑宠物档案，
     * 而作者被写死为"登录后台账号关联的官方作者身份" —— 那个账号没有档案，
     * 于是"该宠物是否属于所选作者"这条校验永远过不去，且错误文案看不出原因。
     */
    @Test
    void growthMomentCanFinallyBePublishedWithARealAccountThatHasAPet() throws Exception {
        User u = realAccountWithPet();
        long petId = pets.findByOwnerId(u.getId()).orElseThrow().getId();
        String marker = "成长日历-" + SEQ.incrementAndGet();

        mvc.perform(post("/admin/seed-post").with(authentication(superAdmin())).with(csrf())
                        .param("authorUserId", String.valueOf(u.getId()))
                        .param("type", ContentType.GROWTH_MOMENT.name())
                        .param("petId", String.valueOf(petId))
                        .param("text", marker))
                .andExpect(status().isOk());

        assertThat(postsOf(u)).extracting(ContentPost::getText).contains(marker);
    }

    /** 上传时量到的原始宽高要**一起入库** —— 否则刚发完就刷首页的人看到的仍是占位比例。 */
    @Test
    void uploadedImageSizesArePersistedWithThePost() throws Exception {
        User u = realAccountWithPet();
        String marker = "带尺寸的一条-" + SEQ.incrementAndGet();

        mvc.perform(post("/admin/seed-post").with(authentication(superAdmin())).with(csrf())
                        .param("authorUserId", String.valueOf(u.getId()))
                        .param("type", ContentType.DAILY.name())
                        .param("text", marker)
                        .param("imageUrlsRaw", "https://cdn.test/a.jpg\nhttps://cdn.test/b.jpg")
                        .param("imageSizesRaw", "1200x900\n800x800"))
                .andExpect(status().isOk());

        ContentPost saved = postsOf(u).stream()
                .filter(p -> marker.equals(p.getText())).findFirst().orElseThrow();
        assertThat(saved.getImageSizes())
                .containsExactly(new com.tailtopia.content.domain.ImageSize(1200, 900),
                        new com.tailtopia.content.domain.ImageSize(800, 800));
    }

    /**
     * 🛡 尺寸与 URL **长度不符 ⇒ 整组作废**，绝不"跳过不放"。
     *
     * <p>错位比没有更糟：第 2 张图会套上第 3 张的比例，运营看到的卡片高度莫名其妙。
     *
     * <p>🔴 <b>"整组作废"的既有契约是「同序等长的全 null 占位」，不是"这一列变成 null"</b>
     * （见 {@code ImageSizeResolver#normalize}）——全 null 表示"每一张都交给异步兜底去量"。
     * 我一开始按"整列为 null"写断言，红了一次：那是<b>断言错，不是实现错</b>。
     * 真把这一列去掉反而会让它与 imageUrls 长度不符，制造出 Story 3.5 记过的那类问题。
     */
    @Test
    void mismatchedSizeCountIsDroppedWholesaleRatherThanMisaligned() throws Exception {
        User u = realAccountWithPet();
        String marker = "长度不符-" + SEQ.incrementAndGet();

        mvc.perform(post("/admin/seed-post").with(authentication(superAdmin())).with(csrf())
                        .param("authorUserId", String.valueOf(u.getId()))
                        .param("type", ContentType.DAILY.name())
                        .param("text", marker)
                        .param("imageUrlsRaw", "https://cdn.test/a.jpg\nhttps://cdn.test/b.jpg")
                        .param("imageSizesRaw", "1200x900"))
                .andExpect(status().isOk());

        ContentPost saved = postsOf(u).stream()
                .filter(p -> marker.equals(p.getText())).findFirst().orElseThrow();
        assertThat(saved.getImageSizes())
                .as("长度不符 ⇒ 同序等长的全 null 占位，交服务端异步兜底")
                .hasSize(2)
                .containsOnlyNulls();
    }

    /** 🛡 池外账号不能作为作者 —— 与批量发布同一口径。 */
    @Test
    void authorOutsideThePoolIsRejected() throws Exception {
        User outsider = newUser();
        String marker = "池外不该发出去-" + SEQ.incrementAndGet();

        mvc.perform(post("/admin/seed-post").with(authentication(superAdmin())).with(csrf())
                        .param("authorUserId", String.valueOf(outsider.getId()))
                        .param("type", ContentType.DAILY.name())
                        .param("text", marker))
                .andExpect(status().isOk());

        assertThat(postsOf(outsider)).isEmpty();
    }

    /** 🛡 只持 {@code virtual_account.manage} 的人不能以运营真实账号发单条（三处同一口径之二）。 */
    @Test
    void publishingAsARealIdentityNeedsTheDedicatedPermissionHereToo() throws Exception {
        User u = realAccountWithPet();
        String marker = "越权单条-" + SEQ.incrementAndGet();

        mvc.perform(post("/admin/seed-post")
                        .with(authentication(auth(AdminAccountType.STAFF,
                                AdminPermissions.VIRTUAL_ACCOUNT_MANAGE)))
                        .with(csrf())
                        .param("authorUserId", String.valueOf(u.getId()))
                        .param("type", ContentType.DAILY.name())
                        .param("text", marker))
                .andExpect(status().isOk());

        assertThat(postsOf(u)).extracting(ContentPost::getText).doesNotContain(marker);
    }

    // ——————————————————— AC4 宠物下拉 ———————————————————

    /** 选中的账号名下有档案 ⇒ 下拉里出现它。 */
    @Test
    void petDropdownListsOnlyThePetsOfTheChosenAccount() throws Exception {
        User u = realAccountWithPet();
        PetProfile pet = pets.findByOwnerId(u.getId()).orElseThrow();

        String html = mvc.perform(get("/admin/seed-post/pets")
                        .param("authorUserId", String.valueOf(u.getId()))
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(pet.getName());
        assertThat(html).contains("value=\"" + pet.getId() + "\"");
    }

    /**
     * 🔴 虚拟账号必然是空 —— 它没有宠物档案。
     *
     * <p>空态必须**说明原因**：不写这句，运营只会看到一个空下拉，
     * 然后回去手填 ID（那正是本 story 要去掉的东西）。
     */
    @Test
    void virtualAccountHasNoPetAndTheEmptyStateExplainsWhy() throws Exception {
        long virtualId = virtualAccounts.create("虚拟号-" + SEQ.incrementAndGet(), null, 1L);

        String html = mvc.perform(get("/admin/seed-post/pets")
                        .param("authorUserId", String.valueOf(virtualId))
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("空态要说清为什么，而不是只给一个空下拉")
                .containsIgnoringCase("Diary");
    }

    /** 没给账号（页面刚打开）⇒ 空下拉，不该 500。 */
    @Test
    void petDropdownWithoutAnAccountRendersEmptyInsteadOfFailing() throws Exception {
        mvc.perform(get("/admin/seed-post/pets").with(authentication(superAdmin())))
                .andExpect(status().isOk());
    }
}
