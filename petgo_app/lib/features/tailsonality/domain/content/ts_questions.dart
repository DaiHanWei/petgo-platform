import 'ts_text.dart';

/// Tailsonality 题库文案（V1.3.2 Story 2.2 · 内容设计 §6.2–§6.5）。
///
/// 键 = `'<SET>.<QID>'`（SET ∈ CAT|DOG|GENERAL，QID ∈ Q1..Q15|P1..P3），共 54 条；**键写成单引号字面量、一行起头**，
/// 后端 `TailsonalityContentParityTest` 用正则跨库抽取，与 `TailsonalityCatalog` 的「3 套 × 18 题」逐条比对。
///
/// 选项按内容设计原序（+2 → −2）存，**下标即提交给后端的原始序号 0..3**；本版本不打乱显示顺序（Story 2.3）。
/// 题意一律以内容设计为准，UI 稿 A4 / A5 只借句式（二者题目不符处见 Story 2.2「已知文档矛盾」）。
class TsQuestion {
  const TsQuestion({required this.stem, required this.options, this.imageGroup});

  final TsText stem;

  /// 恰 4 个；下标 = 原始选项序号（0 = +2 … 3 = −2）。图片题时是图下的文字标签（与图同一语义）。
  final List<TsText> options;

  /// 仅 P1..P3：`p1` | `p2` | `p3` → `assets/tailsonality/quiz_<group>_<i>.webp`（已入库，缺失时 Story 2.3 回落占位）。
  final String? imageGroup;
}

/// 图片题 P1（§6.5，三套共用题干与选项）。
const TsQuestion _kP1 = TsQuestion(
  stem: (
    en: "When you're not home, where is {pet} most likely to be?",
    id: "Pas kamu nggak di rumah, {pet} paling mungkin ada di mana?",
  ),
  options: [
    (en: "Waiting by the door", id: "Jagain pintu depan"),
    (en: "Center of the room", id: "Tengah ruang tamu"),
    (en: "In a corner", id: "Di pojokan"),
    (en: "Inside a hiding spot", id: "Di tempat ngumpet"),
  ],
  imageGroup: 'p1',
);

/// 图片题 P2（§6.5，三套共用题干与选项）。
const TsQuestion _kP2 = TsQuestion(
  stem: (
    en: "Which one looks most like {pet} the moment it gets startled?",
    id: "Mana yang paling mirip {pet} pas lagi kaget?",
  ),
  options: [
    (en: "Leaps away", id: "Loncat menghindar"),
    (en: "Freezes and stares", id: "Diem melotot"),
    (en: "Tiny flinch", id: "Kaget dikit"),
    (en: "Doesn't budge", id: "Tetap anteng"),
  ],
  imageGroup: 'p2',
);

/// 图片题 P3（§6.5，三套共用题干与选项）。
const TsQuestion _kP3 = TsQuestion(
  stem: (en: "Which time of day is {pet}'s prime time?", id: "Kapan waktu paling heboh buat {pet}?"),
  options: [
    (en: "Late night", id: "Tengah malam"),
    (en: "Early morning", id: "Pagi-pagi"),
    (en: "Dusk", id: "Sore hari"),
    (en: "No peak, all the same", id: "Nggak ada, sama aja"),
  ],
  imageGroup: 'p3',
);

const Map<String, TsQuestion> kTsQuestions = {
  // ===== 猫套 §6.2 =====
  'CAT.Q1': TsQuestion(
    stem: (en: "A stranger walks in. {pet}'s first move?", id: "Ada orang asing masuk rumah, reaksi pertama {pet} gimana?"),
    options: [
      (en: "Goes right up to sniff and rub", id: "Langsung nyamperin, endus-endus"),
      (en: "Watches from afar, comes closer later", id: "Ngeliatin dari jauh, lama-lama baru deketin"),
      (en: "Hides under the sofa till they leave", id: "Ngumpet di kolong sofa sampai orangnya pulang"),
      (en: "Vanishes — no show the whole visit", id: "Langsung hilang, nggak nongol sama sekali"),
    ],
  ),
  'CAT.Q2': TsQuestion(
    stem: (
      en: "While you're busy with your own stuff, where's {pet} usually?",
      id: "Pas kamu lagi sibuk sendiri, {pet} biasanya ada di mana?",
    ),
    options: [
      (en: "Lying right on your hands or keyboard", id: "Tiduran langsung di tangan atau keyboard kamu"),
      (en: "Curled up somewhere next to you", id: "Cari posisi rebahan di sebelah kamu"),
      (en: "Same room, but far from you", id: "Satu ruangan, tapi jauh dari kamu"),
      (en: "In another room", id: "Di ruangan lain"),
    ],
  ),
  'CAT.Q3': TsQuestion(
    stem: (en: "The house is full of people and noise. What does {pet} do?", id: "Rumah lagi rame banyak orang, biasanya {pet} gimana?"),
    options: [
      (en: "Checks on every single person, asks for pets too", id: "Nyamperin satu-satu, sekalian minta dielus"),
      (en: "Watches from a corner, pops out now and then", id: "Ngamatin dari pojok, sesekali keluar"),
      (en: "Watches from up high the whole time", id: "Nonton dari tempat tinggi sepanjang waktu"),
      (en: "Heads straight to a room and stays there", id: "Langsung masuk kamar dan nggak keluar-keluar"),
    ],
  ),
  'CAT.Q4': TsQuestion(
    stem: (en: "Something new is sitting on the floor. What does {pet} do?", id: "Ada barang baru ditaruh di lantai, biasanya {pet} gimana?"),
    options: [
      (en: "Investigates right away, pawing at it", id: "Langsung diselidiki, dicakar-cakar"),
      (en: "Watches a bit, then goes to sniff", id: "Ngeliatin dulu, baru deketin buat endus"),
      (en: "Stares from a distance, won't go near", id: "Ngeliatin dari jauh, nggak mau deket"),
      (en: "Ignores it completely, carries on", id: "Cuek total, lanjut aktivitas biasa"),
    ],
  ),
  'CAT.Q5': TsQuestion(
    stem: (en: "You open a door that's usually closed. What does {pet} do?", id: "Kamu buka pintu yang biasanya ditutup, {pet} gimana?"),
    options: [
      (en: "Rushes in to explore", id: "Langsung nyelonong masuk buat eksplor"),
      (en: "Peeks in first, then decides", id: "Ngintip dulu, baru mutusin"),
      (en: "Stays at the door, won't go in", id: "Diem di depan pintu, nggak masuk"),
      (en: "Doesn't even notice", id: "Nggak ngeh sama sekali"),
    ],
  ),
  'CAT.Q6': TsQuestion(
    stem: (en: "After playing with the same toy for ages, how does {pet} feel about it?", id: "Udah lama main mainan yang sama, {pet} gimana?"),
    options: [
      (en: "Got bored long ago, always hunting for new ones", id: "Udah bosen dari tadi, terus nyari yang baru"),
      (
        en: "Gets bored, but only switches when there's a new one",
        id: "Bosen sih, tapi baru ganti kalau ada yang baru",
      ),
      (en: "Happy to keep playing with it", id: "Main yang itu terus juga nggak masalah"),
      (en: "Only wants that one — swap it and it stops playing", id: "Cuma mau yang itu, diganti malah nggak main"),
    ],
  ),
  'CAT.Q7': TsQuestion(
    stem: (
      en: "A new smell in the house (a takeout bag, a new purchase). What does {pet} do?",
      id: "Ada bau asing di rumah (bungkus makanan, barang baru), biasanya {pet} gimana?",
    ),
    options: [
      (en: "Follows the smell everywhere, checks every corner", id: "Ngikutin baunya terus, semua pojok dicek"),
      (en: "Goes over for a sniff, then leaves", id: "Ngendus sebentar, terus pergi"),
      (en: "Watches from afar", id: "Mantau dari jauh"),
      (en: "Barely reacts", id: "Nggak ada reaksi apa-apa"),
    ],
  ),
  'CAT.Q8': TsQuestion(
    stem: (
      en: "A sudden loud bang (something drops, firecrackers). What does {pet} do?",
      id: "Tiba-tiba ada suara keras (barang jatuh, petasan), biasanya {pet} gimana?",
    ),
    options: [
      (en: "Jumps straight up and bolts to a hiding spot", id: "Loncat kaget, langsung kabur ke tempat ngumpet"),
      (en: "Visibly startled, then on high alert", id: "Kaget banget, terus waspada celingak-celinguk"),
      (en: "Looks up once", id: "Cuma angkat kepala sekali"),
      (en: "Barely reacts, keeps sleeping", id: "Hampir nggak bereaksi, lanjut tidur"),
    ],
  ),
  'CAT.Q9': TsQuestion(
    stem: (
      en: "New furniture, or things got moved around. What does {pet} do?",
      id: "Ada furnitur baru atau barang dipindah, biasanya {pet} gimana?",
    ),
    options: [
      (en: "Avoids it for days, clearly uneasy", id: "Berhari-hari muter menghindar, kelihatan gelisah"),
      (en: "Watches for a while before daring to go near", id: "Ngamatin dulu baru berani deketin"),
      (en: "A couple of sniffs and it's fine", id: "Endus dua kali, langsung oke"),
      (en: "Lies down on it right away", id: "Langsung rebahan di situ saat itu juga"),
    ],
  ),
  'CAT.Q10': TsQuestion(
    stem: (
      en: "After a scare, how long until {pet} is back to normal?",
      id: "Habis kaget, berapa lama {pet} balik normal?",
    ),
    options: [
      (en: "Half a day or more, on edge the whole time", id: "Setengah hari lebih, waspada terus"),
      (en: "An hour or two", id: "Satu-dua jam"),
      (en: "Ten-ish minutes", id: "Belasan menit"),
      (en: "Forgets almost instantly", id: "Hampir langsung lupa"),
    ],
  ),
  'CAT.Q11': TsQuestion(
    stem: (
      en: "A toy rolls into a gap it can't reach. What does {pet} do?",
      id: "Mainannya masuk celah yang nggak kejangkau, biasanya {pet} gimana?",
    ),
    options: [
      (en: "Keeps pawing until you come help", id: "Terus ngorek sampai kamu bantuin"),
      (
        en: "Tries again and again, gives up only when it really can't",
        id: "Nyoba berkali-kali, baru nyerah kalau emang nggak bisa",
      ),
      (en: "Tries twice, then walks off", id: "Nyoba dua kali, terus pergi"),
      (en: "One look, then acts like nothing happened", id: "Liat sekali, terus pura-pura nggak terjadi apa-apa"),
    ],
  ),
  'CAT.Q12': TsQuestion(
    stem: (
      en: "You're playing with {pet} using a teaser wand. How into it is {pet}?",
      id: "Kamu ajak {pet} main pakai tongkat mainan bulu, dia gimana?",
    ),
    options: [
      (en: "Locked on the whole time, not a second off", id: "Fokus melotot terus, nggak lepas sedetik pun"),
      (en: "Pretty into it, drifts off sometimes", id: "Cukup serius main, sesekali bengong"),
      (en: "Plays a bit, then lies down and watches", id: "Main sebentar, terus rebahan nonton"),
      (en: "Not interested, needs coaxing to move", id: "Ogah-ogahan, harus dibujuk dulu baru gerak"),
    ],
  ),
  'CAT.Q13': TsQuestion(
    stem: (
      en: "{pet} wants to get onto an off-limits spot. After you stop it, what does it do?",
      id: "{pet} mau naik ke tempat yang dilarang. Habis kamu larang, dia gimana?",
    ),
    options: [
      (
        en: "Goes back the moment you turn around, again and again",
        id: "Begitu kamu balik badan, naik lagi, berkali-kali",
      ),
      (en: "Tries once more a bit later", id: "Nyoba sekali lagi nanti"),
      (en: "Gets told once and stops", id: "Sekali ditegur, udah nggak naik lagi"),
      (en: "Wasn't that keen to begin with", id: "Dari awal juga nggak terlalu pengen"),
    ],
  ),
  'CAT.Q14': TsQuestion(
    stem: (
      en: "How much of the day is {pet} at peak energy?",
      id: "Dalam sehari, seberapa lama {pet} lagi aktif-aktifnya?",
    ),
    options: [
      (
        en: "Several bursts — could zoom off any time of day",
        id: "Beberapa kali, bisa tiba-tiba lari-larian kapan aja",
      ),
      (en: "One burst in the morning, one at night", id: "Sekali pagi, sekali malam"),
      (en: "Just one short burst a day", id: "Cuma sebentar dalam sehari"),
      (en: "Almost never has a real peak", id: "Hampir nggak pernah kelihatan aktif"),
    ],
  ),
  'CAT.Q15': TsQuestion(
    stem: (en: "How long does one play session last?", id: "Sekali main, {pet} bisa tahan berapa lama?"),
    options: [
      (en: "Half an hour and still wants more", id: "Setengah jam lebih, masih kurang"),
      (en: "Ten-ish minutes", id: "Belasan menit"),
      (en: "About five minutes", id: "Sekitar lima menit"),
      (en: "A couple of swats and it's lying down", id: "Main dikit, langsung rebahan"),
    ],
  ),
  'CAT.P1': _kP1,
  'CAT.P2': _kP2,
  'CAT.P3': _kP3,
  // ===== 狗套 §6.3 =====
  'DOG.Q1': TsQuestion(
    stem: (en: "A stranger walks in. {pet}'s first move?", id: "Ada orang asing masuk rumah, reaksi pertama {pet} gimana?"),
    options: [
      (en: "Jumps right on them, begging for pets", id: "Langsung loncat minta dielus"),
      (en: "Barks a couple of times, then goes to sniff", id: "Gonggong dikit, terus deketin buat endus"),
      (en: "Hides behind you and watches", id: "Ngumpet di belakang kamu sambil ngamatin"),
      (en: "Backs off far and won't come near", id: "Mundur jauh, nggak mau deket"),
    ],
  ),
  'DOG.Q2': TsQuestion(
    stem: (en: "On a walk you run into another dog. What does {pet} do?", id: "Lagi jalan-jalan ketemu anjing lain, biasanya {pet} gimana?"),
    options: [
      (en: "Gets excited from far away, wants to go over", id: "Dari jauh udah heboh pengen nyamperin"),
      (en: "Sniffs each other once they're close", id: "Kalau udah deket, saling endus"),
      (en: "Walks around them", id: "Jalan muter menghindar"),
      (en: "Clearly wants to get away fast", id: "Kelihatan pengen cepet-cepet pergi"),
    ],
  ),
  'DOG.Q3': TsQuestion(
    stem: (
      en: "While you're busy with your own stuff, where's {pet} usually?",
      id: "Pas kamu lagi sibuk sendiri, {pet} biasanya ada di mana?",
    ),
    options: [
      (en: "Glued to your feet, follows every move", id: "Nempel di kaki kamu, kamu gerak dikit langsung ngikut"),
      (en: "Lying next to you", id: "Tiduran di sebelah kamu"),
      (en: "Same room, but keeps some distance", id: "Satu ruangan, tapi jaga jarak"),
      (en: "Finds its own spot somewhere", id: "Cari tempat sendiri"),
    ],
  ),
  'DOG.Q4': TsQuestion(
    stem: (
      en: "On a walk, you take a route you've never taken. What does {pet} do?",
      id: "Jalan-jalan lewat rute yang belum pernah dilewatin, biasanya {pet} gimana?",
    ),
    options: [
      (en: "Excitedly pulls you forward", id: "Semangat narik kamu ke depan"),
      (en: "Happy to go, but sniffs all the way", id: "Mau jalan, tapi sambil ngendus-ngendus"),
      (en: "Hesitates, wants to turn back to the usual way", id: "Ragu, pengen balik ke rute biasa"),
      (en: "Clearly resists, pulls you back", id: "Jelas nolak, narik balik"),
    ],
  ),
  'DOG.Q5': TsQuestion(
    stem: (en: "You give {pet} a new toy. How does it react?", id: "Kamu kasih {pet} mainan baru, biasanya dia gimana?"),
    options: [
      (en: "Pounces on it right away", id: "Langsung diterkam dan diselidiki"),
      (en: "Sniffs it, then starts playing", id: "Diendus dulu, baru dimainin"),
      (en: "Takes a look and walks away", id: "Diliatin doang, terus ditinggal"),
      (en: "Only plays with its old one", id: "Tetap main yang lama aja"),
    ],
  ),
  'DOG.Q6': TsQuestion(
    stem: (
      en: "Something it's never seen (an umbrella, a suitcase, a robot vacuum). What does {pet} do?",
      id: "Ketemu barang yang belum pernah dilihat (payung, koper, robot vacuum), biasanya {pet} gimana?",
    ),
    options: [
      (en: "Has to go check it out properly", id: "Harus nyamperin dan ngecek sampai jelas"),
      (en: "Watches from a distance", id: "Ngamatin sambil jaga jarak"),
      (en: "Walks around it", id: "Jalan muter menghindar"),
      (en: "Backs away or hides", id: "Mundur atau ngumpet"),
    ],
  ),
  'DOG.Q7': TsQuestion(
    stem: (en: "New food or new treats. What does {pet} do?", id: "Ganti makanan atau snack baru, biasanya {pet} gimana?"),
    options: [
      (en: "Wants to try it right away", id: "Langsung pengen nyobain"),
      (en: "Sniffs it, then eats", id: "Diendus, terus dimakan"),
      (en: "Hesitates for ages before eating", id: "Ragu lama banget baru mau makan"),
      (en: "Won't eat it, only wants the old one", id: "Nggak mau makan, maunya yang lama"),
    ],
  ),
  'DOG.Q8': TsQuestion(
    stem: (en: "Thunder or firecrackers. What does {pet} do?", id: "Pas ada petir atau petasan, biasanya {pet} gimana?"),
    options: [
      (en: "Shakes, crawls into a corner or jumps on you", id: "Gemetar, nyelip ke pojok atau loncat ke kamu"),
      (en: "Visibly tense, paces back and forth", id: "Kelihatan tegang, mondar-mandir"),
      (en: "Listens alertly, but stays put", id: "Waspada dengerin, tapi diem"),
      (en: "Barely reacts", id: "Hampir nggak bereaksi"),
    ],
  ),
  'DOG.Q9': TsQuestion(
    stem: (
      en: "A new place (a new home, a friend's house). What does {pet} do?",
      id: "Di tempat baru (rumah baru, rumah temen), biasanya {pet} gimana?",
    ),
    options: [
      (en: "Can't relax for ages, stays right behind you", id: "Lama nggak bisa santai, ngintil kamu terus"),
      (en: "Explores carefully for a while, then settles", id: "Eksplor hati-hati dulu, baru bisa adaptasi"),
      (en: "A couple of laps and it's comfy", id: "Muter-muter bentar, langsung nyaman"),
      (en: "Acts like it's home wherever it goes", id: "Di mana aja kayak di rumah sendiri"),
    ],
  ),
  'DOG.Q10': TsQuestion(
    stem: (
      en: "After a scare, how long until {pet} is back to normal?",
      id: "Habis kaget, berapa lama {pet} balik normal?",
    ),
    options: [
      (en: "Half a day or more, clingy the whole time", id: "Setengah hari lebih, nempel terus"),
      (en: "An hour or two", id: "Satu-dua jam"),
      (en: "Ten-ish minutes", id: "Belasan menit"),
      (en: "Forgets almost instantly", id: "Hampir langsung lupa"),
    ],
  ),
  'DOG.Q11': TsQuestion(
    stem: (
      en: "The ball rolls under the sofa, out of reach. What does {pet} do?",
      id: "Bolanya masuk kolong sofa dan nggak kejangkau, biasanya {pet} gimana?",
    ),
    options: [
      (en: "Digs and barks nonstop until it's out", id: "Terus ngorek dan gonggong, pokoknya harus keluar"),
      (en: "Tries for ages, then comes to get you", id: "Nyoba lama, kalau nggak bisa baru nyari kamu"),
      (en: "Tries twice, then plays with something else", id: "Nyoba dua kali, terus main yang lain"),
      (en: "Takes one look and leaves", id: "Liat sekali, terus pergi"),
    ],
  ),
  'DOG.Q12': TsQuestion(
    stem: (en: "You're teaching {pet} a new command. How does it react?", id: "Pas diajarin perintah baru, biasanya {pet} gimana?"),
    options: [
      (en: "Keeps its eyes on you, waiting for what's next", id: "Natap kamu terus, nunggu langkah berikutnya"),
      (en: "Picks it up, gets distracted now and then", id: "Bisa nangkep, sesekali nggak fokus"),
      (en: "Zones out after a few tries", id: "Beberapa kali latihan udah bengong"),
      (en: "Won't cooperate unless there's a treat", id: "Nggak mau nurut kecuali ada snack"),
    ],
  ),
  'DOG.Q13': TsQuestion(
    stem: (
      en: "You're holding a treat but not giving it. What does {pet} do?",
      id: "Ada snack di tangan kamu tapi nggak dikasih, biasanya {pet} gimana?",
    ),
    options: [
      (en: "Sits and stares until it gets it", id: "Duduk natap terus sampai dapet"),
      (en: "Waits a bit, barks when impatient", id: "Nunggu sebentar, kalau nggak sabar gonggong"),
      (en: "Gives up if it doesn't get it", id: "Kalau nggak dapet, nyerah"),
      (en: "Quickly moves on to something else", id: "Cepet banget pindah ngapain yang lain"),
    ],
  ),
  'DOG.Q14': TsQuestion(
    stem: (
      en: "How much of the day is {pet} at peak energy?",
      id: "Dalam sehari, seberapa lama {pet} lagi aktif-aktifnya?",
    ),
    options: [
      (en: "Several bursts — can go wild any time", id: "Beberapa kali, bisa heboh kapan aja"),
      (en: "One burst on each morning and evening walk", id: "Pas jalan pagi dan jalan sore"),
      (en: "Just one short burst a day", id: "Cuma sebentar dalam sehari"),
      (en: "Pretty much calm all day", id: "Hampir selalu kalem"),
    ],
  ),
  'DOG.Q15': TsQuestion(
    stem: (en: "Back from a walk. What does {pet} do?", id: "Habis pulang jalan-jalan, biasanya {pet} gimana?"),
    options: [
      (en: "Rests a bit, then wants to play again", id: "Istirahat bentar, terus pengen main lagi"),
      (en: "Chills for a while, then moves around again", id: "Kalem sebentar, baru aktif lagi"),
      (en: "Flops down and sleeps", id: "Langsung rebahan tidur"),
      (en: "Wanted to go home halfway through", id: "Baru setengah jalan udah pengen pulang"),
    ],
  ),
  'DOG.P1': _kP1,
  'DOG.P2': _kP2,
  'DOG.P3': _kP3,
  // ===== 通用套 §6.4 =====
  'GENERAL.Q1': TsQuestion(
    stem: (en: "You reach into {pet}'s space. How does it react?", id: "Kamu masukin tangan ke area {pet}, biasanya dia gimana?"),
    options: [
      (en: "Comes right over", id: "Langsung nyamperin"),
      (en: "Watches a bit, then comes closer", id: "Ngamatin dulu, baru deketin"),
      (en: "Moves away but doesn't hide", id: "Menjauh, tapi nggak ngumpet"),
      (en: "Hides in the far back right away", id: "Langsung ngumpet paling dalam"),
    ],
  ),
  'GENERAL.Q2': TsQuestion(
    stem: (en: "You're watching {pet} from nearby. How does it react?", id: "Pas kamu ngeliatin {pet} dari dekat, biasanya dia gimana?"),
    options: [
      (en: "Heads your way", id: "Jalan ke arah kamu"),
      (en: "Carries on as usual, doesn't avoid you", id: "Aktivitas biasa, nggak menghindar"),
      (en: "Gets quieter", id: "Jadi lebih diem"),
      (en: "Freezes or hides", id: "Diem nggak gerak atau ngumpet"),
    ],
  ),
  'GENERAL.Q3': TsQuestion(
    stem: (en: "A stranger comes near {pet}'s space. How does it react?", id: "Ada orang asing deketin area {pet}, biasanya dia gimana?"),
    options: [
      (en: "Comes over, same as it does with you", id: "Nyamperin, sama kayak ke kamu"),
      (en: "Watches for a while first", id: "Ngamatin dulu sebentar"),
      (en: "Retreats to the far end", id: "Mundur ke ujung"),
      (en: "Hides immediately", id: "Langsung sembunyi"),
    ],
  ),
  'GENERAL.Q4': TsQuestion(
    stem: (en: "You put something new in {pet}'s space. How does it react?", id: "Ada barang baru ditaruh di area {pet}, biasanya dia gimana?"),
    options: [
      (en: "Goes to investigate right away", id: "Langsung nyamperin buat diselidiki"),
      (en: "Watches a while before going near", id: "Ngamatin dulu baru deketin"),
      (en: "Keeps its distance", id: "Jaga jarak"),
      (en: "Ignores it completely", id: "Cuek total"),
    ],
  ),
  'GENERAL.Q5': TsQuestion(
    stem: (en: "New food. What does {pet} do?", id: "Ganti makanan baru, biasanya {pet} gimana?"),
    options: [
      (en: "Tries it right away", id: "Langsung nyobain"),
      (en: "Hesitates a little, then eats", id: "Ragu sebentar, terus dimakan"),
      (en: "Takes ages before it'll eat", id: "Lama banget baru mau makan"),
      (en: "Won't eat it, only wants the old food", id: "Nggak mau makan, maunya yang lama"),
    ],
  ),
  'GENERAL.Q6': TsQuestion(
    stem: (
      en: "Its space gets bigger (a new area opens up). What does {pet} do?",
      id: "Area {pet} diperluas (ada bagian baru dibuka), biasanya dia gimana?",
    ),
    options: [
      (en: "Goes to explore the new area right away", id: "Langsung eksplor area baru"),
      (en: "Slowly widens its range", id: "Pelan-pelan ngeluasin jangkauannya"),
      (en: "Still sticks to its old spot", id: "Tetap di tempat lama"),
      (en: "Never goes into the new area", id: "Sama sekali nggak masuk area baru"),
    ],
  ),
  'GENERAL.Q7': TsQuestion(
    stem: (
      en: "Something familiar and something new, side by side. What does {pet} do?",
      id: "Barang lama dan barang baru ditaruh bareng di depannya, biasanya {pet} gimana?",
    ),
    options: [
      (en: "Goes for the new one first", id: "Milih yang baru duluan"),
      (en: "Checks out both", id: "Dua-duanya disentuh"),
      (en: "Goes for the familiar one first", id: "Milih yang lama duluan"),
      (en: "Only touches the familiar one", id: "Cuma nyentuh yang lama"),
    ],
  ),
  'GENERAL.Q8': TsQuestion(
    stem: (en: "A sudden loud bang. What does {pet} do?", id: "Tiba-tiba ada suara keras, biasanya {pet} gimana?"),
    options: [
      (en: "Bolts wildly, darting all over", id: "Kabur panik, lari ke mana-mana"),
      (en: "Visibly startled, then on guard", id: "Kaget banget, terus siaga"),
      (en: "Pauses, then carries on", id: "Berhenti sebentar, terus lanjut"),
      (en: "Barely reacts", id: "Hampir nggak bereaksi"),
    ],
  ),
  'GENERAL.Q9': TsQuestion(
    stem: (en: "Its space gets rearranged. What does {pet} do?", id: "Area {pet} ditata ulang, biasanya dia gimana?"),
    options: [
      (en: "Clearly uneasy for days", id: "Berhari-hari kelihatan gelisah"),
      (en: "Watches for a while, then adjusts", id: "Ngamatin dulu, baru bisa adaptasi"),
      (en: "Back to normal quickly", id: "Cepet balik kayak biasa"),
      (en: "Not bothered at all", id: "Sama sekali nggak terpengaruh"),
    ],
  ),
  'GENERAL.Q10': TsQuestion(
    stem: (
      en: "After a scare, how long until {pet} is back to normal?",
      id: "Habis kaget, berapa lama {pet} balik normal?",
    ),
    options: [
      (en: "Half a day or more", id: "Setengah hari lebih"),
      (en: "An hour or two", id: "Satu-dua jam"),
      (en: "Ten-ish minutes", id: "Belasan menit"),
      (en: "Almost instantly", id: "Hampir langsung"),
    ],
  ),
  'GENERAL.Q11': TsQuestion(
    stem: (
      en: "Food is placed somewhere it takes effort to reach. What does {pet} do?",
      id: "Makanan ditaruh di tempat yang butuh usaha buat diambil, biasanya {pet} gimana?",
    ),
    options: [
      (en: "Keeps trying until it gets it", id: "Nyoba terus sampai dapet"),
      (en: "Tries many times before giving up", id: "Nyoba berkali-kali baru nyerah"),
      (en: "Tries twice, then forgets about it", id: "Nyoba dua kali, terus dicuekin"),
      (en: "Hardly even tries", id: "Hampir nggak nyoba sama sekali"),
    ],
  ),
  'GENERAL.Q12': TsQuestion(
    stem: (en: "It's feeding time, but no food yet. What does {pet} do?", id: "Udah jam makan tapi belum dikasih, biasanya {pet} gimana?"),
    options: [
      (en: "Waits by the bowl and won't leave", id: "Nunggu di tempat makan, nggak mau pergi"),
      (en: "Keeps checking, but does other stuff too", id: "Bolak-balik ngecek, sambil ngapain yang lain"),
      (en: "Waits a bit, then wanders off", id: "Nunggu sebentar, terus pergi"),
      (en: "Barely reacts", id: "Nggak ada reaksi apa-apa"),
    ],
  ),
  'GENERAL.Q13': TsQuestion(
    stem: (
      en: "{pet} wants to go somewhere but the way is blocked. How does it react?",
      id: "{pet} mau ke suatu tempat tapi jalannya ketutup, biasanya dia gimana?",
    ),
    options: [
      (en: "Keeps trying to find a way", id: "Terus nyari jalan lain"),
      (en: "Tries a few times, then gives up", id: "Nyoba beberapa kali, terus nyerah"),
      (en: "Stops trying pretty fast", id: "Cepet banget berhenti nyoba"),
      (en: "Just settles somewhere else", id: "Langsung pindah ke tempat lain"),
    ],
  ),
  'GENERAL.Q14': TsQuestion(
    stem: (
      en: "Added up, how much of the day is {pet} active?",
      id: "Kalau ditotal, berapa lama {pet} aktif dalam sehari?",
    ),
    options: [
      (en: "On the move most of the day", id: "Hampir seharian gerak terus"),
      (en: "A few set stretches", id: "Ada beberapa waktu tetap"),
      (en: "Just one short stretch a day", id: "Cuma sebentar dalam sehari"),
      (en: "Rarely seen active", id: "Jarang banget kelihatan aktif"),
    ],
  ),
  'GENERAL.Q15': TsQuestion(
    stem: (en: "How long does one active stretch last?", id: "Sekali aktif, {pet} bisa tahan berapa lama?"),
    options: [
      (en: "Ages — can't stop", id: "Lama banget, nggak bisa berhenti"),
      (en: "Ten-ish minutes", id: "Belasan menit"),
      (en: "A few minutes", id: "Beberapa menit"),
      (en: "Stops almost right away", id: "Sebentar banget udah berhenti"),
    ],
  ),
  'GENERAL.P1': _kP1,
  'GENERAL.P2': _kP2,
  'GENERAL.P3': _kP3,
};
