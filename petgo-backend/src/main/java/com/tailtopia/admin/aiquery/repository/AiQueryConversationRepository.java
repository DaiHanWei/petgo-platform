package com.tailtopia.admin.aiquery.repository;

import com.tailtopia.admin.aiquery.domain.AiQueryConversation;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
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

    /** 左栏：本人未过期对话，按最后更新倒序。 */
    @Query("""
            SELECT c FROM AiQueryConversation c
             WHERE c.adminAccountId = :owner AND c.updatedAt >= :notBefore
             ORDER BY c.updatedAt DESC, c.id DESC""")
    Page<AiQueryConversation> pageOwned(@Param("owner") long owner, @Param("notBefore") Instant notBefore,
            Pageable pageable);

    /** 一轮结束：保存 Claude 会话 id（为空则保留原值，供追问 --resume），并刷新最后更新时间。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_query_conversations
               SET agent_session_id = COALESCE(:sessionId, agent_session_id), updated_at = now()
             WHERE id = :id""", nativeQuery = true)
    int finishTurn(@Param("id") long id, @Param("sessionId") String sessionId);
}
