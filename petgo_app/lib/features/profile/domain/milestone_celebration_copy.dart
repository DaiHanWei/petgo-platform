import 'package:flutter/widgets.dart';

import 'milestone_titles.dart';

/// 里程碑庆祝文案（标题 + 正文，en / id）。来源：`milestone-celebration.md`（FR-42）。
///
/// 与 [kMilestoneTitles]（列表页/分享用的简短标签）分开：此处是 **P-35 里程碑解锁庆祝动效**用的完整文案
/// （标题以 emoji 结尾、字体更粗；正文 S=1 句 / M=2 句 / L=3 句）。沿用本模块「后端只给稳定
/// `code`、显示文案一律客户端按 locale 出」的约定（杜绝后端中文泄漏）。
///
/// 占位符 `{name}` = 宠物名，渲染时 [localizedMilestoneCelebration] 替换为真实昵称。
/// ⚠️ id/en 文案取自设计文档原文；id 用词建议印尼母语者复核。
class MilestoneCelebrationCopy {
  const MilestoneCelebrationCopy({
    required this.titleId,
    required this.bodyId,
    required this.titleEn,
    required this.bodyEn,
  });

  final String titleId;
  final String bodyId;
  final String titleEn;
  final String bodyEn;
}

const Map<String, MilestoneCelebrationCopy> kMilestoneCelebrationCopy = {
  // ===== CAT (C) · S 级 =====
  'C-S1': MilestoneCelebrationCopy(
    titleId: 'Profil {name} sudah lengkap! 🎉',
    bodyId: 'Sekarang semua momen {name} punya rumahnya sendiri.',
    titleEn: "{name}'s profile is all set! 🎉",
    bodyEn: 'Every moment {name} shares now has a home.',
  ),
  'C-S2': MilestoneCelebrationCopy(
    titleId: 'Foto pertama {name} tersimpan! 📸',
    bodyId: 'Kenangan indah mulai terkumpul, satu foto sekaligus.',
    titleEn: "{name}'s first photo is saved! 📸",
    bodyEn: 'Beautiful memories are starting to grow, one photo at a time.',
  ),
  'C-S3': MilestoneCelebrationCopy(
    titleId: '{name} sudah go public! 🌟',
    bodyId: 'Kartu nama {name} kini ada di luar sana — siap bikin orang jatuh cinta.',
    titleEn: '{name} just went public! 🌟',
    bodyEn: "{name}'s profile card is out there — ready to make hearts melt.",
  ),
  'C-S4': MilestoneCelebrationCopy(
    titleId: 'Rekam kesehatan pertama tersimpan! 🏥',
    bodyId: 'Riwayat kesehatan {name} sudah tercatat rapi di sini.',
    titleEn: 'First health record saved! 🏥',
    bodyEn: "{name}'s health history is now safely on record.",
  ),
  'C-S5': MilestoneCelebrationCopy(
    titleId: 'Postingan pertama live! ✨',
    bodyId: 'Cerita {name} sekarang bisa dilihat semua orang.',
    titleEn: 'First post is live! ✨',
    bodyEn: "{name}'s story can now be seen by everyone.",
  ),
  'C-S6': MilestoneCelebrationCopy(
    titleId: '{name} wangi sekarang! 🛁',
    bodyId: 'Mandi pertama berhasil — selangkah lebih bersih, sekali lebih sayang.',
    titleEn: '{name} is squeaky clean! 🛁',
    bodyEn: 'First bath done — one step cleaner, one level more loved.',
  ),
  'C-S7': MilestoneCelebrationCopy(
    titleId: 'Kuku {name} rapi sekarang! ✂️',
    bodyId: 'Perawatan kecil yang berarti besar — kamu sudah jaga {name} dengan teliti.',
    titleEn: "{name}'s claws are trimmed! ✂️",
    bodyEn: "Small care, big love — you're paying attention to every detail.",
  ),
  'C-S8': MilestoneCelebrationCopy(
    titleId: '{name} doyan camilan! 😋',
    bodyId: 'Snack pertama berhasil masuk. Favoritnya apa nih?',
    titleEn: '{name} loves snacks! 😋',
    bodyEn: "First treat conquered. What's going to be the favorite?",
  ),
  'C-S9': MilestoneCelebrationCopy(
    titleId: 'Momen paling hangat terabadikan! 🌙',
    bodyId: 'Tidur berdua dengan {name} — ini jenis ketenangan yang susah dijelaskan.',
    titleEn: 'The warmest moment, captured! 🌙',
    bodyEn: "Sleeping side by side with {name} — the kind of peace that's hard to explain.",
  ),
  'C-S10': MilestoneCelebrationCopy(
    titleId: '{name} mengeluarkan purr-nya! 😻',
    bodyId: 'Suara kecil itu artinya satu hal: {name} bahagia bersamamu.',
    titleEn: '{name} started purring! 😻',
    bodyEn: 'That little rumble means one thing: {name} is happy with you.',
  ),
  'C-S11': MilestoneCelebrationCopy(
    titleId: 'Sesi berjemur pertama selesai! ☀️',
    bodyId: '{name} sudah menemukan spot favorit — dan mungkin tak akan mau pindah.',
    titleEn: 'First sunbathing session complete! ☀️',
    bodyEn: "{name} has found the favorite spot — and probably won't leave.",
  ),
  'C-S12': MilestoneCelebrationCopy(
    titleId: 'Sesi main perdana sukses! 🎣',
    bodyId: 'Naluri berburu {name} mulai terasah — waspada, kamu musuhnya sekarang!',
    titleEn: 'First play session: success! 🎣',
    bodyEn: "{name}'s hunting instincts are awakening — watch out, you're the target!",
  ),
  'C-S13': MilestoneCelebrationCopy(
    titleId: '{name} sudah kuasai kardus! 📦',
    bodyId: 'Kalau muat, harus masuk — begitu prinsip hidup {name}.',
    titleEn: '{name} conquered the box! 📦',
    bodyEn: 'If it fits, {name} sits — that\'s the rule.',
  ),
  'C-S14': MilestoneCelebrationCopy(
    titleId: 'Ada yang komentar soal {name}! 💬',
    bodyId: 'Cerita {name} sudah menyentuh hati orang lain.',
    titleEn: 'Someone commented on {name}! 💬',
    bodyEn: "{name}'s story has touched someone else's heart.",
  ),
  'C-S15': MilestoneCelebrationCopy(
    titleId: '{name} dapat likes pertama! ❤️',
    bodyId: 'Orang-orang mulai lihat betapa spesialnya {name}. Terus bagikan momennya!',
    titleEn: '{name} got the first like! ❤️',
    bodyEn: 'People are starting to see how special {name} is. Keep sharing those moments!',
  ),

  // ===== CAT (C) · M 级 =====
  'C-M1': MilestoneCelebrationCopy(
    titleId: 'Petualangan pertama {name} dimulai! 🌏',
    bodyId: 'Kaki kecil {name} akhirnya menginjak dunia luar. Ini baru permulaan dari banyak eksplorasi yang akan datang.',
    titleEn: "{name}'s first adventure begins! 🌏",
    bodyEn: "{name}'s little paws finally touched the outside world. This is just the start of many explorations to come.",
  ),
  'C-M2': MilestoneCelebrationCopy(
    titleId: '{name} naik kendaraan untuk pertama kalinya! 🚗',
    bodyId: 'Perjalanan pertama bersama — entah itu panik atau kalem, ini momen yang tak terlupakan. Selamat datang di era mobilitas {name}.',
    titleEn: "{name}'s first car ride! 🚗",
    bodyEn: 'The first journey together — panicked or calm, this moment is unforgettable. Welcome to {name}\'s era of mobility.',
  ),
  'C-M3': MilestoneCelebrationCopy(
    titleId: '{name} makin terlindungi! 💉',
    bodyId: 'Vaksin pertama selesai — kamu baru saja memberikan perlindungan terbaik untuk {name}. Ini adalah bentuk kasih sayang yang nyata.',
    titleEn: '{name} is now better protected! 💉',
    bodyEn: 'First vaccine done — you just gave {name} the best protection possible. This is what real love looks like.',
  ),
  'C-M4': MilestoneCelebrationCopy(
    titleId: '{name} sehat dari dalam! 🌿',
    bodyId: 'Obat cacing pertama sudah diberikan — {name} siap tumbuh tanpa gangguan. Kamu memperhatikan hal-hal yang orang lain mungkin lewatkan.',
    titleEn: '{name} is healthy from the inside out! 🌿',
    bodyEn: 'First deworming done — {name} is ready to grow without any obstacles. You pay attention to things others might overlook.',
  ),
  'C-M5': MilestoneCelebrationCopy(
    titleId: 'Kunjungan pertama ke dokter hewan selesai! 🩺',
    bodyId: 'Membawa {name} ke dokter hewan untuk pertama kalinya — itu adalah tindakan kasih sayang. {name} beruntung punya kamu yang selalu peduli.',
    titleEn: 'First vet visit complete! 🩺',
    bodyEn: 'Taking {name} to the vet for the first time — that\'s an act of love. {name} is lucky to have someone who always cares.',
  ),
  'C-M6': MilestoneCelebrationCopy(
    titleId: '{name} ketemu teman sesama kucing! 🐱',
    bodyId: 'Pertemuan pertama dengan kucing lain — momen paling penasaran dalam hidup {name} hari ini. Apakah mereka langsung akur, atau butuh waktu?',
    titleEn: '{name} met another cat! 🐱',
    bodyEn: 'First encounter with a fellow feline — probably the most curious moment of {name}\'s day. Did they bond instantly, or is this going to take some time?',
  ),
  'C-M7': MilestoneCelebrationCopy(
    titleId: '{name} kenal namanya sendiri! 🔔',
    bodyId: 'Setiap kali dipanggil, {name} menoleh — kalian semakin terhubung setiap harinya. Nama itu bukan sekadar kata; itu adalah cara {name} tahu siapa yang peduli.',
    titleEn: '{name} knows their own name! 🔔',
    bodyEn: "Every time you call, {name} turns — your bond grows a little stronger each day. That name isn't just a word; it's how {name} knows who cares.",
  ),
  'C-M8': MilestoneCelebrationCopy(
    titleId: '30 hari bersama {name}! 🗓️',
    bodyId: 'Satu bulan penuh sudah terlewati — satu bulan penuh cinta, tawa, dan kebersamaan. Masih banyak hari indah yang menunggu di depan.',
    titleEn: '30 days together with {name}! 🗓️',
    bodyEn: 'One full month down — one month of love, laughter, and togetherness. There are so many more beautiful days waiting ahead.',
  ),
  'C-M9': MilestoneCelebrationCopy(
    titleId: '{name} sudah melewati operasi dengan baik! 🏥',
    bodyId: 'Ini keputusan yang tidak mudah, tapi kamu melakukannya demi kesehatan jangka panjang {name}. Terima kasih sudah menjadi pemilik yang bertanggung jawab.',
    titleEn: '{name} came through the surgery just fine! 🏥',
    bodyEn: "It wasn't an easy decision, but you made it for {name}'s long-term health. Thank you for being a responsible owner.",
  ),
  'C-M10': MilestoneCelebrationCopy(
    titleId: '10 kenangan {name} sudah terabadikan! 📖',
    bodyId: 'Kamu sudah merekam 10 momen berharga yang akan selalu bisa kamu lihat kembali. Perjalanan ini baru saja dimulai — dan sudah indah.',
    titleEn: '10 memories of {name} are now saved! 📖',
    bodyEn: "You've captured 10 precious moments you can always look back on. This journey has just begun — and it's already beautiful.",
  ),

  // ===== CAT (C) · L 级 =====
  'C-L1': MilestoneCelebrationCopy(
    titleId: 'Selamat Ulang Tahun, {name}! 🎂🎉',
    bodyId: 'Satu tahun penuh cinta, tawa, dan momen tak terlupakan bersama {name}. Dari hari pertama yang penuh rasa ingin tahu, hingga hari ini yang penuh kasih sayang — kamu adalah alasan {name} tumbuh bahagia. Semoga panjang umur dan selalu sehat, {name}! 🐾',
    titleEn: 'Happy Birthday, {name}! 🎂🎉',
    bodyEn: 'One full year of love, laughter, and unforgettable moments together. From the very first curious day to today — you are the reason {name} grew up happy. Here\'s to many more healthy, joyful years, {name}! 🐾',
  ),
  'C-L2': MilestoneCelebrationCopy(
    titleId: '100 hari bersama {name}! 🌟',
    bodyId: 'Seratus hari perjalanan yang luar biasa — dari pertemuan pertama yang canggung hingga rutinitas yang penuh kehangatan. Setiap momen yang kamu rekam adalah bukti nyata betapa berartinya {name} bagimu. Kamu telah memberikan {name} sesuatu yang tak ternilai: sebuah rumah yang penuh cinta.',
    titleEn: '100 days with {name}! 🌟',
    bodyEn: "A hundred extraordinary days — from the first awkward meeting to a warm daily routine. Every moment you've recorded is real proof of how much {name} means to you. You've given {name} something priceless: a home full of love.",
  ),
  'C-L3': MilestoneCelebrationCopy(
    titleId: 'Satu tahun penuh bersama {name}! 🎊',
    bodyId: '365 hari, ratusan momen, dan satu ikatan yang tidak akan pernah putus. {name} sangat beruntung punya kamu — dan kamu juga beruntung punya {name}. Terima kasih sudah hadir setiap hari. Ikatan ini adalah kisah yang paling berharga.',
    titleEn: 'One full year with {name}! 🎊',
    bodyEn: '365 days, hundreds of moments, and a bond that will never break. {name} is so lucky to have you — and you\'re just as lucky to have {name}. Thank you for showing up every single day. This bond is the most precious story of all.',
  ),
  'C-L4': MilestoneCelebrationCopy(
    titleId: '{name}: Kucing Paling Sehat! 🏆',
    bodyId: 'Vaksin, obat cacing, pemeriksaan rutin — kamu sudah menyelesaikan semuanya tanpa terlewat satu pun. Ini bukan hal yang mudah, tapi kamu melakukannya dengan penuh tanggung jawab dan kasih sayang. {name} sehat hari ini karena ada kamu yang selalu jaga.',
    titleEn: '{name}: The Healthiest Cat! 🏆',
    bodyEn: "Vaccines, deworming, vet checkup — you completed all of them without missing a single one. It's not easy, but you did it with full responsibility and love. {name} is healthy today because you're always there.",
  ),
  'C-L5': MilestoneCelebrationCopy(
    titleId: '30 momen {name} telah terabadikan! ✨',
    bodyId: 'Tiga puluh cerita yang akan selalu bisa kamu kenang — senang, lucu, haru, semuanya ada. Kamu sedang menulis buku kehidupan {name}, halaman demi halaman. Suatu hari nanti, kamu akan sangat bersyukur sudah merekam ini semua.',
    titleEn: '30 moments of {name} are forever saved! ✨',
    bodyEn: "Thirty stories you can always look back on — happy, funny, touching, all of it. You are writing {name}'s book of life, page by page. Someday, you'll be so glad you recorded all of this.",
  ),

  // ===== DOG (D) · S 级 =====
  'D-S1': MilestoneCelebrationCopy(
    titleId: 'Profil {name} sudah lengkap! 🎉',
    bodyId: 'Semua petualangan {name} sekarang punya tempat untuk diingat.',
    titleEn: "{name}'s profile is all set! 🎉",
    bodyEn: 'All of {name}\'s adventures now have a place to be remembered.',
  ),
  'D-S2': MilestoneCelebrationCopy(
    titleId: 'Foto pertama {name} tersimpan! 📸',
    bodyId: 'Senyum pertama yang terekam — album kenangan {name} resmi dimulai.',
    titleEn: "{name}'s first photo is saved! 📸",
    bodyEn: "The first captured smile — {name}'s memory album has officially begun.",
  ),
  'D-S3': MilestoneCelebrationCopy(
    titleId: '{name} sudah dikenal dunia! 🌟',
    bodyId: 'Kartu nama {name} kini ada di luar sana — siap curi perhatian semua orang.',
    titleEn: '{name} is known to the world! 🌟',
    bodyEn: "{name}'s profile card is out there — ready to steal everyone's heart.",
  ),
  'D-S4': MilestoneCelebrationCopy(
    titleId: 'Rekam kesehatan pertama tersimpan! 🏥',
    bodyId: 'Riwayat kesehatan {name} sudah tercatat — kamu bisa lacak semuanya dari sini.',
    titleEn: 'First health record saved! 🏥',
    bodyEn: "{name}'s health history is on record — you can track everything from here.",
  ),
  'D-S5': MilestoneCelebrationCopy(
    titleId: 'Postingan pertama live! ✨',
    bodyId: 'Cerita {name} resmi dimulai — dan ini baru awal.',
    titleEn: 'First post is live! ✨',
    bodyEn: "{name}'s story has officially begun — and this is just the start.",
  ),
  'D-S6': MilestoneCelebrationCopy(
    titleId: '{name} bersih dan wangi sekarang! 🛁',
    bodyId: 'Mandi pertama selesai — entah {name} menikmatinya atau tidak, yang pasti kamu berhasil!',
    titleEn: '{name} is clean and fresh! 🛁',
    bodyEn: 'First bath done — whether {name} enjoyed it or not, you made it through!',
  ),
  'D-S7': MilestoneCelebrationCopy(
    titleId: '{name} tampil keren habis di-grooming! ✂️',
    bodyId: 'Penampilan pertama setelah grooming — {name} sekarang siap jadi bintang.',
    titleEn: '{name} looks amazing after grooming! ✂️',
    bodyEn: 'First post-grooming look — {name} is ready to be a star.',
  ),
  'D-S8': MilestoneCelebrationCopy(
    titleId: '{name} sudah cicipi snack pertama! 😋',
    bodyId: 'Ekspresi bahagia {name} saat makan camilan — ini momen yang wajib direkam.',
    titleEn: '{name} tasted their first snack! 😋',
    bodyEn: "{name}'s happy face when eating treats — this is a moment worth recording.",
  ),
  'D-S9': MilestoneCelebrationCopy(
    titleId: 'Tidur bersama {name} terabadikan! 🌙',
    bodyId: 'Dipilih sebagai tempat tidur favorit {name} — ini kepercayaan tertinggi yang bisa diberikan seekor anjing.',
    titleEn: 'Sleeping together with {name}, captured! 🌙',
    bodyEn: 'Being chosen as {name}\'s favorite sleeping spot — this is the highest trust a dog can give.',
  ),
  'D-S10': MilestoneCelebrationCopy(
    titleId: 'Kibasan ekor {name} terekam! 🐕',
    bodyId: 'Ekor yang bergoyang itu hanya punya satu arti: {name} bahagia bersamamu.',
    titleEn: "{name}'s tail wag is on record! 🐕",
    bodyEn: 'That wagging tail has only one meaning: {name} is happy with you.',
  ),
  'D-S11': MilestoneCelebrationCopy(
    titleId: '{name} sudah pakai perlengkapannya! 🏷️',
    bodyId: 'Kalung atau tali pertama terpasang — {name} resmi siap jalan-jalan bareng kamu.',
    titleEn: '{name} is geared up! 🏷️',
    bodyEn: 'First collar or leash on — {name} is officially ready to go out with you.',
  ),
  'D-S12': MilestoneCelebrationCopy(
    titleId: '{name} jago main bola! ⚽',
    bodyId: 'Bola pertama bergulir dan {name} langsung mengejarnya — naluri terbaik.',
    titleEn: '{name} is a ball player! ⚽',
    bodyEn: 'The first ball rolled and {name} chased it immediately — pure instinct.',
  ),
  'D-S13': MilestoneCelebrationCopy(
    titleId: '{name} berani main air! 💦',
    bodyId: 'Pertama kali {name} menyentuh air — ternyata pemberani kan?',
    titleEn: '{name} took on the water! 💦',
    bodyEn: "{name}'s first time touching water — turns out pretty brave, right?",
  ),
  'D-S14': MilestoneCelebrationCopy(
    titleId: 'Ada yang komentar soal {name}! 💬',
    bodyId: 'Cerita {name} sudah menyentuh hati orang lain — ini baru saja dimulai.',
    titleEn: 'Someone commented on {name}! 💬',
    bodyEn: "{name}'s story has touched someone's heart — this is just the beginning.",
  ),
  'D-S15': MilestoneCelebrationCopy(
    titleId: '{name} dapat likes pertama! ❤️',
    bodyId: 'Dunia mulai mengenal {name}. Terus bagikan momen-momen itu!',
    titleEn: '{name} got the first like! ❤️',
    bodyEn: 'The world is starting to know {name}. Keep sharing those moments!',
  ),

  // ===== DOG (D) · M 级 =====
  'D-M1': MilestoneCelebrationCopy(
    titleId: 'Jalan-jalan pertama {name} dimulai! 🦮',
    bodyId: 'Kaki {name} akhirnya menginjak trotoar dan menghirup udara luar untuk pertama kalinya. Setiap langkah kecil adalah eksplorasi baru yang menyenangkan.',
    titleEn: "{name}'s first walk has begun! 🦮",
    bodyEn: "{name}'s paws finally hit the pavement and breathed outdoor air for the first time. Every small step is a joyful new exploration.",
  ),
  'D-M2': MilestoneCelebrationCopy(
    titleId: '{name} naik kendaraan untuk pertama kalinya! 🚗',
    bodyId: 'Apakah {name} duduk tenang atau terus bergerak? Bagaimanapun, perjalanan pertama ini tidak terlupakan. Selamat datang di era mobilitas {name}.',
    titleEn: "{name}'s first car ride! 🚗",
    bodyEn: 'Did {name} sit still or keep moving? Either way, this first ride is unforgettable. Welcome to {name}\'s era of mobility.',
  ),
  'D-M3': MilestoneCelebrationCopy(
    titleId: '{name} makin terlindungi! 💉',
    bodyId: 'Vaksin pertama selesai — kamu baru saja memberikan perisai terbaik untuk {name}. Ini bentuk kasih sayang yang paling nyata.',
    titleEn: '{name} is now better protected! 💉',
    bodyEn: 'First vaccine done — you just gave {name} the best shield possible. This is the most tangible form of love.',
  ),
  'D-M4': MilestoneCelebrationCopy(
    titleId: '{name} sehat dari dalam! 🌿',
    bodyId: 'Obat cacing pertama sudah diberikan — {name} siap aktif tanpa gangguan dari dalam. Kamu memperhatikan hal-hal yang tidak terlihat tapi sangat penting.',
    titleEn: '{name} is healthy from the inside! 🌿',
    bodyEn: 'First deworming done — {name} is ready to be active without invisible obstacles. You pay attention to things unseen but deeply important.',
  ),
  'D-M5': MilestoneCelebrationCopy(
    titleId: 'Kunjungan pertama ke dokter hewan selesai! 🩺',
    bodyId: 'Membawa {name} ke dokter hewan pertama kalinya — itu adalah salah satu tindakan cinta terbesar. {name} sehat hari ini karena ada kamu yang selalu jaga.',
    titleEn: 'First vet visit complete! 🩺',
    bodyEn: 'Taking {name} to the vet for the first time — one of the greatest acts of love. {name} is healthy today because you always look after them.',
  ),
  'D-M6': MilestoneCelebrationCopy(
    titleId: '{name} ketemu teman sesama anjing! 🐶',
    bodyId: 'Pertemuan pertama dengan anjing lain — momen paling penasaran dalam hari {name}. Apakah ekornya langsung bergoyang kencang?',
    titleEn: '{name} met another dog! 🐶',
    bodyEn: "First encounter with another dog — the most curious moment of {name}'s day. Did the tail start wagging immediately?",
  ),
  'D-M7': MilestoneCelebrationCopy(
    titleId: '{name} berhasil belajar perintah pertama! 🐾',
    bodyId: 'Duduk, jabat tangan, atau apa pun yang pertama dipelajari — {name} lebih pintar dari yang kamu kira. Setiap perintah yang dipahami adalah bukti kepercayaan antara kalian.',
    titleEn: '{name} learned the first command! 🐾',
    bodyEn: 'Sit, shake, or whatever came first — {name} is smarter than you thought. Every command learned is proof of the trust between you two.',
  ),
  'D-M8': MilestoneCelebrationCopy(
    titleId: '30 hari bersama {name}! 🗓️',
    bodyId: 'Satu bulan penuh sudah terlewati — satu bulan penuh lari, main, dan tidur bersama. Masih banyak petualangan yang menunggu di depan.',
    titleEn: '30 days together with {name}! 🗓️',
    bodyEn: 'One full month down — one month of running, playing, and sleeping together. So many adventures are still waiting ahead.',
  ),
  'D-M9': MilestoneCelebrationCopy(
    titleId: '{name} melewati operasi dengan baik! 🏥',
    bodyId: 'Keputusan yang penuh tanggung jawab, dan kamu melakukannya demi masa depan yang lebih sehat untuk {name}. Terima kasih sudah menjadi pemilik yang benar-benar peduli.',
    titleEn: '{name} came through the surgery well! 🏥',
    bodyEn: "A responsible decision, made for {name}'s healthier future. Thank you for being an owner who truly cares.",
  ),
  'D-M10': MilestoneCelebrationCopy(
    titleId: '10 kenangan {name} sudah terabadikan! 📖',
    bodyId: 'Sepuluh momen yang tidak akan pernah hilang — kamu sudah merekam awal perjalanan luar biasa ini. Dan cerita terbaik masih akan datang.',
    titleEn: '10 memories of {name} are now saved! 📖',
    bodyEn: "Ten moments that will never disappear — you've captured the beginning of this amazing journey. And the best stories are still to come.",
  ),

  // ===== DOG (D) · L 级 =====
  'D-L1': MilestoneCelebrationCopy(
    titleId: 'Selamat Ulang Tahun, {name}! 🎂🎉',
    bodyId: 'Satu tahun sudah {name} mengisi setiap sudut hidupmu dengan kegembiraan dan kesetiaan. Dari hari pertama yang penuh kejutan, hingga hari ini yang penuh kasih sayang — kamu adalah segalanya bagi {name}. Semoga panjang umur, terus sehat, dan selalu setia, {name}! 🐾',
    titleEn: 'Happy Birthday, {name}! 🎂🎉',
    bodyEn: 'One year of {name} filling every corner of your life with joy and loyalty. From the surprising first day to this love-filled one — you are everything to {name}. May {name} live long, stay healthy, and always be loyal! 🐾',
  ),
  'D-L2': MilestoneCelebrationCopy(
    titleId: '100 hari bersama {name}! 🌟',
    bodyId: 'Seratus hari — seratus alasan untuk bersyukur punya {name} di hidupmu. Dari langkah pertama yang kikuk hingga kesetiaan yang sudah kamu rasakan hari ini. Kamu telah memberikan {name} sesuatu yang tak ternilai: rumah dan keluarga.',
    titleEn: '100 days with {name}! 🌟',
    bodyEn: "A hundred days — a hundred reasons to be grateful for {name} in your life. From the first clumsy steps to the loyalty you feel today. You've given {name} something priceless: a home and a family.",
  ),
  'D-L3': MilestoneCelebrationCopy(
    titleId: 'Satu tahun penuh bersama {name}! 🎊',
    bodyId: '365 hari kesetiaan, keceriaan, dan kasih sayang tanpa syarat. {name} sudah buktikan bahwa cinta seekor anjing tidak ada duanya — dan kamu pantas mendapatkannya. Terima kasih sudah ada setiap hari. Ikatan ini adalah kisah paling berharga yang pernah ada.',
    titleEn: 'One full year with {name}! 🎊',
    bodyEn: "365 days of loyalty, joy, and unconditional love. {name} has proven that a dog's love is unmatched — and you deserve every bit of it. Thank you for being there every day. This bond is the most precious story there ever was.",
  ),
  'D-L4': MilestoneCelebrationCopy(
    titleId: '{name}: Anjing Paling Sehat! 🏆',
    bodyId: 'Vaksin, obat cacing, pemeriksaan rutin — tiga pilar kesehatan {name} sudah semua terpenuhi. Kamu tidak hanya mencintai {name}, kamu menjaganya dengan penuh tanggung jawab. {name} sehat hari ini — dan itu semua karena kamu.',
    titleEn: '{name}: The Healthiest Dog! 🏆',
    bodyEn: "Vaccines, deworming, vet checkup — all three pillars of {name}'s health are complete. You don't just love {name}, you protect {name} with full responsibility. {name} is healthy today — and all of that is because of you.",
  ),
  'D-L5': MilestoneCelebrationCopy(
    titleId: '30 momen {name} telah terabadikan! ✨',
    bodyId: 'Tiga puluh halaman dari buku kehidupan {name} sudah tertulis oleh tanganmu. Setiap foto, setiap cerita — semua adalah bukti nyata perjalanan kalian bersama. Suatu hari nanti, kamu akan sangat bersyukur sudah merekam ini semua.',
    titleEn: '30 moments of {name} are forever saved! ✨',
    bodyEn: 'Thirty pages of {name}\'s life story, written by your hands. Every photo, every story — all real proof of the journey you share. Someday, you\'ll be so glad you recorded all of this.',
  ),

  // ===== OTHER (G) · S 级 =====
  'G-S1': MilestoneCelebrationCopy(
    titleId: 'Profil {name} sudah siap! 🎉',
    bodyId: 'Setiap momen bersama {name} sekarang punya tempat yang aman untuk dikenang.',
    titleEn: "{name}'s profile is ready! 🎉",
    bodyEn: 'Every moment with {name} now has a safe place to be remembered.',
  ),
  'G-S2': MilestoneCelebrationCopy(
    titleId: 'Foto pertama {name} tersimpan! 📸',
    bodyId: 'Album kenangan {name} resmi dimulai — dari satu foto kecil ini.',
    titleEn: "{name}'s first photo is saved! 📸",
    bodyEn: "{name}'s memory album officially starts — from this one small photo.",
  ),
  'G-S3': MilestoneCelebrationCopy(
    titleId: '{name} sudah bisa dilihat dunia! 🌟',
    bodyId: 'Kartu nama {name} sudah dibagikan — biarkan semua orang tahu betapa spesialnya {name}.',
    titleEn: '{name} can now be seen by the world! 🌟',
    bodyEn: "{name}'s profile card is shared — let everyone know how special {name} is.",
  ),
  'G-S4': MilestoneCelebrationCopy(
    titleId: 'Rekam kesehatan pertama tersimpan! 🏥',
    bodyId: 'Riwayat kesehatan {name} sudah tercatat — satu langkah lebih tenang.',
    titleEn: 'First health record saved! 🏥',
    bodyEn: "{name}'s health history is on record — one step more at ease.",
  ),
  'G-S5': MilestoneCelebrationCopy(
    titleId: 'Postingan pertama live! ✨',
    bodyId: 'Cerita {name} sekarang ada di sini untuk semua orang.',
    titleEn: 'First post is live! ✨',
    bodyEn: "{name}'s story is now out here for everyone.",
  ),
  'G-S6': MilestoneCelebrationCopy(
    titleId: '{name} sudah cicipi snack pertama! 😋',
    bodyId: 'Reaksi {name} saat pertama kali mencicipi camilan — ini momen yang wajib diingat.',
    titleEn: '{name} tried their first snack! 😋',
    bodyEn: "{name}'s reaction to tasting a treat for the first time — a moment worth remembering.",
  ),
  'G-S7': MilestoneCelebrationCopy(
    titleId: 'Ada yang komentar soal {name}! 💬',
    bodyId: 'Dunia mulai melihat keunikan {name} — cerita ini baru saja dimulai.',
    titleEn: 'Someone commented on {name}! 💬',
    bodyEn: "The world is starting to see {name}'s uniqueness — this story has just begun.",
  ),
  'G-S8': MilestoneCelebrationCopy(
    titleId: '{name} dapat likes pertama! ❤️',
    bodyId: 'Keistimewaan {name} sudah dirasakan orang lain. Terus bagikan momennya!',
    titleEn: '{name} got the first like! ❤️',
    bodyEn: "{name}'s specialness has been felt by others. Keep sharing those moments!",
  ),

  // ===== OTHER (G) · M 级 =====
  'G-M1': MilestoneCelebrationCopy(
    titleId: 'Kunjungan pertama ke dokter hewan selesai! 🩺',
    bodyId: 'Membawa {name} ke dokter hewan untuk pertama kalinya memerlukan usaha — dan kamu melakukannya. {name} beruntung punya pemilik yang benar-benar peduli.',
    titleEn: 'First vet visit complete! 🩺',
    bodyEn: 'Taking {name} to the vet for the first time took effort — and you did it. {name} is lucky to have an owner who truly cares.',
  ),
  'G-M2': MilestoneCelebrationCopy(
    titleId: 'Pemeriksaan kesehatan pertama {name} selesai! 💉',
    bodyId: 'Vaksin atau cek kesehatan pertama sudah selesai — kamu sudah memberikan fondasi terbaik untuk {name}. Langkah kecil ini artinya besar untuk masa depan {name}.',
    titleEn: "{name}'s first health checkup is complete! 💉",
    bodyEn: "First vaccine or health check done — you've given {name} the best foundation possible. This small step means a lot for {name}'s future.",
  ),
  'G-M3': MilestoneCelebrationCopy(
    titleId: '30 hari bersama {name}! 🗓️',
    bodyId: 'Satu bulan penuh kebersamaan — penuh momen belajar mengenal satu sama lain. Ikatan kalian semakin kuat, hari demi hari.',
    titleEn: '30 days with {name}! 🗓️',
    bodyEn: 'One full month of togetherness — full of moments learning about each other. Your bond grows stronger, day by day.',
  ),
  'G-M4': MilestoneCelebrationCopy(
    titleId: '10 kenangan {name} sudah terabadikan! 📖',
    bodyId: 'Sepuluh momen yang tidak akan hilang — awal dari kisah yang panjang dan indah. Terus rekam, karena setiap momen itu berharga.',
    titleEn: '10 memories of {name} are now saved! 📖',
    bodyEn: 'Ten moments that won\'t disappear — the start of a long and beautiful story. Keep recording, because every moment is precious.',
  ),

  // ===== OTHER (G) · L 级 =====
  'G-L1': MilestoneCelebrationCopy(
    titleId: 'Selamat Ulang Tahun, {name}! 🎂🎉',
    bodyId: 'Satu tahun penuh keunikan, kejutan, dan kasih sayang bersama {name}. Dari hari pertama yang belum saling kenal, hingga hari ini yang penuh kepercayaan — kamu adalah dunia bagi {name}. Semoga panjang umur, selalu sehat, dan terus tumbuh bersama kamu! 🐾',
    titleEn: 'Happy Birthday, {name}! 🎂🎉',
    bodyEn: 'One year of uniqueness, surprises, and love with {name}. From the first day of not knowing each other, to this day full of trust — you are {name}\'s whole world. May {name} live long, stay healthy, and keep growing with you! 🐾',
  ),
  'G-L2': MilestoneCelebrationCopy(
    titleId: '100 hari bersama {name}! 🌟',
    bodyId: 'Seratus hari penuh keajaiban kecil yang hanya bisa kamu dan {name} rasakan bersama. Dari rasa ingin tahu di awal, hingga kepercayaan yang sudah terbangun hari ini. {name} sudah menemukan rumahnya — dan itu di sisimu.',
    titleEn: '100 days with {name}! 🌟',
    bodyEn: 'A hundred days of small wonders that only you and {name} can feel together. From the curiosity at the beginning, to the trust built today. {name} has found home — and it\'s right beside you.',
  ),
  'G-L3': MilestoneCelebrationCopy(
    titleId: 'Satu tahun penuh bersama {name}! 🎊',
    bodyId: '365 hari — satu tahun penuh cerita yang hanya milik kalian berdua. {name} mungkin berbeda dari yang lain, tapi itulah yang membuatnya istimewa bagimu. Terima kasih sudah memilih untuk selalu ada. Ikatan ini adalah satu-satunya.',
    titleEn: 'One full year with {name}! 🎊',
    bodyEn: '365 days — one full year of stories that belong only to the two of you. {name} may be different from the rest, but that\'s what makes {name} special to you. Thank you for always choosing to be there. This bond is one of a kind.',
  ),
};

/// 按 code + locale 返回本地化庆祝文案（标题 + 正文），并把 `{name}` 占位替换为宠物名。
///
/// 未知 code 兜底：标题回退到 [localizedMilestoneTitle]（简短标签），正文为空 —— 绝不显示后端中文。
({String title, String body}) localizedMilestoneCelebration(
  String code,
  Locale locale,
  String petName,
) {
  final copy = kMilestoneCelebrationCopy[code];
  final isId = locale.languageCode == 'id';
  if (copy == null) {
    return (title: localizedMilestoneTitle(code, locale), body: '');
  }
  final title = (isId ? copy.titleId : copy.titleEn).replaceAll('{name}', petName);
  final body = (isId ? copy.bodyId : copy.bodyEn).replaceAll('{name}', petName);
  return (title: title, body: body);
}
