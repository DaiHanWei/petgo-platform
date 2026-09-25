---
title: 'V1.3.0 批次 A 架构 Delta —— Rubric Walker 评审'
target: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-delta.md
reviewer: 'rubric walker（BMAD 架构 spine）'
date: 2026-09-10
verdict: 'CHANGES REQUESTED —— 骨架方向对，但有 2 条会让 story 直接卡死/写错的硬伤，另有 4 条真分叉未钉'
---

# V1.3.0 批次 A 架构 Delta —— Rubric 评审

> 评审方法：按「好 spine」七条清单逐条走；每一条涉及既有代码的判断都回仓库实读核对（引用处均给了文件与行）。
> 结论优先级用 **critical / high / medium / low** 标注。critical = 照现文档拆 story 会写出错误实现或直接卡住。

## 0. 总体判定

**这是一份质量在平均线以上的 delta。** 它做对了三件 spine 该做的事：

1. **敢订正上游**。AD-A1 把 PRD 写的「`pet_milestones` 加 `celebrated_at`」纠到 `milestone_completions`，我照 `V27__init_milestones.sql` 核过——`pet_milestones` 是建档即全量铺行的目录实例，`milestone_completions` 才是完成事实且已有 `uq_milestone_completions_milestone` 唯一约束。这条订正**完全正确**，而且救掉了一个"恒空列 + 每次判定都要 join"的坏形状。
2. **拒绝了两条最容易被顺手引进来的东西**：冗余计数列（AD-A7.3，与全库现状一致，`V86` 的列注释明写 `content_likes` 是实时 COUNT）、第三方包（AD-A15/A16，`pubspec.yaml` 实读确认无 photo_view / reorderable 类依赖）。
3. **把几条真分叉钉死了**：灯箱上提共享层（AD-A14，现状确为 `content_detail_page.dart:504` 的私有 `_Lightbox`）、比例走同一出口函数（AD-A11.2，现状确有唯一出口 `resolveFeedImageAspect`）、旧 KTP 路径必须重定向（AD-A17.2）。

**但它有一个系统性的失误：把整个批次的接口面严重低估了。** 文档反复宣称「零/近零接口变更」「运行时增量 = 四支迁移 + 两处查询」（§1.1、AD-A22），而实读代码后至少有 **三个新写端点 + 一个必须新增的响应字段 + 一张漏掉的记账表**。这不是措辞问题——AD-A22 的那句枚举正是 story 拆分时会被当成清单用的东西，照它拆会漏掉半个后端。

另外**里程碑那条线的 brownfield 核对做浅了**：AD-A4/A5 只核到了「判定集合」这一层，没核「完成映射」那一层，而后者是按「后缀 + 物种前缀」寻址的，通用宠物上现在就在点亮**错误的里程碑**。文档反而写了「现无用户可感知影响」。

---

## 1. 它是否钉住了下一层真正会分叉的点？

### 钉得好的（确认有效，不必再议）

| AD | 钉住的分叉 | 核对结论 |
|---|---|---|
| AD-A1 | 庆祝态落哪张表 | ✅ 正确，且订正了 PRD（`V27__init_milestones.sql:25-38`） |
| AD-A2.1 | 补庆祝挂哪个页面（两处都弹 vs 只列表页弹） | ✅ 真分叉，钉法可验收 |
| AD-A7.3 | 加不加冗余计数列 | ✅ 真分叉，与既有 `content_likes` 口径一致 |
| AD-A11.2 | 详情页写第二套比例规则 vs 走同一出口 | ✅ 出口函数 `feed_image_layout.dart:141 resolveFeedImageAspect` 确实存在且以 `maxImageHeight` 为入参，详情页可传不同值——**这条是可落地的** |
| AD-A14 | 灯箱私有 vs 共享 | ✅ 真分叉（B1 会复用），钉法明确 |
| AD-A16.2 | 界面顺序 vs 上传顺序两套 | ✅ 且现状天然满足：`publish_controller.dart` 的 `items` 是 bytes/url/size 三合一的单一列表，重排它 = 尺寸数组同序重排。**建议把这句写进 AD**（「重排必须作用于 `items` 单一列表」），否则新人可能只重排 url 列表，那会直接违反 v1.1.6 AD-5「同序等长是硬约束」，后果是每张图在 Feed 里都拿到别人的比例 |
| AD-A9 | 作者标签要不要新字段 | ✅ 实读确认 `ContentDetailResponse.authorId` 与 `CommentResponse.authorId` 都在，客户端直接比成立 |

### 漏掉的分叉点

**F-1 · [critical] 详情页拿不到图片尺寸 —— AD-A12 立了规矩却没给数据来源**

AD-A12.1 规定「尺寸一律取 v1.1.6 AD-5 的尺寸列」，AD-A12.2 明令「不采纳现场取尺寸」。但实读：

- `ContentDetailResponse`（`petgo-backend/src/main/java/com/tailtopia/content/dto/ContentDetailResponse.java:20-46`）只有 `List<String> imageUrls`，**没有 `imageSizes`**；
- 有 `imageSizes` 的只有 `FeedItemResponse.java:61`；
- 这正是 v1.1.6 AD-5 第 1 条主动定的：「既有读取点（Feed 首图 / 内容详情 / 时间线 / 宠物名片 / 后台）一处都不动」。

**所以 FR-113 必须改详情接口**，加一个与 `imageUrls` 同序等长的 `imageSizes`。而本 delta §1.1 白纸黑字写 FR-113 是「纯客户端改造（零接口变更）」。

后果不是"少写一行文档"：一个照 AD-A12 施工的前端工程师会发现详情响应里根本没有尺寸，此时他面前是三条路——(a) 回头改后端（对的）、(b) 偷偷现场测量（AD-A12.2 明令禁止，但这是阻力最小的路）、(c) 全部走占位比例（等于 FR-113 白做）。**spine 的职责就是不让这个岔口出现在 story 里。**

修法：AD-A12 增一条「详情接口须并排下发 `imageSizes`，与 `imageUrls` 同序等长，口径逐字对齐 `FeedItemResponse`；服务端只下发原始宽高，不 clamp（v1.1.6 AD-6.6）」；§1.1 与 AD-A22 的「零接口变更」相应订正。

**F-2 · [critical] G-M1/G-M2 改自动达成，缺一支存量迁移 + 一处映射改造**

三处实读：

1. `pet_milestones` 表**物化了 `trigger_type`**（`V27__init_milestones.sql:12-22`），`MilestoneService.assignRoster`（`MilestoneService.java:54-58`）**已存在 roster 就整体跳过**——改 `MilestoneCatalog` 常量对**存量档案一行都不会改**。而 `MilestoneService.java:126` 下发给客户端的 `triggerType` 取的是**DB 行**不是 catalog。⇒ 上线后所有存量「其他宠物」档案的 G-M1/G-M2 仍报 `USER_CHECKIN`。
   项目自己有先例：`V77__alter_milestone_newbie_tasks.sql` 就是为 catalog 变更专门补的一支 roster 数据迁移。**§5 的四支迁移少了第五支**：`UPDATE pet_milestones SET trigger_type='SYSTEM_AUTO' WHERE code IN ('G-M1','G-M2')`。

2. **完成映射是「后缀 + 物种前缀」寻址，不是按 code**。`MilestoneAutoCompleteListener.suffixFor()`（`:117-124`）产出 `"M3"/"M4"/"M9"`，`MilestoneCompletionService.complete()`（`:81`）再拼 `prefixOf(petType) + "-" + suffix`。对 `OTHER` 宠物：
   - `VACCINE → "M3" → G-M3`，而通用清单里 **G-M3 是「陪伴满 30 天」**（`MilestoneCatalog.java:190`）；
   - `DEWORM → "M4" → G-M4 =「成长日历记录满 10 条」**；
   - `NEUTER → "M9"`、`ConsultClosed → "M5"`：通用清单无此 code，静默 no-op。

   **即：现网上，给"其他宠物"录一条疫苗记录，点亮的是「陪伴满 30 天」。** 这是一个比 A-2 那个"白点"更严重的现网 bug，而 AD-A4.3 写的是「顺带消除 G-M3 / G-M4 的误判隐患 —— **现无用户可感知影响**」。这句话是错的，而且方向恰好相反：错点亮一个里程碑会发通知、会进 KOLEKSI、会弹庆祝，是十足可感知的。

   更要紧的是：**AD-A5.1「G-M2 只由 VACCINE 触发」在现有寻址机制下根本无法直接实现**——`VACCINE` 的后缀是 `M3`，通用清单要点的是 `G-M2`。必须改造映射（按物种给出目标 code，或把 `completeForOwner` 的入参从 suffix 换成 code）。这是本批次后端最实质的一处改造，delta 里**一个字都没有**。两个 story 会各自发明写法。

3. AD-A4.4 说「三处覆盖缺一不可：列表页点击去向 / 已打卡候选过滤 / 后端拒绝打卡护栏」。实读 `HealthMilestones` 的引用点是**四处**（其 Javadoc 自己也列了三处后端 + 提到前端另有一份）：
   - `MilestoneCheckInService.java:72` 拒绝打卡 ✅ 在清单里
   - `MilestoneAnalyticsPath.java:56` 埋点 T-12 path 映射 ❌ 不在清单里
   - 自动完成映射（上面第 2 点）❌ 不在清单里
   - 前端 `milestone_list_page.dart:918 healthPresetTypeFor` ✅ 在清单里
   - 另有 `TimelineClassifier.java:130` 自己复制了一份 `HEALTH_MILESTONE_SUFFIXES`（用途不同，Javadoc 已声明刻意分开——**这条要在 AD 里点名"刻意不动"**，否则"必须与之等长同集"这句会被理解成连它一起改）

   `MilestoneAnalyticsPath` 那处还有一个具体错误在等着：它按 `suffix == "M5"` 判 `consult`、其余健康类判 `health_record`。G-M1 是**兽医咨询触发**的，但后缀是 `M1` ⇒ 会被标成 `health_record`。AD-A4 一旦把 G-M1 纳入健康集合，就必须同时改这处判定，否则埋点口径当场错。

**F-3 · [high] 年龄卡分享奖励：整条服务端链路没定**

`AD-A18「不落库不建接口」` 与 `AD-A20「接入 FR-96 奖励体系」` 是直接张力——发币必然要一次服务端调用。实读既有 KTP 渠道：

- 端点：`ProfileApiController.java:240 POST /me/id-cards/{cardId}/share-rewards`，**挂在一个服务端实体（cardId）上**；
- 去重与日限记账：`V20260825_0248__create_id_card_share_rewards.sql`，`UNIQUE(pet_profile_id)`（一个档案一辈子一次）+ `(user_id, share_date)` 索引算日上限；
- 月度额度：`share_reward_quotas`（**刻意没有渠道列**，防止上限被乘以渠道数）。

年龄卡**没有任何服务端产物**（无 card、无 post、无 token），且 PRD 明写「生成与分享免费、无次数限制」。于是：

- 领奖端点长什么样、挂在哪个资源下（`/me/...` 的什么）？未定。
- **日上限靠什么记账**？`id_card_share_rewards` 的唯一键是 `pet_profile_id`，塞不进第二个渠道。要么加一张 `age_card_share_rewards`，要么把它泛化成带 `channel` 列的通用表。**§5 只加了配置两列，缺这张记账表。** 这是两个 story 会各自选路的典型分叉。
- **反刷**：KTP 渠道天然有"一个档案一辈子一次"这道结构性闸门；年龄卡无限生成 + 客户端出图 + 客户端自报"分享成功"，唯一闸门就是日上限与月度上限。delta 对限流、去重键、"分享成功"如何认定**全无一字**。这属于变现相关面，按 CLAUDE.md 的口径应当在 spine 上有姿态。

**F-4 · [high] 三个新端点在文档里不存在，AD-A22 的枚举因此是错的**

盘一下必然新增的写接口：

1. 庆祝回报（AD-A3.1「展示成功后回报一次」）—— 端点未定；
2. 一次性引导标记的读 + 写（AD-A21，服务端表意味着必有接口）—— 未定；
3. 年龄卡领奖（F-3）—— 未定。

而 AD-A22 写的是「新增的全部运行时开销是四支 Flyway 迁移与两处查询」。AD-A22 的 **Rule 本身没问题**（不引中间件/包/定时任务），问题是这句枚举会被当清单用。改法：把枚举换成「新增接口一律走 `/api/v1/me`、RFC 9457、不引入新运行时组件」，并在 §4 列出三个端点的归属模块。

**F-5 · [high] 评论游标：`FeedCursor` 是共享类型，改三元组会溢出到 Feed**

`CommentQueryService.java:180-189` 直接用 `FeedCursor`（`content/service/FeedCursor.java`）编解码评论游标，Feed 时间序也用它。AD-A8.2 说「游标由 `(createdAt,id)` 改为 `(likeCount,createdAt,id)` 三元组」，却没说**在哪改**。改 `FeedCursor` 本身就会波及 Feed 与访客投影。

项目里已有一份写得很好的教训可以照抄：`FeedRankCursor.java:20-33` 专门加了 `SEED_PREFIX`，理由原文是「两者都是 `base64url("<a>:<b>")`，不加区分标记时会被**静默解出**一个看似合法的值……且服务端一条错都不记」。三元组评论游标与二元组评论游标之间**存在完全相同的静默误解码风险**（旧客户端在灰度期持二元组游标打新服务端）。

AD-A8 应补两条：① 新建独立的 `CommentHeatCursor`，**不得改 `FeedCursor`**；② 编码空间与旧游标隔离，旧格式一律拒（422），不做"尽力解读"。

**F-6 · [medium] AD-A15 的手势优先级漏了「单击 vs 双击」**

AD-A15.2 自称「**这个顺序是防冲突的全部**」，但它只排了 平移 / 翻页 / 下滑关闭 三者，没有排 **单击关闭 vs 双击缩放**。而这恰恰是本批次里唯一有明确判例的冲突——PRD FR-113 第 3 条取消双击点赞的理由原文就是「避免单击开灯箱被双击判定拖慢 300ms」。灯箱内 PRD 又要求同时保留「点击关闭」（3.5 已确认项 6）和「双击缩放」（同 3），两者共存必然引入单击判定延迟或误触。

这是 spine 该裁的：要么"单击立即关闭、双击缩放靠 `onDoubleTapDown` 抢占"，要么"取消单击关闭、只留 ✕"。现在留白，两个实现会不一样，且都会在 L2 才被发现。

**F-7 · [medium] AD-A17 只给了一条重定向，KTP 的兄弟路由没定**

实读 `app_router.dart:675-680`：`/profile/id-card`（列表）、`/profile/id-cards/create`、`/profile/id-cards/:id` 是三条**平级**路由。AD-A17.1「KTP 页整体平移到新路径下」+ A17.2 只要求 `/profile/id-card` 重定向 ⇒ 没说 create 与 `:id` 是否随迁。随迁则要两条以上重定向；不随迁则出现"父页在新路径、子页在旧路径"的分裂层级。真分叉，一句话可闭合。

（附带核实：后端源码与迁移里**没有**任何指向 `/profile/id-card` 的深链，A17.2 说的"潜在历史通知深链"是推测；站内两处跳转属实——`growth_archive_page.dart:368` 与 `:706`。重定向该做，但"断链是硬失败"的措辞略强于事实。）

---

## 2. 每条 Rule 是否可执行、可验收？

逐条走过 A1–A23，绝大多数是**可判定**的（有明确的表、列、集合、顺序、开关）。以下几条是口号，落不到验收：

**F-8 · [medium] AD-A11.3「高度护栏的可视区口径按详情页实测重取」——没有口径，就没有护栏**

对照 v1.1.6 AD-6.2：它把三个被减项一个一个钉死了（可视区 = `LayoutBuilder` 实测、条目其余部分 = 142 且逐项拆解、露出余量 = 40 且给了改动理由与回归测试）。代码里也确实是常量化的（`FeedCardMetrics.chrome = 142`、`kFeedRevealMargin = 40`）。

本 delta 只写了「按详情页实测重取」「护栏必须有」。问题是**详情页根本没有"露出余量"这个概念**——Feed 的护栏判据是那句可观测的「下一条内容的顶部总能露出来」，详情页图片在最顶部、下方是正文，没有"下一条"。所以"重取"重的是什么、判据是哪一句可观测的话，全空。两个 story 会给出两个数。

更麻烦的是这条的**理由站不住**：AD-A11.3 说「极端长图仍需上限」——可是 ② 的 clamp 已经把比例夹到 ≥0.75，图高最多 1.33×屏宽，长图早在 ② 就被裁完了。护栏在详情页真正起作用的场景只有一个：**小屏视口比 1.33×屏宽还矮**。理由写错，实现者就会按错的理由取值。

修法：要么给出详情页三个被减项的口径与一句可观测判据（对齐 AD-6.2 的写法），要么诚实地说「详情页 clamp 后天然有上限，护栏只在 `视口 < 1.33×屏宽` 时生效，取 `视口高 − 固定底栏高`」——后者一句话就可验收。

**F-9 · [medium] AD-A7.3 与 AD-A8.5 互斥**

- AD-A7.3：计数**实时聚合**，不加冗余计数列。
- AD-A8.5：「需要**覆盖排序的索引**支撑（`post_id` + 层级 + 排序键）」。

排序键 `likeCount` 是 `comment_likes` 上的跨表聚合值。**`comments` 上不可能存在覆盖它的索引**——没有任何 B-tree 能索引另一张表的 COUNT。这不是"实现选择"（D-9 把它 defer 了），是一个**自相矛盾**：实现者只有两条路，(a) `LEFT JOIN + GROUP BY + ORDER BY count`（每页对全帖评论做一次聚合，keyset 退化成"先聚合后比较"，500 DAU 下没问题但和"覆盖索引"无关），(b) 加计数列（直接违反 A7.3）。

AD 应当明说走 (a)，并把 A8.5 改成「索引服务于 `comment_likes(comment_id)` 的聚合与 `comments(post_id, parent_id)` 的过滤；排序键无法索引，这是接受实时聚合的代价，在当前量级可接受」。否则 A7.3 在评审时会被 A8.5 当成"架构自己允许了计数列"来推翻。

**F-10 · [low] 几处措辞与代码不逐字对齐**

- AD-A7.1 抄 `content_likes` 形态写成 `created_at TIMESTAMPTZ DEFAULT now()`，实际是 `NOT NULL DEFAULT now()`（`V10__init_content_likes.sql:8`）。AD 自称"逐字对齐"，就该逐字。
- AD-A5.4「文案三处同步（Java 中文目录 / 印尼语标题 / 客户端 en·id 双表）」——这是**标题**的三处。而 A5.4 要改的是**打卡引导文案**，那份在 `milestone_checkin_prompt_copy.dart:309/317`，另有 `milestone_celebration_copy.dart:451/457` 的庆祝文案。用"三处"这个既有术语指代另一组文件，会让人改错地方。

---

## 3. Deferred 里有没有其实不该 defer 的？

逐条看 D-1 ~ D-9：

- D-1 / D-2 / D-3 / D-4 / D-6 / D-7 / D-8：**defer 正确**，都是范围/物料问题，不会让两个 story 各选一套。
- **D-5（评论排序真快照）**：defer 正确，AD-A8.4 已给了明确的暂行口径（客户端不重排），有归属、有重访条件。⚠️ 但它的论证不完整：文档说"真快照要么引服务端状态要么引存储"，而**项目内已有一个反例**——`FeedRankCursor`（seed + consumed）在不引任何服务端状态的前提下做到了翻页稳定。它不能直接搬（那是种子随机序，不是可变排序键），但一份 spine 说"两条路都要么引状态要么引存储"时，应当先处理掉自家最像的那个先例。属论证瑕疵，不改结论。
- **D-9「索引具体形态、服务端兜底测量机制等实现选择」：不该这么 defer**。见 F-9——"索引形态"在这里不是实现细节，它把一条自相矛盾藏进了 defer。至少要把"不加计数列"这条约束在 defer 里复述一遍，否则 defer 等于给计数列开了后门。

**真正该 defer 而没 defer 的反向问题**：没有。倒是有几项**既没定也没 defer**，见第 7 节。

---

## 4. 它是批准还是违背既有约定？（brownfield 抽查）

抽查了任务点名的五处，结论：

| 抽查项 | 结论 |
|---|---|
| `milestone_completions` / `pet_milestones` 结构 | ✅ AD-A1 对现状的描述**逐条属实**，订正 PRD 正确（`V27:12-38`） |
| `content_likes` / `comments` 结构 | ✅ AD-A7 的同构描述属实（`V10`、`V9`）；软删 `deleted_at` 在 `comments` 上，A7.5 成立。小瑕疵见 F-10 |
| 健康类里程碑判定 | ⚠️ **只核了一半**。判定集合（`HealthMilestones.SUFFIXES`）核对无误，A-2 那个"白点" bug 的成因链（前端 `healthPresetTypeFor` 只映射 M3/M4 → 落 `_showBadgeSheet` → 后端 `MilestoneCheckInService:72` 拒绝）**与代码完全吻合，这段做得很扎实**。但完成映射与埋点 path 映射两层没核，见 F-2 |
| 分享卡基建 | ⚠️ 基建存在且可复用（`shared/card_render/` 六件套 + `CardQr.minExportSide=140`），但**模块归属判断错了**，见 F-11 |
| Feed 图片比例口径 | ✅ 三段口径与唯一出口 `resolveFeedImageAspect` 属实，AD-A11.2 的"必须走同一出口函数"是可落地的真约束 |

**F-11 · [medium] §2 授权了一条并不需要、且与现状相反的依赖方向**

§2 写：「年龄卡在 `profile` 侧，但复用 `content` 的分享卡基建 —— **允许 `profile → content` 的单向复用**」。

实读：可复用的底座**已经全在共享层** `petgo_app/lib/shared/card_render/`（`card_canvas` / `card_frame` / `card_export` / `card_qr` / `card_watermark` / `card_render_pipeline`），而且 **`features/profile/presentation/id_card_detail_page.dart` 已经直接 import 它**——profile 侧现在对 content 侧**零依赖**。`content` 下的 `share_card_template.dart` / `share_card_preview_page.dart` 是绑死 `ShareCardData`（内容帖投影）的**业务模板**，本来就不该被年龄卡复用。

所以这条"允许"是拿一个不存在的困境换来的架构让步：它批准了一条本可以不存在的跨 feature 依赖。而且它与同一份文档的 AD-A14 自相矛盾——灯箱那边的处理是"上提共享层，两侧都不互相依赖"，这边同样的形状却给了"允许单向依赖"。同一份 spine 对同构问题给两种答案，且没说理由。

修法：§2 改为「年龄卡复用 `shared/card_render/`（KTP 已在用），**不得依赖 `content/.../share_card/`**；年龄卡模板新建在 profile 侧」。顺带 AD-A19.1 的"只替换内容模板"就有了明确落点。

---

## 5. 有没有新 AD 削弱或抵触继承自 v1.1.6 的 AD？

### AD-A19.3 对 v1.1.6 AD-15.4 的「范围澄清」—— **论证成立，判为合法澄清**

AD-15.4 原文把「内容 = 该条内容的分享链接（不是通用下载页）」列为二维码四条硬要求之一。AD-A19.3 的论证是：年龄卡没有可指向的单条内容，不在该条的适用对象内；该条的意图是"有内容链接却偷懒指下载页"。

**我认可这个论证**，理由是 AD-15 自己的上下文支持它：AD-15.5 紧接着写「单条分享严格只看这一条：访客不得由此进入该宠物的 Diary 时间线或任何其它内容」——可见 Rule 4 第 1 条的真实目的是**把访客锁在被分享的那一条上**（隐私 + 落地页语义），而不是"禁止下载页这种 URL"。年龄卡指下载页**不违反**那个目的（下载页不通往任何用户内容）。这不是偷换概念。

⚠️ 但有两个落地缺口：

- **F-12 · [medium]** 代码里 `shared/card_render/card_qr.dart:33-36` 有一段 🔴 标注的契约注释：「必须是**该条内容的分享链接**，不是通用下载页（AD-15 Rule 4 第 1 条）」。这是一个**共享组件**上的文档契约。年龄卡传下载页进去，就会出现"代码注释红字禁止、线上正在这么干"的状态。AD-A19 必须要求同步订正 `CardQr` 的文档契约（写清两类调用方各自的合法内容），否则下一个读代码的人会判它是违规实现。
- **F-13 · [medium]** A19.5「下载页 URL 走配置项，不硬编码」——**哪一侧的配置项没定**。客户端编译期常量 / 远端配置 / 后端 `platform_config`（`V78__init_platform_config.sql` 确实存在且有后台配置页）是三条完全不同的路，第三条还要新增一个下发接口。真分叉，一句话可闭合。

### AD-A21 对 v1.1.6 AD-14.1 的「按账号 vs 按设备」—— **结论可接受，但论证窄化了 AD-14，需重写**

AD-A21.2 的论证是：「AD-14 管的是**推送权限引导**，其判断依据『系统通知开关』本身就是设备级状态，故标记也按设备；本条管的是认知性告知，换设备不需要再告知一次。」

**这个区分本身是有道理的**（设备级状态 vs 账号级认知），但作为对继承 AD 的差异论证，它**对 AD-14 的描述不准确**：

AD-14 并不只管推送权限。AD-14.2 的四个键里，第四个是**手机号软引导**；AD-14.8 明确说手机号软引导「维持全局仅一次」、其去重的真实依据是**服务端 `users.phone` 非空**——这是一个**彻头彻尾的账号级**关切。而 AD-14 仍然把它的标记位放在按设备的本地 prefs 里，并在 A14.8 结尾用 ⚠️ 明写：「『全局仅一次』在按设备存储下只能是『本设备仅一次』……换设备会再提示一次 —— 这是**既定代价**，非缺陷」。

也就是说：**v1.1.6 面对"账号级关切 + 一次性引导"这个完全同构的情形时，已经做过一次裁决，裁的是"按设备存 + 接受换设备重提示"。** AD-A21 现在对同一形状做出相反裁决，却把 AD-14 描述成"只管设备级状态"——这不是偷换概念（区分是真的），但确实**回避了那个已经存在的同构判例**。

真正需要在 AD 里称重、而现在完全没称的，是这次裁决的**代价**：

- 引导标记从"本地 prefs、零后端"变成"**新建服务端表 + 新增读写接口**"，这是一次**范式变更**，不只是存储位置变更；
- 首次进成长档案页要**等一次网络往返**才知道该不该弹蒙层——**请求失败/离线时弹还是不弹**？弹了会重复弹（用户每次离线进档案都被弹），不弹会永远错过。AD-A21 对此无一字；
- 与 AD-A22「运维零增量 = 四支迁移 + 两处查询」直接冲突（见 F-4）。

**F-14 · [medium-high]** 修法二选一：① 保留按账号，但把论证改成"AD-14.8 已接受按设备的代价，本条判定该代价对认知性告知不可接受，故升级为账号级"，并补齐失败/离线口径（建议：读失败一律**不弹**，宁可漏也不要重复打扰）；② 改回本地 prefs，与 AD-14 一致，代价是换设备多弹一次蒙层——对一个"KTP 挪位置了"的告知，这个代价**明显小于**新增一张表 + 一套接口 + 一个离线分支。个人倾向 ②，但这是产品判断，spine 只需要把代价摆到台面上。

### 其余继承关系

- AD-A11 / A12 继承 AD-5 / AD-6：方向正确，但 AD-A12 没接上数据来源（F-1），且 A11.3 把 AD-6.2 那种"逐项钉死"的严谨度丢了（F-8）。
- AD-A7.4 继承 AD-7/AD-11 的批量口径：✅ 无削弱。
- AD-A19.1 继承 AD-15.1c：✅ 引用准确（AD-15.1c 确实预先把 FR-65 写成了复用前提）。

---

## 6. 该 altitude 应覆盖而整块沉默的维度

**F-15 · [high] 回滚与版本 skew：整块沉默**

本批次至少有三处"发布后不可轻易回退"或"新旧客户端并存"的风险，delta 一处都没提：

1. **评论排序 + 游标格式变更**：灰度期老客户端持二元组游标打新服务端（见 F-5 的静默误解码风险）；新客户端打老服务端（分批发布时）拿不到 `likeCount`。
2. **`celebrated_at` 一次性回填**：AD-A1.4 是对的且不可省。但若后端回滚而客户端已发版，客户端调不存在的回报端点 → AD-A3.1 已定"失败静默"，实际上兜住了。**这条其实是安全的，值得在 AD 里点明**（一句"回报失败静默 ⇒ 后端回滚对客户端无害"就把回滚顾虑消掉了，是便宜的收益）。
3. **详情接口新增 `imageSizes`**：新增字段向后兼容，老客户端忽略即可。也安全，同样值得点明。

spine 不需要写发布流程，但需要一句话把"哪些改动跨版本安全、哪些必须同版本"钉住。现在是空的。

**F-16 · [medium] 验证层级（L0/L1/L2）整块沉默**

CLAUDE.md 要求每条 AC 标验证层级，且明确 L2 视觉验收必须回本地真机。本批次的两个自实现交互（灯箱四手势、拖拽重排）**只能在 L2 验证**，而它们恰恰是"不引包"这条决定的全部代价所在。delta 通篇没有一句关于可测试性的话——没有说哪些 AD 有 L0 纯函数可钉（比如年龄换算规则、`healthPresetTypeFor` 的等长同集、`celebrated_at` 幂等），也没说哪些必须 L2。

对照 v1.1.6 AD-6.2 那句「客户端有专门的回归测试钉住这条」——那才是 spine 该有的可测试性姿态。建议至少补一条 AD：**"三处等长同集（后端 `HealthMilestones` / 前端跳转映射 / 埋点 path 映射）由 L0 测试钉住；年龄换算规则为纯函数、L0 全覆盖；手势与拖拽为 L2-only，story 的 Completion Notes 须标注。"**

**F-17 · [medium] 数据完整性 / 注销口径：新表没有表态**

CLAUDE.md 把注销级联（story 7.3、D1/D2）列为三类安全攸关节点之一。本批次新增两张带 `user_id` 外键的表（`comment_likes`、引导标记表），§4 只说了"引导标记表不含 PII"。

实读 `AuthAccountDeletionService.java:15-23`：注销是**就地匿名化**、不物理删 user 行，理由正是 `content_posts/comments/content_likes` 都是 `NOT NULL + RESTRICT` 外键。所以两张新表**天然安全**——但正因为答案是"什么都不用做"，才更该写一句，否则下一个人会以为漏了级联删除而去补一段错误的删除逻辑。一句话的成本，防一次返工。

**F-18 · [low] companions 三个文件不存在**

frontmatter 声明的 `.memlog-architecture-batch-a-2026-09-10.md`、`架构决策-人话版.md`、`工作拆分预览.md` 在 `v1.3.0/` 目录下都不存在（目录里只有 5 个文件）。要么补齐，要么从 frontmatter 摘掉——悬空引用会让下游 agent 反复去找。

**运维 envelope（AD-A22）**：这条是**覆盖了的**，且姿态正确（不引中间件/包/定时任务/异步链路）。唯一问题是那句错误的枚举（F-4）。

---

## 7. PRD 逐条覆盖核查

按 PRD §3 的"已确认项"逐条对照。**未被任何 AD 覆盖、也未进 §6 Deferred** 的：

| # | PRD 出处 | 未覆盖的要求 | 严重度 | 说明 |
|---|---|---|---|---|
| P-1 | §3.3 已确认项 1 | **元素顺序**（作者行 → 图片 → 文字 → 互动栏 → 评论区）+「发布时间移至作者名下方」 | medium | 这是 FR-113 的第一条已确认项，也是"详情页与 Feed 顺序相反"这个问题的正面答案。delta 只写了比例（A11）和底栏（A13），顺序本身既没定也没 defer。若判为 UX 稿职责，也应显式写「顺序以 UI 稿 D1 为准」 |
| P-2 | §3.3 已确认项 6 | `?focus=comments` **评论锚点"明确不改"** | medium | AD-A13 把互动栏与评论框合并成单一固定底栏，恰恰会动到评论区的滚动/聚焦结构。PRD 明列这是"不改"项，spine 应显式承接（"合并底栏不得破坏 `?focus=comments` 锚点行为"） |
| P-3 | §3.4 已确认项 6 | **空态引导语**「还没有评论，来说点什么吧」（en/id） | low | AD-A10 自称覆盖"其余四项"，实际 PRD 有六项，空态与点赞被漏在外（点赞另有 A7/A8，空态无人认领） |
| P-4 | §3.5 已确认项 6 | **保留：点击关闭、初始页为点击那张** | medium | 与 F-6 的单击/双击冲突直接相关。AD-A15 只提了"悬浮关闭 ✕"，没承接"点击关闭"这条保留项——是有意取消还是遗漏？ |
| P-5 | §3.1 卡片段 | **生成与分享免费、无次数限制** | low | 与 AD-A20 的日上限并存无矛盾（限的是发币不是生成），但正因为容易被读成矛盾，值得一句话（"限的是奖励次数，不是生成次数"） |
| P-6 | §3.1 换算规则 | 体型档「档位名旁标注体重区间辅助判断」 | low | 纯 UI，可不进 spine |

**已妥善覆盖或显式 defer 的**（抽样确认）：换算规则与插值（A18）、非猫狗置灰（A17.4）、深链重定向（A17.2）、迁移引导按账号一次（A21）、G-M1/G-M2 存量不回滚（A5.3）、补庆祝不按级别过滤（A2.4）、多条只弹最高一条扩展为全路径（A2.5）、长按菜单四项（A10）、作者标签（A9）、时间格式共用一个函数（A10.3）、灯箱七项手势与"不做保存相册"（A15、D-7）、拖拽重排与封面角标（A16）、六个埋点事件（A23）、视觉重绘/护照/Tailsonality/已发布重排/体检类型（D-1/2/6/8）。

**覆盖率评价**：主体覆盖到位，漏的六条里有四条集中在"PRD 明写的保留项 / 不改项"上——这是一个有规律的盲区：**delta 只承接了 PRD 的"要做什么"，没承接 PRD 的"不许动什么"**。而"不许动"恰恰是 spine 最该守的东西（AD-A13.4、A13.6 做到了，其余没有）。

---

## 8. 修补清单（按优先级，开工前）

**必须改（否则 story 会写错）**

1. **F-1**：AD-A12 增补「详情接口须下发 `imageSizes`，同序等长，服务端不 clamp」；订正 §1.1 与 AD-A22 的"零接口变更"。
2. **F-2**：AD-A4/A5 增补三件事——① §5 补第五支迁移 `UPDATE pet_milestones SET trigger_type='SYSTEM_AUTO' WHERE code IN ('G-M1','G-M2')`（先例 V77）；② 明写完成映射须从「后缀寻址」改为「按物种给出目标 code」（现状 `VACCINE→M3→G-M3=陪伴30天` 是错点亮，**A4.3「现无用户可感知影响」必须删掉**）；③ 覆盖点从"三处"改为"五处"，含 `MilestoneAnalyticsPath`（G-M1 会被误标 `health_record`）与"刻意不动 `TimelineClassifier` 那份"。
3. **F-3 + F-4**：新增一条 AD 管"本批次新增的三个端点"（庆祝回报 / 引导标记读写 / 年龄卡领奖），并在 §5 补年龄卡渠道的**日限记账表**（或把 `id_card_share_rewards` 泛化出 `channel` 列），同时给出领奖的去重键与限流姿态。
4. **F-5**：AD-A8 增补「新建独立评论游标类型，不得改 `FeedCursor`；编码空间隔离，旧格式拒绝」。

**应该改（真分叉，一两句话可闭合）**

5. **F-9**：解开 A7.3 与 A8.5 的互斥，明说走跨表聚合、排序键不可索引是已接受的代价。
6. **F-8**：给详情页护栏一个可验收的口径与判据，或改成"clamp 后天然有上限，护栏只兜小屏"。
7. **F-11**：§2 改为"复用 `shared/card_render/`，不得依赖 `content/.../share_card/`"，与 AD-A14 的处理方式对齐。
8. **F-14**：AD-A21 重写差异论证（正视 AD-14.8 的同构判例），补离线/失败口径；或改回本地 prefs。
9. **F-6 / P-4**：裁一次"单击关闭 vs 双击缩放"。
10. **F-12 / F-13**：要求同步订正 `CardQr` 的文档契约；明确下载页 URL 配置项在哪一侧。
11. **F-7**：KTP 的两条兄弟路由是否随迁。
12. **P-1 / P-2**：承接 FR-113 的元素顺序与 `?focus=comments` 不改承诺。

**建议补（便宜的收益）**

13. **F-15**：一句话钉住跨版本安全性（回报失败静默 ⇒ 后端回滚无害；`imageSizes` 新增字段向后兼容；游标格式必须同版本）。
14. **F-16**：一条 AD 管可测试性（等长同集 L0 钉死 / 换算纯函数 L0 / 手势与拖拽 L2-only）。
15. **F-17**：一句话写明两张新表在注销（D1/A 就地匿名化）下无需级联删除。
16. **F-10 / F-18 / P-3 / P-5**：措辞与悬空引用的零散修正。

---

## 附：核对过的代码位置

- `petgo-backend/src/main/resources/db/migration/V27__init_milestones.sql`（里程碑两表）、`V9__init_comments.sql`、`V10__init_content_likes.sql`、`V77__alter_milestone_newbie_tasks.sql`（catalog 变更需数据迁移的先例）、`V86__add_column_comments.sql`（列注释即口径）、`V20260825_0235/0247/0248`（分享奖励三层）、`V78__init_platform_config.sql`
- `petgo-backend/.../profile/domain/HealthMilestones.java`、`MilestoneCatalog.java:176-200`（通用清单）
- `petgo-backend/.../profile/service/MilestoneAutoCompleteListener.java:96-145`、`MilestoneCompletionService.java:61-96`、`MilestoneCheckInService.java:66-100`、`MilestoneAnalyticsPath.java`、`MilestoneService.java:50-80,118-130`、`TimelineClassifier.java:110-137`
- `petgo-backend/.../content/dto/ContentDetailResponse.java`、`FeedItemResponse.java`、`CommentResponse.java`、`CommentPageResponse.java`
- `petgo-backend/.../content/service/CommentQueryService.java`、`FeedCursor.java`、`rank/FeedRankCursor.java`
- `petgo-backend/.../auth/service/AuthAccountDeletionService.java`
- `petgo-backend/.../profile/web/ProfileApiController.java:169-270`
- `petgo_app/lib/features/content/domain/feed_image_layout.dart`、`publish_controller.dart:150-176`
- `petgo_app/lib/features/content/presentation/content_detail_page.dart:415-520`
- `petgo_app/lib/features/profile/presentation/milestone_list_page.dart:455-500,915-923`、`growth_archive_page.dart:368,706`
- `petgo_app/lib/shared/card_render/*`、`petgo_app/lib/features/content/presentation/share_card/*`、`features/profile/presentation/id_card_detail_page.dart`
- `petgo_app/lib/core/router/app_router.dart:675-680`、`petgo_app/pubspec.yaml`
