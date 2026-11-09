package com.tailtopia.admin.aiquery.service;

import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.aiquery.domain.AiQueryConversation;
import com.tailtopia.admin.aiquery.domain.AiQueryMessage;
import com.tailtopia.admin.aiquery.domain.AiQueryStatus;
import com.tailtopia.admin.aiquery.repository.AiQueryConversationRepository;
import com.tailtopia.admin.aiquery.repository.AiQueryMessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 页面读模型：左栏本人对话、右栏某个对话。只读，归属与保留期在查询条件里。 */
@Service
public class AiQueryQueries {

    public static final int PAGE_SIZE = 20;

    /** 左栏一行：对话标题 + 最新一轮的状态与时间。 */
    public record ConversationRow(String token, String title, Instant updatedAt, AiQueryStatus status,
            String errorCode, Instant startedAt) {

        public boolean unfinished() {
            return status != null && status.isUnfinished();
        }
    }

    /** 右栏：对话与全部轮次（按时间正序）。 */
    public record ConversationView(AiQueryConversation conversation, List<AiQueryMessage> messages) {

        public boolean busy() {
            return messages.stream().anyMatch(AiQueryMessage::isUnfinished);
        }

        public AiQueryMessage latest() {
            return messages.isEmpty() ? null : messages.get(messages.size() - 1);
        }
    }

    private final AiQueryConversationRepository conversations;
    private final AiQueryMessageRepository messages;
    private final AiQueryProperties props;

    public AiQueryQueries(AiQueryConversationRepository conversations, AiQueryMessageRepository messages,
            AiQueryProperties props) {
        this.conversations = conversations;
        this.messages = messages;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public Page<ConversationRow> list(long accountId, int page) {
        Page<AiQueryConversation> p = conversations.pageOwned(accountId, props.notBefore(),
                PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        List<Long> ids = p.getContent().stream().map(AiQueryConversation::getId).toList();
        Map<Long, AiQueryMessage> latest = ids.isEmpty() ? Map.of()
                : messages.findLatestOf(ids).stream()
                        .collect(Collectors.toMap(AiQueryMessage::getConversationId, Function.identity(), (a, b) -> a));
        return p.map(c -> {
            AiQueryMessage m = latest.get(c.getId());
            return new ConversationRow(c.getToken(), c.getTitle(), c.getUpdatedAt(),
                    m == null ? null : m.getStatus(), m == null ? null : m.getErrorCode(),
                    m == null ? null : startedAt(m));
        });
    }

    /** 不存在 / 属于他人 / 已过期 → {@link AiQueryNotFound}。 */
    @Transactional(readOnly = true)
    public ConversationView view(long accountId, String token) {
        if (!AiQueryTokens.isValid(token)) {
            throw new AiQueryNotFound();
        }
        AiQueryConversation c = conversations.findOwned(token, accountId, props.notBefore())
                .orElseThrow(AiQueryNotFound::new);
        return new ConversationView(c, messages.findByConversationIdOrderByIdAsc(c.getId()));
    }

    /** 计时起点：排队中从提交算，查询中从派发给 agent 算。 */
    public static Instant startedAt(AiQueryMessage m) {
        return m.getStatus() == AiQueryStatus.RUNNING && m.getSubmittedAt() != null ? m.getSubmittedAt() : m.getCreatedAt();
    }
}
