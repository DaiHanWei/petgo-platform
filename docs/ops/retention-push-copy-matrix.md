# 留存推送 · 分层文案矩阵与召回可达性

> 配套《TailTopia 留存运营作战手册 v1.1.2》抓手 1 / 第二章用户分层 / 第三章本周三件事。
> 面向运营执行，**不需要动代码**——除非你要改文案本身（改哪个文件见文末）。

---

## 0. 先说一件手册没说、但会决定成败的事：**谁能被推送到**

推送通道绑在 IM 账号 `u_{userId}` 上，而 IM 登录发生在**用户登录之后**
（`push_service.dart` → `imService.loginIfNeeded()` → `registerPush()`）。

**结论：没注册过的安装，我们一条推送都发不出去。**

对照诊断报告的实测：v1.1.0 那 473 个人里**只有 1 人注册**。也就是说手册第三章
「立刻要做的第 1 件事：召回 1.1.0 那 506 人，push + 深链直达建档」——
**这批人里绝大多数收不到 push**。不是文案问题，是根本没有通道。

| 分层 | 人数（手册） | push 能不能到 | 真正能走的通道 |
|---|---|---|---|
| 已注册 · 未建档 | 建档 336 之外的注册用户 | ✅ 能 | 生命周期推送 `CREATE_PROFILE` 变体（已上线） |
| 已建档 · 未发布 | ≈230 | ✅ 能 | `RECORD` / `FEED` 变体 |
| 已发布 | 106 | ✅ 能 | `REVIEW` 变体 + 分享引导 |
| 只打开过 1 天 | 557 / 85.8% | ⚠️ 看有没有注册过 | 注册过的走召回；没注册的同下 |
| **1.1.0 残留（未注册）** | ≈472 | ❌ **发不出去** | 见下方 §4 |

> 这不是推翻手册的判断——「这批人成本已沉没、只差一步」完全成立。
> 但**手段必须换**：把预算花在给他们发 push 上会一条都发不出去，
> 而做成再营销受众是当天就能启动的。

---

## 1. 已上线的四节点 × 分层文案（生命周期推送引擎）

引擎已按手册抓手 1 落地：每日 19:00 WIB 日扫，一人一天至多一条，节点互斥。
下表 = 用户实际会收到的话（印尼语为准，英语按 `users.locale` 自动切）。

| 节点 | 分层 | 落点 | 印尼语 · 标题 / 正文（`{0}`=宠物名） |
|---|---|---|---|
| **D1** | 已建档 | 发布页（预选成长日历） | `Momen {0} hari ini` / `Satu foto, satu kalimat — simpan momen {0} hari ini di buku tumbuh kembangnya.` |
| D1 | 未建档 | 建档页 | `Tinggal 30 detik lagi` / `Bikin profil anabulmu dulu, biar setiap momennya punya tempat untuk disimpan.` |
| **D3** | 已建档未发布 | Feed | `Lagi rame di TailTopia` / `Lihat apa yang dilakukan anabul lain hari ini — siapa tahu {0} juga mau ikutan.` |
| D3 | 未建档 | 建档页 | `Anabulmu belum punya profil` / `Sudah 3 hari nih. Bikin profilnya sekarang, cuma butuh 30 detik.` |
| **D7** | 已发布 | 成长档案 | `Seminggu bareng {0}` / `Lihat rangkuman tumbuh kembang {0} minggu ini — bisa langsung dibagikan ke teman.` |
| D7 | 已建档未发布 | 发布页 | `Buku {0} masih kosong` / `Isi halaman pertama {0} yuk — satu foto hari ini sudah cukup.` |
| D7 | 未建档 | 建档页 | `Profil anabulmu belum jadi` / `Tinggal satu langkah: bikin profilnya, 30 detik selesai.` |
| **召回** | 已建档 | 发布页 | `Sudah lama {0} nggak update` / `Apa kabar {0}? Catat satu momennya hari ini biar bukunya lanjut lagi.` |
| 召回 | 未建档 | 建档页 | `Dulu belum sempat selesai` / `Profil anabulmu belum jadi. Lanjutkan sekarang — 30 detik langsung kelar.` |

**铁律（有测试守着，改文案时不会被绕过）**：
- 面向已建档用户的文案**必须带宠物名** `{0}`；
- 全部文案**禁止出现**「回来看看 / come back and see / kembali lihat」这类空话。
  `LifecyclePushCopyTest` 会因此变红。这不是风格洁癖——手册指出发布是全站唯一的强行为（30.9%），
  「记录 Mochi 的一个瞬间」和「回来看看」是两个转化率量级。

---

## 2. A/B 备选文案（下一轮换文案时用）

手册第五章把「push 文案 A/B」列为**第一批可以交给 agent 执行的动作**。
下面每层给一条备选臂，换的时候只改 `messages_id.properties`，一次只换一层、只换一个变量。

| 节点·分层 | 现行臂（A） | 备选臂（B） | B 在赌什么 |
|---|---|---|---|
| D1 · 已建档 | 「一张照片、一句话」（降低门槛） | `{0} hari ini lagi apa?` / `Ceritain satu hal kecil tentang {0} hari ini.` | 赌**提问**比**指令**更容易引发回应 |
| D1 · 未建档 | 「30 秒」（省时承诺） | `Anabulmu belum punya nama di sini` / `Kasih dia profil sendiri — nama, foto, tanggal lahir.` | 赌**具体化缺失感**强于**省时承诺** |
| D3 · 已建档未发布 | Feed 社交钩子 | `Anabul lain lagi pada update` / `Kamu belum posting apa-apa soal {0}. Intip punya orang lain dulu?` | 赌**轻微的社会比较**能推动第一条 |
| D7 · 已发布 | 周回顾 + 可分享 | `1 minggu, sekian momen {0}` / `Rangkuman minggumu sudah siap. Kirim ke yang paling pengen lihat.` | 赌**指定收件人**比泛泛的「分享」更易触发 |
| 召回 · 未建档 | 「上次没走完」 | `Kami simpan tempatnya buat anabulmu` / `Profilnya masih kosong. Lanjut dari tempat kamu berhenti?` | 赌**「位置给你留着」**弱化被追赶感 |

**A/B 纪律**：一次只跑一层；至少跑到该层 cohort ≥ 100 人再看；
看的是**点击→建档/发布**，不是点击率——点击率高但不建档的文案是骗点击，比不发还糟。

---

## 3. 运营旋钮（不改代码，改 env）

| 变量 | 默认 | 什么时候动 |
|---|---|---|
| `LIFECYCLE_PUSH_ENABLED` | `false` | **上线后由你显式打开**。默认关是刻意的：迁移把存量用户的「最后活跃」回填成 `updated_at`，一开就会有一大批人同时命中「7 天未回」 |
| `LIFECYCLE_PUSH_DAILY_CAP` | `200` | 按天放量。第一周建议先 `50` 看漏斗，再往上抬 |
| `LIFECYCLE_PUSH_WINBACK_AFTER_DAYS` | `7` | 手册定义 7 天。召回打扰感太强就往 `10`–`14` 抬 |
| `LIFECYCLE_PUSH_CRON` | `0 0 12 * * *`（19:00 WIB） | 换时段做时段 A/B 时改 |

**放量建议节奏**：`50 → 100 → 200`，每档跑满 3 天、看召回漏斗不劣化再抬。
一次性放开的代价不是「效果差」，是**用户关掉推送权限**——那是不可逆的。

---

## 4. 1.1.0 残留（≈472 人未注册）的三条可行通道

push 到不了他们，但下面三条当天就能启动，按 ROI 排序：

1. **再营销受众（最高 ROI，当天可做）**
   在 AppsFlyer / TikTok Ads 里用「已安装但未完成注册」建自定义受众，投一条
   **单一诉求**的素材：*「Profil anabulmu belum jadi — 30 detik」*，落地页直达建档深链。
   这批人 CPI 已经付过了，再营销只付一次极便宜的展示费。
   ⚠️ 前置依赖：手册第三章第 2 件事「切 AEO」——优化目标必须是**注册**，不是安装。

2. **强制升级（他们一旦打开 App 就生效，零成本）**
   1.1.0 是断链包，他们打开只会再被劝退一次。后端已有现成开关：
   把 env `APP_MIN_SUPPORTED_VERSION` 抬到 `1.1.2`（现为默认 `1.0.0`），
   1.1.0 用户下次开 App 即命中强制更新提示。诊断报告里有 26 个人反复重开过 App
   ——每一次重开都是一次白白浪费掉的接住机会。
   ⚠️ 同时确认 `APP_ANDROID_STORE_URL` 已配（留空点了没反应），并把 `APP_LATEST_VERSION` 一起更到当前版本。

3. **应用商店在架页**（被动，但零成本）
   商店截图第一屏换成「记录你的宠物」的价值主张而不是功能罗列。
   这批人如果哪天在商店里再看到，第一屏决定他会不会再点开。

> 明确不建议：给未注册用户买短信/邮件召回——我们**没有**他们的手机号或邮箱
> （未注册就没有账号），这条路根本不存在。

---

## 5. 改文案改哪里

| 改什么 | 文件 |
|---|---|
| 推送真正发出去的那句话（按用户语言） | `petgo-backend/src/main/resources/i18n/messages_id.properties` · `messages_en.properties`（键 `notify.LIFECYCLE_<节点>.<分层>.title/body`） |
| 通知中心里那一行（App 按类型本地化，**没有宠物名**） | `petgo_app/lib/l10n/app_id.arb` · `app_en.arb`（键 `notifyTypeLifecycle*` / `notifyBodyLifecycle*`） |
| 触发规则（几天、哪层、去哪页） | `petgo-backend/.../notify/lifecycle/LifecyclePushPlanner.java` —— 改这个要走开发 |

⚠️ 三语必须同改（`messages_zh_CN` 也要），且**占位符集合必须一致**，
否则 `AdminMessagesParityTest` 会红。带参数的串里**单引号要写成 `''`**，
不然 MessageFormat 会把后面的内容整段吞掉，用户收到半句话。
