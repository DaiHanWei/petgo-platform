import 'package:flutter/widgets.dart';

/// 里程碑标题本地化（en / id）。
///
/// 后端 `MilestoneCatalog` 只是稳定 `code`（C/D/G-S/M/L+序号）的事实源；**显示文案一律在客户端
/// 按设备 locale 出**，杜绝任何后端语言（中文）泄漏到 App。兜底：未知 code 返回 code 本身，
/// 绝不回退到后端中文 title。
///
/// ⚠️ id 翻译为工程初稿，建议印尼母语者复核用词（尤其 perawatan/steril 等术语）。
const Map<String, ({String en, String id})> kMilestoneTitles = {
  // ===== CAT (C) =====
  'C-S1': (en: 'Profile created', id: 'Profil dibuat'),
  'C-S2': (en: 'First growth photo', id: 'Foto pertama di kalender'),
  'C-S3': (en: 'First card shared', id: 'Kartu pertama dibagikan'),
  'C-S4': (en: 'First vet note saved', id: 'Catatan dokter pertama'),
  'C-S5': (en: 'First daily post', id: 'Postingan harian pertama'),
  'C-S6': (en: 'First bath', id: 'Mandi pertama'),
  'C-S7': (en: 'First nail trim', id: 'Potong kuku pertama'),
  'C-S8': (en: 'First treat', id: 'Camilan pertama'),
  'C-S9': (en: 'First nap beside you', id: 'Tidur di sisimu pertama'),
  'C-S10': (en: 'First purr', id: 'Dengkuran pertama'),
  'C-S11': (en: 'First window sunbath', id: 'Berjemur di jendela pertama'),
  'C-S12': (en: 'First wand play', id: 'Main tongkat pertama'),
  'C-S13': (en: 'First box dive', id: 'Masuk kardus pertama'),
  'C-S14': (en: 'First comment', id: 'Komentar pertama'),
  'C-S15': (en: 'First like', id: 'Suka pertama'),
  'C-M1': (en: 'First outdoor adventure', id: 'Petualangan pertama'),
  'C-M2': (en: 'First car ride', id: 'Naik mobil pertama'),
  'C-M3': (en: 'First vaccination', id: 'Vaksinasi pertama'),
  'C-M4': (en: 'First deworming', id: 'Obat cacing pertama'),
  'C-M5': (en: 'First vet visit', id: 'Ke dokter hewan pertama'),
  'C-M6': (en: 'Met another cat', id: 'Bertemu kucing lain'),
  'C-M7': (en: 'Knows their name', id: 'Kenal namanya'),
  'C-M8': (en: '30 days together', id: '30 hari bersama'),
  'C-M9': (en: 'Spay/neuter done', id: 'Steril selesai'),
  'C-M10': (en: '10 growth entries', id: '10 catatan tumbuh kembang'),
  'C-L1': (en: 'First birthday 🎂', id: 'Ulang tahun pertama 🎂'),
  'C-L2': (en: '100 days together', id: '100 hari bersama'),
  'C-L3': (en: '365 days together', id: '365 hari bersama'),
  'C-L4': (en: 'All health milestones', id: 'Semua tonggak kesehatan'),
  'C-L5': (en: '30 growth entries', id: '30 catatan tumbuh kembang'),
  'C-S16': (en: 'Beginner graduate 🎓', id: 'Lulus Pemula 🎓'),

  // ===== DOG (D) =====
  'D-S1': (en: 'Profile created', id: 'Profil dibuat'),
  'D-S2': (en: 'First growth photo', id: 'Foto pertama di kalender'),
  'D-S3': (en: 'First card shared', id: 'Kartu pertama dibagikan'),
  'D-S4': (en: 'First vet note saved', id: 'Catatan dokter pertama'),
  'D-S5': (en: 'First daily post', id: 'Postingan harian pertama'),
  'D-S6': (en: 'First bath', id: 'Mandi pertama'),
  'D-S7': (en: 'First grooming', id: 'Perawatan bulu pertama'),
  'D-S8': (en: 'First treat', id: 'Camilan pertama'),
  'D-S9': (en: 'First nap beside you', id: 'Tidur di sisimu pertama'),
  'D-S10': (en: 'First tail wag', id: 'Kibas ekor pertama'),
  'D-S11': (en: 'First collar & leash', id: 'Kalung & tali pertama'),
  'D-S12': (en: 'First ball play', id: 'Main bola pertama'),
  'D-S13': (en: 'First swim', id: 'Berenang pertama'),
  'D-S14': (en: 'First comment', id: 'Komentar pertama'),
  'D-S15': (en: 'First like', id: 'Suka pertama'),
  'D-M1': (en: 'First walk', id: 'Jalan-jalan pertama'),
  'D-M2': (en: 'First car ride', id: 'Naik mobil pertama'),
  'D-M3': (en: 'First vaccination', id: 'Vaksinasi pertama'),
  'D-M4': (en: 'First deworming', id: 'Obat cacing pertama'),
  'D-M5': (en: 'First vet visit', id: 'Ke dokter hewan pertama'),
  'D-M6': (en: 'Met another dog', id: 'Bertemu anjing lain'),
  'D-M7': (en: 'First command learned', id: 'Perintah pertama dikuasai'),
  'D-M8': (en: '30 days together', id: '30 hari bersama'),
  'D-M9': (en: 'Spay/neuter done', id: 'Steril selesai'),
  'D-M10': (en: '10 growth entries', id: '10 catatan tumbuh kembang'),
  'D-L1': (en: 'First birthday 🎂', id: 'Ulang tahun pertama 🎂'),
  'D-L2': (en: '100 days together', id: '100 hari bersama'),
  'D-L3': (en: '365 days together', id: '365 hari bersama'),
  'D-L4': (en: 'All health milestones', id: 'Semua tonggak kesehatan'),
  'D-L5': (en: '30 growth entries', id: '30 catatan tumbuh kembang'),
  'D-S16': (en: 'Beginner graduate 🎓', id: 'Lulus Pemula 🎓'),

  // ===== OTHER (G) =====
  'G-S1': (en: 'Profile created', id: 'Profil dibuat'),
  'G-S2': (en: 'First growth photo', id: 'Foto pertama di kalender'),
  'G-S3': (en: 'First card shared', id: 'Kartu pertama dibagikan'),
  'G-S4': (en: 'First vet note saved', id: 'Catatan dokter pertama'),
  'G-S5': (en: 'First daily post', id: 'Postingan harian pertama'),
  'G-S6': (en: 'First treat', id: 'Camilan pertama'),
  'G-S7': (en: 'First comment', id: 'Komentar pertama'),
  'G-S8': (en: 'First like', id: 'Suka pertama'),
  'G-M1': (en: 'First vet visit', id: 'Ke dokter hewan pertama'),
  'G-M2': (en: 'First health check', id: 'Cek kesehatan pertama'),
  'G-M3': (en: '30 days together', id: '30 hari bersama'),
  'G-M4': (en: '10 growth entries', id: '10 catatan tumbuh kembang'),
  'G-L1': (en: 'First birthday 🎂', id: 'Ulang tahun pertama 🎂'),
  'G-L2': (en: '100 days together', id: '100 hari bersama'),
  'G-L3': (en: '365 days together', id: '365 hari bersama'),
  'G-S9': (en: 'Beginner graduate 🎓', id: 'Lulus Pemula 🎓'),
};

/// 按 code + locale 返回本地化里程碑标题。未知 code 兜底返回 code（绝不显示中文）。
String localizedMilestoneTitle(String code, Locale locale) {
  final t = kMilestoneTitles[code];
  if (t == null) return code;
  return locale.languageCode == 'id' ? t.id : t.en;
}
