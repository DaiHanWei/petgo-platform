---
title: '对抗性评审：V1.3.0 批次 A 架构 Delta（spine）'
target: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-delta.md
reviewer: 对抗性评审员（BMAD adversarial review）
date: 2026-09-10
method: '构造两个逐字遵守全部 AD、却仍互不兼容的实现单元。每一对 = 一个必须堵的洞。'
verdict: '结构可用，约束不足 —— 22 个可复现的兼容性洞，其中 7 个高危，2 个是 spine 内部自相矛盾（照做即写不出来）'
---

# 对抗性评审 · V1.3.0 批次 A 架构 Delta

## 0. 总体判定

这份 spine 的**形态判断是对的**（前端为主、复用优先、两个新交互自实现），
`Prevents` 字段的写法也确实拦住了一批显而易见的重复造轮子。
但它有一个系统性弱点：

> **它反复约束「不许写第二套」，却很少约束「这一套的边界在哪、由谁持有、写入范围多大」。**

于是攻击面全部落在同一个位置：两个下一层单元**都只写了一套**、都逐字守了 AD，
但对「这一套」的**范围 / 归属 / 值域 / 时序**理解不同 —— 产物照样不兼容。

另外有两处 AD **互斥到照做就写不出代码**（H-3、H-5），
以及一处 AD 描述的现网入口**已经不存在**（H-20 附）。

**分布**

| 严重度 | 数量 | 编号 |
|---|---|---|
| 🔴 高（会造成用户可感知错误 / 数据错误 / 钱） | 7 | H-1 H-2 H-3 H-4 H-5 H-6 H-11 |
| 🟠 中高（上线后要返工一整条链路） | 4 | H-7 H-8 H-9 H-10 |
| 🟡 中 | 8 | H-12 … H-19、H-22 |
| ⚪ 低 | 3 | H-20 H-21 + 附录事实错误 |

---

## 1. 攻击面 A：庆祝状态（celebrated_at）的四条写入路径

### 🔴 H-1 — 即时庆祝与补庆祝会连弹两次，而 AD-A2 的 Prevents 只挡了另一对

**两个单元**

- **单元 α（FR-111 即时路径 story）**：守 AD-A2.6「既有即时庆祝路径与三次短轮询全部保留」
  + AD-A3.1「庆祝页展示成功后回报一次，**异步、失败静默**」。
  实现：弹庆祝 → `unawaited(reportCelebrated(code))` → 关页。
- **单元 β（FR-111 补庆祝 story）**：守 AD-A2.3「进入列表页 → 若存在未庆祝条目 → 自动弹一次」。
  实现：`initState` 拉 `/milestones` → 有 `celebratedAt == null` 就弹。

**各自都合规，为什么不兼容**

现网「去发布」路径的既定时序是（PRD §2 原文）：
`发布成功 → 回填打卡 → 弹庆祝 → 关发布 sheet → **跳里程碑列表页**`。

α 的回报是异步 best-effort，β 的拉取紧随其后。两者之间没有任何 happens-before。
回报还没落库，列表接口就已经返回 `celebrated_at IS NULL` ——
**同一条里程碑在两秒内连弹两次**，第二次还是「补庆祝」。

AD-A2 的 Prevents 写的是「两处都弹全屏庆祝（用户从档案 Tab 点进列表页会被连弹两次）」，
挡的是 **档案 Tab × 列表页**；而真正会连弹的是 **instant × catchup**，恰好没挡。
健康记录路径同理：轮询拉到 → 弹 instant → 用户随手点进里程碑列表 → 再弹一次。

**堵法（建议新增 AD-A2.7）**：客户端在本次会话内持有「已弹过的 code 集合」，
补庆祝判定 = `celebrated_at IS NULL` **且** 不在本会话已弹集合内；
或规定即时路径的回报**必须先于导航到列表页完成**（把 best-effort 收窄为「本路径同步等待，超时 300ms 后放行并置本地已弹标记」）。
无论取哪条，都要写进 AD —— 现在它是空白。

---

### 🔴 H-2 — 「全部条目一并置位」的写入范围没定，服务端 mark-all 会吞掉窗口期内的新解锁

**两个单元**

- **单元 α（后端 story）**：守 AD-A2.3「本次涉及的全部条目一并置位（不是只置弹出的那条）」
  + AD-A3.2「只在 `IS NULL` 时写入」。最省事、也最贴字面的实现：
  `UPDATE milestone_completions SET celebrated_at = now() WHERE pet_milestone_id IN (该宠全部) AND celebrated_at IS NULL`。
- **单元 β（前端 story）**：守同一条，实现为「把我这次读到、并由 KOLEKSI 圆点带过的 N 个 code 回报上去」。

**各自都合规，为什么不兼容**

α 是 **mark-all**，β 是 **mark-list**。两者在正常情况下等价 —— 除了那个窗口：

客户端 T0 读列表（3 条未庆祝）→ T0+200ms 用户的另一条里程碑被系统自动解锁（被点赞 / 陪伴满天数）
→ T0+800ms 客户端回报 → α 把**第 4 条也一起置位**。
那第 4 条**从未被展示过，也永远不会再被补弹**。

**这正好是 FR-111 要消灭的那个 bug，只是换了个成因。**
更糟的是它无声无息：`celebrated_at` 非空，看起来一切正常。

**堵法**：AD-A2.3 必须明确「置位对象 = 客户端在本次展示中**显式列举**的 code 列表」，
接口形状是 `POST .../celebrations {codes: [...]}`，服务端**禁止**做「把当前所有未庆祝的都置了」的推断。

---

### 🔴 H-11 — 完成态按「语义后缀 + pet_type 前缀」寻址，而 AD-A4/A5 全篇按完整 code 说话（现网已在错点里程碑）

**代码事实**（`MilestoneCompletionService.complete`，`profile/service/`）：

```java
String code = prefixOf(petType) + "-" + suffix;   // 调用方传的是 "M3"，不是 "C-M3"
```

所有触发源都传后缀：`VACCINE → "M3"`、`DEWORM → "M4"`、`NEUTER → "M9"`、`ConsultClosed → "M5"`。
而通用清单（`MilestoneCatalog.buildOther()`）是：
`G-M1 第一次看兽医 / G-M2 完成第一次健康检查·疫苗 / **G-M3 陪伴满 30 天** / **G-M4 成长日历记录满 10 条**`。

**现网即存在的错误**（不是我构造的，是跑起来就有的）：
非猫狗宠物的主人录一条 **疫苗** 记录 → `completeForOwner(owner, "M3")` → 拼出 `G-M3` → 点亮**「陪伴满 30 天」**。
录**驱虫** → `G-M4` → 点亮**「记录满 10 条」**。
（`NEUTER→M9`、`ConsultClosed→M5` 因 G 系无该节点而 no-op，所以只有前两条会误点亮。）

**两个单元**

- **单元 α（AD-A5 story）**：守 AD-A5.1「G-M2 只由 `VACCINE` 触发」。
  最自然的实现是在 `onHealthRecordCreated` 里加一句「OTHER 宠物额外完成 G-M2」。
- **单元 β（AD-A4 story）**：守 AD-A4.1「判定改按完整 code 显式列举」，只动 `HealthMilestones` 这一个类
  （AD-A4.2 说它是唯一事实源，AD-A22 说不许顺手扩范围）。**不碰后缀映射。**

**各自都合规，为什么不兼容**

α 加完之后，一条疫苗记录同时点亮 **G-M2（正确）** 与 **G-M3 陪伴 30 天（错误，β 没动它）**。
接着 AD-A2.3 的「多条同解锁只弹最高一条 + 全部置位」把这两条**一起标成已庆祝**，
AD-A1.4 的存量回填再把历史上所有误点亮的 G-M3 永久固化为 `celebrated_at = completed_at`。
**错误完成态被本批次的两条 AD 联手洗白，再也查不出来。**

**堵法**：spine 缺一条「里程碑完成的寻址口径」AD。必须明确：
① 完成 API 的入参从**语义后缀**改为**完整 code**（或至少在 G 系加显式映射表并由测试钉住）；
② AD-A5.3「存量不回滚」要区分「合法完成」与「误触发完成」——
G 系因后缀撞车产生的 G-M3/G-M4 完成行，**是脏数据，不该被 AD-A1.4 的回填一并盖章**。
③ AD-A4.3 说「顺带消除 G-M3/G-M4 的误判隐患」——它只说了「被误判为**健康类**」，
没说「被**误点亮**」。这两件事不是一回事，spine 把小的那件当成了全部。

---

### 🟠 H-8 — 角标计数下发在哪个接口没定，而这决定了角标能不能被消掉

**代码事实**：档案 Tab 的里程碑进度条在 `DiaryHeader`，数据来自 `archiveStatsProvider`
（`GET /api/v1/pet-profiles/me/archive-stats` → `ArchiveStatsResponse`）；
里程碑列表页用的是另一个 provider `milestoneListProvider`（`GET .../me/milestones`）。
**这是两个接口、两个 provider、两套失效时机。**

**两个单元**

- **单元 α（后端 story）**：守 AD-A2.2「角标数据由**里程碑相关接口**下发」→ 加在 `MilestoneListResponse` 上（字面最贴）。
- **单元 β（前端档案 Tab story）**：角标要渲染在 `DiaryHeader` 上，而它手里只有 `archiveStats`。

**为什么不兼容**：β 拿不到 α 下发的字段。β 的两条出路都糟：
拉一次完整里程碑列表（30 条 + 分组，只为一个数字，且 AD-A22 明说「新增开销只有两处查询」），
或者自己在 `ArchiveStatsResponse` 上再加一个字段 —— 于是**两个接口两个计数**，
AD-A2.2 的 Prevents「角标与补弹各自判定、口径分叉」当场发生。

更硬的连带：`archiveStatsProvider` 在 `app.dart`、`publish_compose_page`、`triage_result_view`、
`content_detail_page` 四处被 `invalidate`；`milestoneListProvider` 不在这些点上。
AD-A2 要求「点进列表页后角标消除」——**如果角标挂在里程碑列表接口上，档案 Tab 那份数据没人失效，角标不会消。**

**堵法**：AD-A2.2 要点名接口：`ArchiveStatsResponse` 新增 `uncelebratedCount`，
列表接口逐条下发 `celebratedAt`；并明确「补庆祝完成后必须 `invalidate(archiveStatsProvider)`」。

---

### 🟡 H-19（并入本节）— 并发双设备下埋点会双计

两台设备同时进列表页 → 都读到未庆祝 → 都弹 → 都发 `milestone_celebration_shown{path:catchup}`
→ 只有一个 `UPDATE` 生效（AD-A3.2 幂等），但**埋点发了两次**（AD-A3.4 明说埋点与回报是两件事）。
FR-111 的效果度量恰恰是「补庆祝弹了多少次」，这个数天生偏高，且偏高幅度不可观测。
不是数据错误，但**是这个 FR 唯一的验收指标**。spine 应说明「celebration_shown 允许重复，评估口径按 (user, code) 去重」。

---

## 2. 攻击面 B：健康类 code 集合的「两个主人」

### 🟠 H-10 — 「唯一事实源」只覆盖了实际存在的四份集合中的两份，且「等长同集」这条要求本身自相矛盾

**代码事实 —— 现在有四份集合，不是两份：**

| # | 位置 | 内容 | 用途 |
|---|---|---|---|
| 1 | `profile/domain/HealthMilestones.SUFFIXES`（Java） | `M3 M4 M5 M9` | 拒绝打卡护栏 + 自动完成映射 + 埋点 T-12 |
| 2 | `profile/domain/health_milestones.dart` `kAutoOnlyHealthMilestoneSuffixes` | `M3 M4 M5 M9` | 前端**隐藏打卡入口** |
| 3 | `milestone_list_page.dart` `healthPresetTypeFor` | 只有 `M3→VACCINE`、`M4→DEWORM` | 前端**点击去向** |
| 4 | `profile/service/TimelineClassifier.HEALTH_MILESTONE_SUFFIXES`（Java） | `M3 M4 M5 M9 **S4**` | 时间线胶囊承载规则（**刻意不同，两处注释都写了**） |

AD-A4.2 只说了「后端 `HealthMilestones` 是唯一事实源，前端的**跳转映射表**必须与之等长同集」。

**两个单元**

- **单元 α**：把 #3 `healthPresetTypeFor` 补齐成十条（AD-A4.1 列举的完整集合），
  逐字执行「必须与之等长同集」。
- **单元 β**：读 AD-A6.2 —— `*-M5` 与 `G-M1` **跳兽医咨询入口**、`G-M2` 预选 `VACCINE`、`*-M9` 预选 `NEUTER`，
  于是把 #3 拆成「预选类型表（M3/M4/M9/G-M2）」+「跳兽医表（M5/G-M1）」两张，
  逐字执行 AD-A6。

**为什么不兼容**：AD-A4.2 的「等长同集」与 AD-A6.2 的「M5/G-M1 无类型可选」**在字面上直接打架**。
`healthPresetTypeFor` 返回 `String?`（健康记录类型），它按定义就装不下「兽医咨询入口」这个去向。
α 会造出 `G-M1 → 'VET'` 这种把去向塞进类型字段的怪东西；β 会造出两张表，
而 AD-A4.2 的「任一侧单独增删即为违规」的测试写法（等长同集断言）在 β 的产物上必然红。

再叠 #4：一个读了 AD-A4.2「唯一事实源」的人，很可能顺手把 `TimelineClassifier` 也对齐 ——
那会**改掉时间线的胶囊承载规则**（S4 被移出 / G-M1·G-M2 被移入），
而两处代码注释都白纸黑字写了「刻意分开，合并会让其中一侧悄悄改错」。
**AD-A4 完全没提 #4 的存在。**

**堵法**：AD-A4 要改成三段式：
① **功能集合**（禁打卡 / 拒绝护栏 / 自动完成 / T-12）—— 一份，后端 `HealthMilestones` 为源，前端 #2 等长同集，测试钉住；
② **去向映射**—— 是 `code → 目的地枚举{健康记录页(预选类型) | 兽医咨询入口}` 的**全函数**，
定义域必须等于 ①，但**值域不是类型字符串**；
③ **展示集合 `TimelineClassifier`（含 S4）明令不在本次范围**，写进 AD 的排除清单，否则一定有人去动它。

---

## 3. 攻击面 C：评论点赞与热度序

### 🔴 H-3 — AD-A7.3 与 AD-A8.5 互斥：排序键是聚合值，按定义无法建索引（照做写不出来）

- **AD-A7.3**：「计数实时聚合，**不加冗余计数列**。」
- **AD-A8.1**：一级评论默认序 = `点赞数 DESC, created_at ASC, id ASC`。
- **AD-A8.5**：「需要**覆盖排序的索引**支撑（`post_id` + 层级 + 排序键）。」

排序键的第一元就是 `COUNT(comment_likes)`。它**不是 `comments` 表上的列**，
PostgreSQL 无法在 `comments(post_id, parent_id, <一个 join 出来的聚合>)` 上建索引。
能建的只有 `comment_likes(comment_id)`；排序仍要对该帖全部一级评论做 `LEFT JOIN + GROUP BY + ORDER BY count`，
**每翻一页都要把全帖评论重算一遍**。

**两个单元**

- **单元 α（FR-114 后端 story）**：守 AD-A7.3，实现聚合排序，AD-A8.5 的索引只好落成 `comment_likes(comment_id)`，
  在 Completion Notes 写「索引形态属实现选择（D-9）」—— 完全合规。
- **单元 β（后续任一性能 story / 或同一人回头优化）**：看到 AD-A8.5 要求「覆盖排序的索引」，
  唯一能满足它的办法就是把 `like_count` 物化到 `comments` 上 —— **直接违反 AD-A7.3**。

**这不是两个单元互不兼容，这是 spine 自己写不出来。**
AD-A7.3 的论据「与全库现状一致（`content_likes` 亦为实时 COUNT）」是**假类比**：
现网从没按点赞数**排序**过，只是取单帖的一个数。「聚合出一个数」与「按聚合值全序排列并 keyset 翻页」
是完全不同量级的两件事。

**堵法（三选一，必须在 spine 里选死）**：
① 删掉 AD-A8.5，明写「一级评论排序不建索引，接受全帖聚合；上限由帖内评论数天然约束」+ 给出可接受的评论数上界；
② 承认这是全库第一个冗余计数列，为 `comments.like_count` 单开一条 AD（含并发增减、软删口径、回填与对账），并把 D-5 提前；
③ 退回时间序 —— PRD 的收益是否值这个代价，由 Dai 判。
**现在这三条哪条都没选，OQ-2 只问了「排序抖动可不可接受」，没问「排序本身怎么落地」。**

---

### 🔴 H-4 — 热度序 × 「发完评论刷新列表」：用户自己刚发的评论会从第一页消失

**代码事实**（`comment_composer.dart:60` / `comment_section.dart:189`）：
发评成功 → `commentsRefreshProvider.bump()` → `comment_section` 监听到即 `_reload()`（重拉第一页）。

**两个单元**

- **单元 α（FR-114 排序 story）**：守 AD-A8.1（热度序）+ AD-A8.3（「**已渲染的列表不重排**，赞数变化下次进入才生效」）。
- **单元 β（FR-114 回复态 story）**：守 AD-A10.4「回复成功后展开父评论并滚动定位」——
  它只管**二级**回复（二级仍是时间正序，AD-A8.1 明说不变），
  一级评论的发布沿用既有 `bump → _reload`，**零改动，复用优先（§1 形态判断 2）**。

**为什么不兼容**：`_reload()` 就是 AD-A8.3 所说的「下次进入」。
用户发一条**新的一级评论**（0 赞）→ 列表按热度重拉 → 新评论排在**所有有赞评论之后**，
在一个有 10+ 条热门评论的帖子里，**它落在第一页之外，用户看不到自己刚发的东西**。
α 合规（他没重排已渲染列表，是 β 触发的重拉）；β 合规（他压根没改一级评论的刷新逻辑）。

**堵法**：AD-A8 要加一条「**自己刚发的一级评论必须在本次会话内置顶可见**」——
客户端在重拉后把本会话新发的 id 插到列表首位（不改服务端排序），
并明确该条不参与 keyset 游标（否则又是重复/漏条）。

---

### 🟡 H-16 — 游标从二元组换三元组，没有兼容条款；且 `FeedCursor` 是 Feed 与评论**共用**的类

**代码事实**：`content/service/FeedCursor.java` 编码 `base64url("<epochMicros>:<id>")`，
被 `FeedService` **与** `CommentQueryService` 共同使用；解码失败一律 `422 游标无效`。

**两个单元**

- **单元 α**：守 AD-A8.2「游标改三元组」，最短路径是**就地扩展 `FeedCursor`** ——
  同一个类，Feed 也跟着变了。
- **单元 β**：另起 `CommentHotCursor`，`FeedCursor` 不动 —— 也合规。

**为什么不兼容 / 为什么都不够**：
α 会连带改 Feed 的对外 token 格式（Feed 的游标在客户端内存里，但**灰度期两个后端实例并存**时会互相解不开）；
β 干净，但两条路都漏了同一件事：**存量 App**。
上线后老版本客户端拿着二元组 token 请求新端点 → `decode` 抛 → `422` →
**评论区「查看更多」在旧版本上直接坏掉**，而 App 是 store 分发、灰度不可控。

**堵法**：AD-A8 加一条「游标格式换代规则」：新建独立编解码器；
解码时**兼容旧二元组**（缺失 likeCount 视为「从头」或按 createdAt 降级路径），至少保留一个大版本。
spine 通篇没有任何一处提到「App 版本 skew」，而这是一个 store 分发的移动产品。

---

### 🟡 H-22 — 「KOMENTAR (N)」的 N 到底是哪个数，AD-A13.3 只禁了两处渲染，没定义口径

**代码事实**：
- `ContentDetailResponse.commentCount` ← `countByPostIdAndDeletedAtIsNull(postId)`
  —— **不过滤 `moderationStatus`、不过滤拉黑关系、含二级回复**。
- 列表查询 `topLevel()/replies()` ← 过滤 `deletedAt` **且** `moderationStatus=VISIBLE or 自己` **且** `UserHideRelationReader` 拉黑过滤。

**两个单元**：AD-A13.3 的「评论数由评论区标题承担」——
单元 α 直接用 `detail.commentCount`（一个已有字段，零接口变更，最符合「复用优先」）；
单元 β 用评论区自己算出来的可见条数（与用户实际看到的一致）。
两者在**有拉黑关系 / 有被下架评论 / 有二级回复**的帖子上必然不等。

现网这个不一致已经存在（底栏与评论区各显各的），只是 AD-A13.3 把它**收敛到一处渲染**之后，
「显示的那一个数到底是哪个」就成了必须定死的事 —— 而 spine 没定。

**堵法**：AD-A13.3 补一句「N = 该 viewer **可见**的一级 + 二级评论总数，由评论区接口下发；
`ContentDetailResponse.commentCount` 的既有口径不再用于渲染」（或反过来，但必须选一个）。

---

### 🟡 H-17 — `comment_likes` 的注销口径没写进 §4，而 CLAUDE.md 把注销级联列为安全攸关

AD-A7.1 说「**逐字对齐 `content_likes` 的形态**」—— 那是**列与约束**的形态。
`content_likes` 的注销口径是**「D1/A：账号就地匿名化、行为数据保留，不做级联删除」**
（见 `V20260831_1254__init_content_post_views.sql` 的注释）。
这条口径**不在表结构里**，「逐字对齐形态」不会把它带过来。

**两个单元**：单元 α 建表时给 FK 加 `ON DELETE CASCADE`（看起来更安全、也符合「注销级联删除」的项目护栏措辞）；
单元 β 按 `content_likes` 原样（无 CASCADE、无 `deleteByUserId`）。
两者对同一条 D1 决策做出相反解读，且 §4「安全攸关」只写了「评论点赞不改变任何可见性判定」，没有一个字提注销。

**堵法**：§4 加一行「`comment_likes` 注销口径 = 与 `content_likes` 同（就地匿名化，行为数据保留，不级联删除，不进 `AccountDeletionService`）」。

---

## 4. 攻击面 D：灯箱共享组件（本批次 × 批次 B1）

### 🔴 H-6 — 「只认 URL 列表 + 初始下标」的接口装不下 AD-A15.5 的飞入动画和 AD-A15.6 的缩略图预热

**三条 AD 同时成立时，接口是不够的：**

- AD-A14.2：组件接口**只认「图片 URL 列表 + 初始下标」**，不得耦合任何业务实体。
- AD-A15.5：过渡动画 **从缩略图原位置放大飞入、关闭时缩回原位，开关双向**。
- AD-A15.6：加载态 **先显示已有缩略图（模糊→清晰）**。

**AD-A15.5 在 Flutter 里就是 `Hero`。`Hero` 需要一个 tag，而 tag 必须由调用方与被调方共享。**
接口只有 URL 和下标 → 唯一可推导的 tag 就是 URL（或 `URL+index`）。

**两个单元**

- **单元 α（本批次 FR-115）**：tag = `'lightbox_${url}'`，详情页轮播的 `PageView` 一屏只挂一个当前项 —— 跑得通。
- **单元 β（批次 B1 FR-112 场所照片）**：完全按 AD-A14.1「`places` 侧接入它」，
  场所详情页的典型版式是**头图 + 下方照片网格**，同一张照片在一屏内出现两次。
  → **同一 tag 的两个 Hero 同屏 → Flutter 直接抛 `There are multiple heroes that share the same tag`。**

α 与 β 都逐字守了 AD-A14.2（谁都没往接口里塞业务实体），产物却在 B1 上线当天崩。

**AD-A15.6 是同一个洞的第二面**：详情页缩略图用的是 `AppImage.widget(url, thumbWidth: 1080)`，
即缓存键里带着变体宽度。灯箱要「先显示已有缩略图」，就得知道调用方用的是哪个变体。
接口只有 URL → 组件只能猜一个宽度 → 猜错就是**缓存未命中，退回白屏**，
B1 的场所照片若用别的 `thumbWidth`，「模糊→清晰」在 B1 上等于不存在。

**堵法**：AD-A14.2 的「只认 URL + 下标」要放宽为一组**明确的、与业务无关的**参数：
`urls`、`initialIndex`、`heroTagBuilder(index) → Object`（由调用方提供、保证同屏唯一）、
`thumbWidth`（调用方的缩略图变体宽度）。
这三个都不耦合内容帖或场所，AD-A14.2 的意图不受损，但**必须现在写进 AD** ——
否则 B1 只有两条路：改接口（违反「本批次不为场所场景做任何特化」的隐含冻结）或另写一个灯箱（AD-A14 的 Prevents 当场失效）。

---

### 🟠 H-7 — AD-A14.2（禁业务耦合）× AD-A23（属性不得增删）= B1 的灯箱埋点必然污染 FR-115 的指标

- AD-A23：`lightbox_opened` / `lightbox_dismissed` 的属性只有 `dismiss_gesture`、`max_zoom_used`，
  **「实现不得自行改名或增删属性」**。
- AD-A14.1：`places` 侧接入同一个组件。
- AD-A14.2：组件不得知道自己在为谁服务。

**两个单元**：本批次把埋点埋在共享组件内部（最自然，也保证两边一致）；
B1 接入组件 → **场所照片的每一次打开都算进 `lightbox_opened`，且无法区分**。
FR-115 的验收指标（详情页大图查看行为）从 B1 上线那天起断裂，而回溯不了。

B1 的三条路都撞墙：加 `source` 属性 → 违反 AD-A23；
给组件传一个 `analyticsSource` → 违反 AD-A14.2 的字面（虽然它并不是业务实体）；
不接组件 → 违反 AD-A14.1。

**堵法**：AD-A23 现在就为这两个事件预留一个 `surface` 属性（值域 `content_detail | place_photos`，
本批次恒为 `content_detail`），并在 AD-A14.2 的例外清单里点名「`surface` 字符串不算业务耦合」。
**跨批次共享组件的埋点归属，必须在共享它的那一批里定，不能留给接入方。**

---

### 🟡 附：`dismiss_gesture` 的值域没定，且系统返回键这条路径没人管

AD-A15.2 定死了手势优先级，AD-A15.1 要求「异常退出路径也必须恢复系统栏」，
但 `dismiss_gesture` 的取值（`tap` / `swipe_down` / `close_button` / `system_back` / `page_pop`）
一个都没列。现网灯箱**点图即关**（`onTap → pop`），AD-A15 保留了它（PRD 第 6 条），
于是至少四条关闭路径共用一个未定义值域的属性。见 H-13。

---

## 5. 攻击面 E：图片顺序与上传

### 🟡 H-15 — 「只有一份顺序数据」没说这份数据住在哪，而上传状态机遍历的是快照

**代码事实**（`domain/publish_controller.dart`）：
`items` 是唯一的顺序源，`publish()` 用 `items.map(url)` 与 `items.map(size)` **同序生成两个数组**——
所以只要重排落在 `items` 上，URL 与尺寸列天然同步（这一点 spine 运气好，蒙对了）。

但 `_upload()` 遍历的是 `items.where(selector).toList()` 的**快照**，
而 AD-A16 允许「即选即传」期间拖拽（只有发布按钮置灰，重排没被禁）。

**两个单元**

- **单元 α**：把顺序做在 `PublishController.reorder(oldIndex, newIndex)` 上 —— 完全合规，最干净。
- **单元 β**：拖拽重排需要逐格动画与 `RenderBox` 命中测试，**自实现不引包**（AD-A16.1），
  于是在 widget 里维护 `List<int> _order` 做视觉映射，发布时……忘了它。
  β 也读得通 AD-A16.2 ——「界面顺序即上传顺序」他理解为「渲染时按 `_order` 映射即可」。

β 的产物：界面第 1 格显示 B，`publish()` 上传的第 1 个 URL 仍是 A。
**首图错 → Feed 封面错 → 详情页首图错 → 分享卡主图错**（AD-A16.3 点名的三处全错）。

**spine 未覆盖的两个组合态（AD-A16 一个字没提）**：
1. **部分成功**：3 张里第 2 张 `failed`，用户把失败那张拖到第 1 格 →
   「封面」角标（AD-A16.3 要求常驻第一格）与失败态盖层 + 重试按钮**叠在同一格**，谁在上、能不能点，无定义。
2. **上传中重排**：`_upload` 的快照与重排后的 `items` 顺序不同 —— 状态本身安全（挂在 item 对象上），
   但「上传进度按第几格显示」的口径没定。

**堵法**：AD-A16.2 补一句「顺序的唯一持有者是 `PublishController.items`，
widget 不得持有第二份顺序或索引映射」；
AD-A16.3 补「首图角标与失败/上传中盖层的层级与互斥」；
补一条「上传进行中是否允许重排」的明确取值。

---

## 6. 攻击面 F：分享奖励与配置（钱）

### 🔴 H-5 — AD-A20 要求「单日上限」，但 §5 没给账本表、AD-A22 禁止新增，且既有渠道的「日」是 WIB 不是 UTC

**代码事实**（`share/service/IdCardShareRewardService`、`share/repository/IdCardShareRewardRepository`）：

- 全局层 `ShareRewardService`：月度上限，**月界按 WIB**（源码注释：「刻意偏离项目全局 UTC 惯例…**勿订正**」）。
- 渠道层 KTP：`countByUserIdAndShareDate(userId, shareDate)`，`shareDate` 是 **WIB 当地日**的 `LocalDate`，
  存在**独立账本表 `id_card_share_rewards`**（还带 `uq_..._profile`：一个档案一辈子只发一次）。

**AD-A20.1 说「与既有 KTP 渠道两列同构」——「同构」只覆盖了配置表的两个列，没覆盖：**

| 缺失项 | 后果 |
|---|---|
| **账本表** | 没有 `(user_id, share_date)` 的行，`单日上限` 无从计数。§5 只列了四支迁移，AD-A22 说「新增开销 = 四支迁移 + 两处查询」——**第五支迁移是必须的，但被 AD-A22 禁止**。 |
| **日界时区** | 既有渠道是 WIB；本 delta §4 通篇「timestamptz，UTC」，AD-A20 只字未提时区。 |
| **去重键** | KTP 是「一个档案一辈子一次」；年龄卡 PRD 明写「生成与分享**免费、无次数限制**」→ 没有天然去重键，**日上限成了唯一闸门**，用户每天都能刷满。 |
| **服务端有无记录** | §4「安全攸关」写着「年龄卡**不落服务端**、不产生可外露标识」，而发币必须在服务端留痕 —— **spine 的两节自相矛盾**。 |

**两个单元**

- **单元 α（FR-65 后端奖励 story）**：按 delta 全局口径实现，`share_date` 用 **UTC** 当日；
  为了有地方计数，新建第五支迁移 `age_card_share_rewards`（于是撞 AD-A22，要「先回来改 AD」→ 阻塞）。
- **单元 β（后台配置 story）**：AD-A20.4 要求「后台配置页新增两项、进既有配置变更日志」，
  运营在后台看到的「单日上限」旁边写的是与 KTP 渠道一致的「按印尼当地日」。

**结果**：运营配 3 次/日，代码按 UTC 切日 —— 印尼时间**每天早上 7 点重置**，
用户在 06:00–07:00 之间能拿到「昨天的余额 + 今天的额度」。
这是钱，而且是 v1.1.6 已经踩过一次、并在源码里用「**勿订正**」四个字警告过的坑。

**堵法（必须的三条新规则）**：
① AD-A20 补「渠道层账本表」为**第五支迁移**，并同步修正 §5 与 AD-A22 的「四支」措辞；
② 明写「**渠道日上限与全局月上限一律按 WIB 切界**，与 `ShareRewardService.periodOf` / `IdCardShareRewardService` 同口径，
这是全局 UTC 惯例的既有例外，勿订正」；
③ 明写年龄卡渠道的**去重键**（建议 `(user_id, pet_profile_id, WIB 当日)` 或干脆「每档案每日一次」），
否则「无次数限制的分享」× 「按次发币」= 刷币入口；
④ 订正 §4 的「年龄卡不落服务端」——它对**卡片**成立，对**奖励留痕**不成立，现在这句话会让人以为不用建表。

---

## 7. 攻击面 G：入口迁移、引导标记、共享页头

### 🟠 H-9 — `DiaryHeader` 是 owner / 游客 / 访客**三态共用**的同一个组件，AD-A17 与 AD-A2 都往它身上加东西，都没给三态分支

**代码事实**：`widgets/diary_header.dart` 同时被
`growth_archive_page.dart`（本人）、`diary_guest_page.dart`（游客种草页）、
`visitor_archive_view.dart`（**别人的宠物**）使用；
KTP 入口 (`onOpenIdCard`) 与里程碑进度条 (`onOpenMilestones`) 都在它里面。

**两个单元**

- **单元 α（FR-65）**：AD-A17.1「KTP 入口条改造为「Know more about your pet」聚合入口」——
  改的就是 `DiaryHeader` 里那一条。
- **单元 β（FR-111）**：AD-A2.2「档案 Tab 的里程碑进度条入口加角标」——
  改的也是 `DiaryHeader`。

**为什么不兼容**：两人都只改了组件，没人改三个调用点。结果：
- **访客视图**（看别人的宠物）出现「Know more about **your** pet」，点进去是**自己的** KTP / 自己的年龄卡（`/me` 语义）；
- **访客视图**的里程碑条上挂着**观看者自己**的未庆祝角标，点进去弹的是自己的庆祝页 —— 在别人的档案里；
- **游客态**（未登录）同样渲染这条入口，而聚合页在 `/profile/` 前缀下受控 → 点了撞登录框
  （`diary_header.dart` 的注释已经为这类事故留过话：「不得注入 `/profile/health`、`/profile/id-card` 等受控页 —— 游客点了会在种草页中间撞上登录框」）。

**堵法**：AD-A17 与 AD-A2 各补一条「三态口径」：
入口改名对三态生效但**访客/游客态去向与角标一律不渲染**（`readOnly` 已在 `DiaryHeader` 的参数里，用它）。

---

### 🟡 H-12 — 聚合页路由挂在哪儿没定，而游客门控是 `/profile` 的 `startsWith` 前缀匹配

**代码事实**：`_controlledLocations = {'/profile','/triage','/me','/consult','/notifications','/publish'}`，
判定 `path == p || path.startsWith('$p/')`，例外集只有精确 `/profile`。
另有一个**必须人肉同步的孪生纯函数** `redirectWouldRewrite`（注释：「逻辑镜像下方 GoRouter redirect，改守卫时**必须同步维护**」）。

AD-A17.1 只说「新增聚合页路由」，没说路径。
**单元 α** 放 `/profile/insights` → 自动受控，游客被挡；
**单元 β** 放 `/pet-insights`（顶层，语义上「不属于宠物档案」）→ **对游客完全敞开**，
且 AD-A17.2 的重定向会把受控的老路径 `/profile/id-card` 重定向到一个**不受控**的新路径 ——
等于亲手拆掉一道现有的门。

**堵法**：AD-A17.1 明写「聚合页与其全部子路由**必须落在 `/profile/` 前缀下**（游客门控依赖前缀匹配）」，
并提醒重定向落地后同步 `redirectWouldRewrite` 的孪生用例。

---

### ⚪ H-20 — 第三个一次性引导没有会合点：AD-14 有队列，AD-A21 明令「不得互相套用」

v1.1.6 **AD-14 Rule 7** 定死了同一时机多个一次性引导的**排队规则**（先推送权限、后手机号，**不叠同屏**），
Rule 2 定死了「四键物理隔离」。

AD-A21 引入**第三类**一次性引导（认知性告知 · 按账号 · 服务端表），
第 2 条明写「两条并存，各管各的场景，**不得互相套用**」——
这句话解决了「标记存哪」，却**顺手废掉了排队规则**：AD-A21 的蒙层不进 AD-14 的队列，
两者在同一屏命中时谁先谁后、能否叠加，**无人定义**。

「将来第三个引导该走哪套」——AD-A21.2 给的判据是「**设备级状态 → 按设备；认知性告知 → 按账号**」。
这个判据够用来选**存储**，但下一个人还要问两件 AD-A21 没答的事：
① 它进不进 AD-14 的排队？②「按账号」的引导在**游客态**怎么办（见 H-21）。

**堵法**：AD-A21 补 Rule 5「一次性引导的**同屏排队**统一走 AD-14 Rule 7 的队列，
新引导必须登记优先级；存储介质（prefs / 服务端表）与排队规则是**两件独立的事**，不因存储不同而脱离队列」。

**附 · 事实错误**：AD-A21.4 写「触发时机：首次进入成长档案页（**含从建档庆祝页「查看档案」进入**）」。
现网**没有这个入口** —— `app_router.dart` 里 `ProfileCreatedCelebrationPage` 的主 CTA
已于 **2026-08-27 产品拍板**改为「记录第一个瞬间 📸」→ **直接 `go('/publish')`**，
源码注释详述了改动理由。spine 沿用了 PRD 的旧描述。

---

### ⚪ H-21 — 「按账号存」的引导标记，在游客态无处落地

`DiaryHeader` 在 `diary_guest_page` 下也渲染那条入口（H-9），
而 AD-A21.4 的触发条件是「首次进入成长档案页」——游客也进得来。
标记按账号存（AD-A21.2）、写入要打 `/api/v1/me/...` → 游客 401。

**两个单元**：α 判定「游客不弹」（合理但 AD 没说）；β 判定「弹了但写不进去」→ 每次进都弹。
**堵法**：AD-A21 补一条「未登录态不触发；登录后按账号首次触发」。

---

## 8. 攻击面 H：埋点与时间口径

### 🟡 H-13 — AD-A23 钉死了事件名与属性名，**一个值域都没钉**

PRD §4 与 AD-A23 给了名字，没给取值词表。需要词表的至少五处：

| 属性 | 缺失的词表 | 会怎么分叉 |
|---|---|---|
| `size_class` | 小/中/大/超大 | `small\|medium\|large\|giant` vs `S\|M\|L\|XL` vs `<9kg\|9-23kg\|…` |
| `canvas` | 9:16 / 1:1 | `9_16\|1_1` vs `portrait\|square` vs `1080x1920\|1080x1080` |
| `comment_level` | 一级 / 二级 | `1\|2` vs `top\|reply` vs `root\|child` |
| `dismiss_gesture` | 四条关闭路径 | 见 §4 附 |
| `species` | 猫/狗/其他 | `CAT\|DOG\|OTHER`（后端枚举）vs `cat\|dog`（前端 l10n key） |

**两个单元**：FR-65 的前端 story 与 FR-114/FR-115 的前端 story 由不同人做，
各自都「按 PRD §4 落地、没改名、没增删属性」—— 完全合规，词表却各写各的。
只有 `path`（instant/catchup/revisit）因为 AD-A23 列了值才安全。

**代码里已有正确做法可抄**：`image_crop.dart` 的 `CropPreset.analyticsValue`
（埋点值与枚举分离、集中定义）。AD-A23 应点名这个先例。

---

### 🟡 H-18 — 年龄换算的「按天」判定没有时区口径

AD-A18.3：「年龄按档案生日与**生成当日**算。」
生日是后端 `LocalDate`；「当日」在客户端就是 `DateTime.now()` 的**设备本地日**。

**两个单元**：α 用设备本地日；β 参照站内既有「按当地日切」的先例用 WIB（这个先例在钱那条链路上是**强制**的，见 H-5）。
生日当天，一台设备显示「1 岁 → 15 人岁」，另一台显示「0 岁 11 个月 → 14 人岁」。
娱乐工具，后果轻；但这是一张**要拿去社交传播、并且承诺「对得上任何一家宠物网站」的卡**（PRD 换算规则的论据原文），
两台手机算出不同数字正是它最怕的质疑。

**堵法**：AD-A18.3 补「当日按设备本地日（明确不用 WIB，理由：这是纯本地娱乐工具、无跨端一致性要求）」——
**取哪个不重要，写下来才重要**；顺带说明它与 H-5 的奖励日界**刻意不同**，避免下一个人去"统一"。

---

### 🟡 H-19b — 「>7 天改绝对日期」只绑详情页与评论，Feed 卡片不在本批次

AD-A10.3 保证了**详情页与评论区**同一个格式化函数。但 Feed 卡片的时间显示不在批次 A 范围。
FR-113 的立意是「消灭从 Feed 点进详情的跳变」（图片比例）——
本批次会**新造一处跳变**：同一条帖在 Feed 显示「35 天前」，点进详情显示「2026-08-06」。
**堵法**：要么把 Feed 卡片时间纳入 AD-A10.3 的「同一个格式化函数」，要么在 spine 里显式接受这个不一致并记入 Deferred。

---

## 9. spine 内部自相矛盾清单（不需要构造两个单元，照做就写不出来）

| # | 冲突 | 位置 |
|---|---|---|
| C-1 | 禁冗余计数列 × 要求覆盖排序键的索引（排序键是聚合值） | AD-A7.3 × AD-A8.5 → H-3 |
| C-2 | 「年龄卡不落服务端」× 分享奖励必须服务端留痕并计日上限 | §4 × AD-A20 → H-5 |
| C-3 | 「新增开销 = 四支迁移」× 渠道日上限需要第五张账本表 | §5 / AD-A22 × AD-A20.1 → H-5 |
| C-4 | 「跳转映射表必须与后端等长同集」× 「M5/G-M1 无健康记录类型可指」 | AD-A4.2 × AD-A6.2 → H-10 |
| C-5 | 「组件接口只认 URL + 下标」× Hero 飞入需共享 tag、缩略图预热需变体宽度 | AD-A14.2 × AD-A15.5/15.6 → H-6 |
| C-6 | 「组件不得知道调用方」× 「埋点属性不得增删」× 「B1 接入同一组件」 | AD-A14.2 × AD-A23 × AD-A14.1 → H-7 |

---

## 10. 建议的最小补丁集（按性价比排序）

1. **AD-A2.3 改「显式 code 列表回报」**，禁止服务端 mark-all（H-2）。
2. **新增 AD-A2.7「本会话已弹集合」**，让 instant / catchup / revisit / 短轮询四条路径有一个共同的去重面（H-1）。
3. **新增「里程碑完成寻址口径」AD**：完成 API 改传完整 code；G 系后缀撞车产生的 G-M3 / G-M4 脏完成行，
   排除在 AD-A1.4 回填之外（H-11 —— **这条是现网正在发生的数据错误，不是未来风险**）。
4. **AD-A7/A8 二选一定死**：删 AD-A8.5，或为 `comments.like_count` 单开一条冗余列 AD（H-3）。
5. **AD-A20 补齐四件事**：账本表（第五支迁移）、**WIB 日界**、去重键、订正 §4 的「不落服务端」（H-5，涉及钱）。
6. **AD-A14.2 放宽为四参数接口**（urls / initialIndex / heroTagBuilder / thumbWidth）（H-6）。
7. **AD-A23 预留 `surface` 属性 + 补全五个值域词表**（H-7、H-13）。
8. **AD-A2.2 点名 `ArchiveStatsResponse`**，并写死补庆祝后的 provider 失效清单（H-8）。
9. **AD-A17 / AD-A2 各补「DiaryHeader 三态口径」**（H-9）。
10. **AD-A4 拆三段**（功能集合 / 去向映射全函数 / `TimelineClassifier` 明令排除）（H-10）。
11. **AD-A8 补「自己刚发的评论必须可见」+ 游标换代兼容条款**（H-4、H-16）。
12. AD-A13.3 定义 N 的口径；AD-A16 定义顺序持有者与失败态叠加；AD-A21 接入 AD-14 的排队 + 游客分支
    + 订正「建档庆祝页「查看档案」」这个已不存在的入口（H-22、H-15、H-20、H-21）。

---

## 附录 · 本次评审核实过的代码位置

| 主题 | 文件 |
|---|---|
| 健康类集合（四份） | `petgo-backend/.../profile/domain/HealthMilestones.java`、`.../service/TimelineClassifier.java:52`、`petgo_app/lib/features/profile/domain/health_milestones.dart`、`petgo_app/lib/features/profile/presentation/milestone_list_page.dart:918` |
| 完成态后缀寻址 | `petgo-backend/.../profile/service/MilestoneCompletionService.java`（`complete`）、`.../MilestoneAutoCompleteListener.java`、`.../domain/MilestoneCatalog.java:192-195` |
| 里程碑接口与档案统计 | `.../profile/web/MilestoneController.java`、`.../dto/MilestoneListResponse.java`、`.../dto/ArchiveStatsResponse.java`、`petgo_app/lib/features/profile/presentation/growth_archive_page.dart:340-370` |
| 共享页头三态 | `petgo_app/lib/features/profile/presentation/widgets/diary_header.dart`、`visitor_archive_view.dart:65`、`diary_guest_page.dart:86` |
| 评论计数 / 排序 / 游标 | `petgo-backend/.../content/repository/CommentRepository.java:47,144-167`、`.../service/CommentQueryService.java`、`.../service/FeedCursor.java`、`.../dto/ContentDetailResponse.java` |
| 评论刷新与回复态 | `petgo_app/lib/features/content/presentation/comment_composer.dart:56-70`、`comment_section.dart:189` |
| 灯箱与详情图 | `petgo_app/lib/features/content/presentation/content_detail_page.dart:415-545`、`domain/feed_image_layout.dart` |
| 发布顺序与上传状态机 | `petgo_app/lib/features/content/domain/publish_controller.dart`、`presentation/publish_compose_page.dart`、`domain/image_crop.dart` |
| 分享奖励两层 + WIB | `petgo-backend/.../share/service/ShareRewardService.java`、`.../IdCardShareRewardService.java`、`.../repository/IdCardShareRewardRepository.java`、`.../config/domain/PawCoinConfig.java` |
| 路由门控与引导 | `petgo_app/lib/core/router/app_router.dart:126-206,645-690`、`petgo_app/lib/features/notify/domain/push_permission_prompt.dart`、`core/storage/prefs.dart` |
| 注销口径先例 | `petgo-backend/src/main/resources/db/migration/V20260831_1254__init_content_post_views.sql:5`、`V10__init_content_likes.sql` |
