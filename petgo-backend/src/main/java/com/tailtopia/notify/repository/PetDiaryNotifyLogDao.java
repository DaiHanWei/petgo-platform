package com.tailtopia.notify.repository;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code pet_diary_notify_log} 的原生读写（V1.4.2 social-topic · Story 1.2 · AC4 · D-5）：追更日提醒去重日志。
 *
 * <p>无 JPA 实体（只有三列复合主键 + 时间戳），与 {@code ddl-auto=validate} 无关。
 *
 * <p>🔴 {@link #tryInsert} 是 {@code REQUIRED}：由 {@code PetDiaryNotifyWriter} 的 {@code REQUIRES_NEW} 事务调用，
 * 与写通知同成同败（写通知失败 → 日志行回滚，名额不被白占）。用 {@code ON CONFLICT DO NOTHING} 而非「先查后插」，
 * 并发下不会撞主键中止事务。
 */
@Repository
public class PetDiaryNotifyLogDao {

    private final JdbcTemplate jdbc;

    public PetDiaryNotifyLogDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试登记「该追更者今天（WIB）已收到这只宠物的提醒」。
     *
     * @return {@code true} = 本次新插入（可以发）；{@code false} = 今天已登记过（不发）
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean tryInsert(long followerId, long petProfileId, LocalDate wibDate) {
        int rows = jdbc.update("""
                INSERT INTO pet_diary_notify_log (follower_id, pet_profile_id, wib_date)
                VALUES (?, ?, ?)
                ON CONFLICT (follower_id, pet_profile_id, wib_date) DO NOTHING
                """, followerId, petProfileId, wibDate);
        return rows == 1;
    }

    /** 删除 {@code wib_date < cutoff} 的行（清理任务用）；返回删除行数。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteBefore(LocalDate cutoff) {
        return jdbc.update("DELETE FROM pet_diary_notify_log WHERE wib_date < ?", cutoff);
    }
}
