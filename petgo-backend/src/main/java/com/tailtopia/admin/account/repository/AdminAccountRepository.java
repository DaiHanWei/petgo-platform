package com.tailtopia.admin.account.repository;

import com.tailtopia.admin.account.domain.AdminAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 后台账号仓库（Story 1.1）。后台认证唯一数据源，与 {@code UserRepository} 隔离。
 */
public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {

    /** 按 Lark 邮箱（身份标识/白名单键）精确匹配。 */
    Optional<AdminAccount> findByLarkEmail(String larkEmail);

    /** 超管数量（Story 1.5 上限 5 校验预留；本故事仅 bootstrap 用得到）。 */
    long countByAccountType(com.tailtopia.admin.account.domain.AdminAccountType accountType);
}
