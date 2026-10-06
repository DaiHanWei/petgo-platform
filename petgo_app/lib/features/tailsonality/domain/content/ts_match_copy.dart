import 'ts_text.dart';

/// Tailsonality 主人 × 宠物配型文案（V1.3.2 Story 2.2 · 内容设计 §4.3 · 决策 D-18）。
///
/// 相同字母数**只比四字母，不比能量后缀**（主人没有能量后缀）。
class TsMatchTier {
  const TsMatchTier({required this.name, required this.review, required this.summary, this.slogan});

  /// 档位名（D-18：以设计图为准，EN / ID 同名、专名不译）。
  final TsText name;

  /// 档位总评（配型页页内全文）。
  final TsText review;

  /// 对照图下方唯一的一条免费总结句。
  final TsText summary;

  /// 配型卡卡面 slogan（Jaksel 原文，不翻译；设计资产清单 §3，Story 4.2 消费）。
  final String? slogan;
}

/// 键 = 相同字母数 4..0。
// PENDING D-18: 文案待定稿 —— 档位总评 / 总结句 / 卡面 slogan 仍在讨论；总评、总结句先用内容设计 §4.3 现有文案，
// slogan 取自设计资产清单 §3（尚未回写内容设计）。定稿后只改这一块。
const Map<int, TsMatchTier> kTsMatchTiers = {
  4: TsMatchTier(
    name: (en: "Literally Twins", id: "Literally Twins"),
    review: (
      en: "Same soul, two bodies. {pet} even shares your flaws.",
      id: "Satu jiwa, dua badan. {pet} bahkan punya kekurangan yang sama.",
    ),
    summary: (en: "You two are literally the same person.", id: "Kalian literally orang yang sama."),
    slogan: "Literally me, tapi berbulu — sampai kebiasaan anehnya pun sama.",
  ),
  3: TsMatchTier(
    name: (en: "Twin Flames", id: "Twin Flames"),
    review: (en: "Basically the same, except for one letter.", id: "Basically sama, cuma beda satu huruf."),
    summary: (en: "So close — just one letter apart.", id: "Deket banget — cuma beda satu huruf."),
    slogan: "Almost me, kurang dikit — satu huruf itu justru plot twist-nya.",
  ),
  2: TsMatchTier(
    name: (en: "Backs Together", id: "Backs Together"),
    review: (
      en: "Half you, half its own thing. Just enough to get each other, just enough to roll your eyes.",
      id: "Setengah kamu, setengah dia sendiri. Cukup buat saling ngerti, cukup juga buat saling julid.",
    ),
    summary: (en: "Half the same, half not.", id: "Setengah sama, setengah enggak."),
    slogan: "Half me, half chaos — tapi anehnya kita tetep nyambung.",
  ),
  1: TsMatchTier(
    name: (en: "Counterweight", id: "Counterweight"),
    review: (
      en: "You two run in opposite directions — which is exactly why it does what you can't.",
      id: "Kalian jalan ke arah yang beda — which is justru kenapa dia bisa hal yang kamu nggak bisa.",
    ),
    summary: (en: "Almost total opposites — and that works.", id: "Hampir kebalikan total — dan itu justru cocok."),
    slogan: "Beda arah, same vibes — dan entah kenapa tetap klop.",
  ),
  0: TsMatchTier(
    name: (en: "Magnetic Poles", id: "Magnetic Poles"),
    review: (
      en: "Four letters, zero matches. The universe put you together for a reason.",
      id: "Empat huruf, nol yang sama. Semesta pasti ada maksudnya.",
    ),
    summary: (en: "Zero letters in common. Wow.", id: "Nol huruf yang sama. Wow."),
    slogan: "Zero match, full chemistry — dan itu bukan kebetulan.",
  ),
};

/// 8 条轴差异句（**配型卡卡面专用**，逐字取自内容设计 §4.3）。键 = 主人字母 + 宠物字母（与表头「你 X · 它 Y」同序）。
/// 卡面最多放 2 条，按固定优先级 E/I > T/F > J/P > N/S 取。
const Map<String, TsText> kTsAxisDiffLines = {
  'EI': (en: "You want to go out, it wants to go home.", id: "Kamu pengen keluar, dia pengen pulang."),
  'IE': (
    en: "You want quiet, it wants to meet everyone.",
    id: "Kamu pengen tenang, dia pengen kenalan sama semua orang.",
  ),
  'NS': (en: "You want new things, it wants the usual.", id: "Kamu pengen yang baru, dia maunya yang itu-itu aja."),
  'SN': (en: "You stick to the plan, it has to touch everything.", id: "Kamu ikut rencana, dia pengen nyoba semuanya."),
  'TF': (en: "You're fine, it's already in its feelings.", id: "Kamu santai, dia udah baper duluan."),
  'FT': (en: "You're in your feelings, it's asleep.", id: "Kamu lagi baper, dia lagi tidur."),
  'JP': (en: "You have a schedule, it has other plans.", id: "Kamu punya jadwal, dia punya rencana sendiri."),
  'PJ': (
    en: "You go with the flow, it reminds you when it's dinner time.",
    id: "Kamu santuy, dia yang ngingetin jam makan.",
  ),
};

/// 16 段逐轴详细解读（配型页页内，免费）。键 = 主人字母 + 宠物字母；拼装顺序固定 E/I → N/S → T/F → J/P。
const Map<String, TsText> kTsAxisDetails = {
  'EE': (
    en: "You two are the same kind of creature — any noise in the house and you both go look, any guest and you both rush forward. It's a lively combo, but it also means **nobody calms down first**. When {pet} gets overexcited, you're probably getting excited right along with it instead of settling it down.",
    id: "Kalian satu tipe — ada suara di rumah, dua-duanya nyamperin; ada tamu, dua-duanya maju duluan. Kombinasi ini seru, tapi juga artinya **nggak ada yang tenang duluan**. Pas {pet} kelewat heboh, kemungkinan besar kamu malah ikut heboh bareng dia, bukannya nenangin.",
  ),
  'EI': (
    en: "You want to go out, it wants to go home. Bringing friends over is socializing for you — for it, it's an invasion. **It's not hiding because it doesn't love you; it's recharging.** The key is not to drag it out to 'say hi to everyone' — what it needs isn't practice, it's a corner you'll never bring people to.",
    id: "Kamu pengen keluar, dia pengen pulang. Buat kamu, ngajak temen ke rumah itu sosialisasi — buat dia, itu invasi. **Dia ngumpet bukan karena nggak sayang, tapi lagi ngecas.** Kuncinya, jangan tarik dia keluar buat 'kenalan sama semua orang' — yang dia butuh bukan dibiasain, tapi satu pojok yang nggak bakal kamu datengin bareng orang lain.",
  ),
  'IE': (
    en: "You want quiet, it wants to meet the whole world. The moment you're recharging alone is exactly when it needs interaction most — a mismatch that makes you find it annoying and makes it find you cold. **The middle ground is a fixed, high-intensity play slot** — it wants intensity, not hours. Ten minutes of full attention beats an afternoon of half-hearted company.",
    id: "Kamu pengen tenang, dia pengen kenalan sama seluruh dunia. Pas kamu lagi ngecas sendirian, justru itu waktu dia paling butuh interaksi — beda ritme ini bikin kamu ngerasa dia ganggu, dan dia ngerasa kamu dingin. **Jalan tengahnya: kasih dia jam main khusus yang intens** — yang dia mau itu kualitas, bukan durasi. Sepuluh menit main dengan perhatian penuh lebih ngena daripada seharian nemenin sambil bengong.",
  ),
  'II': (
    en: "Neither of you needs much interaction to feel close. It's the easiest combo — same room, each doing your own thing, and it's a good time for both of you. **The only risk is too much quiet**: {pet} doesn't ask, you don't offer, and over time there's so little interaction that when it really needs you, you might not notice.",
    id: "Kalian berdua nggak butuh banyak interaksi buat ngerasa deket. Ini kombinasi paling nggak ribet — satu ruangan, sibuk masing-masing, dan dua-duanya happy. **Satu-satunya risiko: kelewat sepi**. {pet} nggak minta, kamu juga nggak ngasih, lama-lama interaksinya makin dikit sampai pas dia beneran butuh, kamu nggak sadar.",
  ),
  'NN': (
    en: "You love buying new things, it loves taking them apart — neither of you can resist 'never seen that before'. The house will always have new toys, new decor, new routes. **The risk is an ever-rising bar**: you'll raise its threshold for novelty, and ordinary toys will stop doing it for it pretty fast.",
    id: "Kamu hobi beli barang baru, dia hobi bongkar barang baru — kalian berdua nggak kuat sama yang 'belum pernah lihat'. Rumah bakal selalu ada mainan baru, pajangan baru, rute baru. **Risikonya, standarnya naik terus**: ambang rasa penasarannya jadi makin tinggi gara-gara kamu, dan mainan biasa cepet banget nggak mempan lagi.",
  ),
  'NS': (
    en: "You want to try new things, it only wants the usual. The new bed you excitedly brought home? It might not even look at it and keep sleeping on the flattened old cushion. **It's not being ungrateful** — familiar smells are what safety feels like to it. If you're switching things out, do it slowly, and leave one old thing nearby.",
    id: "Kamu pengen nyoba yang baru, dia maunya yang itu-itu aja. Kasur baru yang kamu beli dengan semangat? Bisa-bisa nggak dilirik sama sekali, dia tetap tidur di bantal lama yang udah kempes. **Ini bukan nggak tahu terima kasih** — bau yang familiar itu rasa aman buat dia. Kalau mau ganti, pelan-pelan aja, dan taruh satu barang lama di sebelahnya.",
  ),
  'SN': (
    en: "You stick to the plan, it has to touch everything. You want life steady; it insists on adding variables — raiding cabinets, crawling into boxes, investigating whatever you just put down. **It isn't making trouble; it's looking for something to do.** Rather than stopping it all the time, give it one new thing a week to focus on — it costs less than cleaning up after it.",
    id: "Kamu ikut rencana, dia pengen nyoba semuanya. Kamu mau hidup stabil, dia malah bikin kejutan — ngacak lemari, nyelip ke kardus, nyelidikin barang yang baru kamu taruh. **Dia bukan bikin ulah, dia lagi cari kerjaan.** Daripada ngelarang terus, mending kasih satu barang baru tiap minggu buat ngalihin perhatiannya — lebih murah daripada beresin kekacauannya.",
  ),
  'SS': (
    en: "You both like things 'just the way they were'. The house stays in steady order, and that's comfortable for both of you. **Watch out for when change comes** — moving, renovations, someone moving in for a while: you'll both struggle at the same time, and nobody's there to keep things steady. Starting the transition a few days early is easier than toughing it out on the day.",
    id: "Kalian berdua suka yang 'kayak biasa aja'. Tatanan rumah stabil terus, dan itu nyaman buat kalian berdua. **Yang perlu diwaspadai itu pas ada perubahan** — pindahan, renovasi, ada orang nginep lama: kalian bakal sama-sama kewalahan, dan nggak ada yang bisa jadi penenang. Mulai transisi beberapa hari sebelumnya jauh lebih gampang daripada maksain di hari-H.",
  ),
  'TT': (
    en: "Neither of you scares easily, so the house rarely descends into chaos. It's a stable, easy combo. **The trade-off is that both of your emotional signals are faint** — {pet} won't make a fuss when it feels unwell, and you're not someone who watches for tiny changes, so health issues may get spotted late. Regular checkups are more reliable than just watching.",
    id: "Kalian berdua nggak gampang kaget, jadi rumah jarang heboh. Kombinasi ini stabil dan gampang diurus. **Harganya: sinyal emosi kalian sama-sama lemah** — {pet} nggak bakal heboh kalau lagi nggak enak badan, dan kamu juga bukan tipe yang merhatiin perubahan kecil, jadi masalah kesehatan bisa ketahuan telat. Cek rutin lebih bisa diandelin daripada cuma ngandelin pengamatan.",
  ),
  'TF': (
    en: "You're fine, it's already in its feelings. The same loud bang: you glance up, it needs half a day to recover. **It's easy for you to underestimate how strongly it reacts** and think 'what's there to be scared of?'. What it wants isn't comfort, it's predictability — a fixed routine and a hiding spot nobody disturbs work better than a cuddle after the fact.",
    id: "Kamu santai, dia udah baper duluan. Suara keras yang sama: kamu cuma nengok, dia butuh setengah hari buat pulih. **Kamu gampang ngeremehin seberapa kuat reaksinya**, mikir 'apaan sih yang ditakutin'. Yang dia butuh bukan dihibur, tapi kepastian — rutinitas yang tetap dan tempat ngumpet yang nggak diganggu lebih ampuh daripada dipeluk setelahnya.",
  ),
  'FT': (
    en: "You're in your feelings, it's asleep. You wish it could sense your mood, but it's the type that keeps eating even if the sky falls. **Don't read that as not caring** — its way of caring is quiet, maybe just staying in the same room. It can't give you emotional echo, but it can give you steadiness.",
    id: "Kamu lagi baper, dia lagi tidur. Kamu pengen dia ngerti perasaan kamu, tapi dia tipe yang tetap makan santai walaupun langit runtuh. **Jangan artiin itu nggak peduli** — cara dia peduli itu kalem, mungkin cuma dengan tetap di ruangan yang sama. Dia nggak bisa ikut baper bareng kamu, tapi dia bisa kasih ketenangan.",
  ),
  'FF': (
    en: "You're both sensitive, both quick to pick up on each other's feelings. That means {pet} really gets you — **but it also means it soaks up your bad moods just as they are**. On the days you're anxious, it's probably having a rough time too. Sometimes the first step in taking care of it is taking care of yourself.",
    id: "Kalian sama-sama sensitif, sama-sama gampang nangkep perasaan satu sama lain. Artinya {pet} beneran ngerti kamu — **tapi juga artinya mood jelek kamu bakal dia serap mentah-mentah**. Pas kamu lagi cemas, kemungkinan besar dia juga lagi nggak baik-baik aja. Kadang, langkah pertama ngerawat dia itu ngerawat diri kamu dulu.",
  ),
  'JJ': (
    en: "You have a schedule, and so does it. When the two line up, everything runs smoothly; when they don't, it's a standoff where neither gives in. **{pet}'s persistence won't disappear just because you're persistent too** — it'll just remind you more punctually. Instead of competing over who's more stubborn, build its routine into yours from the start.",
    id: "Kamu punya jadwal, dia juga punya. Kalau dua jadwal itu cocok, semuanya lancar; kalau nggak, jadi adu keras kepala yang nggak ada yang mau ngalah. **Kegigihan {pet} nggak bakal hilang cuma karena kamu juga gigih** — dia cuma bakal ngingetin kamu makin tepat waktu. Daripada adu siapa paling ngotot, mending dari awal masukin rutinitasnya ke jadwal kamu.",
  ),
  'JP': (
    en: "You have a plan, it has its own ideas. You want walks and training on time; it wants to chase that leaf. **It isn't disobeying; it genuinely forgot.** With a pet like this, repetition works better than strictness — teaching the same thing twenty times is far more realistic than getting it right in one go.",
    id: "Kamu punya rencana, dia punya maunya sendiri. Kamu mau jalan-jalan dan latihan tepat waktu, dia maunya ngejar daun itu. **Dia bukan bandel, dia beneran udah lupa.** Buat hewan kayak gini, diulang-ulang lebih ampuh daripada galak — ngajarin hal yang sama dua puluh kali jauh lebih realistis daripada berharap sekali langsung bisa.",
  ),
  'PJ': (
    en: "You go with the flow, it reminds you when it's dinner time. **The one in this house who's actually punctual is {pet}.** That's a good thing — it keeps the rhythm steady for you. Just don't let its reminders turn into anxiety: being late once in a while is fine, but a chronically irregular routine keeps a pet like this in a constant state of waiting.",
    id: "Kamu santuy, dia yang ngingetin jam makan. **Yang beneran tepat waktu di rumah ini justru dia.** Sebenarnya ini bagus — dia yang jagain ritme buat kamu. Tapi jangan sampai ngingetinnya berubah jadi cemas: sesekali telat nggak apa-apa, tapi kalau nggak teratur terus, hewan tipe ini bakal terus-terusan dalam mode nunggu.",
  ),
  'PP': (
    en: "You both live in the moment — no strict schedule at home, easygoing, relaxed, no pressure on each other. **The only thing to watch is the stuff that can't be left to the moment** — meds, deworming, vaccines, checkups. They don't stop mattering just because neither of you is thinking about them. Leave them to an alarm, not to memory.",
    id: "Kalian berdua hidup buat saat ini — nggak ada jadwal ketat di rumah, santai, ringan, nggak saling nuntut. **Satu-satunya yang perlu diperhatiin: hal-hal yang nggak bisa disantaiin** — kasih obat, obat cacing, vaksin, cek kesehatan. Semua itu tetap penting walaupun kalian berdua nggak kepikiran. Serahin ke alarm, jangan ke ingatan.",
  ),
};
