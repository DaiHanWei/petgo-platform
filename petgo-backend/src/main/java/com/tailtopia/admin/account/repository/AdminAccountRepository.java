package com.tailtopia.admin.account.repository;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 后台账号仓库（Story 1.1）。后台认证唯一数据源，与 {@code UserRepository} 隔离。
 */
public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {

    /**
     * 按 Lark 邮箱精确匹配（任意状态）。V1.3.0 Story 1.3 起邮箱只在 ACTIVE 账号间唯一（D-21），
     * 同邮箱可能同时有 1 ACTIVE + N DISABLED，此方法可能抛 IncorrectResultSize——
     * 登录 / bootstrap / 建号查重一律改用 {@link #findByLarkEmailIgnoreCaseAndStatus}。仅保留给「任意状态」语义的调用。
     */
    Optional<AdminAccount> findByLarkEmail(String larkEmail);

    /** 按 Lark 邮箱（忽略大小写）+ 状态取账号（V1.3.0 Story 1.3）：登录白名单 / bootstrap / 建号查重的 ACTIVE 口径。 */
    Optional<AdminAccount> findByLarkEmailIgnoreCaseAndStatus(String larkEmail, AdminAccountStatus status);

    /** 同邮箱全部账号（任意状态，忽略大小写，新→旧）；bootstrap 复活已停用超管用（V1.3.0 Story 1.3）。 */
    List<AdminAccount> findByLarkEmailIgnoreCaseOrderByIdDesc(String larkEmail);

    /** 换绑查重（V1.3.0 Story 1.3）：排除自身 id 后，是否存在同邮箱（忽略大小写）的指定状态账号。 */
    boolean existsByLarkEmailIgnoreCaseAndStatusAndIdNot(String larkEmail, AdminAccountStatus status, long id);

    /** 超管数量（bootstrap 用）。 */
    long countByAccountType(AdminAccountType accountType);

    /** 按类型 + 状态计数（Story 1.5 AC4：超管上限口径 = ACTIVE 的 SUPER_ADMIN < 5；DISABLED 不占名额）。 */
    long countByAccountTypeAndStatus(AdminAccountType accountType, AdminAccountStatus status);

    /** 按类型 + 状态查（Story 1.3 AC7：全体在职超管告警受众）。 */
    List<AdminAccount> findByAccountTypeAndStatus(AdminAccountType accountType, AdminAccountStatus status);
}
