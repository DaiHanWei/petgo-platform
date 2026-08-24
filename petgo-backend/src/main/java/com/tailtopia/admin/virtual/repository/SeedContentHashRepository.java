package com.tailtopia.admin.virtual.repository;

import com.tailtopia.admin.virtual.domain.SeedContentHash;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 种子内容去重仓储（Story 9.8 Part 2）。 */
public interface SeedContentHashRepository extends JpaRepository<SeedContentHash, String> {

    /**
     * 「经后台代发的内容数」（V1.1.6 Story 12.1 · AC7）。
     *
     * <p>🛡 <b>只数经后台代发的</b>：该账号在 App 内自主发布的内容压根不会进这张表，
     * 所以这个口径天然成立 —— 不需要再加过滤条件，也<b>不该</b>改成查 {@code content_posts}
     * （那样会把持有人自己发的帖算进来，而运营看这个数是为了核对"我们发了多少"）。
     */
    long countByAuthorId(long authorId);

    /**
     * 其中已被删除的条数（V1.1.6 Story 12.1 · AC8）。
     *
     * <p>🛡 运营真实账号有<b>两个写入方</b>（后台代发 / 持有人本人）。后台不阻止持有人
     * 在 App 内删除后台代发的内容 —— 那是他自己的账号，系统去锁定反而越权。
     * 但后台的记录<b>必须能反映"已被删除"</b>，不可继续显示为在线。
     */
    @Query("select count(h) from SeedContentHash h, ContentPost p"
            + " where h.authorId = :authorId and p.id = h.postId and p.deletedAt is not null")
    long countDeletedByAuthorId(@Param("authorId") long authorId);
}
