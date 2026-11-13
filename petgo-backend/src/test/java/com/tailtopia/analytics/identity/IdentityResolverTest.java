package com.tailtopia.analytics.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tailtopia.analytics.ingest.RawEvent;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jwt.Jwt;

/** L0：analytics-pipeline Story 1.5 AC2 / AC4 —— A7 逐条判定、本批关联、查库异常降级、不通过计数与汇总日志。 */
class IdentityResolverTest {

    private static final Instant T = Instant.parse("2026-11-13T01:00:00Z");
    private static final String AID = "3f2c8a1e-9b4d-4c1a-8e7f-0a1b2c3d4e5f";
    private static final long A = 101L;
    private static final long B = 202L;
    private static final String KEY_A = AnalyticsDistinctId.of(A);
    private static final String KEY_B = AnalyticsDistinctId.of(B);

    private IdentityLinkRepository repo;
    private LinkTableIdentityResolver resolver;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        repo = mock(IdentityLinkRepository.class);
        when(repo.findUserId(anyString(), anyString())).thenReturn(Optional.empty());
        resolver = new LinkTableIdentityResolver(repo);
        logger = (Logger) LoggerFactory.getLogger(LinkTableIdentityResolver.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(null);
    }

    private static RawEvent event(String messageId, String name, String claimedUserKey) {
        return RawEvent.of(messageId, name, T, T.plusSeconds(2), T.plusSeconds(5), AID, claimedUserKey, null,
                RawEvent.Source.APP, null, null, false, false);
    }

    private static Jwt token(long sub, String role) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(String.valueOf(sub)).claim("role", role)
                .build();
    }

    private RawEvent one(RawEvent e, Jwt jwt) {
        List<RawEvent> out = resolver.resolve(List.of(e), jwt);
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    @Test
    @DisplayName("无 userKey → 匿名，不查库、不计数")
    void anonymous() {
        RawEvent r = one(event("m1", "app_open", null), token(A, "USER"));

        assertThat(r.userKey()).isNull();
        assertThat(r.userId()).isNull();
        assertThat(r.identityUnverified()).isFalse();
        assertThat(r.identityLink()).isNull();
        verifyNoInteractions(repo);
        assertThat(resolver.unverifiedCount()).isZero();
    }

    @Test
    @DisplayName("USER token 且相等 → 通过，user_id = token 用户，不查库")
    void tokenMatches() {
        RawEvent r = one(event("m1", "app_open", KEY_A), token(A, "USER"));

        assertThat(r.userKey()).isEqualTo(KEY_A);
        assertThat(r.userId()).isEqualTo(A);
        assertThat(r.identityUnverified()).isFalse();
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("USER token 但不等 → 查库命中 → 通过，user_id 为关联用户（跨账号补发）")
    void tokenMismatchLinked() {
        when(repo.findUserId(AID, KEY_A)).thenReturn(Optional.of(A));

        RawEvent r = one(event("m1", "app_open", KEY_A), token(B, "USER"));

        assertThat(r.userKey()).isEqualTo(KEY_A);
        assertThat(r.userId()).isEqualTo(A);
        assertThat(r.identityUnverified()).isFalse();
        assertThat(resolver.unverifiedCount()).isZero();
    }

    @Test
    @DisplayName("USER token 但不等 → 查库未命中 → 剥离 + 标记 + 计数")
    void tokenMismatchUnlinked() {
        RawEvent r = one(event("m1", "app_open", KEY_A), token(B, "USER"));

        assertThat(r.userKey()).isNull();
        assertThat(r.userId()).isNull();
        assertThat(r.identityUnverified()).isTrue();
        verify(repo).findUserId(AID, KEY_A);
        assertThat(resolver.unverifiedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("无 token 带 userKey → 走关联表：命中通过、未命中剥离")
    void noTokenUsesLinks() {
        when(repo.findUserId(AID, KEY_A)).thenReturn(Optional.of(A));

        List<RawEvent> out = resolver.resolve(List.of(event("m1", "app_open", KEY_A),
                event("m2", "app_open", KEY_B)), null);

        assertThat(out.get(0).userId()).isEqualTo(A);
        assertThat(out.get(0).userKey()).isEqualTo(KEY_A);
        assertThat(out.get(0).identityUnverified()).isFalse();
        assertThat(out.get(1).userKey()).isNull();
        assertThat(out.get(1).userId()).isNull();
        assertThat(out.get(1).identityUnverified()).isTrue();
    }

    @Test
    @DisplayName("VET token → 按无 token 处理（即便 sub 算出的哈希恰好相等），也不生成关联")
    void vetTokenIgnored() {
        List<RawEvent> out = resolver.resolve(List.of(event("m1", LinkTableIdentityResolver.USER_IDENTIFIED, KEY_A),
                event("m2", "app_open", KEY_A)), token(A, "VET"));

        assertThat(out).allSatisfy(r -> {
            assertThat(r.userKey()).isNull();
            assertThat(r.userId()).isNull();
            assertThat(r.identityUnverified()).isTrue();
            assertThat(r.identityLink()).isNull();
        });
        // 同批同一 (匿名 id, userKey) 只查一次库
        verify(repo, times(1)).findUserId(AID, KEY_A);
    }

    @Test
    @DisplayName("token 解析：只认 role=USER 且 sub 为 long")
    void tokenUserIdParsing() {
        assertThat(LinkTableIdentityResolver.tokenUserId(null)).isNull();
        assertThat(LinkTableIdentityResolver.tokenUserId(token(A, "USER"))).isEqualTo(A);
        assertThat(LinkTableIdentityResolver.tokenUserId(token(A, "VET"))).isNull();
        assertThat(LinkTableIdentityResolver.tokenUserId(token(A, "SUPER_ADMIN"))).isNull();
        Jwt noRole = Jwt.withTokenValue("t").header("alg", "none").subject("5").claim("x", "y").build();
        assertThat(LinkTableIdentityResolver.tokenUserId(noRole)).isNull();
        Jwt badSub = Jwt.withTokenValue("t").header("alg", "none").subject("abc").claim("role", "USER").build();
        assertThat(LinkTableIdentityResolver.tokenUserId(badSub)).isNull();
    }

    @Test
    @DisplayName("同批先 user_identified（相符）再一条别的事件 → 生成关联；第二条通过且不查库")
    void identifiedThenEventSameBatch() {
        List<RawEvent> out = resolver.resolve(List.of(event("m1", LinkTableIdentityResolver.USER_IDENTIFIED, KEY_A),
                event("m2", "app_open", KEY_A)), token(A, "USER"));

        IdentityLink link = out.get(0).identityLink();
        assertThat(link).isNotNull();
        assertThat(link.anonymousId()).isEqualTo(AID);
        assertThat(link.userId()).isEqualTo(A);
        assertThat(link.userKey()).isEqualTo(KEY_A);
        assertThat(link.messageId()).isEqualTo("m1");
        // client_ts + (received_at − sent_at) = T + 3s
        assertThat(link.linkedAt()).isEqualTo(T.plusSeconds(3));
        assertThat(out.get(0).userId()).isEqualTo(A);
        assertThat(out.get(1).userId()).isEqualTo(A);
        assertThat(out.get(1).identityUnverified()).isFalse();
        assertThat(out.get(1).identityLink()).isNull();
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("user_identified 的 userId 与 token 不符 → 不生成关联，该事件本身按规则判定")
    void identifiedMismatchNoLink() {
        List<RawEvent> out = resolver.resolve(List.of(event("m1", LinkTableIdentityResolver.USER_IDENTIFIED, KEY_A)),
                token(B, "USER"));

        assertThat(out.get(0).identityLink()).isNull();
        assertThat(out.get(0).userKey()).isNull();
        assertThat(out.get(0).identityUnverified()).isTrue();
        verify(repo).findUserId(AID, KEY_A);
    }

    @Test
    @DisplayName("user_identified 无 token → 不生成关联")
    void identifiedWithoutTokenNoLink() {
        List<RawEvent> out = resolver.resolve(List.of(event("m1", LinkTableIdentityResolver.USER_IDENTIFIED, KEY_A)),
                null);

        assertThat(out.get(0).identityLink()).isNull();
    }

    @Test
    @DisplayName("查库抛异常 → 本批需要查库的事件剥离 + 标记，不抛；异常后不再查库；WARN 不含标识")
    void lookupFailureDegrades() {
        when(repo.findUserId(anyString(), anyString())).thenThrow(new DataAccessResourceFailureException("down"));
        List<RawEvent> batch = List.of(event("m1", "app_open", KEY_A), event("m2", "app_open", KEY_B),
                event("m3", "app_open", KEY_B));

        assertThatCode(() -> resolver.resolve(batch, token(999L, "USER"))).doesNotThrowAnyException();
        List<RawEvent> out = resolver.resolve(batch, null);

        assertThat(out).allSatisfy(r -> {
            assertThat(r.userKey()).isNull();
            assertThat(r.userId()).isNull();
            assertThat(r.identityUnverified()).isTrue();
        });
        // 两次 resolve 各只查一次（首次异常后本批不再查库）
        verify(repo, times(2)).findUserId(anyString(), anyString());
        assertThat(logs.list).anySatisfy(l -> {
            assertThat(l.getLevel()).isEqualTo(Level.WARN);
            assertThat(l.getFormattedMessage()).contains("DataAccessResourceFailureException");
        });
        assertNoIdentifiersLogged();
    }

    @Test
    @DisplayName("Story 4.9 AC4：请求线程查询超时（QueryTimeoutException）走「查不到」分支：剥离 + 标记，不抛")
    void queryTimeoutDegradesToUnverified() {
        when(repo.findUserId(anyString(), anyString()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("canceling statement"));
        List<RawEvent> out = resolver.resolve(List.of(event("m1", "app_open", KEY_A)), null);

        assertThat(out.get(0).userKey()).isNull();
        assertThat(out.get(0).userId()).isNull();
        assertThat(out.get(0).identityUnverified()).isTrue();
        assertThat(logs.list).anySatisfy(l -> assertThat(l.getFormattedMessage()).contains("QueryTimeoutException"));
        assertNoIdentifiersLogged();
    }

    @Test
    @DisplayName("不通过计数累加；5 分钟汇总打一条 INFO、清零；日志不含任何标识")
    void unverifiedCountAndSummaryLog() {
        resolver.resolve(List.of(event("m1", "app_open", KEY_A), event("m2", "app_open", KEY_B)), null);
        resolver.resolve(List.of(event("m3", "app_open", KEY_A), event("m4", "app_open", null)), token(B, "USER"));
        assertThat(resolver.unverifiedCount()).isEqualTo(3);

        assertThat(resolver.logUnverifiedSummary()).isEqualTo(3);
        assertThat(resolver.unverifiedCount()).isZero();
        assertThat(logs.list).filteredOn(l -> l.getLevel() == Level.INFO).singleElement()
                .satisfies(l -> assertThat(l.getFormattedMessage())
                        .isEqualTo("analytics identity unverified count=3 window=5m"));

        // 计数为 0 时不打
        assertThat(resolver.logUnverifiedSummary()).isZero();
        assertThat(logs.list).filteredOn(l -> l.getLevel() == Level.INFO).hasSize(1);
        assertNoIdentifiersLogged();
    }

    @Test
    @DisplayName("关联的 toString 不泄露字段")
    void linkToStringRedacted() {
        IdentityLink l = new IdentityLink(AID, A, KEY_A, T, "m1");

        assertThat(l.toString()).doesNotContain(AID).doesNotContain(KEY_A).doesNotContain(String.valueOf(A));
    }

    private void assertNoIdentifiersLogged() {
        assertThat(logs.list).allSatisfy(l -> assertThat(l.getFormattedMessage())
                .doesNotContain(AID)
                .doesNotContain(KEY_A)
                .doesNotContain(KEY_B)
                .doesNotContain(String.valueOf(A))
                .doesNotContain(String.valueOf(B)));
        verify(repo, never()).appendAll(org.mockito.ArgumentMatchers.anyList());
    }
}
