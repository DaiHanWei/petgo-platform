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
}

/// 纯函数：合并 6-1 档案数据 + 会话编辑覆盖 + 趣味默认 → KTP 展示字段。
/// **不触碰档案真值**（AC3）；相同 [data] + 空 [edits] 恒得档案态（可 L0 断言）。
KtpFields buildKtpFields(IdCardData data, KtpEdits edits) {
  final birthday = data.birthday;
  final dob = birthday == null ? '01-01-2020' : _dmy(birthday);
  final tempatTgl = edits.tempatTglLahir ?? '${KtpDefaults.tempatKota}, $dob';
  return KtpFields(
    nik: _buildNik(data.serialId, birthday),
    nama: edits.nama ?? data.name ?? KtpDefaults.namaFallback,
    tempatTglLahir: tempatTgl,
    spesies: edits.spesies ?? KtpDefaults.spesies(data.petType),
    ras: edits.ras ?? (data.breed?.isNotEmpty == true ? data.breed! : '-'),
    jenisKelamin: edits.jenisKelamin ?? KtpDefaults.jenisKelamin,
    alamat: edits.alamat ?? KtpDefaults.alamat,
    statusPerkawinan: edits.statusPerkawinan ?? KtpDefaults.statusPerkawinan,
    pekerjaan: edits.pekerjaan ?? KtpDefaults.pekerjaan,
    kewarganegaraan: edits.kewarganegaraan ?? KtpDefaults.kewarganegaraan,
    berlakuHingga: edits.berlakuHingga ?? KtpDefaults.berlakuHingga,
    placeLine: KtpDefaults.tempatKota,
    dateLine: dob,
    avatarUrl: data.avatarUrl,
  );
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
