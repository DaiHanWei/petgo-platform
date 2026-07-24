package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.HealthRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface HealthRecordRepository extends JpaRepository<HealthRecord, Long> {

    /** 某宠物的健康记录，按发生日期倒序（同日按 id 倒序稳定）。 */
    List<HealthRecord> findByPetProfileIdOrderByEventDateDescIdDesc(long petProfileId);

    /** 某宠物在 [from, to] 内（按 event_date）的健康记录，日期升、同日 id 升——日历角标分类图标（bug 20260722-352）。 */
    List<HealthRecord> findByPetProfileIdAndEventDateBetweenOrderByEventDateAscIdAsc(
            long petProfileId, LocalDate from, LocalDate to);

    /** 归属校验取单条（记录须归属该宠物，否则空 → 调用方 404 防枚举）。 */
    Optional<HealthRecord> findByIdAndPetProfileId(long id, long petProfileId);

    /** 该宠物是否有 ≥1 条健康记录（Story 7.3 第 6 新手任务判定）。 */
    boolean existsByPetProfileId(long petProfileId);

    /** 档案删除级联硬删（Story 7.1 · PDP）。 */
    @Transactional
    void deleteByPetProfileId(long petProfileId);
}
