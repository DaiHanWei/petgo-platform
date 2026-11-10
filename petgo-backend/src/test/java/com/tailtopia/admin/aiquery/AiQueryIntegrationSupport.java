package com.tailtopia.admin.aiquery;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** ops AI 查数 L1 测试基类：造后台账号身份；开关在用例内切换，结束后恢复默认 off（不新起 Spring 上下文）。 */
abstract class AiQueryIntegrationSupport extends ApiIntegrationTest {

    @Autowired
    protected AdminAccountRepository adminAccounts;

    @Autowired
    protected AiQueryProperties aiQueryProperties;

    @AfterEach
    void resetMode() {
        aiQueryProperties.setMode("off");
    }

    protected void enable() {
        aiQueryProperties.setMode("on");
    }

    /** 持有给定权限码的 STAFF（ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它）。 */
    protected Actor staff(String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.create(
                "aiq-" + n + "@tailtopia.test", "AI 查数测试员" + n, AdminRole.CUSTOM, null));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF);
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String s : permissions) {
            auths.add(new SimpleGrantedAuthority(s));
        }
        return new Actor(acc.getId(), new TestingAuthenticationToken(p, null, auths));
    }

    protected Actor querier() {
        return staff(AdminPermissions.AI_QUERY);
    }

    protected Actor superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "aiq-sa-" + n + "@tailtopia.test", "AI 查数超管" + n, "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new Actor(acc.getId(), new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities())));
    }

    protected record Actor(long accountId, Authentication auth) {
    }

    protected static String visible(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }
}
