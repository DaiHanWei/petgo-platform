/// 本次会诊最终诊断（用户侧只读视图，对应后端 `VetDiagnosis` JSONB）。
///
/// 兽医结束会话时定格；用户经 `GET /consult-sessions/{id}/diagnosis` 取（未出诊断 → null）。
/// 健康数据：仅按需展示，不落日志。
class ConsultDiagnosis {
  const ConsultDiagnosis({
    required this.diagnosis,
    this.generalAdvice = '',
    this.needsMedication = false,
    this.medName = '',
    this.medFrequency = '',
    this.followUp = '',
    this.worseningSigns = '',
    this.clinicWithin = '',
  });

  final String diagnosis;
  final String generalAdvice;
  final bool needsMedication;
  final String medName;
  final String medFrequency;
  final String followUp;
  final String worseningSigns;
  final String clinicWithin;

  factory ConsultDiagnosis.fromJson(Map<String, dynamic> json) => ConsultDiagnosis(
        diagnosis: (json['diagnosis'] ?? '') as String,
        generalAdvice: (json['generalAdvice'] ?? '') as String,
        needsMedication: (json['needsMedication'] ?? false) as bool,
        medName: (json['medName'] ?? '') as String,
        medFrequency: (json['medFrequency'] ?? '') as String,
        followUp: (json['followUp'] ?? '') as String,
        worseningSigns: (json['worseningSigns'] ?? '') as String,
        clinicWithin: (json['clinicWithin'] ?? '') as String,
      );
}
