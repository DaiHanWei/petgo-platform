package com.tailtopia.support;

import com.tailtopia.auth.domain.PetStatus;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.shared.security.JwtService;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * API 集成测试基类（L1）：真 Spring 上下文 + MockMvc 走完整安全过滤链 + 真 PostgreSQL/Redis 写库。
 *
 * <p>与既有「直接 new 控制器 + mock service」的 L0 单测互补：此层验证<b>真实 HTTP 行为</b>——
 * 序列化、Bean 校验、JWT 鉴权/角色门控（{@link com.tailtopia.shared.security.SecurityConfig}）、
 * RFC 9457 ProblemDetail、以及数据真正落库。
 *
 * <p>dev profile 生效（{@code DevGoogleTokenVerifier} / {@code DevUserSeeder} 在场，无碍）。
 * <b>允许往数据库写入测试数据</b>：actor 用唯一 {@code google_sub}（{@link #SEQ} 进程内自增 + nanoTime
 * 种子）造，跨次运行不撞唯一约束；限流 key 含 userId，故各测试用独立 actor 互不串扰。
 *
 * <h2>⚠️ 连接池刻意压到很小</h2>
 * 用 {@code @Import} / bean 覆盖的测试类会各自产生<b>独立的 Spring 上下文</b>，
 * 每个上下文一个连接池。按 Hikari 默认的 10 条连接算，十来个上下文就能吃满 PostgreSQL 的
 * {@code max_connections=100} —— 表现是<b>单跑每个测试类都绿、跑全量却成片报
 * {@code FATAL: sorry, too many clients already}</b>，且报错指向的类往往与真凶无关。
 *
 * <p>集成测试是串行跑的，单个上下文<b>用不到 10 条连接</b>，故压到 3 条。
 * 这样上下文数量再涨也不会撞上限。<b>不要为了「快一点」把它调大。</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.hikari.maximum-pool-size=3",
            "spring.datasource.hikari.minimum-idle=0",
        })
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public abstract class ApiIntegrationTest {

    /** 进程内唯一序列（nanoTime 种子避免跨运行撞唯一约束）。 */
    protected static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected UserRepository users;

    /** 造一个已完成 onboarding 的持久化测试用户（唯一 sub），返回实体。 */
    protected User newUser() {
        return newUser(PetStatus.HAS_PET);
    }

    protected User newUser(PetStatus status) {
        long n = SEQ.incrementAndGet();
        User u = User.newGoogleUser("it-sub-" + n, "it" + n + "@petgo.test", "用户" + n, null);
        u.setNickname("用户" + n);
        u.setPetStatus(status);
        u.setOnboardingCompleted(true);
        return users.save(u);
    }

    /** 任意主体 + 角色的 {@code Authorization: Bearer ...} 头值（自签 JWT）。 */
    protected String bearer(long subjectId, Role role) {
        return "Bearer " + jwtService.issueAccessToken(subjectId, role);
    }

    /** USER 角色 Bearer（{@code sub=userId}）。 */
    protected String userBearer(long userId) {
        return bearer(userId, Role.USER);
    }

    /** VET 角色 Bearer（{@code sub=vetId}）。注意：VET 端点经 BannedVetFilter，需 DB 存在 active vet 行。 */
    protected String vetBearer(long vetId) {
        return bearer(vetId, Role.VET);
    }
}
