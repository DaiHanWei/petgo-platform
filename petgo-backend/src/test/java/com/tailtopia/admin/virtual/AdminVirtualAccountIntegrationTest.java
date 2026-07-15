package com.tailtopia.admin.virtual;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（Story 9.8，A-6）：真 pg——V82 迁移（account_type 默认 REAL）、建虚拟账号（VIRTUAL 无密码）、启停。
 * schema validate 由上下文启动隐式验证。
 */
class AdminVirtualAccountIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminVirtualAccountService service;
    @Autowired
    private UserRepository users;

    @Test
    void realUserDefaultsToRealAccountType() {
        User u = newUser(); // 走 User.newGoogleUser
        assertThat(users.findById(u.getId()).orElseThrow().getAccountType()).isEqualTo(AccountType.REAL);
    }

    @Test
    void createVirtualAccountHasNoLoginAndListable() {
        long id = service.create("种子喵", null, 1L);

        User v = users.findById(id).orElseThrow();
        assertThat(v.getAccountType()).isEqualTo(AccountType.VIRTUAL);
        assertThat(v.getPasswordHash()).isNull();
        assertThat(v.getGoogleSub()).startsWith("virtual:");
        assertThat(v.getCreatedBy()).isEqualTo(1L);
        assertThat(service.list()).extracting("id").contains(id);
    }

    @Test
    void toggleEnabledPersists() {
        long id = service.create("种子汪", null, 1L);
        service.setEnabled(id, false, 1L);
        assertThat(users.findById(id).orElseThrow().isEnabled()).isFalse();
        service.setEnabled(id, true, 1L);
        assertThat(users.findById(id).orElseThrow().isEnabled()).isTrue();
    }
}
