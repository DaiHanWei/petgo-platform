---
baseline_commit: 8a5f44e7
---

# Story 16.3: 候选池与推荐序接入 ALL Tab

Status: review

## Story

As a 用户，
I want 首页不再是纯时间倒序，
so that 不会连续刷到同一个人、科普与日常有节奏、养猫少刷到狗、看过的不再反复出现。

---

> 🔴 **前置：16-1（Redis 基建）、16-2（引擎）**。
> 🔴 **全量覆盖 V1.0.0 FR-17 的排序逻辑**；时间倒序降级为内部兜底，**不做任何排序切换入口**。

---

## Acceptance Criteria

### AC1 — 🔴 只在 ALL Tab 启用，非 ALL Tab 是另一条独立路径（L1）

**Then** `[L1]`：

| | ALL Tab | 非 ALL Tab（Moment / Knowledge&Tips / 深链进入的 Growth） |
|---|---|---|
| 排序 | **推荐序** | **纯时间倒序**（`created_at DESC, id DESC`，FR-17 既有逻辑） |
| 属性穿插 / 物种配比 / 防扎堆 | ✅ | ❌ |
| 序列快照 | ✅ 需要 | ❌ **不需要**（时间倒序 + 游标本就稳定） |
| 候选池全部过滤 | ✅ | ✅ **同样生效**（这层与排序无关，两条路径共用） |

**And** 🛡 **不要写成"推荐序的降级分支"** —— 是**两条独立路径** `[L0]`
**And** 🛡 非 ALL Tab **完全不动现有代码** `[L0]`

> 理由：① 用户切到分类 Tab 的预期就是"筛出这一类、按时间看最新"；
> ② 非 ALL 不需要序列快照，**省掉一整套缓存开销**、少一条出错路径；
> ③ 回滚只需关掉 ALL 分支。

### AC2 — 候选池按**实际代码**沿用六条过滤（L1）

**Then** `[L1]`：
1. 公开口径：`deleted_at IS NULL` 且（`status=PUBLISHED` **或**该帖是**查看者本人**的 `UNDER_REVIEW` 挂起帖）
2. 可见性：`visibility = PUBLIC`（三类内容通用，不按类型分支）
3. 举报者隐藏：登录者不看自己举报过的**帖**
4. 账号级隐藏：登录者不看自己隐藏过的**作者**的全部内容（**不区分 source**）
5. 顶置让位（见 AC5）
6. 游标

**And** 🔴 🛡 **第 3 与第 4 条刻意不合并** `[L0]`

> 🔴 **补充 PRD §2 说它们"是同一层、实现上可合并为一次过滤"，这与代码不符**：
> 代码里是两条并列条件、注释明写"不合并"（AD-9）——
> 一条藏**一条帖**、一条藏**一个人**，语义不同。合并会改变已上线行为。
> ⚠️ 且第 4 条**不区分 source**（主动拉黑与举报隐藏一视同仁），PRD 只提了"拉黑"。

**And** 🛡 作者本人的挂起帖**直接插回其时间序位置**，不占算法槽位、不参与配比与打分 `[L1]`

> 否则作者会发现自己刚发的帖从首页消失，是体验回退。

**And** ❌ **宠物状态过滤整条不存在**，且排序链路上**不得出现任何读取 `petStatus` 的分支** `[L0]`

### AC3 — 顺带删掉 `petStatus` 死参数（L0）

**Then** `FeedService` 的 `petStatus` 形参已是死参数，本 story **一并删除** `[L0]`

> PRD A-B3 明确要求：留着它会让后人误以为它还有作用。

### AC4 — 降级兜底链（L1）

**Then** 四级，任一级**都不得报错或出现空页** `[L1]`：

| 级别 | 触发 | 行为 | 告警 |
|---|---|---|---|
| 1 | 属性池不足 | 其他属性补位，不空槽 | ❌ 不告警（预期） |
| 2 | 物种池不足 | 放宽通用池 → 全池 | ❌ 不告警（预期） |
| 3 | Redis 不可用 | 实时算当页，接受翻页重复 | ✅ 告警 |
| 4 | 打分/依赖查询异常 | **整体回落纯时间倒序**，用户无感 | ✅ 告警 |

**And** 🛡 🔴 **任何级别下候选池的全部过滤都不得被绕过**（含级别 4 回落时）`[L1]`

### AC5 — 顶置：✅ 已实现且更严，不要重做也不要收窄（L0）

**Then** ✅ 顶置内容在**第一页整页（20 条）**从算法序列排除 —— **Story 4-2 已交付** `[L0]`
**And** 🛡 **不收窄到"前 10 条"** `[L0]`

> 🔴 **补充 PRD A-B8 把它写成待实现的保守默认（前 10 条窗口去重），这与代码不符**：
> Story 4-2 的「只首屏让位」已经排除整个第一页；而 E-3 埋点的语义在 4-2 时**已随之改写**为
> "观测**后续页**的重复曝光频率与位次"，因为首屏已不可能重复。
> 按 PRD 字面做会**重做一遍已有的事**，还可能把 20 条放宽成 10 条 —— **那是功能回退**。
> OQ-B3 的"发版后用 E-3 复核"照旧。

### AC6 — 分页粒度不变（L0）

**Then** 沿用 FR-17 的「首次 20 条、每批 20 条」；一批 = **两个完整窗口** `[L0]`

### AC7 — 本 story 不做的（L0）

**Then** 🛡 `[L0]`：不做「推荐 / 最新」双 tab 或任何排序切换入口；不做参数配置化（16-4）；
不做埋点（16-5）

---

## Tasks / Subtasks

- [x] **T1 · ALL Tab 接推荐序；非 ALL Tab 原样不动**（AC1）
- [x] **T2 · 候选池六条过滤（🛡 第 3/4 条不合并）+ 挂起帖插回时间序位**（AC2）
- [x] **T3 · 删 `petStatus` 死参数**（AC3）
- [x] **T4 · 降级链四级 + 告警分级**（AC4）
- [x] **T5 · 测试**
  - [x] L1：ALL Tab 走推荐序、非 ALL Tab 仍是严格时间倒序（钉住 AC1 的"两条独立路径"）
        —— 端点层 `FeedRankRoutingIntegrationTest`（按**游标结构**判别，不靠"顺序不一样"）
  - [x] L1：🛡 拉黑与举报隐藏**各自**生效（钉住 AC2 的"不合并"）
  - [x] **L0**：🛡 级别 4 回落时间倒序，**拉黑仍然生效**（钉住 AC4 那条"不得被绕过"）
        ⚠️ 原写 `[L1]`，**实际只做到 L0**（断言调用点实参）—— 真实触发级别 4 需要 bean 覆盖，
        而那会多出一个 Spring 上下文（基类注释里那条连接池告警）。真实演练已列进「留 L2 的」。
  - [x] L1：作者本人挂起帖在自己的 Feed 里、他人看不到
  - [x] L1：顶置内容不在第一页出现（回归 4-2，钉住 AC5 不被放宽）
  - [x] L0：排序链路上不再有 `petStatus`

## Dev Notes

### 🔴 别按 PRD 的两处过期陈述开发

| PRD 陈述 | 实际 |
|---|---|
| 举报者隐藏与拉黑"可合并为一次过滤" | 刻意两条并列，注释明写不合并（AD-9） |
| 顶置首屏去重是"待实现的保守默认（前 10 条）" | 已实现且更严（整个第一页 20 条） |

### References

- [Source: V1.1.6/1-1-6补充prd.md#3.1] §2 候选池 · §6.2 降级链 · §8.3 顶置重复曝光

---

## Dev Agent Record

### Context Reference

- `_bmad-output/planning-artifacts/epics-v1.1.6.md` §补充：Epic 16–18
- `V1.1.6/1-1-6补充prd.md` §2 候选池 · §6.2 降级链 · §8.3 顶置重复曝光

### Agent Model Used

claude-opus-5[1m]

### Debug Log References

一次「测试红得不是地方」与一次 Mockito 重复打桩 NPE，见 Completion Notes 第 6、7 条。

### Completion Notes List

**1. 🔴 一处 AC 没写、但不做就是安全漏洞：从快照读回来的 id 必须重新过一遍过滤**

序列快照有 30 分钟寿命。这期间内容可能被删、被挂起、转成私密，或者**查看者刚拉黑了某个作者**。
按 id 直接 `findAllById` 取回来就是**绕过了安全规则层** —— 而 AC4 明写
「任何级别下候选池的全部过滤都不得被绕过」。

所以新增了 `findRankableByIds`：按 id 取，但**套一遍与候选池逐条相同的 WHERE**。
⚠️ 它返回的条数可能少于传入的 id 数，调用方必须从序列里**再多取几条补齐**
（本实现每页多要 10 条），否则会返回一个短页 —— 而短页会被客户端理解成「到底了」。

**2. 🔴 游标语义不是「页码 × 20」，是「已消费条数」**

被过滤掉的条目**也算消费掉了**。用页码乘法算下一页起点，会让下一页重复读到那些被丢弃的位置。
有一条测试专门钉这个（20 条展示 + 5 条被丢弃 ⇒ `consumed == 25`）。

**3. 🔴 候选池必须有上界（AC 没写，工程上必须）**

不设上界这个查询随总帖数线性变大，而它**每次下拉刷新都要跑一次**，
且推导物种 / 批量取赞评 / 取装饰标签都按这一批规模走。
定为「最近 1000 条」（`petgo.feed.rank.candidate-pool-size`）——
更早的内容新鲜度已趋近 0，靠互动度单独也很难排上来。
⚠️ 顺带这条 `ORDER BY created_at DESC LIMIT N` 让候选池查询**走既有 `idx_content_posts_feed` 索引**
而不是全表扫，正好接上 Story 16.1 索引重估留的那条。

**4. P95 由本次候选池现算**

候选池本身就是「最近 N 条」，天然是近期口径 ⇒ 不用多发一次查询、也不会为空。
🔴 16.4 会换成「近 30 天动态重算 + 重算失败沿用上次」并搬进配置中心；在那之前**这里是唯一取值处**。
荣誉加成直接取既有的 `ContentTagQueryService.RANK_WEIGHT_MULTIPLIER`，🛡 **没有新写一个 1.3**。

**5. 🔴 入参问题不能被降级吞掉**

级别 4 是「算不出来就回落时间倒序」，但**游标非法是入参问题**，必须照常 422。
不区分的话客户端传了个坏游标会得到一页时间倒序内容 —— 表现是「下拉刷新后又从头开始了」，
且服务端一条错都不记，无从排查。实现上先 `catch (AppException) { throw e; }` 再兜 `RuntimeException`。

**6. ⚠️ 一条既有 L1 测试的前提被本 story 推翻了（不是它写错，是路径变了）**

`ReportTriageIntegrationTest.feedContains` 断言「刚发的帖出现在**第一页**」。
那个前提只对时间倒序成立 —— 推荐序里一条 0 赞新帖要和池子里上千条内容按分数竞争，
**落到第 20 名之后完全正常**。它一开始就红了 2 条。

处理：把它改回分类 Tab（时间倒序，确定性），并在注释里写清为什么**不能**留 ALL ——
硬留会得到一条时不时红、且红的时候跟举报过滤没有任何关系的测试。
推荐序路径上的过滤覆盖另建 `FeedRankQueryIntegrationTest`，断言落在**查询层**：
「该内容不在候选池里」。
🔴 **缺席类断言在排序面前是稳的，在席类断言不是** —— 这是本 story 学到的测试设计原则。

**6b. 🔴 全量套件红了 12 条 —— 同一个根因，且解法不是「把测试改成绕开」**

单跑相关类都绿，**全量才红**：`ContentFeedControllerEndpointTest`（4）、
`PinnedSlotIntegrationTest`（4）、`FeedExpandedFieldsIntegrationTest`、`ContentTagIntegrationTest`、
`UserHideRelationIntegrationTest`、`SeedBatchStateMachineIntegrationTest` 各 1。
全都是「刚造的内容出现在 ALL 首页第一页」型断言。

⚠️ **根因是测试库体量，不是排序错了**：共享库里堆着几千条历史测试数据，
其中大量带赞/评（互动度高）。默认候选池「最近 1000 条」下，一条 0 赞新帖要和它们按分数竞争，
落到第 20 名之后完全正常。

🔴 **第一反应「把这些测试改成分类 Tab」是错的** —— 那会让十来条 L1 测试<b>整体退出</b>
ALL Tab 端点的覆盖，而 ALL 恰好是本 story 唯一改动的路径。

实际解法：在 **L1 基类**（`ApiIntegrationTest`）把候选池压到 **60 条**。
「最近 60 条」在集成测试串行跑的前提下**必然包含**单个测试方法刚造的内容 ⇒
那些测试仍然走**真实推荐序路径**，只是不再被历史数据淹没。
🛡 放在基类而不是各个子类，是为了不多出 Spring 上下文（基类注释里那条连接池告警）。
⚠️ 别为了「更像生产」调大 —— 调大就是把这十来条测试变成随机红。

**7. ⚠️ Mockito 重复打桩的坑**

用例里二次 `when(mock.f(any()))` 覆盖 `@BeforeEach` 里的 answer，会**真的调一次 mock** 来记录调用，
此时上一个 answer 带着 null 参数执行 → NPE，报错完全不指向真凶。
改成把「哪些 id 还合格」做成辅助方法的参数。

**8. 🔴 一条反证红得不是地方，补了一条精确断言才算钉住**

反证「读页改成不带过滤的 `findAllById`」时红了 7 条 —— 但都是因为那个方法没打桩、返回空，
**不是因为断言抓到了过滤被绕过**。
真正要钉的是「过滤参数有没有传下去」：把 `hasViewer/viewerId` 写成 `false/null`，
查询照样能跑、页照样满，但两条子查询会被整条短路 —— **拉黑白拉，且没有任何报错**。
补了 `viewerScopedFiltersAreActuallyPassedToTheQuery` 直接断言调用点实参，
再反证时精确红一条。

**9. AC2 的两处「按实际代码而非 PRD」已落实**

- 🛡 举报者隐藏与账号级隐藏**两条并列不合并**（AD-9）—— 候选池查询里原样保留两个 `NOT EXISTS`，
  并有 L1 测试钉住区分（举报只藏一条帖 / 拉黑藏整个人 / 只对该查看者生效）
- 🛡 顶置首屏让位沿用 Story 4.2 的**整页 20 条**口径，**没有收窄到前 10 条**

**10. AC3 `petStatus` 已删净**

`FeedService.loadFeed` 形参删除，控制器里**只为它存在的** `resolvePetStatus` 方法一并删除，
`AccountQueryService` 依赖也从控制器移除。
⚠️ 顺带 `FeedServiceTest` 里那条 `feedNoLongerBranchesOnPetStatus` 的前提**再次失效**
（形参没了，「不同宠物状态取数参数相同」在签名层面就不可能不成立）——
改写成仍有意义的那半：**取数参数只随 viewerId 变化**。

**11. 客户端只多做一件事**

游客带上匿名会话 id（`X-Anon-Session`）。
🛡 **只挂在首页取数上，没做成全局请求头** —— 它长得像跟踪 id，别让它出现在无关请求里。
🛡 **只在进程内存活、不落盘**，冷启动即换新（曝光衰减对游客本就不生效，这个 id 只解决
同一次会话内的翻页重复）。
⚠️ 该请求头**可缺省** —— 老版本客户端不带它照样能刷首页，只是同一批游客共用一份序列快照。

**11b. 🔴 复核时发现两处问题（一处是我报告不准，一处是真缺陷）**

被要求"自己好好看下"之后重新核了一遍，结果两样都有：

**(a) 我勾错了两个框。** story 的测试清单里这两条标着 `[L1]`，我实际只做到 L0：
「ALL 走推荐序、非 ALL 严格时间倒序」与「级别 4 回落时拉黑仍然生效」。
前者已补真正的 L1（见下）；后者**改标签为 L0** 并把真实演练留进 L2 ——
真触发级别 4 要 bean 覆盖，那会多出一个 Spring 上下文（基类注释里那条连接池告警）。

⚠️ 顺带查清一个虚警：surefire 的 `.txt` 汇总（2214）与 `.xml`（2239）差 25，
是 `ScheduleWindowTest`(16) 与 `MilestoneAnalyticsTest`(9) 用了 `@Nested`，
`.txt` 对外层类记 0 —— 报告格式的假象，两边都 0 失败，xml 与 Maven 汇总一致。

**(b) 🔴 两种游标的编码空间没隔开 —— 真缺陷。**

补 L1 路由测试时发现的：两条路径的游标都是 `base64url("<a>:<b>")`。
把**时间倒序游标喂给 ALL Tab**，`FeedRankCursor.decode` 会**静默接受** ——
seed 解成那串毫秒数、consumed 解成 id，用户拿到一个不存在种子的**任意偏移页**。
🔴 **不崩、不报错、服务端一条错都不记**，表现只是「切 Tab 后首页内容很怪」，没人查得出来。
（反方向恰好能 422 纯属运气：种子的 base36 串里一般有字母，`Long.parseLong` 才失败 ——
 而 base36 全是数字的概率约 3.5e-5，那就是一条极难复现的偶发 bug。）

修法：种子加前缀 `s`，解码时校验；两个空间由此**不可能重叠**。
反证（去掉校验）→ `chronoCursorIsRejectedByTheAllTab` 红在
`Status expected:<422> but was:<200>`，正是那个静默接受。

⚠️ **这条缺陷本来会漏掉**：它不在任何 AC 里，也没有测试会自然覆盖到 ——
是为了把一个标错的 `[L1]` 补上、去想「端点层怎么确定性地判别两条路径」时撞出来的。

**11c. 🔴 复核又逼出一条 AC 我根本没实现（不是标签问题，是漏做）**

AC2 有一句：**「作者本人的挂起帖直接插回其时间序位置，不占算法槽位、不参与配比与打分」**。
我的第一版把挂起帖**放进了候选池**（照抄了时间倒序那条路径 `findFeed` 的写法）——
于是它跟着一起打分、占配额、和全平台内容抢分数。

⚠️ **而我勾的那个框「本人挂起帖在自己的 Feed 里、他人看不到」碰巧是绿的** ——
因为它确实在池子里、确实只有本人看得到。测试没错，是**我用一个较弱的命题冒充了 AC**。
🔴 真实后果：作者刚发的帖抢不到分数 ⇒ 从首页消失 ⇒ 作者只会以为发布失败了。
这正是那句 AC 的存在理由。

修法：
- 候选池查询**只收 `PUBLISHED`** —— 挂起帖压根不进引擎
- 新增 `findOwnPendingPosts`，首屏**置顶插回**
  （推荐序里没有「时间序位置」这回事，首屏置顶是最贴近原意的落法：
   作者刚发的本来就是他最新的东西，放最前面最不意外），上限 3 条防连发占满首屏
- 🔴 **去重**：一条帖可能排序时还是 `PUBLISHED`、之后才被挂起 ⇒ 它既在序列里、
  又会被"本人挂起帖"取到（按 id 读回来那个查询允许作者看自己的挂起帖）——
  不去重就是**首屏同一条出现两次**。有专门用例钉这个。
- 🛡 挂起帖**不记曝光**：它没参与打分，记了只会让待审内容占着曝光集合的位置

反证（把挂起帖放回候选池，即我原来那个写法）→
`pendingPostsStayOutOfTheCandidatePoolEvenForTheirAuthor` 精确红一条。

**11d. 复核这一轮的总结**

三样东西是「跑完了、绿了」之后才发现的：
| 发现 | 性质 |
|---|---|
| surefire txt/xml 差 25 条 | ❌ 虚警（`@Nested` 的报告格式） |
| 两个 `[L1]` 框实际只有 L0 覆盖 | ⚠️ 我报告不准 |
| 游标编码空间没隔开 | 🔴 真缺陷（静默接受，不报错） |
| 挂起帖进了候选池 | 🔴 真漏做（AC2 明写，我用较弱命题冒充） |

⚠️ 两条真问题**都不是测试红了才发现的** —— 是逐条把 AC 对回实现、
以及去想「这个 `[L1]` 该怎么在端点层确定性验证」时撞出来的。
**全绿不等于做完了**：绿只证明我写的断言成立，不证明断言覆盖了 AC。

**12. 本 story 零迁移**（只新增两个 JPQL 查询，无 schema 变化）。flyway-guard 无关。

**13. 留 L2 的**
- 真机/模拟器上「首页确实不再连续出现同一个人 / 科普与日常有节奏」的**观感验收**
- **真实 Redis 宕机演练**（停 redis 容器再刷首页，验级别 3：仍出内容、翻页可能重复）
- 级别 4 的真实触发（构造打分链路异常）—— L0 已用异常注入覆盖行为，真实演练留本地

### File List

**新增（后端）**
- `petgo-backend/src/main/java/com/tailtopia/content/rank/FeedRankCursor.java`（含种子前缀校验，见第 11b(b) 条）
- `petgo-backend/src/main/java/com/tailtopia/content/rank/FeedRecommendationService.java`

**修改（后端）**
- `.../content/repository/ContentPostRepository.java`（新增 `findRankCandidatePool` / `findRankableByIds` / `findOwnPendingPosts`）
- `.../content/service/FeedService.java`（ALL/非 ALL 分流、级别 4 回落、抽出 `assemble`、删 `petStatus`）
- `.../content/web/ContentFeedController.java`（删 `petStatus` 与 `resolvePetStatus`、接 `X-Anon-Session`）
- `.../content/rank/FeedRankProperties.java`（新增 `candidatePoolSize`）
- `petgo-backend/src/main/resources/application.yml`（新增 `candidate-pool-size`）

**新增（App）**
- `petgo_app/lib/features/content/data/anon_feed_session.dart`

**修改（App）**
- `petgo_app/lib/features/content/data/feed_repository.dart`（首页取数带 `X-Anon-Session`）

**新增（测试）**
- `.../content/rank/FeedRecommendationServiceTest.java`（L0 · 13）
- `.../content/rank/FeedRankRoutingIntegrationTest.java`（L1 端点层 · 5）
- `.../content/rank/FeedRankQueryIntegrationTest.java`（L1 真库 · 7）
- `petgo_app/test/features/content/data/anon_feed_session_test.dart`（L0 · 3）

**修改（测试）**
- `.../support/ApiIntegrationTest.java`（L1 基类压小候选池，见第 6b 条）
- `.../content/service/FeedServiceTest.java`（签名 + 分类 Tab 化 + 新增 5 条 ALL 分流用例）
- `.../content/service/FeedBatchAggregationTest.java`（签名 + 分类 Tab 化）
- `.../content/rank/FeedSeenStoreTest.java` / `FeedSequenceStoreTest.java`（构造器多一参）
- `.../moderation/web/ReportTriageIntegrationTest.java`（`feedContains` 改走时间倒序，见第 6 条）

---

## Change Log

| 日期 | 变更 |
|---|---|
| 2026-08-24 | 由 1.1.6 补充 PRD 拆出（拆前做过一次代码核查，五处过期陈述已修正），status = ready-for-dev |
| 2026-08-24 | 实现完成：ALL Tab 接推荐序（两条独立路径）、候选池两个新查询、级别 4 回落保过滤、petStatus 删净、客户端匿名会话 id；四次反证确认（其中一次红得不是地方，补精确断言后重做）；status = review |
