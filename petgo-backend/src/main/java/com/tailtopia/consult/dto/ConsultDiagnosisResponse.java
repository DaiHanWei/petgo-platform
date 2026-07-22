package com.tailtopia.consult.dto;

import com.tailtopia.consult.domain.VetDiagnosis;

/**
 * 用户侧最终诊断响应：{@link VetDiagnosis} 扁平字段 + {@code archived}（本次会诊是否已存入宠物 diary）。
 *
 * <p>{@code archived} 供结果页隐藏/切换「保存到 diary」按钮（bug 20260721-333：已存档记录再点开不应再显示保存按钮）。
 * 字段与 {@code VetDiagnosis} 逐一对齐，保持前端既有 {@code ConsultDiagnosis.fromJson} 扁平解析不变，仅新增 {@code archived}。
 * 诊断为健康数据：仅按需返回，绝不进日志。
 */
public record ConsultDiagnosisResponse(
        String diagnosis,
        String generalAdvice,
        boolean needsMedication,
        String medName,
        String medFrequency,
        String followUp,
        String worseningSigns,
        String clinicWithin,
        boolean archived) {

    public static ConsultDiagnosisResponse of(VetDiagnosis d, boolean archived) {
        return new ConsultDiagnosisResponse(
                d.diagnosis(), d.generalAdvice(), d.needsMedication(), d.medName(),
                d.medFrequency(), d.followUp(), d.worseningSigns(), d.clinicWithin(), archived);
    }
}
