import 'ts_text.dart';

/// Tailsonality 维度解读与能量段（V1.3.2 Story 2.2 · 内容设计 §5.4 / §4.2）。
///
/// 维度解读 8 段（付费），按宠物的四个字母各取一段拼成「完整四维度解读」。
const Map<String, TsText> kTsDimensionReadings = {
  'E': (
    en: "{pet} charges up on other people. Guests at home, noise outside, you busy with something else — it has to be part of it. Being alone isn't rest for it, it's waiting. Which also means that when it's left alone too long, it'll remind you in ways you'd rather not see.",
    id: "Energi {pet} datang dari orang lain. Ada tamu, ada suara di luar, kamu lagi sibuk sendiri — dia harus ikut nimbrung. Sendirian buat dia bukan istirahat, tapi nunggu. Artinya, kalau kelamaan ditinggal, dia bakal ngingetin kamu dengan cara yang kamu nggak pengen lihat.",
  ),
  'I': (
    en: "{pet} charges up alone. It's not that it doesn't love you — socializing just costs it something. When the room fills up it slips away, not out of fear, but to save battery. A corner where nobody bothers it does more than an extra half hour of your company.",
    id: "Energi {pet} datang dari waktu sendiri. Bukan karena nggak sayang kamu — cuma, bersosialisasi itu ada harganya buat dia. Pas rame, dia mundur bukan karena takut, tapi lagi hemat baterai. Kasih dia satu pojok yang nggak diganggu, itu lebih berguna daripada nemenin dia setengah jam lebih lama.",
  ),
  'N': (
    en: "To {pet}, new things are a draw, not a threat. Rearranged furniture, a new toy, a route it's never taken — its first instinct is to go take a look. What this kind of pet fears isn't change, it's boredom: if nothing changes for too long, it'll find something to do on its own, and you might not love what it finds.",
    id: "Buat {pet}, hal baru itu menarik, bukan ancaman. Barang dipindah, mainan baru, rute yang belum pernah dilewatin — reaksi pertamanya pasti nyamperin buat ngecek. Yang paling dia takutin bukan perubahan, tapi bosen: kalau lingkungannya itu-itu aja, dia bakal cari kerjaan sendiri, dan belum tentu kamu suka kerjaan yang dia pilih.",
  ),
  'S': (
    en: "{pet} wants things to stay the way they were. Familiar spots, familiar smells, familiar routines — that's where it feels safe. Moving house, switching food, rearranging the room: none of these are small to it. When change has to happen, go slow, and keep one old thing around.",
    id: "{pet} maunya semua tetap kayak biasa. Tempat yang familiar, bau yang familiar, rutinitas yang familiar — dari situ rasa amannya datang. Pindahan, ganti makanan, nata ulang ruangan, buat dia semuanya bukan hal kecil. Kalau memang harus berubah, pelan-pelan aja, dan sisain satu barang lama.",
  ),
  'T': (
    en: "Not much really rattles {pet}. Loud bangs, guests, new places — one look up and it's over. That makes it easy to live with and hard to stress out, but it's also easy to misread as 'not affectionate'. It isn't indifferent; it just doesn't perform. The way it shows it cares is quieter than you think.",
    id: "Jarang ada yang bisa bikin {pet} kaget beneran. Suara keras, tamu, tempat baru — dia cuma angkat kepala bentar, terus lanjut lagi. Ini bikin dia gampang diurus dan nggak gampang stres, tapi juga gampang disangka 'nggak manja'. Dia bukan cuek, cuma nggak suka drama. Cara dia nunjukin sayang lebih kalem dari yang kamu kira.",
  ),
  'F': (
    en: "{pet} wears its feelings on its body. Sounds, the mood in the room, how you're doing — it picks all of it up, and turns the volume up. That's its gift: it knows you're upset before anyone else. It's also its burden: it can't pretend a bad day didn't happen. What it needs is a predictable world, not more comforting.",
    id: "Perasaan {pet} kelihatan jelas di badannya. Suara, suasana, mood kamu — semuanya dia tangkep, dan dia besar-besarin. Itu bakatnya: dia yang pertama tahu kalau kamu lagi nggak happy. Itu juga bebannya: dia nggak bisa pura-pura hari yang buruk nggak terjadi. Yang dia butuh itu lingkungan yang bisa ditebak, bukan dihibur terus.",
  ),
  'J': (
    en: "Once {pet} has decided on something, it doesn't change its mind halfway. If it can't reach what it wants, it keeps trying; when it's dinner time, it reminds you right on the dot. That persistence makes it easy to train — and means that once a habit sticks, it's hard to undo. Including the ones you'd rather it didn't have.",
    id: "Kalau {pet} udah mau sesuatu, dia nggak bakal berubah pikiran di tengah jalan. Barang yang dia mau nggak kejangkau, dia coba terus; udah jam makan, dia ngingetin kamu tepat waktu. Kegigihan ini bikin dia gampang dilatih — tapi juga artinya, sekali kebiasaan terbentuk, susah diubah. Termasuk kebiasaan yang sebenarnya nggak kamu pengen.",
  ),
  'P': (
    en: "{pet} lives in the moment. A second ago it was shredding a delivery box; now it's chasing a shadow. You can't tie it down with a goal, because its attention always belongs to whatever showed up last. That keeps it happy and easy to please — just don't expect it to remember the rule you taught it yesterday.",
    id: "{pet} hidup buat saat ini. Barusan lagi bongkar kardus paket, sekarang udah ngejar bayangan. Kamu nggak bisa ngiket dia pakai satu tujuan, karena perhatiannya selalu buat hal paling baru yang muncul. Ini bikin dia happy dan gampang puas — tapi jangan harap dia inget aturan yang kamu ajarin kemarin.",
  ),
};

/// 能量后缀：`label` 逐字取自内容设计 §4.2；`line` 是能量段正文。
// PENDING D-19: 能量段正文待提供 —— 先用 §4.2「一句话」列占位，定稿后只改 `line`。
const Map<String, ({TsText label, TsText line})> kTsEnergy = {
  'H': (
    label: (en: "High energy", id: "Energi tinggi"),
    line: (
      en: "Battery always full — wakes up and zooms off.",
      id: "Baterai selalu penuh — bangun tidur langsung ngacir.",
    ),
  ),
  'L': (
    label: (en: "Low energy", id: "Energi rendah"),
    line: (
      en: "Why sit when you can lie down? Why today when there's tomorrow?",
      id: "Kalau bisa rebahan, ngapain duduk. Kalau bisa besok, ngapain sekarang.",
    ),
  ),
};
