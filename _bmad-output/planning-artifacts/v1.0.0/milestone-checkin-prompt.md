# TailTopia 里程碑打卡引导文案
**版本：** V1.0.0  
**用途：** 用户点击灰色（未完成）徽章后弹出的卡片文案，引导用户确认是否已经经历过该里程碑  
**语气定位：** 活泼有活人感，像朋友在问你「诶这件事发生了没？来打卡啊！」  
**占位符：** `[nama hewan]` = 宠物名（印尼语版）/ `[pet name]` = 宠物名（英语版）

---

## 卡片结构说明

```
┌─────────────────────────────────────┐
│  [徽章图标（灰色）]  里程碑名称        │
│                                     │
│  ← Header：一句轻量提问              │
│  Body：2–3行，描述这个里程碑的       │
│         独特感和"为什么值得记录"     │
│                                     │
│  [CTA 1 主按钮] 已经经历过           │
│  [CTA 2 次按钮] 还没有，去拍         │
└─────────────────────────────────────┘
```

**标准 CTA 按钮文案（全局通用）**

| 场景 | CTA 1（主，已经历） | CTA 2（次，去记录） |
|------|---------------------|---------------------|
| S 级里程碑 | 🇮🇩 `Udah pernah! Pilih fotonya ✓` | 🇮🇩 `Belum, yuk abadikan dulu 📸` |
| S 级里程碑 | 🇬🇧 `Already done! Pick a photo ✓` | 🇬🇧 `Not yet, let's capture it 📸` |
| M 级里程碑 | 🇮🇩 `Sudah! Ceritakan momennya 🎉` | 🇮🇩 `Belum, yuk rekam sekarang 📸` |
| M 级里程碑 | 🇬🇧 `Done! Mark this moment 🎉` | 🇬🇧 `Not yet, let's record it now 📸` |

> CTA 以下的「已打卡」流程和「去发布」流程详见 FR-42。

---

---

# 🐱 猫咪打卡引导（Cat）

---

## S 级（8 个）

---

### C-S6｜第一次洗澡

**🇮🇩**
> **Mandi bersejarah [nama hewan] pernah terjadi? 🛁**
> Biasanya lebih banyak air yang nyiprat ke kamu daripada ke [nama hewan]. Tapi apapun yang terjadi, ini momen yang gak bisa dilupain dan layak diabadikan.

**🇬🇧**
> **Has [pet name]'s legendary first bath happened yet? 🛁**
> Usually ends with you getting wetter than [pet name]. But whatever went down, it's a moment worth keeping.

---

### C-S7｜第一次修剪指甲

**🇮🇩**
> **Kuku [nama hewan] udah pernah dipotong? ✂️**
> Butuh kesabaran ekstra dan sedikit doa supaya gak kena kulit — tapi kalau berhasil, itu achievement tersendiri. Udah pernah lakuin ini?

**🇬🇧**
> **Has [pet name] had their first nail trim? ✂️**
> It takes extra patience and a little prayer not to hit the quick — but when it works, it's its own kind of achievement. Has this happened yet?

---

### C-S8｜第一次�akan零食

**🇮🇩**
> **[nama hewan] udah coba camilan pertama? 😋**
> Ekspresi pertama kali [nama hewan] dapet snack itu gak bisa bohong — pure happiness yang gak bisa direkayasa. Setelah ini, siap-siap deh selalu dimata-matain waktu kamu makan.

**🇬🇧**
> **Has [pet name] tried their first snack yet? 😋**
> The look on [pet name]'s face the first time they got a treat is impossible to fake — pure, unfiltered joy. After this, get ready to be watched every time you eat.

---

### C-S9｜第一次睡dalam你身边（被记录）

**🇮🇩**
> **[nama hewan] pernah tidur di sebelah kamu dan ke-foto? 🌙**
> Level ketenangan tidur bareng [nama hewan] itu susah banget dijelasin ke orang yang belum pernah ngerasain. Kalau momen ini udah pernah kejadian, tandai sekarang.

**🇬🇧**
> **Has [pet name] ever slept beside you and got captured on camera? 🌙**
> The kind of peace that comes from sleeping next to [pet name] is nearly impossible to explain to anyone who hasn't felt it. If this has happened, mark it now.

---

### C-S10｜第一次发出咕噜声（被记录）

**🇮🇩**
> **[nama hewan] pernah nge-purr di dekat kamu? 😻**
> Suara purr pertama [nama hewan] itu artinya satu hal: kamu berhasil bikin [nama hewan] beneran nyaman. Ini bukan hal kecil. Kalau udah pernah ke-rekam, ini waktunya diabadikan.

**🇬🇧**
> **Has [pet name] ever purred near you — and was it caught on camera? 😻**
> That first purr means one thing: you actually made [pet name] feel genuinely at home. That's not nothing. If it's ever been recorded, now's the time to mark it.

---

### C-S11｜第一次dalam窗边晒太阳

**🇮🇩**
> **[nama hewan] udah nemu spot jemur favoritnya? ☀️**
> Posisi sempurna, tampang puas, sinar matahari menerpa wajah — ini mode relaksasi tertinggi yang pernah [nama hewan] capai. Udah pernah ke-foto?

**🇬🇧**
> **Has [pet name] found their favorite sunbathing spot? ☀️**
> Perfect pose, satisfied face, sunlight hitting just right — this is [pet name]'s peak relaxation mode. Has this ever been caught on camera?

---

### C-S12｜第一次玩逗猫棒

**🇮🇩**
> **[nama hewan] udah pernah main mainan? 🎣**
> Pertama kali mainan muncul dan mode berburu [nama hewan] langsung aktif — responsnya gak bisa dibohongin. Fun fact: sejak saat itu, tangan kamu juga masuk kategori "mangsa".

**🇬🇧**
> **Has [pet name] played with a toy for the first time? 🎣**
> The moment a toy appears and [pet name]'s hunt mode switches on — that reaction cannot be faked. Fun fact: since then, your hand has also been classified as "prey."

---

### C-S13｜第一次钻进纸箱

**🇮🇩**
> **[nama hewan] udah pernah masuk kardus? 📦**
> Kalau muat, harus masuk — itu hukum alam yang berlaku buat semua kucing di dunia tanpa terkecuali. Pertama kali [nama hewan] buktiin ini, ekspresinya layak diabadikan.

**🇬🇧**
> **Has [pet name] ever climbed into a cardboard box? 📦**
> If it fits, they sit — a universal cat law with zero exceptions. The first time [pet name] proved this, that expression was worth capturing forever.

---

## M 级（8 个）

---

### C-M1｜第一次出门探险

**🇮🇩**
> **[nama hewan] udah pernah keluar rumah untuk pertama kalinya? 🌏**
> Angin, suara baru, bau yang beda dari biasanya — [nama hewan] ngerasain semua itu untuk pertama kali. Ekspresi pertamanya waktu itu gak bisa direkayasa dan gak bisa diulang.
> Udah pernah terjadi?

**🇬🇧**
> **Has [pet name] ventured outside for the very first time? 🌏**
> New wind, new sounds, new smells — [pet name] experienced all of it at once for the first time. That first expression cannot be staged, and it cannot be repeated.
> Has this happened yet?

---

### C-M2｜第一次坐车

**🇮🇩**
> **[nama hewan] udah pernah naik kendaraan? 🚗**
> Panik, kalem, atau penasaran nempel-nempel di jendela — reaksi pertama [nama hewan] di dalam kendaraan itu selalu unik dan gak ada yang sama. Udah pernah terjadi?

**🇬🇧**
> **Has [pet name] ever been in a vehicle for the first time? 🚗**
> Panicked, chill, or nose-pressed-to-the-window curious — every pet's first car ride reaction is unique and happens exactly once. Has [pet name]'s happened yet?

---

### C-M3｜完成第一次疫苗接种

**🇮🇩**
> **[nama hewan] udah vaksin pertama? 💉**
> Gak semua orang mau repot-repot lakuin ini, tapi kamu mau — dan itu yang bikin beda. Vaksin pertama [nama hewan] adalah bukti nyata kamu ngurusin [nama hewan] dengan serius.
> Udah pernah dilakuin?

**🇬🇧**
> **Has [pet name] had their first vaccine? 💉**
> Not everyone bothers, but you do — and that makes all the difference. [Pet name]'s first vaccine is real proof you take care of [pet name] for the long run.
> Has this been done?

---

### C-M4｜完成第一次驱虫

**🇮🇩**
> **[nama hewan] udah pernah dikasih obat cacing? 🌿**
> Hal yang gak keliatan tapi penting banget — ini bukti nyata kamu ngurusin kesehatan [nama hewan] sampai ke detail yang banyak orang skip. Kalau udah pernah lakuin, tandai sekarang.

**🇬🇧**
> **Has [pet name] had their first deworming? 🌿**
> The kind of care that's invisible but vital — real proof you look after [pet name]'s health down to the details most people skip. If you've done this, mark it now.

---

### C-M5｜第一次看兽医

**🇮🇩**
> **[nama hewan] udah pernah ke dokter hewan? 🩺**
> Kunjungan perdana ke dokter hewan itu butuh niat, waktu, dan usaha ekstra — tapi ini salah satu tindakan kasih sayang terbesar yang bisa kamu kasih ke [nama hewan].
> Udah pernah terjadi?

**🇬🇧**
> **Has [pet name] been to the vet for the first time? 🩺**
> That first vet visit takes real intention, time, and extra effort — but it's one of the biggest acts of care you can give [pet name].
> Has this happened yet?

---

### C-M6｜第一次见到其他猫咪

**🇮🇩**
> **[nama hewan] udah pernah ketemu kucing lain? 🐱**
> Drama atau langsung akrab — dua kemungkinan yang sama-sama menarik. Apapun yang terjadi, pertemuan pertama [nama hewan] dengan sesama kucing itu selalu momen yang gak terlupakan.
> Udah pernah?

**🇬🇧**
> **Has [pet name] ever met another cat? 🐱**
> Drama or instant friendship — both are equally worth seeing. Either way, [pet name]'s first encounter with a fellow feline is always a moment you don't forget.
> Has this happened yet?

---

### C-M7｜学会回应自己的名字

**🇮🇩**
> **[nama hewan] udah bisa kenal namanya sendiri? 🔔**
> Kamu panggil, [nama hewan] noleh — kelihatan sepele, tapi ini tanda kepercayaan yang dalam. Ini momen kamu sadar kalian beneran udah terhubung.
> Udah pernah terjadi?

**🇬🇧**
> **Does [pet name] respond when you call their name? 🔔**
> You call, [pet name] turns — looks small, but it's a sign of deep trust. This is the moment you realize the two of you are genuinely connected.
> Has this happened yet?

---

### C-M9｜完成绝育手术

**🇮🇩**
> **[nama hewan] udah selesai operasi? 🏥**
> Ini keputusan yang gak gampang dan gak murah, tapi kamu ambil demi kesehatan jangka panjang [nama hewan]. Bukan hal kecil — ini bukti nyata kamu pemilik yang beneran bertanggung jawab.
> Udah pernah dilakuin?

**🇬🇧**
> **Has [pet name] had their surgery? 🏥**
> Not an easy or cheap decision, but you made it for [pet name]'s long-term health. Not a small thing — this is real proof you're a genuinely responsible owner.
> Has this been done?

---

---

# 🐶 狗狗打卡引导（Dog）

---

## S 级（8 个）

---

### D-S6｜第一次洗澡

**🇮🇩**
> **Mandi bersejarah [nama hewan] udah pernah terjadi? 🛁**
> Ada yang langsung enjoy, ada yang kabur-kaburan — tapi apapun reaksi [nama hewan], mandi pertama itu selalu lebih ribut dari yang kamu bayangkan dan lebih lucu dari yang bisa dijelasin.

**🇬🇧**
> **Has [pet name]'s legendary first bath happened yet? 🛁**
> Some dogs love it, some make it an event — but either way, the first bath is always louder than expected and funnier than words can describe.

---

### D-S7｜第一次美容/梳毛

**🇮🇩**
> **[nama hewan] udah pernah grooming pertama kali? ✂️**
> Penampilan baru sehabis grooming pertama itu selalu bikin gemes — bersih, rapi, dan sedikit bingung sama perubahan yang dia sendiri belum ngerti. Udah pernah?

**🇬🇧**
> **Has [pet name] had their first grooming session? ✂️**
> The post-grooming glow-up is always adorable — clean, neat, and slightly confused about the whole transformation. Has this happened yet?

---

### D-S8｜第一次吃零食

**🇮🇩**
> **[nama hewan] udah coba camilan pertama? 😋**
> Dari sini, kamu punya senjata rahasia untuk panggil [nama hewan] kapanpun. Ekspresi bahagia pertama kali dapet snack itu terlalu polos untuk gak diabadikan.

**🇬🇧**
> **Has [pet name] tried their first treat yet? 😋**
> From this point on, you have a secret weapon to call [pet name] anytime. That pure happy face the first time they get a treat is too genuine not to capture.

---

### D-S9｜第一次睡dalam你身边

**🇮🇩**
> **[nama hewan] pernah tidur di sebelah kamu dan ke-foto? 🌙**
> Dipilih jadi tempat tidur favorit [nama hewan] adalah kepercayaan paling tulus yang bisa dikasih seekor anjing — tanpa syarat, tanpa alasan lain selain nyaman sama kamu.
> Udah pernah terjadi?

**🇬🇧**
> **Has [pet name] ever slept beside you and got captured? 🌙**
> Being chosen as [pet name]'s favorite sleeping spot is the most sincere trust a dog can give — no conditions, no other reason except feeling safe with you.
> Has this happened yet?

---

### D-S10｜第一次摇尾巴（被记录）

**🇮🇩**
> **Kibasan ekor [nama hewan] pernah ke-foto atau ke-video? 🐕**
> Ekor yang bergoyang cuma punya satu makna: [nama hewan] bahagia ada kamu. Pertama kali momen ini berhasil terekam, itu jadi kenangan yang gak bisa digantiin.

**🇬🇧**
> **Has [pet name]'s tail wag ever been caught on camera? 🐕**
> A wagging tail has only one meaning: [pet name] is happy you exist. The first time that moment gets captured, it becomes a memory nothing can replace.

---

### D-S11｜第一次戴项圈/牵引绳

**🇮🇩**
> **[nama hewan] udah pernah pakai kalung atau tali pertama kali? 🏷️**
> Momen kecil yang artinya besar — dari sini, petualangan bareng [nama hewan] di luar bisa resmi dimulai. Ekspresi pertama kali [nama hewan] ngerasain ini biasanya unik banget.

**🇬🇧**
> **Has [pet name] worn their first collar or leash yet? 🏷️**
> A small moment that means a lot — from here, real adventures outside with [pet name] can officially begin. That first reaction is usually pretty unique.

---

### D-S12｜第一次玩球

**🇮🇩**
> **[nama hewan] udah pernah main bola? ⚽**
> Bola bergulir, [nama hewan] langsung ngejar — naluri terbaik yang gak perlu diajarin. Momen pertama ini biasanya lebih spontan dari yang kamu perkirain, dan lebih seru.

**🇬🇧**
> **Has [pet name] played with a ball for the first time? ⚽**
> Ball rolls, [pet name] chases — pure instinct that needs no training. This moment usually happens more spontaneously than you expected, and it's even better for it.

---

### D-S13｜第一次游泳/玩air

**🇮🇩**
> **[nama hewan] udah pernah main air atau berenang? 💦**
> Langsung nyemplung berani atau perlu dirayu dulu? Pemberani atau penakut air — apapun reaksi [nama hewan], ini momen yang selalu bikin kaget dan gak bisa diulang.

**🇬🇧**
> **Has [pet name] ever played in water or gone for a swim? 💦**
> Brave jumper or needed some convincing? Water lover or not — whatever [pet name]'s reaction was, it's always surprising and it only happens once for real.

---

## M 级（8 个）

---

### D-M1｜第一次出门散步

**🇮🇩**
> **[nama hewan] udah pernah jalan-jalan bareng kamu di luar? 🦮**
> Kaki [nama hewan] menginjak trotoar untuk pertama kali, ngehirup udara baru, merasakan segalanya sekaligus — ini langkah pertama yang cuma terjadi sekali dan gak bisa diulang.
> Udah pernah?

**🇬🇧**
> **Has [pet name] gone on their first walk outside with you? 🦮**
> [Pet name]'s paws hitting the pavement for the first time, breathing new air, taking in everything at once — this first step only happens once and can never be repeated.
> Has this happened yet?

---

### D-M2｜第一次坐车

**🇮🇩**
> **[nama hewan] udah pernah naik kendaraan pertama kali? 🚗**
> Duduk tenang atau gak bisa anteng dari awal? Apapun reaksi [nama hewan], naik kendaraan pertama kali itu selalu jadi cerita yang seru dan layak diingat.
> Udah pernah?

**🇬🇧**
> **Has [pet name] been in a vehicle for the first time? 🚗**
> Sat still or bounced around the whole way? Whatever [pet name]'s reaction, that first car ride always becomes one of the better stories you'll tell.
> Has this happened yet?

---

### D-M3｜完成第一次疫苗接种

**🇮🇩**
> **[nama hewan] udah vaksin pertama? 💉**
> Gak semua orang mau lakuin ini — tapi kamu mau, dan itu yang bikin beda. Vaksin pertama adalah bentuk kasih sayang yang paling nyata dan gak bisa bohong.
> Udah dilakuin?

**🇬🇧**
> **Has [pet name] had their first vaccine? 💉**
> Not everyone bothers — but you do, and that's what makes the difference. A first vaccine is the most honest form of care there is.
> Has this been done?

---

### D-M4｜完成第一次驱虫

**🇮🇩**
> **[nama hewan] udah pernah dikasih obat cacing? 🌿**
> Ngurusin hal yang gak keliatan tapi krusial — ini level kepedulian yang beneran niat. Banyak yang skip ini, tapi kamu gak.
> Kalau sudah, tandai sekarang.

**🇬🇧**
> **Has [pet name] had their first deworming? 🌿**
> Taking care of what's invisible but crucial — that's real, intentional care. A lot of people skip this, but not you.
> If it's been done, mark it now.

---

### D-M5｜第一次看兽医

**🇮🇩**
> **[nama hewan] udah pernah ke dokter hewan? 🩺**
> Perlu persiapan, butuh effort, dan kadang perlu nahan nafas di ruang tunggu — tapi ini salah satu cara paling nyata untuk buktiin rasa sayang kamu ke [nama hewan].
> Udah pernah?

**🇬🇧**
> **Has [pet name] been to the vet yet? 🩺**
> Takes preparation, takes effort, sometimes takes waiting nervously in the lobby — but it's one of the most real ways to prove how much you care about [pet name].
> Has this happened?

---

### D-M6｜第一次见到其他狗狗

**🇮🇩**
> **[nama hewan] udah pernah ketemu anjing lain pertama kali? 🐶**
> Langsung akrab atau butuh adaptasi dulu? Apapun hasilnya, ini momen sosial terbesar dalam hidupnya hari itu — dan biasanya lebih dramatis dari yang dibayangkan.
> Udah pernah?

**🇬🇧**
> **Has [pet name] ever met another dog for the first time? 🐶**
> Instant best friends or needed time to warm up? Either way, this was the biggest social event of [pet name]'s day — and usually more dramatic than expected.
> Has this happened?

---

### D-M7｜学会第一个指令（坐下/握手）

**🇮🇩**
> **[nama hewan] udah bisa ikutin perintah pertama? 🐾**
> Duduk, salaman, atau perintah apapun yang pertama berhasil — ini bukan cuma soal latihan. Ini bukti kepercayaan yang tumbuh antara kamu dan [nama hewan], dan itu yang beneran berarti.
> Udah pernah?

**🇬🇧**
> **Has [pet name] ever followed their first command? 🐾**
> Sit, shake, or whatever came first — this isn't just about training. It's proof of the trust growing between you and [pet name], and that's what actually matters.
> Has this happened?

---

### D-M9｜完成绝育手术

**🇮🇩**
> **[nama hewan] udah selesai operasi? 🏥**
> Bukan keputusan yang gampang atau murah, tapi kamu ambil demi masa depan yang lebih sehat untuk [nama hewan]. Ini bukan hal kecil — ini bukti nyata kamu pemilik yang serius.
> Udah dilakuin?

**🇬🇧**
> **Has [pet name] had their surgery? 🏥**
> Not an easy or cheap decision, but you made it for [pet name]'s healthier future. This isn't a small thing — it's real proof you're a serious, caring owner.
> Has this been done?

---

---

# 🐾 通用宠物打卡引导（Other Pets）

---

## S 级（1 个）

---

### G-S6｜第一次吃零食

**🇮🇩**
> **[nama hewan] udah pernah coba camilan pertama? 😋**
> Reaksi [nama hewan] pertama kali dapet sesuatu yang enak itu paling jujur — gak bisa dibuat-buat, gak bisa diulang persis sama. Momen ini priceless banget kalau ke-foto.

**🇬🇧**
> **Has [pet name] tried their first treat yet? 😋**
> [Pet name]'s reaction the first time they tasted something good is the most honest thing — can't be faked, can't be exactly recreated. That moment is priceless if it was captured.

---

## M 级（2 个）

---

### G-M1｜第一次看兽医

**🇮🇩**
> **[nama hewan] udah pernah ke dokter hewan? 🩺**
> Untuk hewan yang "beda dari biasanya", cari dokter yang tepat itu butuh usaha ekstra. Kalau kamu udah berhasil lakuinnya, itu pencapaian yang beneran patut dicatat.
> Udah pernah?

**🇬🇧**
> **Has [pet name] ever been to the vet? 🩺**
> For a less-common pet, finding the right vet takes extra effort. If you've already made this happen, that's a real achievement worth recording.
> Has this happened?

---

### G-M2｜完成第一次健康检ana/疫苗

**🇮🇩**
> **[nama hewan] udah pernah cek kesehatan atau vaksin? 💉**
> Fondasi kesehatan yang kamu bangun dari awal — gak semua orang mau repot-repot lakuin ini. Tapi kamu mau, dan itu yang bikin kamu beda.
> Udah dilakuin?

**🇬🇧**
> **Has [pet name] had their first health check or vaccine? 💉**
> Building the health foundation from the very start — not everyone bothers with this. But you do, and that's what sets you apart.
> Has this been done?

---

---

## 附：系统自动触发里程碑的说明文案

以下里程碑触发方式为「系统自动」或「系统推送 + 用户发布」——用户点击灰色徽章时，弹出说明卡片，**不展示「已完成」确认按钮**（详见 FR-42）。

| ID | 里程碑 | 说明文案（印尼语） | 说明文案（英语） |
|----|--------|-------------------|-----------------|
| C-S1 / D-S1 / G-S1 | 档案创建完成 | `Otomatis selesai saat profil [nama hewan] sudah lengkap — foto profil dan bio sudah terisi.` | `Automatically completed once [pet name]'s profile is fully set up with a photo and bio.` |
| C-S2 / D-S2 / G-S2 | 第一张照片 | `Otomatis selesai saat kamu pertama kali mengunggah foto ke Kalender Tumbuh.` | `Automatically completed when you first upload a photo to the Growth Calendar.` |
| C-S3 / D-S3 / G-S3 | 第一次分享名片 | `Otomatis selesai saat kamu pertama kali membagikan kartu nama [nama hewan].` | `Automatically completed the first time you share [pet name]'s profile card.` |
| C-S4 / D-S4 / G-S4 | 第一次保存问诊 | `Otomatis selesai saat kamu menyimpan hasil konsultasi ke arsip [nama hewan].` | `Automatically completed when you save a consultation result to [pet name]'s record.` |
| C-S5 / D-S5 / G-S5 | 第一次发布分享 | `Otomatis selesai saat kamu pertama kali membuat postingan di platform.` | `Automatically completed when you publish your first post on the platform.` |
| C-S14 / D-S14 / G-S7 | 第一次被评论 | `Otomatis selesai saat postinganmu pertama kali mendapat komentar dari pengguna lain.` | `Automatically completed when your post receives its first comment from another user.` |
| C-S15 / D-S15 / G-S8 | 第一次收到点赞 | `Otomatis selesai saat postinganmu pertama kali mendapat suka dari pengguna lain.` | `Automatically completed when your post receives its first like from another user.` |
| C-M8 / D-M8 / G-M3 | 陪伴满30天 | `Otomatis selesai saat [nama hewan] sudah menemanimu selama 30 hari sejak profil dibuat.` | `Automatically completed when [pet name] has been with you for 30 days since the profile was created.` |
| C-M10 / D-M10 / G-M4 | 日历满10条 | `Otomatis selesai saat [nama hewan] sudah punya 10 catatan di Kalender Tumbuh.` | `Automatically completed when [pet name]'s Growth Calendar reaches 10 entries.` |
| C-L2 / D-L2 / G-L2 | 陪伴满100天 | `Otomatis selesai saat [nama hewan] sudah menemanimu selama 100 hari. Hampir sampai!` | `Automatically completed when [pet name] has been with you for 100 days. Almost there!` |
| C-L3 / D-L3 / G-L3 | 陪伴满365天 | `Otomatis selesai saat genap satu tahun [nama hewan] bersamamu. Ini momen yang ditunggu-tunggu.` | `Automatically completed on [pet name]'s one-year anniversary with you. The moment worth waiting for.` |
| C-L4 / D-L4 | 全健康里程碑 | `Otomatis selesai saat C-M3 + C-M4 + C-M5 (atau D-M3 + D-M4 + D-M5) semua sudah selesai.` | `Automatically completed when all three health milestones (vaccine + deworming + vet visit) are done.` |
| C-L5 / D-L5 | 日历满30条 | `Otomatis selesai saat [nama hewan] sudah punya 30 catatan di Kalender Tumbuh.` | `Automatically completed when [pet name]'s Growth Calendar reaches 30 entries.` |

---

*文档版本：V1.0.0 | 创建日期：2026-06-26 | 关联需求：FR-42 | 配套文件：milestone-celebration-copy.md*
