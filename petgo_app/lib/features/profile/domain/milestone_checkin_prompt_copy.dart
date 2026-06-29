import 'package:flutter/widgets.dart';

/// 里程碑「打卡引导 / 说明」文案（P-33b 徽章详情底抽屉）。来源：`milestone-checkin-prompt.md`（FR-43）。
///
/// 两类内容共用一张表，按 code 取：
/// - **用户打卡类**（USER_CHECKIN，未完成时点灰徽章）：[header] = 一句轻量提问（粗体、emoji 结尾），
///   [body] = 2–3 行描述「为什么值得记录」。配 S/M 两级 CTA（在 ARB，按 level 取）。
/// - **系统自动 / 推送类**（SYSTEM_AUTO / PUSH_PUBLISH）：[header] 留空，[body] = 一句说明「何时自动点亮」，
///   不出 CTA（详见 FR-42）。
///
/// 与 [kMilestoneCelebrationCopy]（P-35 庆祝文案）分开维护：庆祝是「已达成」的祝贺语气，此处是「未达成」的
/// 引导/说明语气。沿用「后端只给稳定 `code`、显示文案一律客户端按 locale 出」的约定（杜绝后端中文泄漏）。
///
/// 占位符 `{name}` = 宠物名，渲染时 [localizedMilestoneCheckinPrompt] 替换为真实昵称。
/// ⚠️ id/en 文案取自设计文档原文；id 用词建议印尼母语者复核。
class MilestoneCheckinPromptCopy {
  const MilestoneCheckinPromptCopy({
    this.headerId = '',
    this.headerEn = '',
    required this.bodyId,
    required this.bodyEn,
  });

  /// 打卡类的提问标题；系统自动类留空。
  final String headerId;
  final String headerEn;
  final String bodyId;
  final String bodyEn;
}

const Map<String, MilestoneCheckinPromptCopy> kMilestoneCheckinPromptCopy = {
  // ============================================================
  // 用户打卡类（USER_CHECKIN）：Header 提问 + Body 描述
  // ============================================================

  // ----- CAT (C) · S 级打卡 -----
  'C-S6': MilestoneCheckinPromptCopy(
    headerId: 'Mandi bersejarah {name} pernah terjadi? 🛁',
    bodyId:
        'Biasanya lebih banyak air yang nyiprat ke kamu daripada ke {name}. Tapi apapun yang terjadi, ini momen yang gak bisa dilupain dan layak diabadikan.',
    headerEn: "Has {name}'s legendary first bath happened yet? 🛁",
    bodyEn:
        "Usually ends with you getting wetter than {name}. But whatever went down, it's a moment worth keeping.",
  ),
  'C-S7': MilestoneCheckinPromptCopy(
    headerId: 'Kuku {name} udah pernah dipotong? ✂️',
    bodyId:
        'Butuh kesabaran ekstra dan sedikit doa supaya gak kena kulit — tapi kalau berhasil, itu achievement tersendiri. Udah pernah lakuin ini?',
    headerEn: 'Has {name} had their first nail trim? ✂️',
    bodyEn:
        "It takes extra patience and a little prayer not to hit the quick — but when it works, it's its own kind of achievement. Has this happened yet?",
  ),
  'C-S8': MilestoneCheckinPromptCopy(
    headerId: '{name} udah coba camilan pertama? 😋',
    bodyId:
        'Ekspresi pertama kali {name} dapet snack itu gak bisa bohong — pure happiness yang gak bisa direkayasa. Setelah ini, siap-siap deh selalu dimata-matain waktu kamu makan.',
    headerEn: 'Has {name} tried their first snack yet? 😋',
    bodyEn:
        "The look on {name}'s face the first time they got a treat is impossible to fake — pure, unfiltered joy. After this, get ready to be watched every time you eat.",
  ),
  'C-S9': MilestoneCheckinPromptCopy(
    headerId: '{name} pernah tidur di sebelah kamu dan ke-foto? 🌙',
    bodyId:
        'Level ketenangan tidur bareng {name} itu susah banget dijelasin ke orang yang belum pernah ngerasain. Kalau momen ini udah pernah kejadian, tandai sekarang.',
    headerEn: 'Has {name} ever slept beside you and got captured on camera? 🌙',
    bodyEn:
        "The kind of peace that comes from sleeping next to {name} is nearly impossible to explain to anyone who hasn't felt it. If this has happened, mark it now.",
  ),
  'C-S10': MilestoneCheckinPromptCopy(
    headerId: '{name} pernah nge-purr di dekat kamu? 😻',
    bodyId:
        'Suara purr pertama {name} itu artinya satu hal: kamu berhasil bikin {name} beneran nyaman. Ini bukan hal kecil. Kalau udah pernah ke-rekam, ini waktunya diabadikan.',
    headerEn: 'Has {name} ever purred near you — and was it caught on camera? 😻',
    bodyEn:
        "That first purr means one thing: you actually made {name} feel genuinely at home. That's not nothing. If it's ever been recorded, now's the time to mark it.",
  ),
  'C-S11': MilestoneCheckinPromptCopy(
    headerId: '{name} udah nemu spot jemur favoritnya? ☀️',
    bodyId:
        'Posisi sempurna, tampang puas, sinar matahari menerpa wajah — ini mode relaksasi tertinggi yang pernah {name} capai. Udah pernah ke-foto?',
    headerEn: 'Has {name} found their favorite sunbathing spot? ☀️',
    bodyEn:
        "Perfect pose, satisfied face, sunlight hitting just right — this is {name}'s peak relaxation mode. Has this ever been caught on camera?",
  ),
  'C-S12': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah main mainan? 🎣',
    bodyId:
        'Pertama kali mainan muncul dan mode berburu {name} langsung aktif — responsnya gak bisa dibohongin. Fun fact: sejak saat itu, tangan kamu juga masuk kategori "mangsa".',
    headerEn: 'Has {name} played with a toy for the first time? 🎣',
    bodyEn:
        '''The moment a toy appears and {name}'s hunt mode switches on — that reaction cannot be faked. Fun fact: since then, your hand has also been classified as "prey."''',
  ),
  'C-S13': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah masuk kardus? 📦',
    bodyId:
        'Kalau muat, harus masuk — itu hukum alam yang berlaku buat semua kucing di dunia tanpa terkecuali. Pertama kali {name} buktiin ini, ekspresinya layak diabadikan.',
    headerEn: 'Has {name} ever climbed into a cardboard box? 📦',
    bodyEn:
        'If it fits, they sit — a universal cat law with zero exceptions. The first time {name} proved this, that expression was worth capturing forever.',
  ),

  // ----- CAT (C) · M 级打卡 -----
  'C-M1': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah keluar rumah untuk pertama kalinya? 🌏',
    bodyId:
        'Angin, suara baru, bau yang beda dari biasanya — {name} ngerasain semua itu untuk pertama kali. Ekspresi pertamanya waktu itu gak bisa direkayasa dan gak bisa diulang. Udah pernah terjadi?',
    headerEn: 'Has {name} ventured outside for the very first time? 🌏',
    bodyEn:
        'New wind, new sounds, new smells — {name} experienced all of it at once for the first time. That first expression cannot be staged, and it cannot be repeated. Has this happened yet?',
  ),
  'C-M2': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah naik kendaraan? 🚗',
    bodyId:
        'Panik, kalem, atau penasaran nempel-nempel di jendela — reaksi pertama {name} di dalam kendaraan itu selalu unik dan gak ada yang sama. Udah pernah terjadi?',
    headerEn: 'Has {name} ever been in a vehicle for the first time? 🚗',
    bodyEn:
        "Panicked, chill, or nose-pressed-to-the-window curious — every pet's first car ride reaction is unique and happens exactly once. Has {name}'s happened yet?",
  ),
  'C-M3': MilestoneCheckinPromptCopy(
    headerId: '{name} udah vaksin pertama? 💉',
    bodyId:
        'Gak semua orang mau repot-repot lakuin ini, tapi kamu mau — dan itu yang bikin beda. Vaksin pertama {name} adalah bukti nyata kamu ngurusin {name} dengan serius. Udah pernah dilakuin?',
    headerEn: 'Has {name} had their first vaccine? 💉',
    bodyEn:
        "Not everyone bothers, but you do — and that makes all the difference. {name}'s first vaccine is real proof you take care of {name} for the long run. Has this been done?",
  ),
  'C-M4': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah dikasih obat cacing? 🌿',
    bodyId:
        'Hal yang gak keliatan tapi penting banget — ini bukti nyata kamu ngurusin kesehatan {name} sampai ke detail yang banyak orang skip. Kalau udah pernah lakuin, tandai sekarang.',
    headerEn: 'Has {name} had their first deworming? 🌿',
    bodyEn:
        "The kind of care that's invisible but vital — real proof you look after {name}'s health down to the details most people skip. If you've done this, mark it now.",
  ),
  'C-M5': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah ke dokter hewan? 🩺',
    bodyId:
        'Kunjungan perdana ke dokter hewan itu butuh niat, waktu, dan usaha ekstra — tapi ini salah satu tindakan kasih sayang terbesar yang bisa kamu kasih ke {name}. Udah pernah terjadi?',
    headerEn: 'Has {name} been to the vet for the first time? 🩺',
    bodyEn:
        "That first vet visit takes real intention, time, and extra effort — but it's one of the biggest acts of care you can give {name}. Has this happened yet?",
  ),
  'C-M6': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah ketemu kucing lain? 🐱',
    bodyId:
        'Drama atau langsung akrab — dua kemungkinan yang sama-sama menarik. Apapun yang terjadi, pertemuan pertama {name} dengan sesama kucing itu selalu momen yang gak terlupakan. Udah pernah?',
    headerEn: 'Has {name} ever met another cat? 🐱',
    bodyEn:
        "Drama or instant friendship — both are equally worth seeing. Either way, {name}'s first encounter with a fellow feline is always a moment you don't forget. Has this happened yet?",
  ),
  'C-M7': MilestoneCheckinPromptCopy(
    headerId: '{name} udah bisa kenal namanya sendiri? 🔔',
    bodyId:
        'Kamu panggil, {name} noleh — kelihatan sepele, tapi ini tanda kepercayaan yang dalam. Ini momen kamu sadar kalian beneran udah terhubung. Udah pernah terjadi?',
    headerEn: 'Does {name} respond when you call their name? 🔔',
    bodyEn:
        "You call, {name} turns — looks small, but it's a sign of deep trust. This is the moment you realize the two of you are genuinely connected. Has this happened yet?",
  ),
  'C-M9': MilestoneCheckinPromptCopy(
    headerId: '{name} udah selesai operasi? 🏥',
    bodyId:
        'Ini keputusan yang gak gampang dan gak murah, tapi kamu ambil demi kesehatan jangka panjang {name}. Bukan hal kecil — ini bukti nyata kamu pemilik yang beneran bertanggung jawab. Udah pernah dilakuin?',
    headerEn: 'Has {name} had their surgery? 🏥',
    bodyEn:
        "Not an easy or cheap decision, but you made it for {name}'s long-term health. Not a small thing — this is real proof you're a genuinely responsible owner. Has this been done?",
  ),

  // ----- DOG (D) · S 级打卡 -----
  'D-S6': MilestoneCheckinPromptCopy(
    headerId: 'Mandi bersejarah {name} udah pernah terjadi? 🛁',
    bodyId:
        'Ada yang langsung enjoy, ada yang kabur-kaburan — tapi apapun reaksi {name}, mandi pertama itu selalu lebih ribut dari yang kamu bayangkan dan lebih lucu dari yang bisa dijelasin.',
    headerEn: "Has {name}'s legendary first bath happened yet? 🛁",
    bodyEn:
        'Some dogs love it, some make it an event — but either way, the first bath is always louder than expected and funnier than words can describe.',
  ),
  'D-S7': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah grooming pertama kali? ✂️',
    bodyId:
        'Penampilan baru sehabis grooming pertama itu selalu bikin gemes — bersih, rapi, dan sedikit bingung sama perubahan yang dia sendiri belum ngerti. Udah pernah?',
    headerEn: 'Has {name} had their first grooming session? ✂️',
    bodyEn:
        'The post-grooming glow-up is always adorable — clean, neat, and slightly confused about the whole transformation. Has this happened yet?',
  ),
  'D-S8': MilestoneCheckinPromptCopy(
    headerId: '{name} udah coba camilan pertama? 😋',
    bodyId:
        'Dari sini, kamu punya senjata rahasia untuk panggil {name} kapanpun. Ekspresi bahagia pertama kali dapet snack itu terlalu polos untuk gak diabadikan.',
    headerEn: 'Has {name} tried their first treat yet? 😋',
    bodyEn:
        'From this point on, you have a secret weapon to call {name} anytime. That pure happy face the first time they get a treat is too genuine not to capture.',
  ),
  'D-S9': MilestoneCheckinPromptCopy(
    headerId: '{name} pernah tidur di sebelah kamu dan ke-foto? 🌙',
    bodyId:
        'Dipilih jadi tempat tidur favorit {name} adalah kepercayaan paling tulus yang bisa dikasih seekor anjing — tanpa syarat, tanpa alasan lain selain nyaman sama kamu. Udah pernah terjadi?',
    headerEn: 'Has {name} ever slept beside you and got captured? 🌙',
    bodyEn:
        "Being chosen as {name}'s favorite sleeping spot is the most sincere trust a dog can give — no conditions, no other reason except feeling safe with you. Has this happened yet?",
  ),
  'D-S10': MilestoneCheckinPromptCopy(
    headerId: 'Kibasan ekor {name} pernah ke-foto atau ke-video? 🐕',
    bodyId:
        'Ekor yang bergoyang cuma punya satu makna: {name} bahagia ada kamu. Pertama kali momen ini berhasil terekam, itu jadi kenangan yang gak bisa digantiin.',
    headerEn: "Has {name}'s tail wag ever been caught on camera? 🐕",
    bodyEn:
        'A wagging tail has only one meaning: {name} is happy you exist. The first time that moment gets captured, it becomes a memory nothing can replace.',
  ),
  'D-S11': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah pakai kalung atau tali pertama kali? 🏷️',
    bodyId:
        'Momen kecil yang artinya besar — dari sini, petualangan bareng {name} di luar bisa resmi dimulai. Ekspresi pertama kali {name} ngerasain ini biasanya unik banget.',
    headerEn: 'Has {name} worn their first collar or leash yet? 🏷️',
    bodyEn:
        'A small moment that means a lot — from here, real adventures outside with {name} can officially begin. That first reaction is usually pretty unique.',
  ),
  'D-S12': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah main bola? ⚽',
    bodyId:
        'Bola bergulir, {name} langsung ngejar — naluri terbaik yang gak perlu diajarin. Momen pertama ini biasanya lebih spontan dari yang kamu perkirain, dan lebih seru.',
    headerEn: 'Has {name} played with a ball for the first time? ⚽',
    bodyEn:
        "Ball rolls, {name} chases — pure instinct that needs no training. This moment usually happens more spontaneously than you expected, and it's even better for it.",
  ),
  'D-S13': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah main air atau berenang? 💦',
    bodyId:
        'Langsung nyemplung berani atau perlu dirayu dulu? Pemberani atau penakut air — apapun reaksi {name}, ini momen yang selalu bikin kaget dan gak bisa diulang.',
    headerEn: 'Has {name} ever played in water or gone for a swim? 💦',
    bodyEn:
        "Brave jumper or needed some convincing? Water lover or not — whatever {name}'s reaction was, it's always surprising and it only happens once for real.",
  ),

  // ----- DOG (D) · M 级打卡 -----
  'D-M1': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah jalan-jalan bareng kamu di luar? 🦮',
    bodyId:
        'Kaki {name} menginjak trotoar untuk pertama kali, ngehirup udara baru, merasakan segalanya sekaligus — ini langkah pertama yang cuma terjadi sekali dan gak bisa diulang. Udah pernah?',
    headerEn: 'Has {name} gone on their first walk outside with you? 🦮',
    bodyEn:
        "{name}'s paws hitting the pavement for the first time, breathing new air, taking in everything at once — this first step only happens once and can never be repeated. Has this happened yet?",
  ),
  'D-M2': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah naik kendaraan pertama kali? 🚗',
    bodyId:
        'Duduk tenang atau gak bisa anteng dari awal? Apapun reaksi {name}, naik kendaraan pertama kali itu selalu jadi cerita yang seru dan layak diingat. Udah pernah?',
    headerEn: 'Has {name} been in a vehicle for the first time? 🚗',
    bodyEn:
        "Sat still or bounced around the whole way? Whatever {name}'s reaction, that first car ride always becomes one of the better stories you'll tell. Has this happened yet?",
  ),
  'D-M3': MilestoneCheckinPromptCopy(
    headerId: '{name} udah vaksin pertama? 💉',
    bodyId:
        'Gak semua orang mau lakuin ini — tapi kamu mau, dan itu yang bikin beda. Vaksin pertama adalah bentuk kasih sayang yang paling nyata dan gak bisa bohong. Udah dilakuin?',
    headerEn: 'Has {name} had their first vaccine? 💉',
    bodyEn:
        "Not everyone bothers — but you do, and that's what makes the difference. A first vaccine is the most honest form of care there is. Has this been done?",
  ),
  'D-M4': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah dikasih obat cacing? 🌿',
    bodyId:
        'Ngurusin hal yang gak keliatan tapi krusial — ini level kepedulian yang beneran niat. Banyak yang skip ini, tapi kamu gak. Kalau sudah, tandai sekarang.',
    headerEn: 'Has {name} had their first deworming? 🌿',
    bodyEn:
        "Taking care of what's invisible but crucial — that's real, intentional care. A lot of people skip this, but not you. If it's been done, mark it now.",
  ),
  'D-M5': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah ke dokter hewan? 🩺',
    bodyId:
        'Perlu persiapan, butuh effort, dan kadang perlu nahan nafas di ruang tunggu — tapi ini salah satu cara paling nyata untuk buktiin rasa sayang kamu ke {name}. Udah pernah?',
    headerEn: 'Has {name} been to the vet yet? 🩺',
    bodyEn:
        "Takes preparation, takes effort, sometimes takes waiting nervously in the lobby — but it's one of the most real ways to prove how much you care about {name}. Has this happened?",
  ),
  'D-M6': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah ketemu anjing lain pertama kali? 🐶',
    bodyId:
        'Langsung akrab atau butuh adaptasi dulu? Apapun hasilnya, ini momen sosial terbesar dalam hidupnya hari itu — dan biasanya lebih dramatis dari yang dibayangkan. Udah pernah?',
    headerEn: 'Has {name} ever met another dog for the first time? 🐶',
    bodyEn:
        "Instant best friends or needed time to warm up? Either way, this was the biggest social event of {name}'s day — and usually more dramatic than expected. Has this happened?",
  ),
  'D-M7': MilestoneCheckinPromptCopy(
    headerId: '{name} udah bisa ikutin perintah pertama? 🐾',
    bodyId:
        'Duduk, salaman, atau perintah apapun yang pertama berhasil — ini bukan cuma soal latihan. Ini bukti kepercayaan yang tumbuh antara kamu dan {name}, dan itu yang beneran berarti. Udah pernah?',
    headerEn: 'Has {name} ever followed their first command? 🐾',
    bodyEn:
        "Sit, shake, or whatever came first — this isn't just about training. It's proof of the trust growing between you and {name}, and that's what actually matters. Has this happened?",
  ),
  'D-M9': MilestoneCheckinPromptCopy(
    headerId: '{name} udah selesai operasi? 🏥',
    bodyId:
        'Bukan keputusan yang gampang atau murah, tapi kamu ambil demi masa depan yang lebih sehat untuk {name}. Ini bukan hal kecil — ini bukti nyata kamu pemilik yang serius. Udah dilakuin?',
    headerEn: 'Has {name} had their surgery? 🏥',
    bodyEn:
        "Not an easy or cheap decision, but you made it for {name}'s healthier future. This isn't a small thing — it's real proof you're a serious, caring owner. Has this been done?",
  ),

  // ----- OTHER (G) · 打卡 -----
  'G-S6': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah coba camilan pertama? 😋',
    bodyId:
        'Reaksi {name} pertama kali dapet sesuatu yang enak itu paling jujur — gak bisa dibuat-buat, gak bisa diulang persis sama. Momen ini priceless banget kalau ke-foto.',
    headerEn: 'Has {name} tried their first treat yet? 😋',
    bodyEn:
        "{name}'s reaction the first time they tasted something good is the most honest thing — can't be faked, can't be exactly recreated. That moment is priceless if it was captured.",
  ),
  'G-M1': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah ke dokter hewan? 🩺',
    bodyId:
        'Untuk hewan yang "beda dari biasanya", cari dokter yang tepat itu butuh usaha ekstra. Kalau kamu udah berhasil lakuinnya, itu pencapaian yang beneran patut dicatat. Udah pernah?',
    headerEn: 'Has {name} ever been to the vet? 🩺',
    bodyEn:
        "For a less-common pet, finding the right vet takes extra effort. If you've already made this happen, that's a real achievement worth recording. Has this happened?",
  ),
  'G-M2': MilestoneCheckinPromptCopy(
    headerId: '{name} udah pernah cek kesehatan atau vaksin? 💉',
    bodyId:
        'Fondasi kesehatan yang kamu bangun dari awal — gak semua orang mau repot-repot lakuin ini. Tapi kamu mau, dan itu yang bikin kamu beda. Udah dilakuin?',
    headerEn: 'Has {name} had their first health check or vaccine? 💉',
    bodyEn:
        "Building the health foundation from the very start — not everyone bothers with this. But you do, and that's what sets you apart. Has this been done?",
  ),

  // ============================================================
  // 系统自动 / 推送类（SYSTEM_AUTO / PUSH_PUBLISH）：仅说明文案，无 Header / CTA
  // ============================================================

  // 档案创建完成
  'C-S1': _autoProfileSet,
  'D-S1': _autoProfileSet,
  'G-S1': _autoProfileSet,
  // 第一张照片
  'C-S2': _autoFirstPhoto,
  'D-S2': _autoFirstPhoto,
  'G-S2': _autoFirstPhoto,
  // 第一次分享名片
  'C-S3': _autoShareCard,
  'D-S3': _autoShareCard,
  'G-S3': _autoShareCard,
  // 第一次保存问诊
  'C-S4': _autoConsultSaved,
  'D-S4': _autoConsultSaved,
  'G-S4': _autoConsultSaved,
  // 第一次发布分享
  'C-S5': _autoFirstPost,
  'D-S5': _autoFirstPost,
  'G-S5': _autoFirstPost,
  // 第一次被评论
  'C-S14': _autoFirstComment,
  'D-S14': _autoFirstComment,
  'G-S7': _autoFirstComment,
  // 第一次收到点赞
  'C-S15': _autoFirstLike,
  'D-S15': _autoFirstLike,
  'G-S8': _autoFirstLike,
  // 陪伴满 30 天
  'C-M8': _auto30Days,
  'D-M8': _auto30Days,
  'G-M3': _auto30Days,
  // 日历满 10 条
  'C-M10': _auto10Entries,
  'D-M10': _auto10Entries,
  'G-M4': _auto10Entries,
  // 陪伴满 100 天
  'C-L2': _auto100Days,
  'D-L2': _auto100Days,
  'G-L2': _auto100Days,
  // 陪伴满 365 天
  'C-L3': _auto365Days,
  'D-L3': _auto365Days,
  'G-L3': _auto365Days,
  // 全健康里程碑
  'C-L4': _autoAllHealth,
  'D-L4': _autoAllHealth,
  // 日历满 30 条
  'C-L5': _auto30Entries,
  'D-L5': _auto30Entries,
};

// 系统自动说明（C/D/G 共用，提取常量避免重复）。
const _autoProfileSet = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat profil {name} sudah lengkap — foto profil dan bio sudah terisi.',
  bodyEn: "Automatically completed once {name}'s profile is fully set up with a photo and bio.",
);
const _autoFirstPhoto = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat kamu pertama kali mengunggah foto ke Kalender Tumbuh.',
  bodyEn: 'Automatically completed when you first upload a photo to the Growth Calendar.',
);
const _autoShareCard = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat kamu pertama kali membagikan kartu nama {name}.',
  bodyEn: "Automatically completed the first time you share {name}'s profile card.",
);
const _autoConsultSaved = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat kamu menyimpan hasil konsultasi ke arsip {name}.',
  bodyEn: "Automatically completed when you save a consultation result to {name}'s record.",
);
const _autoFirstPost = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat kamu pertama kali membuat postingan di platform.',
  bodyEn: 'Automatically completed when you publish your first post on the platform.',
);
const _autoFirstComment = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat postinganmu pertama kali mendapat komentar dari pengguna lain.',
  bodyEn: 'Automatically completed when your post receives its first comment from another user.',
);
const _autoFirstLike = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat postinganmu pertama kali mendapat suka dari pengguna lain.',
  bodyEn: 'Automatically completed when your post receives its first like from another user.',
);
const _auto30Days = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat {name} sudah menemanimu selama 30 hari sejak profil dibuat.',
  bodyEn: 'Automatically completed when {name} has been with you for 30 days since the profile was created.',
);
const _auto10Entries = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat {name} sudah punya 10 catatan di Kalender Tumbuh.',
  bodyEn: "Automatically completed when {name}'s Growth Calendar reaches 10 entries.",
);
const _auto100Days = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat {name} sudah menemanimu selama 100 hari. Hampir sampai!',
  bodyEn: 'Automatically completed when {name} has been with you for 100 days. Almost there!',
);
const _auto365Days = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat genap satu tahun {name} bersamamu. Ini momen yang ditunggu-tunggu.',
  bodyEn: "Automatically completed on {name}'s one-year anniversary with you. The moment worth waiting for.",
);
// 全健康里程碑：原文档 id 直接列了 code（C-M3 + …），对用户不友好 → 与 en 一致改为可读描述。
const _autoAllHealth = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat tiga tonggak kesehatan (vaksin + obat cacing + periksa dokter) semua sudah selesai.',
  bodyEn: 'Automatically completed when all three health milestones (vaccine + deworming + vet visit) are done.',
);
const _auto30Entries = MilestoneCheckinPromptCopy(
  bodyId: 'Otomatis selesai saat {name} sudah punya 30 catatan di Kalender Tumbuh.',
  bodyEn: "Automatically completed when {name}'s Growth Calendar reaches 30 entries.",
);

/// 按 code + locale 返回 P-33b 打卡引导/说明文案（header 提问 + body 描述），并把 `{name}` 替换为宠物名。
///
/// 未配文案的 code（如生日 *-L1）返回空 header + 空 body —— 调用方据此回退到通用 hint。
({String header, String body}) localizedMilestoneCheckinPrompt(
  String code,
  Locale locale,
  String petName,
) {
  final copy = kMilestoneCheckinPromptCopy[code];
  if (copy == null) return (header: '', body: '');
  final isId = locale.languageCode == 'id';
  final header = (isId ? copy.headerId : copy.headerEn).replaceAll('{name}', petName);
  final body = (isId ? copy.bodyId : copy.bodyEn).replaceAll('{name}', petName);
  return (header: header, body: body);
}
