package com.tailtopia.admin.failedrequest.repository;

import com.tailtopia.admin.failedrequest.domain.FailedConsultRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 失败问诊请求仓库（Story 2.9）。活动区 = 未归档；归档区 = 已归档（archived_at 非空）。
 */
public interface FailedConsultRequestRepository extends JpaRepository<FailedConsultRequest, Long> {

    Optional<FailedConsultRequest> findByRequestToken(String requestToken);

    /** 活动区（未归档），按取消时间倒序。 */
    List<FailedConsultRequest> findByArchivedAtIsNullOrderByCancelledAtDesc();

    /** 归档区（已归档），按归档时间倒序。 */
    List<FailedConsultRequest> findByArchivedAtIsNotNullOrderByArchivedAtDesc();
}
