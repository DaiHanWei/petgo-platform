package com.tailtopia.vet.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.domain.VetStatus;
import com.tailtopia.vet.repository.VetAccountRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兽医账号业务（Story 5.1）。开户 / 重置密码 / 封禁开关 / 登录校验。
 *
 * <p>护栏：明文密码仅在本类内 {@link PasswordEncoder#encode} 一次，<b>绝不落库/落日志/进 MDC</b>；
 * 对外暴露的视图绝不含 {@code passwordHash}。Admin slice 经本 service 接口操作，不直访 repository。
 */
@Service
public class VetAccountService {

    private final VetAccountRepository repo;
    private final PasswordEncoder passwordEncoder;

    public VetAccountService(VetAccountRepository repo, PasswordEncoder passwordEncoder) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
    }

    /** 运营开户：username 唯一校验 → BCrypt encode → 落库。返回新建账号（不含明文）。 */
    @Transactional
    public VetAccount create(String displayName, String username, String rawPassword) {
        return create(displayName, username, rawPassword, null);
    }

    /** 运营开户（Story 2.3 重载）：附运营联系手机号（非登录凭证，可空）。 */
    @Transactional
    public VetAccount create(String displayName, String username, String rawPassword, String contactPhone) {
        validateInputs(displayName, username, rawPassword);
        if (repo.existsByUsername(username)) {
            throw AppException.conflict("该登录邮箱已被占用");
        }
        VetAccount v = VetAccount.create(username, passwordEncoder.encode(rawPassword), displayName);
        v.setContactPhone(contactPhone);
        return repo.save(v);
    }

    /** 运营重置密码：重算 BCrypt hash（旧凭证随即失效）。 */
    @Transactional
    public void resetPassword(long vetId, String newRawPassword) {
        if (newRawPassword == null || newRawPassword.length() < 8) {
            throw AppException.validation("密码至少 8 位");
        }
        VetAccount vet = repo.findById(vetId)
                .orElseThrow(() -> AppException.notFound("兽医账号不存在"));
        vet.resetPassword(passwordEncoder.encode(newRawPassword));
        repo.save(vet);
    }

    /**
     * 编辑账号资料（Story 2.4）：改显示名/登录邮箱/联系手机号。**不触碰 status/会话**（编辑不中断进行中会话）。
     * 邮箱改动时唯一校验排除自身。
     */
    @Transactional
    public void updateProfile(long vetId, String displayName, String email, String contactPhone) {
        if (displayName == null || displayName.isBlank()) {
            throw AppException.validation("兽医昵称不能为空");
        }
        if (email == null || email.isBlank()) {
            throw AppException.validation("登录邮箱不能为空");
        }
        VetAccount vet = repo.findById(vetId)
                .orElseThrow(() -> AppException.notFound("兽医账号不存在"));
        // 邮箱改动 → 唯一校验排除自身。
        if (!email.equals(vet.getUsername()) && repo.existsByUsername(email)) {
            throw AppException.conflict("该登录邮箱已被占用");
        }
        vet.setDisplayName(displayName);
        vet.setUsername(email);
        vet.setContactPhone(contactPhone);
        repo.save(vet);
    }

    /** 封禁/解封（5.7 复用）。本故事落 ACTIVE↔BANNED 切换。 */
    @Transactional
    public void setStatus(long vetId, VetStatus status) {
        VetAccount vet = repo.findById(vetId)
                .orElseThrow(() -> AppException.notFound("兽医账号不存在"));
        vet.setStatus(status);
        repo.save(vet);
    }

    /**
     * 登录校验：账密比对 + 状态门控。失败统一抛 401（不区分账号不存在/密码错/封禁，防枚举）。
     * BANNED 直接拒登（与 5.7 同源语义）。
     */
    @Transactional(readOnly = true)
    public VetAccount authenticate(String username, String rawPassword) {
        Optional<VetAccount> found = repo.findByUsername(username);
        // 防时序侧信道：账号不存在也走一次假比对（保持比对耗时一致）。
        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, "$2a$10$invalidinvalidinvalidinvalidinvalidinvalidinvalidinva");
            throw AppException.unauthorized("账号或密码错误");
        }
        VetAccount vet = found.get();
        if (!passwordEncoder.matches(rawPassword, vet.getPasswordHash())) {
            throw AppException.unauthorized("账号或密码错误");
        }
        if (!vet.isActive()) {
            throw AppException.unauthorized("账号或密码错误"); // BANNED 不可登录，文案不泄露封禁状态
        }
        return vet;
    }

    @Transactional(readOnly = true)
    public VetAccount getById(long vetId) {
        return repo.findById(vetId)
                .orElseThrow(() -> AppException.notFound("兽医账号不存在"));
    }

    /** 账号是否活跃（Story 5.7 封禁即生效：已登录请求每次校验 status，BANNED/不存在 → false）。 */
    @Transactional(readOnly = true)
    public boolean isActive(long vetId) {
        return repo.findById(vetId).map(VetAccount::isActive).orElse(false);
    }

    @Transactional(readOnly = true)
    public List<VetAccount> listAll() {
        return repo.findAll();
    }

    private static void validateInputs(String displayName, String username, String rawPassword) {
        if (displayName == null || displayName.isBlank()) {
            throw AppException.validation("兽医昵称不能为空");
        }
        if (username == null || username.length() < 3) {
            throw AppException.validation("登录账号至少 3 位");
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            throw AppException.validation("密码至少 8 位");
        }
    }
}
