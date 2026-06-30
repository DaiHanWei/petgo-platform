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

        List<AdminUserRow> byId = adminUserService.search(String.valueOf(u.getId()));
        assertThat(byId).extracting(AdminUserRow::id).containsExactly(u.getId());

        List<AdminUserRow> byEmail = adminUserService.search(u.getEmail());
        assertThat(byEmail).extracting(AdminUserRow::id).containsExactly(u.getId());

        AdminUserDetailView detail = adminUserService.detail(u.getId());
        assertThat(detail.id()).isEqualTo(u.getId());
        assertThat(detail.email()).isEqualTo(u.getEmail());
        assertThat(detail.deactivated()).isFalse(); // 3.2 前恒正常
        assertThat(detail.posts()).isNotNull();
        assertThat(detail.sessions()).isNotNull();
    }

    @Test
    void searchUnknownReturnsEmpty() {
        assertThat(adminUserService.search("999000111")).isEmpty();
        assertThat(adminUserService.search("nobody-" + SEQ.incrementAndGet() + "@none.test")).isEmpty();
    }
}
