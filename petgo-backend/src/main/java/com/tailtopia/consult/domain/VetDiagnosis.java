package com.tailtopia.consult.domain;

/**
 * 兽医最终诊断（Story C，原型 {@code #p-vet-final-diagnosis}）。结构化表单，整体以 JSONB 存
 * {@code consult_sessions.vet_diagnosis}（Hibernate {@code @JdbcTypeCode(JSON)} + Jackson 桥接）。
 *
 * <p>{@code diagnosis} 必填（后端 422 兜底）；其余按原型选填。健康数据：绝不进日志。
 *
 * @param diagnosis       诊断结论（必填，如 "Gastritis akut ringan"）
 * @param generalAdvice   一般建议（Saran Umum）
 * @param needsMedication 是否需用药（Perlu Obat）
 * @param medName         药名（needsMedication 时有意义）
 * @param medFrequency    用药频次
 * @param followUp        复诊时间（Waktu Kontrol Ulang）
 * @param worseningSigns  恶化征兆（Tanda Kondisi Memburuk）
 * @param clinicWithin    多久内恶化须就医（Bawa ke Klinik Jika Memburuk Dalam）
 */
public record VetDiagnosis(
        String diagnosis,
        String generalAdvice,
        boolean needsMedication,
        String medName,
        String medFrequency,
        String followUp,
        String worseningSigns,
        String clinicWithin) {
}
