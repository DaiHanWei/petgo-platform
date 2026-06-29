package com.tailtopia.admin.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首个超级管理员开户（Story 1.1 重构，AG-1）。**凭证 env 注入绝不入库**：仅当
 * {@code ADMIN_BOOTSTRAP_EMAIL} + {@code ADMIN_BOOTSTRAP_PASSWORD} 同时由 env 提供时，
 * 启动时幂等 upsert，否则跳过（无默认管理员）。
 *
 * <p>upsert <b>两处</b>：
 * <ol>
 *   <li>{@code admin_accounts} 的 {@code SUPER_ADMIN}（认证源真相，密码 BCrypt）；</li>
 *   <li>{@code users(role=ADMIN)} 的官方内容作者 shim（AC5：种子发帖 {@code content_posts.author_id} 有 FK→users，
 *       后台账号迁出 users 表后仍需一个合法 users.id 承载内容作者；{@code AdminUserDetailsService} 按同邮箱解析它）。
 *       此 users 行<b>不再</b>作为后台登录依据（旧 V7 password_hash 列保留停用），仅作内容作者。</li>
 * </ol>
 * 明文密码绝不落库/日志（仅记 email 与 created/updated 事件）。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminAccountRepository adminAccounts;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    public AdminBootstrap(AdminAccountRepository adminAccounts, UserRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${ADMIN_BOOTSTRAP_EMAIL:}") String bootstrapEmail,
            @Value("${ADMIN_BOOTSTRAP_PASSWORD:}") String bootstrapPassword) {
        this.adminAccounts = adminAccounts;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapEmail == null || bootstrapEmail.isBlank()
                || bootstrapPassword == null || bootstrapPassword.isBlank()) {
            return; // 未配置 env → 不开户（无默认管理员）
        }
        String hash = passwordEncoder.encode(bootstrapPassword);

        // 1) admin_accounts 超管（认证源真相）
        adminAccounts.findByLarkEmail(bootstrapEmail).ifPresentOrElse(existing -> {
            existing.setPasswordHash(hash);
            existing.setStatus(AdminAccountStatus.ACTIVE);
            adminAccounts.save(existing);
            log.info("ADMIN bootstrap：已重置超管密码（admin_accounts）email={}", bootstrapEmail);
        }, () -> {
            adminAccounts.save(AdminAccount.newSuperAdmin(bootstrapEmail, "TailTopia 运营", hash));
            log.info("ADMIN bootstrap：已创建超管（admin_accounts）email={}", bootstrapEmail);
        });

        // 2) users(role=ADMIN) 官方内容作者 shim（AC5；不再作登录依据）
        users.findByEmailAndRole(bootstrapEmail, Role.ADMIN).ifPresentOrElse(existing -> {
            // 已存在则不动（其 password_hash 旧列保留停用，无需重置）
            log.info("ADMIN bootstrap：官方内容作者 users 行已存在 email={}", bootstrapEmail);
        }, () -> {
            users.save(User.newAdmin(bootstrapEmail, "TailTopia 运营", hash));
            log.info("ADMIN bootstrap：已创建官方内容作者 users 行 email={}", bootstrapEmail);
        });
    }
}
