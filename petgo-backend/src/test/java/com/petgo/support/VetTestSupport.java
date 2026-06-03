package com.petgo.support;

import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.domain.ConsultSource;
import com.petgo.consult.repository.ConsultSessionRepository;
import com.petgo.vet.domain.VetAccount;
import com.petgo.vet.domain.VetStatus;
import com.petgo.vet.repository.VetAccountRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * vet / consult 集成测试数据工厂。用 repository 直接造 active/banned 兽医账号与各状态会话，
 * 避免散落在各测试类。{@code username} 用进程内唯一序列，跨次运行不撞唯一约束。
 *
 * <p>关键：{@code vetBearer(vetId)} 经 {@code BannedVetFilter}，需 DB 存在对应 status 的 vet 行；
 * 故造 token 前必先 {@link #newActiveVet}。密码哈希经注入的 {@link PasswordEncoder} 编码（明文不落库）。
 */
@Component
public class VetTestSupport {

    private static final AtomicLong VET_SEQ = new AtomicLong(System.nanoTime());

    private final VetAccountRepository vetAccounts;
    private final ConsultSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;

    public VetTestSupport(VetAccountRepository vetAccounts, ConsultSessionRepository sessions,
            PasswordEncoder passwordEncoder) {
        this.vetAccounts = vetAccounts;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
    }

    /** active 兽医（可通过 BannedVetFilter），唯一 username，BCrypt 哈希。 */
    public VetAccount newActiveVet(String displayName) {
        long n = VET_SEQ.incrementAndGet();
        VetAccount v = VetAccount.create("it-vet-" + n, passwordEncoder.encode("secret-pass"), displayName);
        return vetAccounts.save(v);
    }

    /** BANNED 兽医：token 打任意 vet 端点应被 BannedVetFilter 401 踢下线。 */
    public VetAccount newBannedVet(String displayName) {
        VetAccount v = newActiveVet(displayName);
        v.setStatus(VetStatus.BANNED);
        return vetAccounts.save(v);
    }

    /** 待接单 DIRECT 会话（WAITING）。 */
    public ConsultSession newWaitingSession(long userId) {
        return sessions.save(ConsultSession.startWaiting(userId, ConsultSource.DIRECT));
    }

    /** 待接单 AI_UPGRADE 会话（WAITING）+ AI 上下文快照（GREEN/YELLOW，绝不含 RED）。 */
    public ConsultSession newWaitingAiSession(long userId, String dangerLevel, String symptomText,
            List<String> imageRefs) {
        ConsultSession s = ConsultSession.startWaiting(userId, ConsultSource.AI_UPGRADE);
        s.bindAiContext(null, dangerLevel, symptomText, imageRefs);
        return sessions.save(s);
    }

    /** 进行中会话（IN_PROGRESS），已绑定 vet + IM 会话标识（模拟接单后态）。 */
    public ConsultSession newInProgressSession(long userId, long vetId) {
        ConsultSession s = ConsultSession.startWaiting(userId, ConsultSource.DIRECT);
        s.markInProgress(vetId);
        s.attachImConversation("stub-conv-it-" + VET_SEQ.incrementAndGet());
        return sessions.save(s);
    }

    public ConsultSession reload(long sessionId) {
        return sessions.findById(sessionId).orElseThrow();
    }
}
