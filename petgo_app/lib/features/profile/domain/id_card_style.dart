import 'package:flutter/widgets.dart';

import '../../../l10n/app_localizations.dart';
import '../presentation/id_card/kitas_card.dart';
import '../presentation/id_card/ktp_card.dart';
import '../presentation/id_card/ktp_fields.dart';
import '../presentation/id_card/passport_card.dart';
import '../presentation/id_card/sim_card.dart';
import '../presentation/id_card/student_card.dart';
import '../presentation/id_card/student_variant_card.dart';
import 'id_card.dart';

/// 卡款分组（V1.3.4 ktp）：「Kartu Identitas」与「Kartu Pelajar」两组。
enum IdCardStyleGroup { identity, student }

/// 建卡表单字段（PRD §3.4 字段表）。本枚举只定义数据，表单渲染归 Story 1.7。
enum IdCardFormField {
  name,
  petType,
  breed,
  gender,
  birthday,
  birthCity,
  address,
  occupation,
  maritalStatus,
  school,
  faculty,
}

/// 单一「卡款」注册表（AD-6）：8 款卡的卡种 wire 值、配色、分组、名称、缩略图、new 角标、
/// 埋点 `card_style` 值（D-7）、表单字段集、卡面画布与构建函数**只在这里写一次**。
/// 列表、详情、建卡、付费抽屉、埋点都从这里取，页面不得再各写一份 `switch`。
///
/// 枚举顺序 = 「Pilih Kartu」网格顺序。
///
/// ⚠️ 注册表放在 domain/ 却 import 卡面组件（presentation）与 [AppLocalizations]，属有意为之（AD-6）。
enum IdCardStyle {
  ktp(
    cardType: 'KTP',
    group: IdCardStyleGroup.identity,
    thumbnailAsset: 'assets/id_card/styles/ktp.webp',
    isNew: false,
    analyticsCardStyle: 0,
    formFields: {
      IdCardFormField.name,
      IdCardFormField.petType,
      IdCardFormField.breed,
      IdCardFormField.gender,
      IdCardFormField.birthday,
      IdCardFormField.birthCity,
      IdCardFormField.address,
      IdCardFormField.occupation,
      IdCardFormField.maritalStatus,
    },
  ),
  passport(
    cardType: 'PASSPORT',
    group: IdCardStyleGroup.identity,
    thumbnailAsset: 'assets/id_card/styles/passport.webp',
    isNew: false,
    analyticsCardStyle: 1,
    formFields: {
      IdCardFormField.name,
      IdCardFormField.petType,
      IdCardFormField.gender,
      IdCardFormField.birthday,
      IdCardFormField.birthCity,
    },
  ),
  kitas(
    cardType: 'KITAS',
    group: IdCardStyleGroup.identity,
    thumbnailAsset: 'assets/id_card/styles/kitas.webp',
    isNew: true,
    analyticsCardStyle: 4,
    formFields: {
      IdCardFormField.name,
      IdCardFormField.petType,
      IdCardFormField.breed,
      IdCardFormField.gender,
      IdCardFormField.birthday,
    },
  ),
  sim(
    cardType: 'SIM',
    group: IdCardStyleGroup.identity,
    thumbnailAsset: 'assets/id_card/styles/sim.webp',
    isNew: true,
    analyticsCardStyle: 3,
    formFields: {
      IdCardFormField.name,
      IdCardFormField.petType,
      IdCardFormField.gender,
      IdCardFormField.birthday,
      IdCardFormField.birthCity,
      IdCardFormField.address,
      IdCardFormField.occupation,
    },
  ),
  studentBintang(
    cardType: 'STUDENT',
    cardVariant: 'BINTANG',
    group: IdCardStyleGroup.student,
    thumbnailAsset: 'assets/id_card/styles/student_bintang.webp',
    isNew: true,
    analyticsCardStyle: 2,
    formFields: _studentFields,
  ),
  studentMalam(
    cardType: 'STUDENT',
    cardVariant: 'MALAM',
    group: IdCardStyleGroup.student,
    thumbnailAsset: 'assets/id_card/styles/student_malam.webp',
    isNew: true,
    analyticsCardStyle: 2,
    formFields: _studentFields,
  ),
  studentCeria(
    cardType: 'STUDENT',
    cardVariant: 'CERIA',
    group: IdCardStyleGroup.student,
    thumbnailAsset: 'assets/id_card/styles/student_ceria.webp',
    isNew: true,
    analyticsCardStyle: 2,
    formFields: _studentFields,
  ),
  studentKlasik(
    cardType: 'STUDENT',
    cardVariant: 'KLASIK',
    group: IdCardStyleGroup.student,
    thumbnailAsset: 'assets/id_card/styles/student_klasik.webp',
    isNew: false,
    analyticsCardStyle: 2,
    formFields: _studentFields,
  );

  const IdCardStyle({
    required this.cardType,
    this.cardVariant,
    required this.group,
    required this.thumbnailAsset,
    required this.isNew,
    required this.analyticsCardStyle,
    required this.formFields,
  });

  /// 卡种 wire 值（KTP / PASSPORT / KITAS / SIM / STUDENT）。
  final String cardType;

  /// 学生卡配色 wire 值（BINTANG / MALAM / CERIA / KLASIK）；非学生卡 null。
  final String? cardVariant;
  final IdCardStyleGroup group;

  /// 「Pilih Kartu」缩略图路径常量（图片由 Story 1.5 的渲染脚本产出）。
  final String thumbnailAsset;

  /// 是否挂 `new` 角标（是否显示还受 Story 1.5 的截止日期常量约束）。
  final bool isNew;

  /// 分享埋点 `card_style` 值（D-7）：KTP 0、护照 1、学生卡四款 2、SIM 3、居住证 4。
  final int analyticsCardStyle;

  /// 建卡表单字段集（PRD §3.4）。
  final Set<IdCardFormField> formFields;

  /// 卡款名称。
  String label(AppLocalizations l10n) => switch (this) {
        IdCardStyle.ktp => l10n.idCardStyleKtp,
        IdCardStyle.passport => l10n.idCardStylePaspor,
        IdCardStyle.kitas => l10n.idCardStyleKitas,
        IdCardStyle.sim => l10n.idCardStyleSim,
        IdCardStyle.studentBintang => l10n.idCardStyleStudentBintang,
        IdCardStyle.studentMalam => l10n.idCardStyleStudentMalam,
        IdCardStyle.studentCeria => l10n.idCardStyleStudentCeria,
        IdCardStyle.studentKlasik => l10n.idCardStyleStudentKlasik,
      };

  /// 卡面逻辑画布（导出按此宽算 pixelRatio）。
  Size get canvas => switch (this) {
        IdCardStyle.ktp => kIdCardCanvas,
        IdCardStyle.passport => kPassportCardCanvas,
        IdCardStyle.kitas => kKitasCardCanvas,
        IdCardStyle.sim => kSimCardCanvas,
        _ => kStudentCardCanvas,
      };

  /// 卡面圆角（画布坐标），供水印层裁剪对齐。
  double get canvasRadius => switch (this) {
        IdCardStyle.ktp => kIdCardCanvasRadius,
        IdCardStyle.passport => kPassportCardCanvasRadius,
        IdCardStyle.kitas => kKitasCardCanvasRadius,
        IdCardStyle.sim => kSimCardCanvasRadius,
        IdCardStyle.studentKlasik => kStudentCardCanvasRadius,
        _ => kStudentVariantCanvasRadius,
      };

  /// 卡面（KTP 传 [KtpEdits.empty]，与改版前相同）。
  Widget buildFront(IdCardData data) => switch (this) {
        IdCardStyle.ktp => KtpCardFront(fields: buildKtpFields(data, KtpEdits.empty)),
        IdCardStyle.passport => PassportCardFront(fields: buildPassportFields(data)),
        IdCardStyle.kitas => KitasCardFront(fields: buildKitasFields(data)),
        IdCardStyle.sim => SimCardFront(fields: buildSimFields(data)),
        IdCardStyle.studentBintang => StudentVariantCardFront(
            layout: StudentVariantLayout.bintang, fields: buildStudentFields(data)),
        IdCardStyle.studentMalam => StudentVariantCardFront(
            layout: StudentVariantLayout.malam, fields: buildStudentFields(data)),
        IdCardStyle.studentCeria => StudentVariantCardFront(
            layout: StudentVariantLayout.ceria, fields: buildStudentFields(data)),
        IdCardStyle.studentKlasik => StudentCardFront(fields: buildStudentFields(data)),
      };

  /// 按卡快照反查卡款：`cardType` null → KTP（同 [IdCard.effectiveCardType]）；学生卡配色 null 或未知 →
  /// Klasik；未知卡种 → KTP（不崩溃，与改版前 `switch` 的兜底分支一致）。
  static IdCardStyle fromCard(IdCard card) => fromWire(card.effectiveCardType, card.cardVariant);

  /// 按 wire 值反查（[fromCard] 的纯值版本）。
  static IdCardStyle fromWire(String? cardType, String? cardVariant) {
    final type = cardType ?? 'KTP';
    if (type == 'STUDENT') {
      for (final s in values) {
        if (s.cardType == 'STUDENT' && s.cardVariant == cardVariant) return s;
      }
      return IdCardStyle.studentKlasik;
    }
    for (final s in values) {
      if (s.cardType == type) return s;
    }
    return IdCardStyle.ktp;
  }
}

const Set<IdCardFormField> _studentFields = {
  IdCardFormField.name,
  IdCardFormField.petType,
  IdCardFormField.gender,
  IdCardFormField.birthday,
  IdCardFormField.school,
  IdCardFormField.faculty,
};

/// `new` 角标截止时刻（V1.3.4 ktp D-3 / AD-11）。
///
/// **发版检查项：上线日 + 30 天（WIB）**——发版时改成「上线日 + 30 天」的 WIB 00:00（以 UTC 写出：
/// WIB 某日 00:00 = 前一日 UTC 17:00）。到达该时刻起所有用户都不再显示 `new` 角标，不做后台配置。
/// 开发期取远期占位值（T-10）：WIB 2027-01-01 00:00。
/// ⚠️ `DateTime` 没有 const 构造，只能是 `final`。
final DateTime kIdCardNewStylesBadgeUntil = DateTime.utc(2026, 12, 31, 17);

/// 该卡款当下是否显示 `new` 角标：注册表标了 new，且尚未到截止时刻。页面只调它，不自己比日期。
bool idCardStyleShowsNewBadge(IdCardStyle style, {DateTime? now}) =>
    style.isNew && (now ?? DateTime.now()).toUtc().isBefore(kIdCardNewStylesBadgeUntil);
