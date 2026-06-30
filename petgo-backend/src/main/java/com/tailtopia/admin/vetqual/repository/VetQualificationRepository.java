package com.tailtopia.admin.vetqual.repository;

import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.admin.vetqual.domain.VetQualification;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 兽医资质仓库（Story 2.1 / 2.8）。1:1 行按 {@code vet_account_id} 取。
 */
public interface VetQualificationRepository extends JpaRepository<VetQualification, Long> {

    Optional<VetQualification> findByVetAccountId(long vetAccountId);

    boolean existsByVetAccountId(long vetAccountId);

    /** SIPDH 到期扫描（Story 2.8）：取指定状态且有到期日的资质行。 */
    List<VetQualification> findByStatusInAndSipdhExpiryNotNull(Collection<QualificationStatus> statuses);

    /** 状态计数（Story 2.8 预警徽标）。 */
    long countByStatus(QualificationStatus status);
}
