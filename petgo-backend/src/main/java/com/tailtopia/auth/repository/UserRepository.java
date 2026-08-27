package com.tailtopia.auth.repository;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByGoogleSub(String googleSub);

    /** FR-44：Apple 登录按 apple_sub 取号（首登未命中则建号）。 */
    Optional<User> findByAppleSub(String appleSub);

    /** Story 3.1：ADMIN 账密登录按 email + role 精确匹配。 */
    Optional<User> findByEmailAndRole(String email, Role role);

    /** bug 20260701-164：后台用户管理按角色分页列举（只列普通用户 USER）。 */
    Page<User> findByRole(Role role, Pageable pageable);

    /** 虚拟账号列表（Story 9.8，A-6），近建在前。 */
    java.util.List<User> findByAccountTypeOrderByIdDesc(AccountType accountType);


    /** 概览看板（Story 9.10）：按账号类型计数（如虚拟账号数）。 */
    long countByAccountType(AccountType accountType);

    /** 概览看板（bug 20260731-442）：真实且未注销的用户数（剔除虚拟/种子账号与已注销）。 */
    long countByAccountTypeAndDeletedAtIsNull(AccountType accountType);

    /** Lark 定时发帖：按「发布账号(邮箱)」定位指定作者（同邮箱可能多角色，调用方再筛 ACTIVE）。 */
    java.util.List<User> findByEmailIgnoreCase(String email);

    /** 内容审核 story 4：违规重置默认昵称唯一性查重（DefaultNameGenerator）。 */
    boolean existsByNickname(String nickname);
}
