package com.tailtopia.admin.aiquery.repository;

import com.tailtopia.admin.aiquery.domain.AiQueryConversation;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiQueryConversationRepository extends JpaRepository<AiQueryConversation, Long> {

    /**
     * 归属强校验 + 未过期（spec §5.2）：不存在、属于他人、已过期一律查不到 → 调用方统一 404。
     * 超管也只能看自己的对话。
     */
    @Query("""
            SELECT c FROM AiQueryConversation c
             WHERE c.token = :token AND c.adminAccountId = :owner AND c.updatedAt >= :notBefore""")
    Optional<AiQueryConversation> findOwned(@Param("token") String token, @Param("owner") long owner,
            @Param("notBefore") Instant notBefore);

    /** 左栏首屏：本人未过期对话，按最后更新倒序（同一时刻按 token 倒序，供游标分页）。 */
    @Query("""
            SELECT c FROM AiQueryConversation c
             WHERE c.adminAccountId = :owner AND c.updatedAt >= :notBefore
             ORDER BY c.updatedAt DESC, c.token DESC""")
    List<AiQueryConversation> firstOwned(@Param("owner") long owner, @Param("notBefore") Instant notBefore,
            Pageable pageable);

    /**
     * 左栏滚动加载：游标 (updatedAt, token) 之后的一页。用游标而不是 offset：列表在页面打开期间会变
     * （新对话、扫描器刷新 updated_at），offset 分页会重复或漏行。
     */
    @Query("""
            SELECT c FROM AiQueryConversation c
             WHERE c.adminAccountId = :owner AND c.updatedAt >= :notBefore
               AND (c.updatedAt < :u OR (c.updatedAt = :u AND c.token < :t))
             ORDER BY c.updatedAt DESC, c.token DESC""")
    List<AiQueryConversation> ownedAfter(@Param("owner") long owner, @Param("notBefore") Instant notBefore,
            @Param("u") Instant cursorUpdatedAt, @Param("t") String cursorToken, Pageable pageable);

    /** 一轮结束：保存 Claude 会话 id（为空则保留原值，供追问 --resume），并刷新最后更新时间。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_query_conversations
               SET agent_session_id = COALESCE(:sessionId, agent_session_id), updated_at = now()
             WHERE id = :id""", nativeQuery = true)
    int finishTurn(@Param("id") long id, @Param("sessionId") String sessionId);

    /** 轮询：本人未过期对话中的指定几个。 */
    @Query("""
            SELECT c FROM AiQueryConversation c
             WHERE c.token IN :tokens AND c.adminAccountId = :owner AND c.updatedAt >= :notBefore""")
    List<AiQueryConversation> findOwnedByTokens(@Param("tokens") Collection<String> tokens, @Param("owner") long owner,
            @Param("notBefore") Instant notBefore);
}
