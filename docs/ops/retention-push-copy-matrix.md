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
| 已注册 · 未建档 | 建档 336 之外的注册用户 | ✅ 能 | 生命周期推送 B 层 `CREATE_PROFILE`（1.5.0 起 D1 / D3 / D7 线上开启） |
| 已建档 · 未发布 | ≈230 | ✅ 能 | C 层 `RECORD` / `DISCOVER` |
| 已发布 | 106 | ✅ 能 | D / E 层 `DISCOVER` / `RECORD`、第 7 天新手任务 |
| 只打开过 1 天 | 557 / 85.8% | ⚠️ 看有没有注册过 | 注册过的走召回；没注册的同下 |
| **1.1.0 残留（未注册）** | ≈472 | ❌ **发不出去** | 见下方 §4 |

> 这不是推翻手册的判断——「这批人成本已沉没、只差一步」完全成立。
> 但**手段必须换**：把预算花在给他们发 push 上会一条都发不出去，
> 而做成再营销受众是当天就能启动的。

---

## 1. 生命周期推送：节点 × 分层 × 落点（1.5.0 定稿，附录 A）

**1.5.0 起 D1 / D3 / D7 线上开启（`LIFECYCLE_PUSH_ENABLED=true`）；召回节点（7 天未回）默认关**（`LIFECYCLE_WINBACK_ENABLED=false`，本版不开）。
每日 19:00 WIB 日扫，日期一律按 WIB；一人一天至多一条、节点窗口 2 天、`(用户, 节点)` 一辈子一次；**只推 App ≥ 1.5.0 的用户**（旧版跳过、不写去重标记）。
通知中心存的是与推送**同一句**（后端按收件人语言渲染后落库）。

**分层**（发送当时按账号判定，自上而下取第一个）：
A 没宠物（没建档、注册时没选「已养」）/ B 待建档（没建档、选了「已养」）/ C 待发帖（有宠物、没发过帖）/
D 待互动（发过帖，关注 / 点赞 / 评论别人都没做过）/ E 已互动（发过帖且做过其中之一）。

| 节点 | 分层 | 落点（targetRef 变体） | copy 键（`notify.LIFECYCLE_<节点>.<copy>`） | 印尼语 · 标题 / 正文 |
|---|---|---|---|---|
| **D1** | A | 推荐宠物页（`DISCOVER`；做过关注 / 点赞 / 评论 → Feed `FEED`） | `D1.NO_PET` | `Gemesnya anabul hari ini 🥹` / `Kucing, anjing, semua lagi pamer gaya. Ikuti yang bikin kamu meleleh 🐾` |
| D1 | B | 建档页（`CREATE_PROFILE`） | `D1.CREATE_PROFILE`（沿用） | `Tinggal 30 detik lagi` / `Bikin profil anabulmu dulu, …` |
| D1 | C | 发布页预选 Diary（`RECORD`） | `D1.RECORD`（沿用） | `Momen {0} hari ini` / `Satu foto, satu kalimat — …` |
| D1 | D | 推荐宠物页（`DISCOVER`） | `D1.SQUAD` | `Geng {1} lagi ngumpul 🐾` / `Kenalin {0} ke sesama {1}, siapa tahu jadi bestie 💜` |
| D1 | E | 发布页预选 Diary（`RECORD`） | `D1.ACTIVE` | `{0} lagi ngapain hari ini? 👀` / `Satu foto lagi, biar Diary {0} makin seru! 📸` |
| **D3** | A | 同 D1 A | `D3.NO_PET` | `Lagi rame banget di TailTopia! 🔥` / `Anabul-anabul lagi pamer pose terbaik. Ikuti favoritmu yuk 🐾` |
| D3 | B | 建档页 | `D3.CREATE_PROFILE`（沿用） | `Anabulmu belum punya profil` / `Sudah 3 hari nih. …` |
| D3 | C | 推荐宠物页（`DISCOVER`） | `D3.SQUAD`（句子同 D1 待互动） | `Geng {1} lagi ngumpul 🐾` / `Kenalin {0} ke sesama {1}, siapa tahu jadi bestie 💜` |
| D3 | D | 推荐宠物页（`DISCOVER`） | `D3.BESTIE` | `{0} belum punya bestie nih 🥺` / `Yuk cariin teman buat {0}, banyak {1} yang lagi aktif! 🐾` |
| D3 | E | **不推** | — | — |
| **D7** | B | 建档页 | `D7.CREATE_PROFILE`（沿用） | `Profil anabulmu belum jadi` / `Tinggal satu langkah: …` |
| D7 | A / C / D / E · 已毕业 | 里程碑分发页 + 补弹 Lulus（`GRADUATED`） | `D7.GRADUATED` | `Yeay, Lulus Pemula! 🎓` / `Semua Tugas Pemula beres! Ketuk buat rayain 🎉` |
| D7 | A / C / D / E · 未毕业 | 里程碑分发页（`NEWBIE_TASKS`） | `D7.TASKS_LEFT` | `Seminggu bareng TailTopia 🥳` / `Tinggal {2} Tugas Pemula lagi, Lulus Pemula udah di depan mata! 💪` |
| 召回（**默认关**） | 已建档 / 未建档 | 发布页 / 建档页 | `WINBACK.RECORD / CREATE_PROFILE`（沿用） | 见 messages 文件 |

参数：`{0}` 默认宠物名；`{1}` 品种（没填用物种词：Kucing / Anjing / Anabul，EN cat / dog / pet）；`{2}` 剩余新手任务数 N（第 7 天「注册满一周」计为已完成）。
**当天已打开过 App 的人**：D1 / D3 本节点作废（只写去重标记、不推不写通知，第二天不补）；D7 照发。
已毕业 = 有 Lulus Pemula 账号标记，或新手任务 ①–⑥ 都做过。

**铁律（有测试守着，改文案时不会被绕过）**：
- C / D / E 层（有宠物）的文案**必须带宠物名** `{0}`；A / B 层与第 7 天「刚毕业 / 还差 N 项」（**账号级内容**，OQ-5 豁免）**不得**带宠物名；
- 全部文案**禁止出现**「回来看看 / come back and see / kembali lihat」这类空话。`LifecyclePushCopyTest` 会因此变红。

### 1.1 其他三类定时 / 社交推送（1.5.0 新增或改时）

| 推送 | 时点 | 规则 | 文案（印尼语） |
|---|---|---|---|
| **陪伴纪念日** `COMPANION_ANNIVERSARY` | 每日 **19:00 WIB** | 建档满 30 / 100 / 365 天（按 WIB 建档日）；只读宠物、主人已注销 / 封号不推不写；同主人同天多只 → 只推建档最早那只，其余只进通知中心。点开直达这只宠物的 Diary 发布页 | `🎉 Hari ke-{1} bareng {0}!` / `Cheers buat {1} hari kalian! Abadikan gaya {0} hari ini, satu foto aja cukup 📸` |
| **生日 / 第一个生日** | 每日 **11:00 WIB**（原 08:00） | 规则、文案、落点不变，只改时间 | 不变 |
| **关注更新** `USER_POST_PUBLISHED` | 实时 | 关注的人发公开帖（Moment / Tips / Diary）；TailUs / 官方号 / 虚拟号发帖不推；追更了帖子里宠物的人只收追更提醒；同一作者每人每天最多推 1 条（通知照写）；**关注更新 + 追更新 Diary 两类合计每人每天最多推 3 条**（第 4 条起只进通知中心）；关了「Yang kamu ikuti」分类、App 旧版只写通知不推 | Diary：`{0} baru update Diary {1}, kepoin yuk 👀`；其他：`{0} baru posting nih, intip yuk 👀`（`{0}` 作者昵称、`{1}` 宠物名；只有标题） |

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

## 3. 运营旋钮（不改代码，改 env；改后须按部署脚本**重建容器**，`docker restart` 不重读 env）

| 变量 | 默认 | 什么时候动 |
|---|---|---|
| `LIFECYCLE_PUSH_ENABLED` | `false` | D1 / D3 / D7 开关。**1.5.0 上线时设 `true`** |
| `LIFECYCLE_WINBACK_ENABLED` | `false` | 召回节点（7 天未回）开关，本版**不开**：存量「最后活跃」按 `updated_at` 回填过，一开会有一大批老用户同时命中 |
| `LIFECYCLE_PUSH_DAILY_CAP` | `2000` | 上线第 1 天设 `300`，第 3 天起删掉回默认 |
| `LIFECYCLE_PUSH_WINBACK_AFTER_DAYS` | `7` | 召回开启后才生效 |
| `LIFECYCLE_PUSH_CRON` | `0 0 12 * * *`（19:00 WIB） | 换时段做时段 A/B 时改 |
| `FOLLOW_UPDATE_PUSH_ENABLED` | `true` | 关注更新回滚开关（false = 不写通知也不推；追更提醒不受影响） |
| `FOLLOW_UPDATE_PUSH_DAILY_CAP` | `3` | 关注更新 + 追更新 Diary 两类合计每人每天推送上限 |
| `ANNIVERSARY_PUSH_ENABLED` | `true` | 纪念日日扫回滚开关 |
| `BIRTHDAY_PUSH_CRON` / `ANNIVERSARY_PUSH_CRON` | `0 0 4 * * *` / `0 0 12 * * *`（UTC） | 生日 11:00 / 纪念日 19:00 WIB；联调时可临时改近一分钟 |

**护栏**：上线第 1 ~ 2 天看推送分类关闭率（`push_category_toggled`）与投诉，≤ 上线前 1.5 倍；推送点开率看 `notifications.read_at`（推送打开没有埋点）。
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
| 通知中心里那一行 | 1.5.0 起与推送同一句（后端落库）；改版前的旧通知仍按 App 本地文案 `notifyTypeLifecycle*` / `notifyBodyLifecycle*` 显示 |
| 触发规则（几天、哪层、去哪页） | `petgo-backend/.../notify/lifecycle/LifecyclePushPlanner.java` —— 改这个要走开发 |

⚠️ 三语必须同改（`messages_zh_CN` 也要），且**占位符集合必须一致**，
否则 `AdminMessagesParityTest` 会红。带参数的串里**单引号要写成 `''`**，
不然 MessageFormat 会把后面的内容整段吞掉，用户收到半句话。

---

## 6. 1.5.0 上线步骤（推送与召回）

> 🔴 云端与本地 agent **不执行任何部署**，本清单只供操作者使用。**前后端须同批发布**（新落点 / 直达依赖 1.5.0 App）。

| 时点 | 动作 | env | 谁 |
|---|---|---|---|
| 部署前 | 确认 prod / stag env：`LIFECYCLE_PUSH_ENABLED=true`、`LIFECYCLE_PUSH_DAILY_CAP=300`、`LIFECYCLE_WINBACK_ENABLED=false`（不写即默认 false）、`FOLLOW_UPDATE_PUSH_ENABLED` / `ANNIVERSARY_PUSH_ENABLED` 不写（默认 true） | 服务器 `~/.env.petgo` / stag 对应文件 | 研发 + 运维（操作者确认后） |
| 部署 | 按部署脚本重建容器（`docker restart` 不重读 env） | — | 研发 |
| 第 1 ~ 2 天 | 看推送分类关闭率（`push_category_toggled`）与投诉，护栏 ≤ 上线前 1.5 倍 | — | 产品 |
| 第 3 天 | 删 `LIFECYCLE_PUSH_DAILY_CAP`（回默认 2000），重建容器 | 同上 | 研发 |
| 回滚 | 生命周期 `LIFECYCLE_PUSH_ENABLED=false`；关注更新 `FOLLOW_UPDATE_PUSH_ENABLED=false`；纪念日 `ANNIVERSARY_PUSH_ENABLED=false`；均重建容器生效 | 同上 | 研发 |

排查 / 复现一律在 stag，不碰生产；stag 与生产共用腾讯推送额度（全局 25 条 / 秒），压测别放大量。

