package com.tailtopia.admin.aiquery.repository;

import com.tailtopia.admin.aiquery.domain.AiQueryMessage;
import com.tailtopia.admin.aiquery.domain.AiQueryStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiQueryMessageRepository extends JpaRepository<AiQueryMessage, Long> {

    List<AiQueryMessage> findByConversationIdOrderByIdAsc(long conversationId);

    java.util.Optional<AiQueryMessage> findByTokenAndConversationId(String token, long conversationId);

    /** 某对话是否有未完成任务（同一 Claude 会话不可并发，spec §5.3）。 */
    @Query("""
            SELECT count(m) > 0 FROM AiQueryMessage m
             WHERE m.conversationId = :conversationId
               AND m.status IN (com.tailtopia.admin.aiquery.domain.AiQueryStatus.PENDING,
                                com.tailtopia.admin.aiquery.domain.AiQueryStatus.RUNNING)""")
    boolean hasUnfinished(@Param("conversationId") long conversationId);

    /** 某账号名下未完成任务数（单人上限 3，spec §5.3）。 */
    @Query("""
            SELECT count(m) FROM AiQueryMessage m, AiQueryConversation c
             WHERE m.conversationId = c.id AND c.adminAccountId = :owner
               AND m.status IN (com.tailtopia.admin.aiquery.domain.AiQueryStatus.PENDING,
                                com.tailtopia.admin.aiquery.domain.AiQueryStatus.RUNNING)""")
    long countUnfinishedByOwner(@Param("owner") long owner);

    /**
     * 新对话的推荐问题：本人各对话的第一个问题（不含追问），相同问题只取最近一次，按对话新旧倒序。
     * 只查本人名下（{@code admin_account_id}），与对话归属规则一致。
     */
    @Query(value = """
            SELECT t.q FROM (
              SELECT DISTINCT ON (f.question) f.question AS q, c.id AS cid
                FROM ai_query_conversations c
                JOIN LATERAL (SELECT m.question FROM ai_query_messages m
                               WHERE m.conversation_id = c.id ORDER BY m.id LIMIT 1) f ON true
               WHERE c.admin_account_id = :owner
               ORDER BY f.question, c.id DESC) t
             ORDER BY t.cid DESC
             LIMIT :limit""", nativeQuery = true)
    List<String> findRecentFirstQuestions(@Param("owner") long owner, @Param("limit") int limit);

    /** 左栏：每个对话最新一轮（按 id 最大）。 */
    @Query("""
            SELECT m FROM AiQueryMessage m
             WHERE m.conversationId IN :ids
               AND m.id = (SELECT max(m2.id) FROM AiQueryMessage m2 WHERE m2.conversationId = m.conversationId)""")
    List<AiQueryMessage> findLatestOf(@Param("ids") Collection<Long> ids);

    // ---- 扫描器（Story 1.5）：状态变更一律带旧状态条件，返回值 = 实际更新行数（0 = 被别人抢先 / 已变化） ----

    /** 按 id 顺序取某状态的一批任务（先来先派）。 */
    List<AiQueryMessage> findByStatusOrderByIdAsc(AiQueryStatus status, Pageable pageable);

    /** 创建早于 cutoff 仍未结束的任务（转 TIMEOUT）。 */
    @Query("""
            SELECT m FROM AiQueryMessage m
             WHERE m.status IN (com.tailtopia.admin.aiquery.domain.AiQueryStatus.PENDING,
                                com.tailtopia.admin.aiquery.domain.AiQueryStatus.RUNNING)
               AND m.createdAt < :cutoff
             ORDER BY m.id""")
    List<AiQueryMessage> findStale(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_query_messages SET status = 'RUNNING', submitted_at = now(), updated_at = now()
             WHERE id = :id AND status = 'PENDING'""", nativeQuery = true)
    int markRunning(@Param("id") long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_query_messages
               SET status = 'DONE', answer_md = :answer, files = CAST(:files AS jsonb), error_code = NULL,
                   agent_secs = :secs, agent_turns = :turns, cost_usd = :cost, finished_at = now(), updated_at = now()
             WHERE id = :id AND status = 'RUNNING'""", nativeQuery = true)
    int markDone(@Param("id") long id, @Param("answer") String answer, @Param("files") String filesJson,
            @Param("secs") Integer secs, @Param("turns") Integer turns, @Param("cost") BigDecimal cost);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_query_messages
               SET status = 'FAILED', error_code = :code,
                   agent_secs = :secs, agent_turns = :turns, cost_usd = :cost, finished_at = now(), updated_at = now()
             WHERE id = :id AND status = :from""", nativeQuery = true)
    int markFailed(@Param("id") long id, @Param("from") String fromStatus, @Param("code") String errorCode,
            @Param("secs") Integer secs, @Param("turns") Integer turns, @Param("cost") BigDecimal cost);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_query_messages SET status = 'TIMEOUT', error_code = 'TIMEOUT', finished_at = now(), updated_at = now()
             WHERE id = :id AND status IN ('PENDING', 'RUNNING') AND created_at < :cutoff""", nativeQuery = true)
    int markTimeout(@Param("id") long id, @Param("cutoff") Instant cutoff);

    /** 轮询：本人未过期对话里的指定几轮。 */
    @Query("""
            SELECT m FROM AiQueryMessage m, AiQueryConversation c
             WHERE m.conversationId = c.id AND m.token IN :tokens
               AND c.adminAccountId = :owner AND c.updatedAt >= :notBefore""")
    List<AiQueryMessage> findOwnedByTokens(@Param("tokens") Collection<String> tokens, @Param("owner") long owner,
            @Param("notBefore") Instant notBefore);

    /** 某时刻之后全系统是否有查询成功（用来判断授权失效是否已被修复，见 {@code AiQueryAuthLock}）。 */
    @Query("""
            SELECT count(m) > 0 FROM AiQueryMessage m
             WHERE m.status = com.tailtopia.admin.aiquery.domain.AiQueryStatus.DONE AND m.finishedAt > :since""")
    boolean anyDoneSince(@Param("since") Instant since);
}
