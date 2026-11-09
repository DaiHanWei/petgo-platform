package com.tailtopia.admin.aiquery.service;

import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.aiquery.domain.AiQueryConversation;
import com.tailtopia.admin.aiquery.domain.AiQueryMessage;
import com.tailtopia.admin.aiquery.domain.AiQueryStatus;
import com.tailtopia.admin.aiquery.repository.AiQueryConversationRepository;
import com.tailtopia.admin.aiquery.repository.AiQueryMessageRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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

        /** 已用时长（秒，排队中从提交算、查询中从派发算），供页面计时器起点。 */
        public long elapsedSecs() {
            return elapsed(startedAt);
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

    /** 左栏一页：行 + 下一页游标（没有更多时为 null）。 */
    public record RowsPage(List<ConversationRow> rows, String next) {

        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /** {@code after} 为空 = 首屏；否则为上一页给出的游标（非法游标按首屏处理）。 */
    @Transactional(readOnly = true)
    public RowsPage list(long accountId, String after) {
        PageRequest limit = PageRequest.of(0, PAGE_SIZE + 1);
        Cursor cur = Cursor.parse(after);
        List<AiQueryConversation> cs = cur == null
                ? conversations.firstOwned(accountId, props.notBefore(), limit)
                : conversations.ownedAfter(accountId, props.notBefore(), cur.updatedAt(), cur.token(), limit);
        boolean more = cs.size() > PAGE_SIZE;
        List<AiQueryConversation> page = more ? cs.subList(0, PAGE_SIZE) : cs;
        Map<Long, AiQueryMessage> latest = latestOf(page);
        List<ConversationRow> rows = page.stream().map(c -> row(c, latest.get(c.getId()))).toList();
        String next = more ? Cursor.of(page.get(page.size() - 1)) : null;
        return new RowsPage(rows, next);
    }

    /** 游标：{@code <updated_at 微秒>.<token>}。只含时间与不可枚举 token，不暴露自增 id。 */
    record Cursor(Instant updatedAt, String token) {

        static String of(AiQueryConversation c) {
            Instant u = c.getUpdatedAt();
            long micros = u.getEpochSecond() * 1_000_000L + u.getNano() / 1_000;
            return micros + "." + c.getToken();
        }

        static Cursor parse(String s) {
            if (s == null) {
                return null;
            }
            int dot = s.indexOf('.');
            if (dot <= 0) {
                return null;
            }
            try {
                long micros = Long.parseLong(s.substring(0, dot));
                String token = s.substring(dot + 1);
                if (!AiQueryTokens.isValid(token)) {
                    return null;
                }
                return new Cursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                        Math.floorMod(micros, 1_000_000L) * 1_000L), token);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** 轮询：只看本人未过期的指定对话（顺序不保证）。 */
    @Transactional(readOnly = true)
    public List<ConversationRow> rows(long accountId, Collection<String> tokens) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<AiQueryConversation> cs = conversations.findOwnedByTokens(tokens, accountId, props.notBefore());
        Map<Long, AiQueryMessage> latest = latestOf(cs);
        return cs.stream().map(c -> row(c, latest.get(c.getId()))).toList();
    }

    /** 轮询：本人未过期对话里的指定几轮。 */
    @Transactional(readOnly = true)
    public List<AiQueryMessage> messages(long accountId, Collection<String> tokens) {
        return tokens.isEmpty() ? List.of() : messages.findOwnedByTokens(tokens, accountId, props.notBefore());
    }

    private Map<Long, AiQueryMessage> latestOf(List<AiQueryConversation> cs) {
        List<Long> ids = cs.stream().map(AiQueryConversation::getId).toList();
        return ids.isEmpty() ? Map.of()
                : messages.findLatestOf(ids).stream()
                        .collect(Collectors.toMap(AiQueryMessage::getConversationId, Function.identity(), (a, b) -> a));
    }

    private static ConversationRow row(AiQueryConversation c, AiQueryMessage m) {
        return new ConversationRow(c.getToken(), c.getTitle(), c.getUpdatedAt(),
                m == null ? null : m.getStatus(), m == null ? null : m.getErrorCode(),
                m == null ? null : startedAt(m));
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

    public static long elapsed(Instant since) {
        return since == null ? 0 : Math.max(0, Duration.between(since, Instant.now()).toSeconds());
    }

    /** 某一轮的已用时长（秒）。 */
    public static long elapsedSecs(AiQueryMessage m) {
        return elapsed(startedAt(m));
    }

    /** 计时起点：排队中从提交算，查询中从派发给 agent 算。 */
    public static Instant startedAt(AiQueryMessage m) {
        return m.getStatus() == AiQueryStatus.RUNNING && m.getSubmittedAt() != null ? m.getSubmittedAt() : m.getCreatedAt();
    }
}
