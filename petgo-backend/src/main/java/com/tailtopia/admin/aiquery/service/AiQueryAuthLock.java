package com.tailtopia.admin.aiquery.service;

import com.tailtopia.admin.aiquery.domain.AiQueryFailure;
import com.tailtopia.admin.aiquery.domain.AiQueryMessage;
import com.tailtopia.admin.aiquery.repository.AiQueryMessageRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 授权失效锁（UX D4）：对话的最新一轮是 {@code AGENT_AUTH_FAILED}，<b>且此后全系统没有任何查询成功</b>，
 * 该对话就不再接受追问 / 重新查询（页面禁用输入框、提示联系管理员）。
 *
 * <p>订阅 token 是全局的：管理员更新 token 后，只要有人（例如新建对话）查询成功，锁自动解除，
 * 不会因为历史记录把对话永久锁死。新建对话不加锁——总得有人验证更新后的 token。
 */
@Component
public class AiQueryAuthLock {

    private final AiQueryMessageRepository messages;

    public AiQueryAuthLock(AiQueryMessageRepository messages) {
        this.messages = messages;
    }

    /** {@code turns} 为对话全部轮次（按时间正序）。 */
    public boolean locked(List<AiQueryMessage> turns) {
        if (turns.isEmpty()) {
            return false;
        }
        AiQueryMessage latest = turns.get(turns.size() - 1);
        if (latest.getFailure() != AiQueryFailure.AUTH_FAILED) {
            return false;
        }
        Instant since = latest.getFinishedAt() != null ? latest.getFinishedAt() : latest.getUpdatedAt();
        return !messages.anyDoneSince(since);
    }
}
