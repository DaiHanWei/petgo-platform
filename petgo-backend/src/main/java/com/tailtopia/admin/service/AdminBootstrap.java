package com.tailtopia.admin.service;

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
 * 运营 ADMIN 账号开户（Story 3.1）。**凭证 env 注入绝不入库**：仅当
 * {@code ADMIN_BOOTSTRAP_EMAIL} + {@code ADMIN_BOOTSTRAP_PASSWORD} 同时由 env 提供时，
 * 在启动时幂等 upsert 一个 ADMIN 账号（密码 BCrypt），否则跳过（不内置任何默认管理员）。
 *
 * <p>L1/L2 开户方式：部署前设这两个 env，启动一次即落库；后续可清空 env，账号已存在。
 * 明文密码绝不落库/日志（仅记录 email 与「created/updated」事件）。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder,
            @Value("${ADMIN_BOOTSTRAP_EMAIL:}") String bootstrapEmail,
            @Value("${ADMIN_BOOTSTRAP_PASSWORD:}") String bootstrapPassword) {
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
        users.findByEmailAndRole(bootstrapEmail, Role.ADMIN).ifPresentOrElse(existing -> {
            existing.setPasswordHash(hash);
            users.save(existing);
            log.info("ADMIN bootstrap：已重置现有管理员密码 email={}", bootstrapEmail);
        }, () -> {
            users.save(User.newAdmin(bootstrapEmail, "TailTopia 运营", hash));
            log.info("ADMIN bootstrap：已创建管理员 email={}", bootstrapEmail);
        });
    }
}
