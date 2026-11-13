package com.tailtopia.analytics.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/** L0：analytics-pipeline Story 1.5 —— 关联表 SQL 形状、绑定、校正时间口径（不连库）。 */
class IdentityLinkRepositoryTest {

    private static final Instant T = Instant.parse("2026-11-13T01:00:00Z");

    @Test
    @DisplayName("SQL：按 (匿名 id, user_key) 查一行；追加吞掉所有冲突（主键 + message_id 唯一）")
    void sqlShape() {
        assertThat(IdentityLinkRepository.FIND_SQL)
                .isEqualTo("SELECT user_id FROM identity_links WHERE anonymous_id = ? AND user_key = ? LIMIT 1");
        assertThat(IdentityLinkRepository.INSERT_SQL)
                .startsWith("INSERT INTO identity_links (anonymous_id, user_id, user_key, linked_at, message_id)")
                .endsWith("ON CONFLICT DO NOTHING");
        assertThat(IdentityLinkRepository.INSERT_SQL.toUpperCase()).doesNotContain("UPDATE");
    }

    @Test
    @DisplayName("空入参不访问库")
    void appendEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThat(new IdentityLinkRepository(jdbc).appendAll(List.of())).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("非空入参走一条 batchUpdate")
    void appendBatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        new IdentityLinkRepository(jdbc).appendAll(List.of(new IdentityLink("aid", 1L, "k", T, "m")));

        verify(jdbc).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

    @Test
    @DisplayName("Story 4.9 AC4 / AC5：findUserId 走请求线程模板（带查询超时），appendAll 走写库线程模板")
    @SuppressWarnings("unchecked")
    void lookupUsesRequestTemplateAndAppendUsesWriterTemplate() {
        JdbcTemplate writer = mock(JdbcTemplate.class);
        JdbcTemplate request = mock(JdbcTemplate.class);
        IdentityLinkRepository repo = new IdentityLinkRepository(writer, request);

        repo.findUserId("aid", "key");
        repo.appendAll(List.of(new IdentityLink("aid", 1L, "k", T, "m")));

        verify(request).query(org.mockito.ArgumentMatchers.eq(IdentityLinkRepository.FIND_SQL),
                any(org.springframework.jdbc.core.ResultSetExtractor.class), any(), any());
        verify(writer).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        org.mockito.Mockito.verifyNoMoreInteractions(request);
        org.mockito.Mockito.verifyNoMoreInteractions(writer);
    }

    @Test
    @DisplayName("绑定：5 列顺序、linked_at 为 UTC")
    void bind() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        IdentityLinkRepository.bind(ps, new IdentityLink("aid", 42L, "key", T, "msg"));

        verify(ps).setString(1, "aid");
        verify(ps).setLong(2, 42L);
        verify(ps).setString(3, "key");
        verify(ps).setObject(4, OffsetDateTime.ofInstant(T, ZoneOffset.UTC));
        verify(ps).setString(5, "msg");
    }

    @Test
    @DisplayName("linked_at = client_ts + (received_at − sent_at)，晚于 received_at 取 received_at")
    void correctedTime() {
        // 客户端时钟慢 10 分钟：client 01:00、sent 01:05、received 01:15 → 01:10
        assertThat(IdentityLink.correctedTime(T, T.plusSeconds(300), T.plusSeconds(900)))
                .isEqualTo(T.plusSeconds(600));
        // 校正后晚于 received_at（client_ts 晚于 sent_at）→ 取 received_at
        assertThat(IdentityLink.correctedTime(T.plusSeconds(100), T, T.plusSeconds(50)))
                .isEqualTo(T.plusSeconds(50));
        // 极端偏差不推出 timestamptz 下限
        assertThat(IdentityLink.correctedTime(Instant.parse("0001-01-01T00:00:00Z"),
                Instant.parse("9999-01-01T00:00:00Z"), T)).isEqualTo(IdentityLink.MIN_TS);
    }
}
