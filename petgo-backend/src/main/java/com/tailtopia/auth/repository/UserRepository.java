package com.tailtopia.auth.repository;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByGoogleSub(String googleSub);

    /** FR-44：Apple 登录按 apple_sub 取号（首登未命中则建号）。 */
    Optional<User> findByAppleSub(String appleSub);

    /** Story 3.1：ADMIN 账密登录按 email + role 精确匹配。 */
    Optional<User> findByEmailAndRole(String email, Role role);

    /** bug 20260701-164：后台用户管理按角色分页列举（只列普通用户 USER）。 */
    Page<User> findByRole(Role role, Pageable pageable);

    /**
     * 后台按**手机号是否已填写**筛选用户（V1.1.6 Story 11.4 · AB-11A）。
     *
     * <p>🔴 「未填写」的判据是 <b>{@code phone IS NULL OR phone = ''}</b> —— 两种空都要算。
     * FR-70 允许用户**留空保存以撤回号码**（保存时写 null），
     * 而历史上也可能存在空串；只判 NULL 会把撤回过的人错分到"已填写"，
     * 于是运营的催填名单里就永远少了这批人。
     *
     * <p>⚠️ 字段名一律用 {@code phone}：日志脱敏按字段名匹配，
     * 换个别名转手该值就会绕过脱敏、让真实号码落盘（见该列的迁移注释）。
     */
    @Query("""
            SELECT u FROM User u
             WHERE u.role = :role
               AND (:filled = true
                    AND u.phone IS NOT NULL AND u.phone <> ''
                    OR :filled = false
                    AND (u.phone IS NULL OR u.phone = ''))
             ORDER BY u.id DESC
            """)
    Page<User> findByRoleAndPhoneFilled(@Param("role") Role role,
            @Param("filled") boolean filled, Pageable pageable);

    /** 召回名单导出（Story 11.4）：同一筛选口径、不分页、id 倒序。 */
    @Query("""
            SELECT u FROM User u
             WHERE u.role = :role
               AND (:filled = true
                    AND u.phone IS NOT NULL AND u.phone <> ''
                    OR :filled = false
                    AND (u.phone IS NULL OR u.phone = ''))
             ORDER BY u.id DESC
            """)
    java.util.List<User> findAllByRoleAndPhoneFilled(@Param("role") Role role,
            @Param("filled") boolean filled);

    /** 虚拟账号列表（Story 9.8，A-6），近建在前。 */
    java.util.List<User> findByAccountTypeOrderByIdDesc(AccountType accountType);


    /** 概览看板（Story 9.10）：按账号类型计数（如虚拟账号数）。 */
    long countByAccountType(AccountType accountType);

    /** 概览看板（bug 20260731-442）：真实且未注销的用户数（剔除虚拟/种子账号与已注销）。 */
    long countByAccountTypeAndDeletedAtIsNull(AccountType accountType);

    /** 内容审核 story 4：违规重置默认昵称唯一性查重（DefaultNameGenerator）。 */
    boolean existsByNickname(String nickname);

    /**
     * 按昵称模糊搜索某类账号（V1.1.6 Story 12.1 · AC3 纳入身份池用）。
     *
     * <p>⚠️ 与后台既有的用户搜索（{@code AdminUserService#search}，按 id 或**注册邮箱**精确命中）
     * <b>刻意分开</b>：纳入身份池时运营手里只有"那个 IP 号叫什么"，没有邮箱；
     * 而把模糊昵称匹配加进那个搜索，会改变它"命中 0 或 1 条"的既有语义。
     */
    java.util.List<User> findTop20ByRoleAndAccountTypeAndNicknameContainingIgnoreCaseOrderByIdDesc(
            Role role, com.tailtopia.auth.domain.AccountType accountType, String nickname);
}
