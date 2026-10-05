import 'ts_text.dart';

/// Tailsonality 16 角色（V1.3.2 Story 2.2 · 内容设计 §4.1 / §5.2 / §5.3 / §5.5）。键 = 四字母代号。
///
/// **键写成单引号字面量 + `TsRole(` 一行起头**：后端 `TailsonalityContentParityTest` 据此抽取，与 `TailsonalityCatalog.TYPE_CODES` 比对。
/// 角色名与 slogan 是 Jaksel 原文，三语共用、**不翻译**（不带内容设计里的斜体星号）。
/// 🔴 **角色名与 slogan 以角色卡图为准**（卡面烤着这两行字，2026-10-05 产品定）：ESTJ / ESTP / ISTP 的名、
/// ESTP / ISTP / ENFP 的 slogan 已按图改。换图或改字时两边一起改，否则结果卡与列表 / Diary / 小标显示两个名字。
/// 🔴 任何文案不得借用外部人格测试的角色别名（`trademark_scan_test.dart` 整词扫描）。
class TsRole {
  const TsRole({required this.name, required this.slogan, required this.summary, required this.deepRead});

  /// Jaksel 角色名（不翻译）。
  final String name;

  /// Jaksel slogan（不翻译）。
  final String slogan;

  /// 免费摘要（§5.3）。
  final TsText summary;

  /// 角色专属深读（§5.5，付费，Story 3.2 展示）。
  final TsText deepRead;
}

const Map<String, TsRole> kTsRoles = {
  'ENTJ': TsRole(
    name: "Literally CEO Banget",
    slogan: "CEO of this house, literally.",
    summary: (
      en: "{pet} isn't just full of energy — it has a plan. The best view in the house, the exact hour bugging you works best: it knows.",
      id: "{pet} bukan cuma aktif — dia punya rencana. Posisi paling strategis di rumah, jam berapa paling ampuh buat ngerecokin kamu, dia hafal semua.",
    ),
    deepRead: (
      en: "Outgoing + curious + unbothered + persistent: in a pet, that combo makes a born boss. {pet} isn't just full of energy — it has a plan. It knows which spot has a view of the whole room, what time bugging you works best, and how to make every newcomer notice it first. It isn't anxious, because it assumes everything is already under its control. The key to living with it isn't discipline, it's giving it something to do: a bored CEO starts a new line of business, and that business is usually your sofa.",
      id: "Ekstrover + penasaran + santai + gigih: di dunia hewan, kombinasi ini bikin bos alami. {pet} bukan cuma aktif — dia punya rencana. Dia tahu posisi mana yang bisa mantau seisi ruangan, jam berapa paling ampuh buat ngerecokin kamu, dan gimana caranya bikin orang baru langsung notice dia. Dia nggak cemas, karena dia anggap semuanya udah di bawah kendalinya. Kunci hidup bareng dia bukan dididik keras, tapi dikasih kerjaan: CEO yang bosen bakal buka bisnis baru sendiri, dan biasanya bisnisnya itu sofa kamu.",
    ),
  ),
  'ENTP': TsRole(
    name: "Party Animal Beneran",
    slogan: "New friend? Yes. Commitment? No.",
    summary: (
      en: "Everyone's a friend and everything's worth a try — but its interest in anything never outlasts the next thing that shows up.",
      id: "Semua orang temennya, semua hal pengen dicoba — tapi minatnya ke apa pun nggak pernah bertahan sampai hal berikutnya muncul.",
    ),
    deepRead: (
      en: "Wants to try everything, friends with everyone, and takes nothing to heart. {pet} is the pet you can bring anywhere and nothing goes wrong — new places, new people, new toys are just today's lineup. But don't mistake that for easy to train: its interest in anything never lasts until the next thing shows up. It isn't disobeying; it genuinely forgot.",
      id: "Semua pengen dicoba, semua orang temennya, dan nggak ada yang dimasukin ke hati. {pet} tipe yang dibawa ke mana aja aman — tempat baru, orang baru, mainan baru, semuanya cuma jadwal acara hari ini. Tapi jangan kira dia gampang dilatih: minatnya ke apa pun nggak pernah awet sampai hal berikutnya muncul. Dia bukan bandel, dia beneran udah lupa.",
    ),
  ),
  'ENFJ': TsRole(
    name: "Main Character Energy",
    slogan: "Main character, always.",
    summary: (
      en: "{pet} reads your face the whole time, and keeps adjusting its moves until it gets a reaction from you.",
      id: "{pet} terus baca ekspresi kamu, dan terus ganti cara sampai dapet respons dari kamu.",
    ),
    deepRead: (
      en: "Warm, curious, goal-driven — and reading your face the whole time. {pet} is one of the rare pets that actively works on the relationship: it remembers when you get home, knows whether to snuggle up or give you space when you're down, and keeps adjusting until it gets a reaction from you. Its happiness leans heavily on your feedback, so when you're too busy to notice it, it doesn't just lose energy — it loses its place.",
      id: "Hangat, penasaran, punya tujuan jelas — dan terus baca ekspresi kamu. {pet} termasuk sedikit hewan yang aktif ngerawat hubungan: dia inget jam kamu pulang, tahu kapan harus nempel dan kapan harus ngejauh pas kamu lagi bad mood, dan terus ganti cara sampai dapet respons dari kamu. Bahagianya sangat bergantung sama respons kamu, jadi pas kamu kesibukan sampai nyuekin dia, yang hilang bukan cuma semangatnya — tapi rasa dianggap ada.",
    ),
  ),
  'ENFP': TsRole(
    name: "Gabut Tapi Heboh",
    slogan: "Quiet house? Not on my watch.",
    summary: (
      en: "{pet}'s day is made of seventeen half-finished things — and it was totally into every one of them.",
      id: "Harinya {pet} isinya tujuh belas hal yang nggak pernah kelar — dan pas lagi dikerjain, semuanya serius banget.",
    ),
    deepRead: (
      en: "Off-the-charts curiosity + big feelings + a three-minute attention span. {pet}'s day is made of seventeen half-finished things. It's loud, but not for any reason; it's clingy, until a bird outside the window steals it away. This kind of pet almost never gets down, but gets bored incredibly easily — and the way it cures boredom usually shows up on your bills.",
      id: "Rasa penasaran tumpah-tumpah + perasaan yang rame + semangat tiga menit. Harinya {pet} isinya tujuh belas hal yang nggak pernah kelar. Dia heboh, tapi nggak ada tujuannya; dia manja, tapi sedetik kemudian udah kebawa burung di luar jendela. Hewan kayak gini hampir nggak pernah murung, tapi gampang banget bosen — dan cara dia ngilangin bosen biasanya muncul di tagihan kamu.",
    ),
  ),
  'ESTJ': TsRole(
    name: "Ministry of House Affairs",
    slogan: "Rules are rules, bestie.",
    summary: (
      en: "You think you're raising {pet}. Actually, it's running the house.",
      id: "Kamu kira kamu yang ngurus {pet}. Padahal dia yang ngatur rumah ini.",
    ),
    deepRead: (
      en: "Social, rule-abiding, unbothered, persistent. {pet} is the real manager of the house: what time dinner is, who sits where, which drawer shouldn't be closed — it has opinions on all of it, and it sticks to them. It doesn't like new things, but it cares deeply about the order already in place. You think you're raising it; actually, it's running the house, and you're the employee who's always a little late.",
      id: "Suka bergaul, taat aturan, santai, gigih. {pet} adalah manajer rumah yang sebenarnya: jam berapa makan, siapa duduk di mana, laci mana yang nggak boleh ditutup — dia punya pendapat soal semuanya, dan dia pegang teguh. Dia nggak suka hal baru, tapi peduli banget sama tatanan rumah yang udah ada. Kamu kira kamu yang ngurus dia, padahal dia yang ngatur rumah ini, dan kamu karyawan yang suka telat.",
    ),
  ),
  'ESTP': TsRole(
    name: "Gas First, Think Later",
    slogan: "Plan? I'm already there!",
    summary: (
      en: "Everyone's welcome — but {pet} won't get up for anyone.",
      id: "Siapa pun yang dateng disambut — tapi {pet} nggak bakal bangun buat siapa-siapa.",
    ),
    deepRead: (
      en: "Everyone's welcome, but it won't get up for anyone. {pet} invests all its energy in whatever it can enjoy right now — food, a sunny spot, a hand that happens to pass by. Not curious, not anxious, not persistent: almost nothing bothers it. It's the lowest-maintenance combo, and the hardest to motivate. There's only one way to get it to do something — make it worth its while.",
      id: "Siapa pun yang dateng disambut, tapi dia nggak bakal bangun buat siapa-siapa. {pet} ngabisin semua energinya buat hal yang bisa dinikmatin saat itu juga — makanan, spot yang kena matahari, tangan yang kebetulan lewat. Nggak kepo, nggak cemas, nggak ngotot: hampir nggak ada yang bisa ganggu dia. Ini kombinasi paling nggak ribet, sekaligus paling susah dimotivasi. Cuma ada satu cara biar dia mau ngapa-ngapain — bikin dia ngerasa worth it.",
    ),
  ),
  'ESFJ': TsRole(
    name: "Bucin Garis Keras",
    slogan: "Kamu ke mana, aku ke situ.",
    summary: (
      en: "{pet} doesn't need new toys or new friends. It needs you where it can see you.",
      id: "{pet} nggak butuh mainan baru atau temen baru. Dia cuma butuh kamu kelihatan di depan matanya.",
    ),
    deepRead: (
      en: "Outgoing + steady + sensitive + persistent, and it all adds up to this: in {pet}'s world, there's only you. It doesn't need new toys or new friends; it needs you where it can see you. When you leave, it counts the minutes; when you're back, it has to check how you smell; when you're sad, it reacts before you do. That's a lot of love — and it means being apart is genuinely hard for it. Not attention-seeking. Genuinely hard.",
      id: "Ekstrover + setia sama yang lama + sensitif + gigih, kalau dijumlahin hasilnya: di dunia {pet}, cuma ada kamu. Dia nggak butuh mainan baru, nggak butuh temen baru, dia butuh kamu kelihatan di depan matanya. Kamu pergi, dia ngitung waktu; kamu pulang, dia harus ngecek bau kamu; kamu sedih, dia bereaksi duluan. Sayangnya pekat banget, tapi artinya pisah itu beneran berat buat dia — bukan caper, beneran berat.",
    ),
  ),
  'ESFP': TsRole(
    name: "Drama Tapi Gemoy",
    slogan: "Drama, but make it cute.",
    summary: (
      en: "Every reaction is one size bigger than the situation — and {pet} knows exactly that it works.",
      id: "Setiap reaksinya selalu lebih heboh dari kejadian aslinya — dan {pet} tahu banget itu ampuh.",
    ),
    deepRead: (
      en: "Every feeling is written on its face — and it knows that works. {pet}'s every reaction is one size bigger than the situation: dinner five minutes late is the end of the world, you coming home is a long-lost reunion. It holds no grudges and keeps no records; one second it's falling apart, the next it's all cuddles. It'll run you ragged, but it's hard to stay mad — it knows exactly where its cuteness lies.",
      id: "Semua perasaannya kelihatan di muka — dan dia tahu itu ampuh. Setiap reaksi {pet} selalu lebih heboh dari kejadian aslinya: makan telat lima menit itu kiamat, kamu pulang itu kayak reuni setelah bertahun-tahun. Dia nggak dendaman dan nggak inget-inget, sedetik drama, sedetik kemudian manja. Kamu bakal dibikin repot, tapi susah beneran marah — dia tahu banget di mana letak gemesnya.",
    ),
  ),
  'INTJ': TsRole(
    name: "Lone Wolf Mode",
    slogan: "Busy. Don't watch.",
    summary: (
      en: "{pet} has its own research project, and you're not on the team.",
      id: "{pet} punya proyek riset sendiri, dan kamu nggak masuk timnya.",
    ),
    deepRead: (
      en: "Introverted + curious + unbothered + persistent: {pet} has its own research project, and you're not on the team. It'll spend ages studying something new, work out problems it can't reach on its own, and never ask for help or show off. It isn't cuddly with people, but it's close to you — it just shows it by staying at the other end of the same room. Don't read its independence as not needing you; it just doesn't think that needs saying.",
      id: "Introver + penasaran + santai + gigih: {pet} punya proyek riset sendiri, dan kamu nggak masuk timnya. Dia bisa lama banget ngamatin barang baru, nyari cara sendiri buat masalah yang nggak kejangkau, tanpa minta tolong dan tanpa pamer. Dia nggak suka sama semua orang, tapi dia sayang kamu — cuma caranya dengan diem di ujung lain ruangan yang sama. Jangan artiin kemandiriannya sebagai nggak butuh kamu, dia cuma ngerasa itu nggak perlu diomongin.",
    ),
  ),
  'INTP': TsRole(
    name: "Solo Healing Trip",
    slogan: "Solo trip, no invite.",
    summary: (
      en: "{pet}'s world is huge — there just aren't many people in it.",
      id: "Dunianya {pet} luas banget — cuma isinya nggak banyak orang.",
    ),
    deepRead: (
      en: "Curious but not social, exploring but in no rush. {pet}'s world is huge — there just aren't many people in it. It likes new things, but wants to study them slowly on its own; it isn't afraid of change, but hates being watched. The best way to be with it is 'there, but not in the way': you scrolling on the sofa, it studying a box beside you — for {pet}, that's a perfect day.",
      id: "Penasaran tapi nggak suka bergaul, suka eksplor tapi nggak buru-buru. Dunianya {pet} luas banget — cuma isinya nggak banyak orang. Dia suka hal baru, tapi mau nyelidikin pelan-pelan sendiri; dia nggak takut perubahan, tapi benci dijadiin tontonan. Cara terbaik bareng dia itu 'ada, tapi nggak ganggu': kamu scroll HP di sofa, dia nyelidikin kardus di sebelah — buat {pet}, itu hari yang sempurna.",
    ),
  ),
  'INFJ': TsRole(
    name: "Quiet Luxury",
    slogan: "Says nothing. Knows everything.",
    summary: (
      en: "No noise, no fuss — but wherever you go, {pet} shows up.",
      id: "Nggak berisik, nggak rewel — tapi ke mana pun kamu pergi, {pet} muncul di situ.",
    ),
    deepRead: (
      en: "Quiet, curious, sensitive — and once it's decided, it doesn't change. {pet} is the kind of pet you have to watch for a long time to understand: no noise, no fuss, but clear preferences that stay the same for years. It's deeply attached to you, yet incredibly restrained about it: no jumping, no calling out — it just shows up wherever you go. Pets like this rarely have problems, but when one surfaces, it's usually been holding it in for a long time.",
      id: "Kalem, penasaran, sensitif — dan kalau udah mutusin sesuatu, nggak bakal berubah. {pet} tipe yang harus lama diamatin baru bisa dipahami: nggak berisik, nggak rewel, tapi punya preferensi jelas yang konsisten bertahun-tahun. Dia lengket banget sama kamu, tapi nunjukinnya super ditahan: nggak loncat, nggak manggil — cuma muncul di mana pun kamu berada. Hewan kayak gini jarang bermasalah, tapi sekali ada masalah, biasanya udah dia pendem lama.",
    ),
  ),
  'INFP': TsRole(
    name: "Overthinker Elite",
    slogan: "Overthinking, currently.",
    summary: (
      en: "{pet} thinks more than it does — a new box needs three days of watching before it dares touch it.",
      id: "{pet} lebih banyak mikir daripada gerak — kotak baru harus diamatin tiga hari dulu baru berani disentuh.",
    ),
    deepRead: (
      en: "Sensitive + introverted + curious + easygoing: {pet} thinks more than it does. A new object needs three days of watching before it dares touch it; one noise keeps it on alert all night. Its inner drama is incredibly rich, and what you see on the outside is — standing still. A pet like this doesn't need encouragement; it needs time and predictability. Push it to hurry, and it just pulls further back.",
      id: "Sensitif + introver + penasaran + santuy: {pet} lebih banyak mikir daripada gerak. Barang baru harus diamatin tiga hari dulu baru berani disentuh; satu suara bisa bikin dia waspada semalaman. Drama di dalam kepalanya rame banget, tapi yang kelihatan dari luar cuma — berdiri diem. Hewan kayak gini nggak butuh disemangatin, dia butuh waktu dan hal yang bisa ditebak. Dipaksa cepet, dia malah makin mundur.",
    ),
  ),
  'ISTJ': TsRole(
    name: "Jadwal Is Everything",
    slogan: "Jam makan is jam makan.",
    summary: (
      en: "When to eat, when to sleep, which spot to sleep in — the same, year after year.",
      id: "Jam makan, jam tidur, posisi tidurnya — sama persis dari tahun ke tahun.",
    ),
    deepRead: (
      en: "Introverted, steady, unbothered, persistent. {pet} runs like clockwork: when to eat, when to sleep, which spot to sleep in — the same, year after year. It isn't loud, isn't curious, isn't anxious; the only thing that upsets it is you messing up the routine. It's the most stable, lowest-fuss combo, but also the one that struggles most with surprises — moving house, new food, a guest who stays for weeks: none of these are small to it.",
      id: "Introver, setia sama yang lama, santai, gigih. {pet} jalan kayak jam: jam makan, jam tidur, posisi tidurnya — sama persis dari tahun ke tahun. Dia nggak heboh, nggak kepo, nggak cemas; satu-satunya yang bikin dia kesel itu kalau kamu ngacak-ngacak rutinitasnya. Ini kombinasi paling stabil dan paling nggak ribet, tapi juga paling kewalahan sama kejutan — pindahan, ganti makanan, tamu yang nginep lama, semuanya bukan hal kecil buat dia.",
    ),
  ),
  'ISTP': TsRole(
    name: "Lowbat on Purpose",
    slogan: "Wake me when the treat bag opens.",
    summary: (
      en: "Food, a comfy spot, nobody bothering it. That's enough.",
      id: "Ada makan, ada tempat nyaman, nggak ada yang ganggu. Udah, cukup.",
    ),
    deepRead: (
      en: "Quiet, practical, unbothered, easygoing — four letters that spell 'no fuss'. {pet} doesn't ask much of the world: food, a comfy spot, nobody bothering it. That's enough. It doesn't explore, doesn't socialize, doesn't chase, doesn't worry. You might wonder whether it loves you — it does. Its way of loving you is sleeping three meters away.",
      id: "Kalem, praktis, santai, ngalir aja — empat huruf yang artinya 'nggak ribet'. {pet} nggak banyak nuntut: ada makan, ada tempat nyaman, nggak ada yang ganggu. Udah, cukup. Dia nggak eksplor, nggak bergaul, nggak ngotot, nggak cemas. Kamu mungkin mikir, dia sayang kamu nggak sih — sayang kok. Cuma cara sayangnya itu tidur tiga meter dari kamu.",
    ),
  ),
  'ISFJ': TsRole(
    name: "Shy Bestie",
    slogan: "Shy to them, clingy to you.",
    summary: (
      en: "All guard with everyone else, all trust with you.",
      id: "Ke orang lain jaga jarak, ke kamu nempel total.",
    ),
    deepRead: (
      en: "All guard with everyone else, all trust with you. Around strangers, {pet} is a different pet — hiding, freezing, vanishing; alone with you, it's clingier than you'd expect. It holds on to everything familiar, and it holds on to you. Its trust is hard to earn, and once given, it's never taken back. The price: when you're gone, it won't go looking for anyone else.",
      id: "Ke orang lain jaga jarak, ke kamu nempel total. Di depan orang asing, {pet} kayak hewan yang beda — ngumpet, kaku, menghilang; tapi pas cuma berdua sama kamu, manjanya bikin kaget. Dia jagain semua yang familiar, termasuk kamu. Kepercayaannya susah didapet, tapi sekali dikasih nggak bakal ditarik lagi. Harganya: kalau kamu pergi, dia nggak bakal nyari orang lain.",
    ),
  ),
  'ISFP': TsRole(
    name: "Sus Radar 24/7",
    slogan: "Sus. Everything sus.",
    summary: (
      en: "The house security system — the kind with a very high false-alarm rate.",
      id: "Sistem keamanan rumah — yang tingkat alarm palsunya tinggi banget.",
    ),
    deepRead: (
      en: "Sensitive + introverted + steady + easygoing: {pet} is the house security system — the kind with a very high false-alarm rate. Every rustle needs checking, every delivery calls for a patrol, and if you change your shirt it has to recognize you all over again. It isn't timid; in its world, 'change' simply defaults to 'suspicious'. Give it a stable setup and plenty of hiding spots, and it'll be far more relaxed than you'd think.",
      id: "Sensitif + introver + setia sama yang lama + santuy: {pet} itu sistem keamanan rumah — yang tingkat alarm palsunya tinggi banget. Ada suara dikit harus dicek, ada kurir dateng harus patroli, kamu ganti baju aja dia harus kenalan ulang. Dia bukan penakut; di dunianya, 'berubah' otomatis sama dengan 'mencurigakan'. Kasih dia lingkungan yang tetap dan tempat ngumpet yang cukup, dia bakal jauh lebih santai dari yang kamu kira.",
    ),
  ),
};
