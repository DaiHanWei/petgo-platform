package com.tailtopia.consult.dto;

import com.tailtopia.consult.domain.VetDiagnosis;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 兽医结束会话请求（Story C）：必须随结束提交最终诊断（原型 {@code #p-vet-final-diagnosis}）。
 * {@code diagnosis} 必填（空 → 422，不结束）；其余按原型选填。
 */
public record VetEndRequest(
        @NotBlank @Size(max = 2000) String diagnosis,
        @Size(max = 2000) String generalAdvice,
        boolean needsMedication,
        @Size(max = 200) String medName,
        @Size(max = 200) String medFrequency,
        @Size(max = 200) String followUp,
        @Size(max = 2000) String worseningSigns,
        @Size(max = 200) String clinicWithin) {

    public VetDiagnosis toDiagnosis() {
        return new VetDiagnosis(diagnosis, generalAdvice, needsMedication,
                medName, medFrequency, followUp, worseningSigns, clinicWithin);
    }
}
