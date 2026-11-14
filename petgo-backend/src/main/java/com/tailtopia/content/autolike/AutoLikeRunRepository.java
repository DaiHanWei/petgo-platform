package com.tailtopia.content.autolike;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 自动点赞轮次（只读；写入走 {@link AutoLikePlanner} / 派发器的原生 SQL）。 */
public interface AutoLikeRunRepository extends JpaRepository<AutoLikeRun, Long> {

    Optional<AutoLikeRun> findByRunDateAndSlot(LocalDate runDate, AutoLikeSlot slot);
}
