package com.tailtopia.admin.usertag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.usertag.dto.UserAssignmentRow;
import com.tailtopia.admin.usertag.service.AdminUserTagService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserTag;
import com.tailtopia.auth.dto.UserTagView;
import com.tailtopia.auth.repository.UserTagAssignmentRepository;
import com.tailtopia.auth.repository.UserTagRepository;
import com.tailtopia.auth.service.UserTagQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：用户标签后台（Story 11.3 · AB-12A）。
 *
 * <p>🛡 三条不得弱化的断言：
 * <ul>
 *   <li>🔴 **后台的"会不会展示"必须与 App 侧那份权威实现给出同一答案** ——
 *       用一组"分配时间顺序 ≠ 插入顺序"的数据来验，naive 的"取前 3 条"会给出不同结果。</li>
 *   <li>🔴 **页面必须写明展示上限的后果** —— 纯提示文案，删掉后运营会以为"分配了就看得见"、
 *       发现看不见就当 bug 再分配一次。</li>
 *   <li>**下线只挡新分配，已分配的照旧生效。**</li>
 * </ul>
 */
class AdminUserTagIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private UserTagRepository tags;

    @Autowired
    private UserTagAssignmentRepository assignments;

    @Autowired
    private UserTagQueryService tagService;

    @Autowired
    private AdminUserTagService adminService;

    @Autowired
    private AdminAccountRepository adminAccounts;

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private Authentication superAdminAuth() {
        return authFor(AdminAccountType.SUPER_ADMIN);
    }

    private Authentication authFor(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "utag-" + n + "@tailtopia.test", "用户标签测试员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new TestingAuthenticationToken(principal, null,
                    new java.util.ArrayList<>(principal.getAuthorities()));
        }
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它。
        List<org.springframework.security.core.GrantedAuthority> auths = new java.util.ArrayList<>();
        auths.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
    }

    /** ⚠️ code 每次唯一：共享库 + 唯一约束（Story 11.2 踩过）。 */
    private UserTag tag(String prefix) {
        String code = prefix + "_" + SEQ.incrementAndGet();
        return tags.save(UserTag.of(code, "标签-" + code, "icon.png", "说明"));
    }

    private static String wib(int day, int hour) {
        return String.format("2026-11-%02dT%02d:00", day, hour);
    }

    private static Instant wibInstant(int day, int hour) {
        return ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, WIB).toInstant();
    }

    // ——————————————————— 🔴 展示上限：与 App 侧同源 ———————————————————

    /**
     * 🔴 **本类最重要的一条。**
     *
     * <p>造 5 个生效中的标签，且**分配时间（starts_at）的顺序与插入顺序相反** ——
     * 于是「按分配时间倒序取 3 个」与「按 id 取前 3 个」给出的是**不同的三个**。
     * 后台那一列若是自己排的序，这里必然对不上。
     */

    /**
     * 一张合规的标签图标（Story 11.5：PNG、正方、≥72px）。
     *
     * <p>⚠️ 图标改成**上传**之后，建标签的请求必须是 multipart —— 原先那种
     * {@code .param("icon", "star.png")} 的写法已经不成立（那时 icon 是个文本框）。
     */
    private static MockMultipartFile iconPng() {
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(96, 96, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return new MockMultipartFile("iconFile", "icon.png", "image/png", out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void adminVisibilityColumnAgreesWithTheAppSideAuthority() throws Exception {
        User u = newUser();
        Instant now = wibInstant(10, 12);
        // 🔴 **插入顺序与分配时间顺序必须相反**，否则这条测试区分不出正确与错误实现。
        //    这里插入顺序 1→5、startsAt 依次**更晚**（07:00→11:00）：
        //      「按分配时间倒序取 3」→ 最后插入的三个（id 大）
        //      「按 id 取前 3」（典型的自己再排一遍序）→ 最先插入的三个
        //    两者是**不同的三个**，错的实现会当场对不上。
        //    ⚠️ 本条第一版写成了 `wibInstant(10, 11 - i)`（时间递减），
        //       于是两种算法答案恰好相同 —— 测试是假绿的，把错实现塞进去照样过。
        List<UserTag> created = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UserTag t = tag("CAP");
            created.add(t);
            tagService.assign(u.getId(), t.getId(), wibInstant(10, 7 + i), null);
        }

        // App 侧权威答案
        List<UserTagView> appSide = tagService.findVisibleTags(List.of(u.getId()), now)
                .getOrDefault(u.getId(), List.of());
        assertThat(appSide).as("上限 3 个").hasSize(UserTagQueryService.MAX_VISIBLE);

        // 后台那一列
        List<UserAssignmentRow> rows = adminService.assignmentsByUser(u.getId(), now);
        List<String> adminVisible = rows.stream()
                .filter(UserAssignmentRow::visible).map(UserAssignmentRow::tagCode).sorted().toList();
        List<String> appVisible = appSide.stream().map(UserTagView::code).sorted().toList();

        assertThat(adminVisible)
                .as("🔴 后台的『当前展示中』必须与 App 侧 findVisibleTags 完全一致 —— "
                        + "各写一遍排序的表现是『后台说展示这三个、App 上却是另三个』")
                .isEqualTo(appVisible);

        // 且它确实**不是**"按 id 取前 3 个"：最近分配的是**后**插入的那批。
        assertThat(appVisible).as("最近分配的三个（startsAt 最大）应当是后插入的那三个")
                .containsExactlyInAnyOrder(created.get(2).getCode(), created.get(3).getCode(),
                        created.get(4).getCode());

        // 超出上限的记录**保留在库**、只是不展示。
        assertThat(rows).as("5 条分配都还在").hasSize(5);
        assertThat(rows.stream().filter(r -> !r.visible()).toList())
                .as("超出上限的 2 条保留但不展示").hasSize(2);
    }

    /** 🛡 分配数量本身不设上限 —— 只有展示封顶。 */
    @Test
    void assigningMoreThanTheDisplayCapIsAllowed() throws Exception {
        User u = newUser();
        UserTag a = tag("MANY");
        UserTag b = tag("MANY");
        UserTag c = tag("MANY");
        UserTag d = tag("MANY");
        for (UserTag t : List.of(a, b, c, d)) {
            mvc.perform(post("/admin/user-tags/assign").with(authentication(superAdminAuth())).with(csrf())
                            .param("userIds", String.valueOf(u.getId()))
                            .param("tagId", String.valueOf(t.getId()))
                            .param("startsAt", wib(11, 10)))
                    .andExpect(status().is3xxRedirection());
        }
        assertThat(assignments.findByUserIdOrderByStartsAtDesc(u.getId()))
                .as("第 4 个照样分配成功（只是不展示）").hasSize(4);
    }

    /** 🔴 页面必须写明展示上限的后果。纯提示文案，删掉之后一切照常绿。 */
    @Test
    void pageExplainsTheDisplayCapConsequence() throws Exception {
        String html = mvc.perform(get("/admin/user-tags").with(authentication(superAdminAuth()))
                        .param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("须说明只展示 3 个").contains("3");
        assertThat(html).as("须说明会顶掉最早的那个").contains("顶掉");
        assertThat(html).as("须说明记录保留但不展示").contains("记录保留");
    }

    /** 按用户看且该用户已达上限时，就地再提示一次。 */
    @Test
    void perUserViewWarnsWhenTheCapIsAlreadyReached() throws Exception {
        User u = newUser();
        for (int i = 0; i < 3; i++) {
            UserTag t = tag("HIT");
            tagService.assign(u.getId(), t.getId(), Instant.now().minusSeconds(60 + i), null);
        }
        String html = mvc.perform(get("/admin/user-tags").with(authentication(superAdminAuth()))
                        .param("userId", String.valueOf(u.getId())).param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("已达");
    }

    // ——————————————————— 标签增改下线 ———————————————————

    @Test
    void retiringBlocksNewAssignmentsButKeepsExistingOnes() throws Exception {
        User u = newUser();
        UserTag t = tag("RETIRE");
        tagService.assign(u.getId(), t.getId(), wibInstant(12, 10), null);

        mvc.perform(post("/admin/user-tags/" + t.getId() + "/retire")
                        .with(authentication(superAdminAuth())).with(csrf())
                        .param("retired", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(tags.findById(t.getId()).orElseThrow().isRetired()).isTrue();
        assertThat(assignments.findActiveByTag(t.getId(), wibInstant(12, 12)))
                .as("已分配的照旧生效").hasSize(1);

        // 新分配被拒
        User other = newUser();
        mvc.perform(post("/admin/user-tags/assign").with(authentication(superAdminAuth())).with(csrf())
                .param("userIds", String.valueOf(other.getId()))
                .param("tagId", String.valueOf(t.getId()))
                .param("startsAt", wib(12, 10)));
        assertThat(assignments.findByUserIdOrderByStartsAtDesc(other.getId()))
                .as("已下线的标签不得再被分配").isEmpty();
    }

    @Test
    void duplicateCodeIsRejected() throws Exception {
        String code = "DUP_" + SEQ.incrementAndGet();
        mvc.perform(multipart("/admin/user-tags").file(iconPng()).with(authentication(superAdminAuth())).with(csrf())
                        .param("code", code).param("name", "n")
                        .param("description", "d"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(multipart("/admin/user-tags").file(iconPng()).with(authentication(superAdminAuth())).with(csrf())
                .param("code", code).param("name", "n2")
                .param("description", "d"));
        assertThat(tags.findAllByOrderByIdDesc().stream()
                .filter(t -> code.equals(t.getCode())).toList()).hasSize(1);
    }

    // ——————————————————— 批量分配 ———————————————————

    @Test
    void bulkAssignHandlesMultipleUsersAndDeduplicates() throws Exception {
        User u1 = newUser();
        User u2 = newUser();
        UserTag t = tag("BULK");

        // 同一用户写两次：不该分配两条。
        mvc.perform(post("/admin/user-tags/assign").with(authentication(superAdminAuth())).with(csrf())
                        .param("userIds", u1.getId() + ", " + u2.getId() + " " + u1.getId())
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", wib(13, 10)))
                .andExpect(status().is3xxRedirection());

        assertThat(assignments.findByUserIdOrderByStartsAtDesc(u1.getId())).hasSize(1);
        assertThat(assignments.findByUserIdOrderByStartsAtDesc(u2.getId())).hasSize(1);
    }

    @Test
    void bulkAssignRejectsNonNumericIds() throws Exception {
        UserTag t = tag("BADID");
        mvc.perform(post("/admin/user-tags/assign").with(authentication(superAdminAuth())).with(csrf())
                        .param("userIds", "1001, abc")
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", wib(14, 10)))
                .andExpect(status().is3xxRedirection());
        assertThat(assignments.findActiveByTag(t.getId(), wibInstant(14, 12))).isEmpty();
    }

    // ——————————————————— 时区与永久 ———————————————————

    @Test
    void emptyEndMeansPermanentAndTimesAreWib() throws Exception {
        User u = newUser();
        UserTag t = tag("FOREVER");
        mvc.perform(post("/admin/user-tags/assign").with(authentication(superAdminAuth())).with(csrf())
                .param("userIds", String.valueOf(u.getId()))
                .param("tagId", String.valueOf(t.getId()))
                .param("startsAt", wib(15, 10)));
        var saved = assignments.findByUserIdOrderByStartsAtDesc(u.getId()).get(0);
        assertThat(saved.getEndsAt()).isNull();
        // WIB 10:00 = UTC 03:00
        assertThat(saved.getStartsAt()).isEqualTo(Instant.parse("2026-11-15T03:00:00Z"));
    }

    @Test
    void pageLabelsTheTimezone() throws Exception {
        String html = mvc.perform(get("/admin/user-tags").with(authentication(superAdminAuth()))
                        .param("lang", "zh_CN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("WIB");
    }

    // ——————————————————— 🛡 双权限码 ———————————————————

    @Test
    void viewPermissionAloneCannotWrite() throws Exception {
        Authentication viewer = authFor(AdminAccountType.STAFF, "user.tag_view");
        mvc.perform(get("/admin/user-tags").with(authentication(viewer)))
                .andExpect(status().isOk());
        String code = "NOPE_" + SEQ.incrementAndGet();
        mvc.perform(multipart("/admin/user-tags").file(iconPng())
                        .with(authentication(viewer)).with(csrf())
                        .param("code", code).param("name", "x")
                        .param("description", "x"))
                .andExpect(status().isForbidden());
        assertThat(tags.findByCode(code)).isEmpty();
    }

    @Test
    void withoutAnyTagPermissionThePageIsForbidden() throws Exception {
        mvc.perform(get("/admin/user-tags")
                        .with(authentication(authFor(AdminAccountType.STAFF, "user.view"))))
                .andExpect(status().isForbidden());
    }

    /** 🛡 侧栏可见性与入口门一致。 */
    @Test
    void navShowsUserTagsForViewOnlyStaff() throws Exception {
        String html = mvc.perform(get("/admin/user-tags").param("lang", "zh_CN")
                        .with(authentication(authFor(AdminAccountType.STAFF, "user.tag_view"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/admin/user-tags");
    }

    /**
     * 🔴 **标签不许分配给已注销账号**（bug 20260828）。
     *
     * <p>实机：运营把用户标签分给了 63 号 —— 一个已经注销的账号，后台一声不吭地接受了。
     * 此前 {@code assign} 对 userId **一个字都不查**：不存在的 id 也照写。
     *
     * <p>为什么这不只是脏数据：注销走的是「就地匿名化」（Story 7.3 · 决策 D1），
     * 账号的昵称/头像/邮箱都被擦成空、UGC 解析为「已注销用户」。
     * 给它挂一枚身份标签，等于把一个本该没有身份标识的账号重新标记出来；
     * 而分配记录页按 userId 展示，运营会看到一行查无此人、也无从判断该不该撤。
     *
     * <p>⚠️ 断言打在 {@code UserTagQueryService.assign} 这一层，不是后台控制器 ——
     * 后台批量、后台单个、将来任何自动发标签的路径都经过它。
     */
    @Test
    void assigningToADeletedAccountIsRejected() {
        UserTag t = tag("DEL");
        User u = newUser();
        u.anonymizeForDeletion(Instant.now());
        users.save(u);

        assertThatThrownBy(() -> tagService.assign(u.getId(), t.getId(), Instant.now(), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已注销");
        assertThat(assignments.findByUserIdOrderByStartsAtDesc(u.getId())).isEmpty();
    }

    /** 🛡 连"用户根本不存在"也要拦 —— 手填框里填错一位数字就是这种情况。 */
    @Test
    void assigningToANonExistentUserIsRejected() {
        UserTag t = tag("GHOST");
        assertThatThrownBy(() -> tagService.assign(999_000_111L, t.getId(), Instant.now(), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不存在");
    }

    /**
     * 🛡 反向：**正常用户照旧分配得上**。
     *
     * <p>没有这一条，把校验写成"一律拒绝"也能让上面两条绿。
     */
    @Test
    void assigningToAnActiveUserStillWorks() {
        UserTag t = tag("OK");
        User u = newUser();
        tagService.assign(u.getId(), t.getId(), Instant.now().minusSeconds(60), null);
        assertThat(assignments.findByUserIdOrderByStartsAtDesc(u.getId())).hasSize(1);
    }

    /**
     * 🔴 **选择器里列不出已注销账号** —— 服务端硬校验之外的第一道闸。
     *
     * <p>两道都要有：只有硬校验的话，运营要在候选表里挑到一个注销账号、点了分配、
     * 才收到一句报错；只有选择器的话，手填 ID 那条路照样绕过去。
     */
    @Test
    void deletedAccountsNeverAppearInThePicker() {
        User alive = newUser();
        User gone = newUser();
        gone.anonymizeForDeletion(Instant.now());
        users.save(gone);

        List<Long> ids = adminService.pickableUsers(null, 0).stream()
                .map(com.tailtopia.admin.usertag.dto.TaggableUserRow::id).toList();
        assertThat(ids).contains(alive.getId());
        assertThat(ids).doesNotContain(gone.getId());
    }
}
