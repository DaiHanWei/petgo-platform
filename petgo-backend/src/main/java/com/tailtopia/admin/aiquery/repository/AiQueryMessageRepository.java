package com.tailtopia.admin.aiquery.repository;

import com.tailtopia.admin.aiquery.domain.AiQueryMessage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiQueryMessageRepository extends JpaRepository<AiQueryMessage, Long> {

    List<AiQueryMessage> findByConversationIdOrderByIdAsc(long conversationId);

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

    /** 左栏：每个对话最新一轮（按 id 最大）。 */
    @Query("""
            SELECT m FROM AiQueryMessage m
             WHERE m.conversationId IN :ids
               AND m.id = (SELECT max(m2.id) FROM AiQueryMessage m2 WHERE m2.conversationId = m.conversationId)""")
    List<AiQueryMessage> findLatestOf(@Param("ids") Collection<Long> ids);
}
