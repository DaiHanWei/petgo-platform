import 'package:flutter/foundation.dart';

import '../../domain/id_card.dart';
import 'ktp_card.dart';

/// KTP 预览会话编辑覆盖（Story 6.2 · AC3）。**仅存本地会话**，绝不写档案、后端不接收。
/// 每个字段 null = 用默认（档案数据 / 趣味默认）；非 null = 用户在预览里改过的值。退出预览即丢弃。
@immutable
class KtpEdits {
  const KtpEdits({
    this.nama,
    this.tempatTglLahir,
    this.spesies,
    this.ras,
    this.jenisKelamin,
    this.alamat,
    this.statusPerkawinan,
    this.pekerjaan,
    this.kewarganegaraan,
    this.berlakuHingga,
  });

  final String? nama;
  final String? tempatTglLahir;
  final String? spesies;
  final String? ras;
  final String? jenisKelamin;
  final String? alamat;
  final String? statusPerkawinan;
  final String? pekerjaan;
  final String? kewarganegaraan;
  final String? berlakuHingga;

  static const KtpEdits empty = KtpEdits();

  KtpEdits copyWith({
    String? nama,
    String? tempatTglLahir,
    String? spesies,
    String? ras,
    String? jenisKelamin,
    String? alamat,
    String? statusPerkawinan,
    String? pekerjaan,
    String? kewarganegaraan,
    String? berlakuHingga,
  }) {
    return KtpEdits(
      nama: nama ?? this.nama,
      tempatTglLahir: tempatTglLahir ?? this.tempatTglLahir,
      spesies: spesies ?? this.spesies,
      ras: ras ?? this.ras,
      jenisKelamin: jenisKelamin ?? this.jenisKelamin,
      alamat: alamat ?? this.alamat,
      statusPerkawinan: statusPerkawinan ?? this.statusPerkawinan,
      pekerjaan: pekerjaan ?? this.pekerjaan,
      kewarganegaraan: kewarganegaraan ?? this.kewarganegaraan,
      berlakuHingga: berlakuHingga ?? this.berlakuHingga,
    );
  }
}

/// KTP 字段默认值（趣味仿制，印尼语——KTP 设计常量，非 app-locale UI）。
class KtpDefaults {
  static const String tempatKota = 'BANDUNG';
  static const String jenisKelamin = 'JANTAN';
  static const String alamat = 'JL. MELATI NO. 25, BANDUNG';
  static const String statusPerkawinan = 'LAJANG';
  static const String pekerjaan = 'CHIEF HAPPINESS OFFICER';
  static const String kewarganegaraan = 'INDONESIA';
  static const String berlakuHingga = 'SEUMUR HIDUP';
  static const String namaFallback = 'MOCHI';

  /// petType(CAT/DOG/OTHER) → 印尼语物种（KTP 展示常量；App 不渲染后端显示串，按 code 本地化）。
  static String spesies(String? petType) {
    switch (petType) {
      case 'CAT':
        return 'KUCING';
      case 'DOG':
        return 'ANJING';
      default:
        return 'HEWAN';
    }
  }

  /// gender wire 值 → KTP 卡面 Jenis Kelamin（印尼语设计常量，非 app-locale）。
  /// null（旧卡/无字段）→ 维持旧默认 [jenisKelamin]，旧卡展示零变化。
  static String jenisKelaminFor(String? gender) {
    switch (gender) {
      case 'MALE':
        return 'JANTAN';
      case 'FEMALE':
        return 'BETINA';
      case 'UNKNOWN':
        return '-';
      default:
        return jenisKelamin;
    }
  }
}

/// petType → 新编码规则物种段 SP（狗 01 / 猫 02 / 其他与未选 00）。
String speciesCodeFor(String? petType) => switch (petType) {
      'DOG' => '01',
      'CAT' => '02',
      _ => '00',
    };

/// 建卡预览用**客户端占位身份码**（未落库无序号，末四位占位 `0000`；创建后以后端 cardNo 为准）。
/// 规则：`TT` + DD(日+性别加码：母50/公10/未知0) + MMYY + SP + `0000`。生日缺失 → null（走旧占位 NIK）。
String? buildPreviewCardNo({DateTime? birthday, String? gender, String? petType}) {
  if (birthday == null) return null;
  final offset = switch (gender) {
    'FEMALE' => 50,
    'MALE' => 10,
    _ => 0,
  };
  final dd = (birthday.day + offset).toString().padLeft(2, '0');
  final mmyy = '${_p2(birthday.month)}${_p2(birthday.year % 100)}';
  return 'TT$dd$mmyy${speciesCodeFor(petType)}0000';
}

/// 建卡预览用**客户端占位护照号**（当年顺序号占位 `00000`；创建后以后端 passportNo 为准）。
/// 规则：`TT` + SP + `P` + 签发年后两位 + `00000`。
String buildPreviewPassportNo({String? petType, required int year}) =>
    'TT${speciesCodeFor(petType)}P${_p2(year % 100)}00000';

/// 纯函数：合并 6-1 档案数据 + 会话编辑覆盖 + 趣味默认 → KTP 展示字段。
/// **不触碰档案真值**（AC3）；相同 [data] + 空 [edits] 恒得档案态（可 L0 断言）。
KtpFields buildKtpFields(IdCardData data, KtpEdits edits) {
  final birthday = data.birthday;
  final dob = birthday == null ? '01-01-2020' : _dmy(birthday);
  // 趣味字段快照（bug 20260729-409）：优先卡快照值（大写展示，与卡面设计一致），null 回落趣味默认。
  final birthCity = _upperOrNull(data.birthCity) ?? KtpDefaults.tempatKota;
  final tempatTgl = edits.tempatTglLahir ?? '$birthCity, $dob';
  return KtpFields(
    // 新编码卡直显后端 cardNo；旧卡（cardNo=null）维持旧拼号，展示零变化。
    nik: (data.cardNo?.isNotEmpty == true) ? data.cardNo! : _buildNik(data.serialId, birthday),
    nama: edits.nama ?? data.name ?? KtpDefaults.namaFallback,
    tempatTglLahir: tempatTgl,
    spesies: edits.spesies ?? KtpDefaults.spesies(data.petType),
    ras: edits.ras ?? (data.breed?.isNotEmpty == true ? data.breed! : '-'),
    jenisKelamin: edits.jenisKelamin ?? KtpDefaults.jenisKelaminFor(data.gender),
    alamat: edits.alamat ?? _upperOrNull(data.address) ?? KtpDefaults.alamat,
    statusPerkawinan:
        edits.statusPerkawinan ?? _upperOrNull(data.maritalStatus) ?? KtpDefaults.statusPerkawinan,
    pekerjaan: edits.pekerjaan ?? _upperOrNull(data.occupation) ?? KtpDefaults.pekerjaan,
    kewarganegaraan: edits.kewarganegaraan ?? KtpDefaults.kewarganegaraan,
    berlakuHingga: edits.berlakuHingga ?? KtpDefaults.berlakuHingga,
    placeLine: birthCity,
    dateLine: dob,
    avatarUrl: data.avatarUrl,
  );
}

/// 趣味字段快照值 → 卡面大写展示；null/空串 → null（回落趣味默认）。
String? _upperOrNull(String? v) {
  final t = v?.trim();
  return (t == null || t.isEmpty) ? null : t.toUpperCase();
}

/// KTP 风格 16 位 NIK：区域码(3276) + 生日 DDMMYY + serial 补零到 6 位。趣味且随 serial 唯一。
/// serial 仅作展示编号，绝不作对外定位键（6-1 AC3）。
String _buildNik(int? serialId, DateTime? birthday) {
  final ddmmyy = birthday == null
      ? '010120'
      : '${_p2(birthday.day)}${_p2(birthday.month)}${_p2(birthday.year % 100)}';
  final seq = (serialId ?? 0).toString().padLeft(6, '0');
  return '3276$ddmmyy$seq';
}

String _dmy(DateTime d) => '${_p2(d.day)}-${_p2(d.month)}-${d.year}';
String _p2(int n) => n.toString().padLeft(2, '0');
