/// 兽医最终诊断输入（Story C，原型 `#p-vet-final-diagnosis`）。提交结束会话时随 body 发后端。
/// **全字段必填**（前端按钮置灰 + 后端 422 双重兜底）；药名/频次仅在 [needsMedication] 时必填。
class VetDiagnosisDraft {
  const VetDiagnosisDraft({
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

  Map<String, dynamic> toJson() => {
        'diagnosis': diagnosis,
        'generalAdvice': generalAdvice,
        'needsMedication': needsMedication,
        'medName': medName,
        'medFrequency': medFrequency,
        'followUp': followUp,
        'worseningSigns': worseningSigns,
        'clinicWithin': clinicWithin,
      };
}
