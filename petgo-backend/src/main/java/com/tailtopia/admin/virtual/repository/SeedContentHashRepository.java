package com.tailtopia.admin.virtual.repository;

import com.tailtopia.admin.virtual.domain.SeedContentHash;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 种子内容去重仓储（Story 9.8 Part 2；V1.1.6 Story 13.4 加作者维度 + 清理）。 */
public interface SeedContentHashRepository
        extends JpaRepository<SeedContentHash, SeedContentHash.Key> {

    /**
     * 这个作者发过这条文案没有（V1.1.6 Story 13.4）。
     *
     * <p>🔴 <b>判据加了作者维度</b>：同一文案不同账号各自独立 ——
     * 原先按 hash 单列判，第二个账号会被静默吞掉，而"同一文案换个账号再发一遍"
     * 是内容运营的常规操作。
     */
    boolean existsByContentHashAndAuthorId(String contentHash, long authorId);

    /**
     * 按内容 id 删指纹（内容被删除时清理，AC4 第三处）。
     *
     * <p>🛡 返回删了几行便于日志留痕 —— 这类"顺带清理"出问题时最难查，
     * 有条日志就能回答"那次删除到底清没清"。
     */
    long deleteByPostId(long postId);

    /** 某作者的全部指纹（注销联动清理用）。 */
    long deleteByAuthorId(long authorId);

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
     *
     * <p>⚠️ V1.1.6 Story 13.4 起指纹会随内容删除被清理，所以这个数**在新数据上会恒为 0**；
     * 它仍然保留，是为了存量（13.4 之前发的、已被删的）那部分仍能被看见。
     */
    @Query("select count(h) from SeedContentHash h, ContentPost p"
            + " where h.authorId = :authorId and p.id = h.postId and p.deletedAt is not null")
    long countDeletedByAuthorId(@Param("authorId") long authorId);
}
