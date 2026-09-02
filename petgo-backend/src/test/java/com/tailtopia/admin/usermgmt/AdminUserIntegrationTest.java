package com.tailtopia.admin.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.usermgmt.dto.AdminUserDetailView;
import com.tailtopia.admin.usermgmt.dto.AdminUserRow;
import com.tailtopia.admin.usermgmt.service.AdminUserService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：用户搜索 + 详情聚合（Story 3.1，需 Docker postgres）。真实经 owning service 聚合读；只读不写。
 */
class AdminUserIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;

    @Test
    void searchByIdAndEmailHitsAndDetailAggregates() {
        User u = newUser(); // ApiIntegrationTest：持久化 USER（唯一 sub/email）

        // 2026-09-02 起：id 精确命中排最前；昵称含相同数字串的用户会作为模糊命中跟在后面。
        List<AdminUserRow> byId = adminUserService.search(String.valueOf(u.getId()));
        assertThat(byId).isNotEmpty();
        assertThat(byId.get(0).id()).isEqualTo(u.getId());

        List<AdminUserRow> byEmail = adminUserService.search(u.getEmail());
        assertThat(byEmail).extracting(AdminUserRow::id).containsExactly(u.getId());

        AdminUserDetailView detail = adminUserService.detail(u.getId());
        assertThat(detail.id()).isEqualTo(u.getId());
        assertThat(detail.email()).isEqualTo(u.getEmail());
        assertThat(detail.deactivated()).isFalse(); // 3.2 前恒正常
        assertThat(detail.posts()).isNotNull();
        assertThat(detail.sessions()).isNotNull();
    }

    /** 2026-09-02：昵称也能搜 —— 子串、大小写不敏感。 */
    @Test
    void searchByNicknameFuzzyMatches() {
        // ⚠️ nickname 列是 varchar(20)：SEQ 是 nanoTime 起步的长数字，取模压短。
        long n = SEQ.incrementAndGet() % 1_000_000_000L;
        User u = newUser();
        u.setNickname("Kucing" + n);
        users.save(u);

        List<AdminUserRow> rows = adminUserService.search("kucing" + n);
        assertThat(rows).extracting(AdminUserRow::id).containsExactly(u.getId());
        assertThat(rows.get(0).displayName()).isEqualTo("Kucing" + n);
    }

    @Test
    void searchUnknownReturnsEmpty() {
        assertThat(adminUserService.search("999000111")).isEmpty();
        assertThat(adminUserService.search("nobody-" + SEQ.incrementAndGet() + "@none.test")).isEmpty();
    }
}
