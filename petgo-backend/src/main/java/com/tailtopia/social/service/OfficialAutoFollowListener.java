package com.tailtopia.social.service;

import com.tailtopia.auth.event.UserSignedUpEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 注册成功后异步自动关注官方号（与 {@code ImAccountRegistrationListener} 同范式）。
 * 失败只记 WARN、不影响注册；写入在 {@link OfficialAutoFollowService} 的 REQUIRES_NEW 事务内完成。
 */
@Component
public class OfficialAutoFollowListener {

    private static final Logger log = LoggerFactory.getLogger(OfficialAutoFollowListener.class);

    private final OfficialAutoFollowService service;

    public OfficialAutoFollowListener(OfficialAutoFollowService service) {
        this.service = service;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserSignedUp(UserSignedUpEvent event) {
        try {
            service.followOfficialAccounts(event.userId());
        } catch (RuntimeException e) {
            log.warn("自动关注官方号失败 userId={}", event.userId(), e);
        }
    }
}
