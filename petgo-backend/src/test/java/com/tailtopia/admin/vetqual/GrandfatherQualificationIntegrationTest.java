package com.tailtopia.admin.vetqual;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.admin.vetqual.domain.VetQualification;
import com.tailtopia.admin.vetqual.repository.VetQualificationRepository;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import com.tailtopia.vet.domain.VetAccount;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：存量兽医 grandfather 资质（V43，code review #1）。验「CERTIFIED + sipdh_expiry=NULL」这一回填行形态：
 * (a) 立即可接单；(b) 到期扫描（2.8）跳过 NULL 到期 → 不被翻成 EXPIRING_SOON/EXPIRED。
 * 注：V43 迁移本体回填的是「迁移时刻已存在」的兽医（测试库迁移时为空），此处验证回填行的语义契约。
 */
class GrandfatherQualificationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private VetTestSupport vetSupport;
    @Autowired
    private VetQualificationService qualService;
    @Autowired
    private VetQualificationRepository vetQualifications;

    @Test
    void grandfatherRowAllowsConsultAndSurvivesExpiryScan() {
        VetAccount vet = vetSupport.newActiveVetWithoutQualification("既往兽医-" + SEQ.incrementAndGet());
        // V43 回填行形态：CERTIFIED + sipdh_expiry=NULL。
        VetQualification q = VetQualification.pendingFor(vet.getId());
        q.markCertified();
        q.setSipdhExpiry(null);
        vetQualifications.save(q);

        assertThat(qualService.canTakeConsult(vet.getId())).isTrue();

        // 即使扫描日设在极远未来，NULL 到期的 grandfather 行也不应被处置（扫描只取 expiry NOT NULL）。
        qualService.scanExpiry(LocalDate.of(2099, 1, 1));

        assertThat(vetQualifications.findByVetAccountId(vet.getId()).orElseThrow().getStatus())
                .isEqualTo(QualificationStatus.CERTIFIED);
        assertThat(qualService.canTakeConsult(vet.getId())).isTrue();
    }
}
