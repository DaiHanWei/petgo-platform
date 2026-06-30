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

    /** 按 Lark 邮箱（身份标识/白名单键）精确匹配。 */
    Optional<AdminAccount> findByLarkEmail(String larkEmail);

    /** 超管数量（bootstrap 用）。 */
    long countByAccountType(AdminAccountType accountType);

    /** 按类型 + 状态计数（Story 1.5 AC4：超管上限口径 = ACTIVE 的 SUPER_ADMIN < 5；DISABLED 不占名额）。 */
    long countByAccountTypeAndStatus(AdminAccountType accountType, AdminAccountStatus status);

    /** 按类型 + 状态查（Story 1.3 AC7：全体在职超管告警受众）。 */
    List<AdminAccount> findByAccountTypeAndStatus(AdminAccountType accountType, AdminAccountStatus status);
}
