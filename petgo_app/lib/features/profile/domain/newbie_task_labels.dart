import 'package:flutter/widgets.dart';

/// 新手任务标签本地化（en / id）（Story 7.3 · FR-47）。后端只回稳定 `key`；显示串一律客户端出，
/// 杜绝后端语言泄漏。兜底：未知 key 返回 key 本身，绝不回退到后端中文。
///
/// ⚠️ id 翻译为工程初稿，建议印尼母语者复核。
const Map<String, ({String en, String id})> kNewbieTaskLabels = {
  'CREATE_PROFILE': (en: 'Create pet profile', id: 'Buat profil hewan'),
  'FIRST_PHOTO': (en: 'Upload first growth photo', id: 'Unggah foto pertama'),
  'SHARE_CARD': (en: 'Share pet ID card', id: 'Bagikan kartu identitas'),
  'SAVE_CONSULT': (en: 'Save a vet consult', id: 'Simpan konsultasi dokter'),
  'FIRST_DAILY': (en: 'Post a daily update', id: 'Buat postingan harian'),
  'FIRST_HEALTH_RECORD': (en: 'Add a health record', id: 'Tambah catatan kesehatan'),
};

/// 按 key + locale 返回本地化新手任务标签。未知 key 兜底返回 key。
String localizedNewbieTaskLabel(String key, Locale locale) {
  final t = kNewbieTaskLabels[key];
  if (t == null) return key;
  return locale.languageCode == 'id' ? t.id : t.en;
}
