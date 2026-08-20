package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1：黑名单列表接口 {@code GET /api/v1/me/blocked-users}（Story 1.5，FR-94）—— 需 Docker postgres+redis。
 *
 * <p>覆盖 AC2（只收 BLOCK / 倒序 / 追加 REPORT 不改位置 / 已举报标记）· AC8（注销匿名态、封号无标记）·
 * AC9（跨模块取展示字段）· 越权隔离与 401。
 */
class BlockedUsersListIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private UserRepository userRepo;

    private List<Long> blockedIdsOf(long userId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/me/blocked-users")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk())
                .andReturn();
        var arr = json.readTree(r.getResponse().getContentAsString());
        return java.util.stream.StreamSupport.stream(arr.spliterator(), false)
                .map(n -> n.get("userId").asLong())
                .toList();
    }

    private tools.jackson.databind.JsonNode rowOf(long userId, long targetId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/me/blocked-users")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk())
                .andReturn();
        for (var n : json.readTree(r.getResponse().getContentAsString())) {
            if (n.get("userId").asLong() == targetId) {
                return n;
            }
        }
        throw new AssertionError("黑名单里没有 " + targetId);
    }

    // ===== AC2 · 列表内容与排序 =====

    /** 只收录主动拉黑：举报产生的隐藏<b>不出现在本页</b>（它没有解除入口，混进来用户会解除不掉）。 */
    @Test
    void ac2_onlyBlockSourcedRelationsAreListed() throws Exception {
        User me = newUser();
        User blocked = newUser();
        User reportedOnly = newUser();

        hideService.block(me.getId(), blocked.getId());
        hideService.hideByReport(me.getId(), reportedOnly.getId());

        assertThat(blockedIdsOf(me.getId())).containsExactly(blocked.getId());
    }

    /** 按拉黑时间倒序：后拉黑的在前。 */
    @Test
    void ac2_orderedByBlockTimeDescending() throws Exception {
        User me = newUser();
        User first = newUser();
        User second = newUser();

        hideService.block(me.getId(), first.getId());
        Thread.sleep(20); // 拉开 created_at，避免同毫秒导致顺序不确定
        hideService.block(me.getId(), second.getId());

        assertThat(blockedIdsOf(me.getId())).containsExactly(second.getId(), first.getId());
    }

    /**
     * ⚠️ 事后又举报了这个人，<b>他在列表里的位置不许变</b>。
     *
     * <p>三元唯一键保证举报写的是另一行、碰不到 BLOCK 行——但那只保证行不被覆盖，
     * <b>不保证排序不被搅乱</b>（比如实现取了 updated_at，或哪天有人给这张表加了 touch）。
     * 真出问题时用户看到的是：「我今天什么都没做，这个三个月前拉黑的人怎么跑到最前面了」。
     */
    @Test
    void ac2_addingReportRowDoesNotMoveTheEntry() throws Exception {
        User me = newUser();
        User older = newUser();
        User newer = newUser();

        hideService.block(me.getId(), older.getId());
        Thread.sleep(20);
        hideService.block(me.getId(), newer.getId());
        assertThat(blockedIdsOf(me.getId())).containsExactly(newer.getId(), older.getId());

        Thread.sleep(20);
        hideService.hideByReport(me.getId(), older.getId()); // 事后举报那个更早拉黑的

        assertThat(blockedIdsOf(me.getId()))
                .as("举报之后顺序必须一字不变")
                .containsExactly(newer.getId(), older.getId());
        assertThat(rowOf(me.getId(), older.getId()).get("reported").asBoolean()).isTrue();
    }

    /** 没被举报过的条目 reported=false（布尔是原始类型，键恒在）。 */
    @Test
    void ac2_reportedFlagIsFalseWhenOnlyBlocked() throws Exception {
        User me = newUser();
        User target = newUser();
        hideService.block(me.getId(), target.getId());

        assertThat(rowOf(me.getId(), target.getId()).get("reported").asBoolean()).isFalse();
    }

    /** 拉黑时间取 BLOCK 行的 created_at，序列化为 ISO-8601（不是 epoch millis）。 */
    @Test
    void ac2_blockedAtIsIso8601() throws Exception {
        User me = newUser();
        User target = newUser();
        hideService.block(me.getId(), target.getId());

        String blockedAt = rowOf(me.getId(), target.getId()).get("blockedAt").asText();
        assertThat(Instant.parse(blockedAt)).isNotNull(); // 解析不了就说明格式变了
    }

    // ===== AC8 · 封号 ≠ 注销（高风险点 R5）=====

    /**
     * 已注销的人：匿名态（nickname/avatarUrl 键消失）+ {@code deleted=true}，
     * <b>条目保留、不自动移除</b>——用户仍需能把他从名单里清掉。
     */
    @Test
    void ac8_deletedUserIsAnonymizedButStaysInTheList() throws Exception {
        User me = newUser();
        User target = newUser();
        hideService.block(me.getId(), target.getId());

        target.anonymizeForDeletion(Instant.now());
        userRepo.save(target);

        var row = rowOf(me.getId(), target.getId());
        assertThat(row.get("deleted").asBoolean()).isTrue();
        // default-property-inclusion: non_null → null 字段整个键消失（前端必须按可空键解析）
        assertThat(row.has("nickname")).isFalse();
        assertThat(row.has("avatarUrl")).isFalse();
    }

    /**
     * ⚠️ 被运营<b>封号</b>的人：照常展示昵称，<b>没有任何标记字段</b>。
     *
     * <p>封号是平台侧处置，不该通过黑名单页透露给用户。这条断言的是响应里
     * <b>压根不存在这么一个字段</b>——有人日后"顺手"加一个 {@code banned}/{@code suspended} 就会红。
     */
    @Test
    void ac8_bannedUserLooksExactlyLikeANormalOne() throws Exception {
        User me = newUser();
        User target = newUser();
        hideService.block(me.getId(), target.getId());

        target.deactivate(); // 运营封号（UserStatus.DEACTIVATED，可逆），≠ 注销
        userRepo.save(target);

        var row = rowOf(me.getId(), target.getId());
        assertThat(row.get("deleted").asBoolean()).isFalse();       // 不是注销
        assertThat(row.get("nickname").asText()).isNotBlank();      // 昵称照常
        assertThat(row.propertyNames())
                .containsExactlyInAnyOrder("userId", "nickname", "deleted", "reported", "blockedAt");
    }

    // ===== 越权与鉴权 =====

    /** 只返回当前用户自己的黑名单，绝不含他人的。 */
    @Test
    void returnsOnlyCurrentUsersOwnList() throws Exception {
        User me = newUser();
        User other = newUser();
        User mine = newUser();
        User theirs = newUser();

        hideService.block(me.getId(), mine.getId());
        hideService.block(other.getId(), theirs.getId());

        assertThat(blockedIdsOf(me.getId())).containsExactly(mine.getId());
        assertThat(blockedIdsOf(other.getId())).containsExactly(theirs.getId());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me/blocked-users")).andExpect(status().isUnauthorized());
    }

    /** 空名单 → 空数组（不是 404、不是 null），设置页据长度显示 0。 */
    @Test
    void emptyListIsAnEmptyArray() throws Exception {
        User me = newUser();
        mvc.perform(get("/api/v1/me/blocked-users")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 解除拉黑后条目从列表消失（Story 1.1 的 DELETE 契约不变，这里只验列表联动）。 */
    @Test
    void unblockRemovesTheEntry() throws Exception {
        User me = newUser();
        User target = newUser();
        hideService.block(me.getId(), target.getId());
        assertThat(blockedIdsOf(me.getId())).contains(target.getId());

        hideService.unblock(me.getId(), target.getId());

        assertThat(blockedIdsOf(me.getId())).doesNotContain(target.getId());
    }

    /**
     * 也被举报过的人，解除拉黑后同样从<b>本页</b>消失（拉黑关系确实解除了），
     * 但 REPORT 行还在——他的内容依旧不展示。这正是 AC6 第二句 Toast 要说的事。
     */
    @Test
    void unblockRemovesEntryEvenWhenAlsoReported() throws Exception {
        User me = newUser();
        User target = newUser();
        hideService.block(me.getId(), target.getId());
        hideService.hideByReport(me.getId(), target.getId());

        hideService.unblock(me.getId(), target.getId());

        assertThat(blockedIdsOf(me.getId())).doesNotContain(target.getId());
    }
}
