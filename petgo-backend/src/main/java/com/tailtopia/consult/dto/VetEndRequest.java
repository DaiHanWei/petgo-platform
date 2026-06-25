package com.tailtopia.consult.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tailtopia.consult.domain.VetDiagnosis;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 兽医结束会话请求（Story C）：必须随结束提交最终诊断（原型 {@code #p-vet-final-diagnosis}）。
 *
 * <p><b>全字段必填</b>（空 → 422，不结束）：diagnosis / generalAdvice / followUp / worseningSigns /
 * clinicWithin 一律 {@code @NotBlank}；用药明细（medName / medFrequency）<b>仅当 needsMedication=true 时</b>
 * 必填（选「不需用药」则不适用、可空）——见 {@link #isMedicationDetailComplete()}。前端表单同步全必填门控。
 */
public record VetEndRequest(
        @NotBlank @Size(max = 2000) String diagnosis,
        @NotBlank @Size(max = 2000) String generalAdvice,
        boolean needsMedication,
        @Size(max = 200) String medName,
        @Size(max = 200) String medFrequency,
        @NotBlank @Size(max = 200) String followUp,
        @NotBlank @Size(max = 2000) String worseningSigns,
        @NotBlank @Size(max = 200) String clinicWithin) {

    /** 需用药时药名 + 频次须齐全（不需用药则不约束）。空 → 422。 */
    @JsonIgnore
    @AssertTrue(message = "需用药时须填写药名与用药频次")
    public boolean isMedicationDetailComplete() {
        if (!needsMedication) {
            return true;
        }
        return medName != null && !medName.isBlank()
                && medFrequency != null && !medFrequency.isBlank();
    }

    public VetDiagnosis toDiagnosis() {
        return new VetDiagnosis(diagnosis, generalAdvice, needsMedication,
                medName, medFrequency, followUp, worseningSigns, clinicWithin);
    }
}
