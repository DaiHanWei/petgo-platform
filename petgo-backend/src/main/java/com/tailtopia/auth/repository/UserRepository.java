package com.tailtopia.auth.repository;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 生命周期推送日扫候选（留存手册抓手 1）。只取<b>能被推、且推了有意义</b>的账号：
     * 普通用户（不推兽医/管理员）、真实账号（<b>不推虚拟种子账号</b>——给自己的运营马甲发召回
     * 既污染漏斗又毫无意义）、未注销、未停用、状态 ACTIVE（被封号的人不该收到「回来记录吧」）。
     */
    @Query("select u from User u where u.role = :role and u.accountType = :accountType "
            + "and u.deletedAt is null and u.enabled = true and u.status = :status")
    List<User> findLifecyclePushCandidates(@Param("role") Role role,
            @Param("accountType") AccountType accountType,
            @Param("status") UserStatus status);

    /**
     * 刷新最后活跃时刻，<b>每日至多写一次</b>（留存手册抓手 1 的流失判定依据）。
     *
     * <p>{@code dayStart} 之后已刷过则 WHERE 不命中 → Postgres 不产生行版本、不写 WAL，
     * 代价只剩一次主键索引查找。这就是为什么这里不需要 Redis 去重键：
     * 条件 UPDATE 本身就是幂等的，再压一层缓存只会多一个可失效的活动部件。
     *
     * <p>JPQL 批量更新绕过 {@code @PreUpdate} —— 刻意为之：活跃刷新<b>不应</b>动 {@code updated_at}
     * （那一列的语义是「业务数据被改过」，不是「人来过」）。
     */
    @Modifying
    @Query("update User u set u.lastActiveAt = :now "
            + "where u.id = :userId and (u.lastActiveAt is null or u.lastActiveAt < :dayStart)")
    int touchLastActiveAt(@Param("userId") long userId, @Param("now") Instant now,
            @Param("dayStart") Instant dayStart);

    /**
     * 用户标签选择器的候选（bug 20260828）：**未注销**的普通用户，按 id 或昵称模糊匹配。
     *
     * <p>🔴 {@code deletedAt is null} 是这条查询存在的主要理由 —— 后台此前只有一个手填
     * 用户 ID 的文本框，运营把标签分给了一个已注销账号。选择器从**列不出来**开始堵，
     * 服务层的硬校验（{@code UserTagQueryService#assign}）是第二道。两道都要有：
     * 只有选择器的话，手填那条路照样能绕过去。
     *
     * <p>⚠️ **不剔除已停用（封号）账号**：运营有时确实要给封号账号挂标签（如「观察中」），
     * 但候选行必须标注状态 —— 与召回名单导出同一条口径（不替运营做决定，
     * 但也不让他在不知情的情况下操作）。
     *
     * <p>⚠️ {@code :pattern} 绝不传 null：空关键词传 {@code "%"} 匹配全部。
     * 绑 null 时 Postgres 推不出类型，而「不带关键词」正是首次加载的那一次
     * （与内容标签的 {@code searchPinnable} 同一个坑）。
     */
    @Query("""
            select u from User u
            where u.role = :role and u.deletedAt is null
              and (:pattern = '%'
                   or lower(coalesce(u.nickname, '')) like :pattern
                   or lower(coalesce(u.displayName, '')) like :pattern
                   or cast(u.id as string) like :pattern)
            order by u.id desc
            """)
    Page<User> searchTaggableUsers(@Param("role") Role role, @Param("pattern") String pattern,
            Pageable pageable);
}

