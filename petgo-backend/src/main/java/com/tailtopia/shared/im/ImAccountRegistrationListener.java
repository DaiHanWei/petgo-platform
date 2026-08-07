package com.tailtopia.shared.im;

import com.tailtopia.auth.event.UserSignedUpEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 建号即注册 IM（2026-08-03 策略：不再等接单时导入/SDK 首登自动注册——两条旧路径都曾断过，
 * 生产事故 u_96 不存在致兽医消息被拒收）。
 *
 * <p>{@code @Async @TransactionalEventListener(AFTER_COMMIT)}（与昵称送审同范式）：
 * 腾讯 IM REST 是同步调用（连接/读取各 5s 超时），留在登录事务内会把一条 DB 连接钉住最长 ~10s，
 * IM 侧劣化时可耗尽 Hikari 池（PR#34 finding #6）——事务提交后异步执行，登录响应不再受 IM 影响。
 * 幂等（account_import 重复导入返回 OK）、非阻断（{@link TencentImClient} 失败仅 WARN），
 * 异步化后语义不变；极端丢失场景由幂等重导兜底（下次触达 IM 的路径可安全重试）。
 */
@Component
public class ImAccountRegistrationListener {

    private final TencentImClient imClient;

    public ImAccountRegistrationListener(TencentImClient imClient) {
        this.imClient = imClient;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserSignedUp(UserSignedUpEvent event) {
        String nick = event.nickname() != null && !event.nickname().isBlank()
                ? event.nickname() : "用户" + event.userId();
        imClient.ensureAccount(ImAccountMapper.userImId(event.userId()), nick);
    }
}
