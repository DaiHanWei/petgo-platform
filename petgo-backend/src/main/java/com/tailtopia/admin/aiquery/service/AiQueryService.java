package com.tailtopia.admin.aiquery.service;

import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.aiquery.domain.AiQueryConversation;
import com.tailtopia.admin.aiquery.domain.AiQueryLanguage;
import com.tailtopia.admin.aiquery.domain.AiQueryMessage;
import com.tailtopia.admin.aiquery.repository.AiQueryConversationRepository;
import com.tailtopia.admin.aiquery.repository.AiQueryMessageRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提问与追问（spec §4.1 / §5，Story 1.4）。
 *
 * <ul>
 * <li>提交只在事务内写 {@code PENDING} 并立即返回，<b>不同步调用 agent</b>（扫描器负责派发，Story 1.5）。</li>
 * <li>单人未完成任务上限 3；同一对话有未完成任务时拒绝追问（同一 Claude 会话不可并发）。
 *     两条都在「按账号的事务级 advisory lock」内检查，并发提交不会越过上限。</li>
 * <li>审计 {@code AI_QUERY_SUBMIT} 只记 token、语言、问题长度，<b>不写问题原文</b>（spec §5.5）。</li>
 * </ul>
 */
@Service
public class AiQueryService {

    public static final int MAX_UNFINISHED_PER_USER = 3;
    public static final String AUDIT_TARGET = "AI_QUERY_MESSAGE";
    /** advisory lock 命名空间（两参版本：namespace, accountId）。 */
    private static final int LOCK_NAMESPACE = 0x41_49_51_31; // "AIQ1"

    public record SubmitResult(String conversationToken, String messageToken) {
    }

    private final AiQueryConversationRepository conversations;
    private final AiQueryMessageRepository messages;
    private final AdminAuditService audit;
    private final AiQueryProperties props;
    private final EntityManager em;

    public AiQueryService(AiQueryConversationRepository conversations, AiQueryMessageRepository messages,
            AdminAuditService audit, AiQueryProperties props, EntityManager em) {
        this.conversations = conversations;
        this.messages = messages;
        this.audit = audit;
        this.props = props;
        this.em = em;
    }

    /** 新建对话并提交第一问。 */
    @Transactional
    public SubmitResult startConversation(long accountId, String question, String lang) {
        String q = validate(question);
        AiQueryLanguage language = AiQueryLanguage.fromCode(lang).orElse(AiQueryLanguage.EN);
        lockOwner(accountId);
        checkUserLimit(accountId);
        AiQueryConversation c = conversations.save(
                AiQueryConversation.start(AiQueryTokens.newToken(), accountId, q, language));
        return submit(accountId, c, q, false);
    }

    /** 在已有对话里追问：语言沿用对话保存的语言。 */
    @Transactional
    public SubmitResult followUp(long accountId, String conversationToken, String question) {
        if (!AiQueryTokens.isValid(conversationToken)) {
            throw new AiQueryNotFound();
        }
        // 先确认归属（不存在 / 他人 / 过期 → 404），再校验内容：对他人对话不泄露任何校验信息。
        AiQueryConversation c = conversations.findOwned(conversationToken, accountId, props.notBefore())
                .orElseThrow(AiQueryNotFound::new);
        return appendTurn(accountId, c, question);
    }

    /**
     * 用某一轮的原问题再问一次（Story 1.7「重新查询」、Story 1.8「用原问题重试」）：在当前对话新增一轮，
     * 遵守同一对话忙、单人上限 3 的限制。
     */
    @Transactional
    public SubmitResult requery(long accountId, String conversationToken, String messageToken) {
        if (!AiQueryTokens.isValid(conversationToken) || !AiQueryTokens.isValid(messageToken)) {
            throw new AiQueryNotFound();
        }
        AiQueryConversation c = conversations.findOwned(conversationToken, accountId, props.notBefore())
                .orElseThrow(AiQueryNotFound::new);
        AiQueryMessage original = messages.findByTokenAndConversationId(messageToken, c.getId())
                .orElseThrow(AiQueryNotFound::new);
        return appendTurn(accountId, c, original.getQuestion());
    }

    /**
     * 在本人已确认归属的对话里追加一轮（追问 / 重新查询 / 重试共用）：校验 → 按账号加锁 → 同一对话忙 → 单人上限。
     * 只有本人能往自己的对话追加，本人的提交已由 advisory lock 串行化，无需再锁对话行。
     */
    private SubmitResult appendTurn(long accountId, AiQueryConversation c, String question) {
        String q = validate(question);
        lockOwner(accountId);
        if (messages.hasUnfinished(c.getId())) {
            throw new AiQueryRejection(AiQueryRejection.Reason.BUSY);
        }
        checkUserLimit(accountId);
        return submit(accountId, c, q, true);
    }

    private SubmitResult submit(long accountId, AiQueryConversation c, String q, boolean followUp) {
        AiQueryMessage m = messages.save(AiQueryMessage.pending(AiQueryTokens.newToken(), c.getId(), q));
        c.touch();
        conversations.save(c);
        audit.record(accountId, AuditActions.AI_QUERY_SUBMIT, AUDIT_TARGET, m.getToken(),
                "conversation=" + c.getToken() + " message=" + m.getToken() + " lang=" + c.getLanguage().code()
                        + " length=" + q.codePointCount(0, q.length()) + " followUp=" + followUp);
        return new SubmitResult(c.getToken(), m.getToken());
    }

    /** 仅供单测：直接跑校验。 */
    public String validateForTest(String question) {
        return validate(question);
    }

    /** 开关、空、超长。返回去掉首尾空白的问题。 */
    String validate(String question) {
        if (!props.isEnabled()) {
            throw new AiQueryRejection(AiQueryRejection.Reason.DISABLED);
        }
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) {
            throw new AiQueryRejection(AiQueryRejection.Reason.EMPTY);
        }
        int len = q.codePointCount(0, q.length());
        if (len > AiQueryMessage.QUESTION_MAX) {
            throw new AiQueryRejection(AiQueryRejection.Reason.TOO_LONG, len);
        }
        return q;
    }

    private void checkUserLimit(long accountId) {
        if (messages.countUnfinishedByOwner(accountId) >= MAX_UNFINISHED_PER_USER) {
            throw new AiQueryRejection(AiQueryRejection.Reason.LIMIT, MAX_UNFINISHED_PER_USER);
        }
    }

    /** 同一账号的提交串行化（事务结束自动释放）。 */
    private void lockOwner(long accountId) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:ns, :id)")
                .setParameter("ns", LOCK_NAMESPACE)
                .setParameter("id", (int) (accountId ^ (accountId >>> 32)))
                .getSingleResult();
    }

}
