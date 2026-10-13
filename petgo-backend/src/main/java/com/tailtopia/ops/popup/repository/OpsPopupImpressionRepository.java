package com.tailtopia.ops.popup.repository;

import com.tailtopia.ops.popup.domain.OpsPopupImpression;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpsPopupImpressionRepository extends JpaRepository<OpsPopupImpression, Long> {

    /** {@code ids} 中该用户<b>曾经</b>曝光过的弹窗（ONCE 判定）。 */
    @Query("select distinct i.popupId from OpsPopupImpression i where i.userId = :uid and i.popupId in :ids")
    Set<Long> findShownPopupIds(@Param("uid") long userId, @Param("ids") Collection<Long> popupIds);

    /** {@code ids} 中该用户自 {@code since} 起曝光过的弹窗（DAILY 判定，since = 今日 00:00 WIB）。 */
    @Query("""
            select distinct i.popupId from OpsPopupImpression i
            where i.userId = :uid and i.popupId in :ids and i.shownAt >= :since
            """)
    Set<Long> findShownPopupIds(@Param("uid") long userId, @Param("ids") Collection<Long> popupIds,
            @Param("since") Instant since);

    @Modifying
    @Query("delete from OpsPopupImpression i where i.userId = :uid")
    int deleteByUserId(@Param("uid") long userId);
}
