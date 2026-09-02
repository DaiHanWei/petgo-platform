package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentShare;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 单条内容分享（Story 9.3）。对外只按 token 查，绝不按顺序 id 对外寻址。 */
public interface ContentShareRepository extends JpaRepository<ContentShare, Long> {

    Optional<ContentShare> findByShareToken(String shareToken);

    Optional<ContentShare> findByContentPostId(long contentPostId);

    /** 账号注销 / 内容硬清理时一并删（分享链接不该比内容活得久）。 */
    void deleteByContentPostIdIn(java.util.Collection<Long> contentPostIds);

    /**
     * 账号注销级联（Story 7.3 / F14「分享链接立即失效」）：删除该作者全部内容的分享行。
     * 注销走批量隐藏拿不到逐条 post id（见 {@code ContentService.deactivateAuthorContent}），
     * 故用子查询整批删。幂等。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
            DELETE FROM ContentShare s
            WHERE s.contentPostId IN
                (SELECT p.id FROM ContentPost p WHERE p.authorId = :authorId)
            """)
    int deleteByAuthorId(@org.springframework.data.repository.query.Param("authorId") long authorId);
}
