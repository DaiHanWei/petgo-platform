package com.tailtopia.auth.service;

import com.tailtopia.auth.domain.PetStatus;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.shared.security.DevGoogleTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEV-ONLY 测试用户 seed：dev profile 启动时<b>幂等 upsert</b> 一个已完成 onboarding 的测试账号
 * （sub={@link DevGoogleTokenVerifier#DEV_SUB}）。配合 {@link DevGoogleTokenVerifier} 让前端
 * 「Google 登录」直接落到此账号，免真实凭证即可验证登录后鉴权链路。
 *
 * <p>🔒 仅 {@code @Profile("dev")} 注册；prod profile 不生效，绝不在生产库写入测试数据。
 */
@Component
@Profile("dev")
public class DevUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private final UserRepository users;

    public DevUserSeeder(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.findByGoogleSub(DevGoogleTokenVerifier.DEV_SUB).isPresent()) {
            return; // 幂等：已存在则不重复创建
        }
        User u = User.newGoogleUser(DevGoogleTokenVerifier.DEV_SUB, "test@petgo.dev", "测试用户", null);
        u.setNickname("测试用户");
        u.setPetStatus(PetStatus.HAS_PET); // 已有宠物，三类内容全显，便于验各页
        u.setOnboardingCompleted(true); // 直接进主框架，免每次走 onboarding
        users.save(u);
        log.info("DEV seed：已创建测试用户 sub={}", DevGoogleTokenVerifier.DEV_SUB);
    }
}
