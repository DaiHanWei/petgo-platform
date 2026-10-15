package com.tailtopia.ops.popup.repository;

import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupAudience;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpsPopupRepository extends JpaRepository<OpsPopup, Long> {

    Optional<OpsPopup> findByPublicToken(String publicToken);

    /**
     * 后台列表（AB-30）：全部弹窗（含停用 / 已结束），与 App 取用顺序同序
     * （{@code priority DESC, created_at DESC, id DESC}），运营一眼看出同时生效时谁压谁。
     */
    List<OpsPopup> findAllByOrderByPriorityDescCreatedAtDescIdDesc();

    /**
     * 当前生效、人群在 {@code audiences} 内的弹窗，按 {@code priority DESC, created_at DESC, id DESC}
     * （命中部分索引 {@code idx_ops_popups_active_pick}）。
     *
     * <p>时间窗左闭右开 {@code [starts_at, ends_at)}，{@code ends_at IS NULL} = 长期有效 ——
     * 与 {@code ScheduleWindow.isActiveAt} 同一语义。
     */
    @Query("""
            select p from OpsPopup p
            where p.status = com.tailtopia.ops.popup.domain.OpsPopupStatus.ACTIVE
              and p.startsAt <= :now
              and (p.endsAt is null or :now < p.endsAt)
              and p.audience in :audiences
            order by p.priority desc, p.createdAt desc, p.id desc
            """)
    List<OpsPopup> findLiveCandidates(@Param("now") Instant now,
            @Param("audiences") Collection<OpsPopupAudience> audiences);
}
