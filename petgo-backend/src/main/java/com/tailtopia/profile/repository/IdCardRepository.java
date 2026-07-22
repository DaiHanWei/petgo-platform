package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.IdCard;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdCardRepository extends JpaRepository<IdCard, Long> {

    /** 历史列表：某用户全部卡，建卡时刻倒序（Story 6-7）。 */
    List<IdCard> findByUserIdOrderByCreatedAtDesc(long userId);

    /** 单卡详情（归属校验）：非本人返回空 → 上层 404 防枚举。 */
    Optional<IdCard> findByIdAndUserId(long id, long userId);
}
