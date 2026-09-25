---
name: 'V1.3.0 批次 A 架构 Delta —— 事实校验评审'
type: architecture-review
lane: reality-check
target: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-delta.md
reviewer: 'BMAD 架构 spine 评审员 · 事实校验关'
date: '2026-09-10'
codebase: 'petgo-platform @ stag'
verdict: 'CHANGES-REQUESTED'
---

# 事实校验评审 —— architecture-v1.3.0-delta.md（批次 A）

## 总体判定

**CHANGES-REQUESTED。** 这份 delta 的"事实密度"在同类文档里算高的：分享卡画布规格、二维码三条硬要求、Feed 三段比例口径、PawCoin 两层配置、`milestone_completions` 的形态与唯一约束、一次性引导标记的现状 —— 我逐条到代码里核过，**全部属实，连数字都对得上**，不是凭印象写的。

但有 **1 条阻断级、4 条严重级**的断言与代码不符，而且它们都不是无关痛痒的边角，恰好是决定 story 怎么拆、拆成单侧还是双侧的那几条：

- FR-113 被判为"零接口变更"，实际**详情接口根本不下发图片尺寸**，AD-A12 照现状无法实现；
- FR-111 的"现网 bug 成因与修复实质"两句都写反了 —— 要修的按钮**代码里早就没有了**，而真正在错误点亮里程碑的那个 bug 被判成了"无用户可感知影响"；
- AD-A8.5 要求的"覆盖排序索引"在 PostgreSQL 里**物理上不存在**（排序键是另一张表的聚合值）；
- AD-A20 漏了既有渠道那一整张记账表。

下面逐项给证据。严重度：🔴 阻断 / 🟠 严重 / 🟡 中 / 🔵 低。

---

## 1. AD-A1 —— 完成态与 `celebrated_at` 的落点 ✅ 全部属实

| 断言 | 核实结果 |
|---|---|
| 完成态在 `milestone_completions` 而不是 `pet_milestones` | ✅ `V27__init_milestones.sql` 表头注释逐字写着"`pet_milestones` … 不含完成数据；完成与否由 `milestone_completions` 是否存在对应行决定（不预插完成行）" |
| `pet_milestones` 是"每宠物的目录实例，建档即全量铺行" | ✅ 同上："建档时物化自后端固定常量 `MilestoneCatalog`" |
| 该表有 `completed_at` | ✅ `completed_at TIMESTAMPTZ NOT NULL DEFAULT now()`，实体 `MilestoneCompletion.java` 同名字段 + `@PrePersist` |
| 与 `pet_milestones` 是 1:1 唯一约束 | ✅ `CONSTRAINT uq_milestone_completions_milestone UNIQUE (pet_milestone_id)`，名字与 delta 引用的完全一致 |

**订正 PRD §3.2 这件事本身是对的**，R-2 的回写要求成立。

回填语句 `UPDATE milestone_completions SET celebrated_at = completed_at WHERE celebrated_at IS NULL` 在新增可空列的场景下会命中全部存量行，语义正确，AD-A1.4 的"不可省"论证也站得住。

**证据文件**
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V27__init_milestones.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/profile/domain/MilestoneCompletion.java`

---

## 2. AD-A4 / AD-A5 —— 健康类判定与 G-M1/G-M2

### 2.1 属实的部分 ✅

| 断言 | 核实结果 |
|---|---|
| 后端健康类判定当前是"后缀集合 M3/M4/M5/M9" | ✅ `HealthMilestones.SUFFIXES = Set.of("M3","M4","M5","M9")`，`isHealthMilestone()` 按 `lastIndexOf('-')` 取后缀比对 |
| 它是唯一事实源、被三处引用 | ✅ 生产代码两处（`MilestoneCheckInService:72` 拒绝打卡护栏、`MilestoneAnalyticsPath:56` 埋点分路），两条测试逐字钉住集合内容 |
| 前端只映射了 M3→VACCINE、M4→DEWORM | ✅ `healthPresetTypeFor(code)` 只有两行：`endsWith('-M3')→'VACCINE'`、`endsWith('-M4')→'DEWORM'`，其余返回 null |
| 健康记录类型只有五种、确无"体检" | ✅ `HealthRecordType` 枚举五值；`V76` 的 `ck_health_records_type CHECK (type IN ('VACCINE','DEWORM','MENSTRUATION','NEUTER','CUSTOM'))`；`HealthRecordService:126` 的校验文案同集 |
| G-M1 / G-M2 当前确为 `USER_CHECKIN` | ✅ `MilestoneCatalog.java:192-193` 两行都是 `q.m(USER_CHECKIN, …)` |
| G 系列 M 级只有 M1–M4（故 AD-A4 的显式清单里没有 G-M5/G-M9 是对的） | ✅ `buildOther()` M 级四条：G-M1/G-M2/G-M3/G-M4 |

### 2.2 🔴 AD-A4.3「G-M3 / G-M4 现无用户可感知影响」—— 与代码不符，且这是本批次真正该修的那个 bug

自动完成的落点不是"后缀"，是**拼出来的完整 code**：

```java
// MilestoneCompletionService.java:81
String code = prefixOf(petType) + "-" + suffix;   // prefixOf: CAT→"C" DOG→"D" OTHER→"G"
```

而健康记录 → 后缀的映射是 `MilestoneAutoCompleteListener.suffixFor()`：`VACCINE→"M3"`、`DEWORM→"M4"`、`NEUTER→"M9"`。

于是对**「其他」类宠物（`OTHER` → 前缀 G）**：

| 用户动作 | 拼出的 code | 实际点亮的里程碑 |
|---|---|---|
| 录一条 `VACCINE` 健康记录 | `G-M3` | **「陪伴满 30 天」** ❌ |
| 录一条 `DEWORM` 健康记录 | `G-M4` | **「成长日历记录满 10 条」** ❌ |
| 录一条 `NEUTER` | `G-M9` | 不存在 → 静默 no-op（这条无害） |
| 真人兽医咨询结束 | `G-M5` | 不存在 → G-M1「第一次看兽医」**永远点不亮** |

前端有**同一条错位的两处镜像**：

- `healthPresetTypeFor()` 按 `endsWith('-M3')` 判定 → "其他"类宠物点未完成的**「陪伴满 30 天」**灰徽章，会跳到"新增疫苗记录"表单（`milestone_list_page.dart:472-474`）；
- `mayUnlockHealthMilestone()` 里 `'VACCINE' => uncompleted('M3')` → 同一错位决定要不要启动庆祝短轮询（`health_list_page.dart:895`）。

**这是明确的用户可感知错误**：错误点亮 + 错误跳转 + 里程碑完成埋点被污染（`MILESTONE_NODE` 完成事件带的是 `G-M3` 的中文标题"陪伴满 30 天"）。

**为什么这条要标阻断**：AD-A4.3 把它定性成"现无用户可感知影响、顺带清理"，实现者照这个定性走，八成只会把 `isHealthMilestone` 改成显式 code 列举 —— 而 `isHealthMilestone` **根本不在这条 bug 的路径上**（它只管拒绝打卡与埋点分路）。真正要动的是 `suffixFor` → `complete()` 这条"后缀跨系列拼 code"的链路。改完 AD-A4，bug 原封不动留着。

**建议**：把这条从 AD-A4.3 的附注提升为独立 Rule，明确"健康记录类型 → 里程碑的映射必须按 **完整 code**（`C-M3/D-M3` 而非 `G-M3`）或按 **pet_type 分表**，禁止用后缀跨系列拼装"，并写清 G-M1/G-M2 接管后 G 系列的对应关系。

### 2.3 🟠 AD-A4 的 Prevents 与 AD-A6.3 —— "现网 bug 的成因/实质"两句都归错因了

delta 说：成因是"后端拒绝打卡的集合与前端跳健康记录页的集合长期不等长"，实质是"绝不能留一个后端必拒的按钮"。核实下来两句都不成立：

**(a) 两个集合不是"不等长"，是逐字等长。** 前端有一份独立的 `kAutoOnlyHealthMilestoneSuffixes = {'M3','M4','M5','M9'}`（`petgo_app/lib/features/profile/domain/health_milestones.dart:14`），文件头注释还明确写了它对应后端 `HealthMilestones.java`。真正只有两条的是**跳转映射表** `healthPresetTypeFor` —— 那是另一张用途不同的表，两者本来就不该等长。

**(b) "后端必拒的按钮"代码里早就没有了。** 底抽屉的两个按钮受这一行门控：

```dart
// milestone_list_page.dart:495
final showCheckinActions = item.trigger.isCheckin && !completed && !autoOnlyHealth;
```

`autoOnlyHealth` 对 M3/M4/M5/M9 全为 true，所以**「已打卡」「去发布」对全部四条健康类 code 现在就不出现**，说明文案也已经切成 `l10n.milestoneHintAutoHealth`（"怎样才会自动点亮"）。

**现网 bug 的真实形态是**：M5/M9 的灰徽章点开后，只有一段只读说明、**没有任何去处** —— 用户"点了白点"是因为点了没地方去，不是因为点了个会被拒的按钮。M3/M4 有去处（跳健康记录页预选类型），M5/M9 没有，这就是决策 A-2 说的那个不一致。

**影响**：AD-A6.3 把一个已完成的修复列为本批次交付物，story 会空转；同时它掩盖了真正要做的事 —— 给 M5 / M9 / G-M1 补上去向（AD-A6.1、A6.2 才是实质，且 `-M9 → NEUTER` 预选确实是新增的，`healthPresetTypeFor` 里没有）。建议把 AD-A6.3 改写成"确认现状已满足，不得回退"，把 Prevents 的归因改成"跳转映射表只覆盖 M3/M4，M5/M9/G-M1 无去向"。

### 2.4 ✅ AD-A5 的其余判断成立

- G-M2 只由 `VACCINE` 触发、不新增"体检"类型 —— 与"五值不变"的现状一致，代价（只做体检没打疫苗点不亮）描述准确。
- G-M1 由真人兽医咨询结束触发、不含 AI 问诊 —— 与 `MilestoneAutoCompleteListener.onConsultClosed` 的既有注释口径一致（"`ConsultClosedEvent` 只由真人兽医会话发布，AI 分诊走 triage 模块不发这个事件，模块隔离天然满足"）。**注意这是新增行为**：如 2.2 所述，G-M5 不存在，所以 G-M1 目前根本没有自动路径，需要新写。

**证据文件**
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/profile/domain/HealthMilestones.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/profile/domain/MilestoneCatalog.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/profile/service/MilestoneCompletionService.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/profile/service/MilestoneAutoCompleteListener.java`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/profile/domain/health_milestones.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/profile/presentation/milestone_list_page.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/profile/presentation/health_list_page.dart`

---

## 3. AD-A7 —— `content_likes` 形态 ✅ / "全库无冗余计数列" ❌

### 3.1 ✅ `content_likes` 的表形态与文中描述逐字一致

```sql
-- V10__init_content_likes.sql
CREATE TABLE content_likes (
    id         BIGSERIAL    PRIMARY KEY,
    post_id    BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT uq_content_likes_post_user UNIQUE (post_id, user_id),
    CONSTRAINT fk_content_likes_post FOREIGN KEY (post_id) REFERENCES content_posts (id),
    CONSTRAINT fk_content_likes_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_content_likes_post ON content_likes (post_id);
```

AD-A7.1 描述的"`id BIGSERIAL` / `comment_id` / `user_id` / `created_at TIMESTAMPTZ DEFAULT now()` + `UNIQUE` + 两条外键"是逐字同构，✅。

"点赞数实时 COUNT、不做缓存"也有官方留痕：`V86__add_column_comments.sql:519` 的表注释写着"取消点赞是直接删行（不软删）；点赞数实时 COUNT 统计，不做缓存"。

### 3.2 🔵 但"全库至今**没有任何**冗余计数列"是过头话

全库扫下来至少两处反例：

| 列 | 出处 | 性质 |
|---|---|---|
| `users.published_count` | `V82__alter_users_account_type.sql:11`，由 `User.java:402` 的 `this.publishedCount++` 维护 | **就是**一列冗余计数（`content_posts` 的派生值），且是应用层自增维护 |
| `content_post_views.view_count` | `V20260831_1254__init_content_post_views.sql:10` | 累加计数列（每"内容×观看者"一行，`SUM(view_count)` 取次数） |

（`retry_count` / `release_count` / `used_count` 这类是状态机计数器，不算，我没往里凑。）

**影响**：结论（评论点赞不加计数列、实时聚合）**依然成立且正确** —— `content_likes` 确实是实时 COUNT，这是本地最相关的先例。但"开了就是全库第一个"这个论据不成立，评审时会被一句话打回。建议改为"**点赞 / 评论侧至今没有冗余计数列**，`content_likes` 亦为实时 COUNT（`V86` 表注释在案）"。

---

## 4. AD-A8 —— 评论分页 ✅ / "覆盖排序索引" 🟠 物理上做不到

### 4.1 ✅ 当前分页确为 `(createdAt, id)` 正序 keyset

`CommentRepository.findTopLevel` 与 `findReplies` 两条 JPQL 的游标条件与排序完全一致：

```
AND (:hasCursor = false
     OR c.createdAt > :cursorTs
     OR (c.createdAt = :cursorTs AND c.id > :cursorId))
ORDER BY c.createdAt ASC, c.id ASC
```

方法上的 javadoc 也写明"游标比较：`(createdAt,id) > (cursorTs,cursorId)`（正序）"。AD-A8.1 / A8.2 的现状陈述 ✅。

### 4.2 🟠 AD-A8.5 的"覆盖排序的索引（post_id + 层级 + 排序键）"不存在

排序键 `likeCount` 是 **`comment_likes` 这张表的聚合值**，不是 `comments` 上的列。PostgreSQL 里没有任何 `comments` 上的索引能给一个跨表聚合结果排序。实际执行计划必然是：

> 取该帖**全部**一级评论 → LEFT JOIN `comment_likes` → GROUP BY → 按 `count DESC, created_at ASC, id ASC` 排序 → 再用三元组 keyset 过滤 → 取 N 条

也就是说，**每翻一页都要把整帖的评论重新聚合排序一遍**，keyset 在这里只省了 OFFSET 的行扫描，省不掉聚合。索引 `comment_likes(comment_id)` 只能加速 JOIN 那一步。

§6 的 D-9 把"索引具体形态"推给实现层，但这不是形态选择的差别 —— 这是**读路径量级不同**。架构层至少要交代其中一件：

1. 单帖一级评论的量级上界（若上界几百条，全量聚合完全可接受，写清楚即可结案）；
2. 或者只对前 N 条做热度排序、N 之后回落时间序（这也是 §6 D-5 备选方案的天然邻居）。

### 4.3 🟡 AD-A7.3 + AD-A8 叠加后的代价没写出来

"热度序" + "实时聚合" + "不加计数列" + "keyset 全序" 四条 Rule 同时成立时，唯一实现路径就是 4.2 那条全量聚合。这四条各自都有充分理由，但 delta 没在任何一处把叠加代价说出来，等于把它交给实现者在写 SQL 时才发现。建议在 AD-A8 里补一句代价声明，并把 4.2 的两个选项之一定死。

### 4.4 ✅ AD-A8.4 的 ASSUMPTION 论证成立

"取消点赞是物理删行、历史计数不可重建"这一条我核过：`content_likes` 无软删列，`V86` 注释明说"取消点赞是直接删行（不软删）"。所以"真快照要么引服务端状态要么引存储"的判断是对的，OQ-2 建议接受弱化方案有据。

---

## 5. AD-A11 / AD-A12 —— 比例口径 ✅ 全对 / 但"零接口变更"🔴 不成立

### 5.1 ✅ 三段口径与闭区间，逐字属实

```dart
// petgo_app/lib/features/content/domain/feed_image_layout.dart
const double kFeedRatioMin = 0.75;   // :55
const double kFeedRatioMax = 1.34;   // :58

double clampFeedRatio(double ratio) {           // :125
  if (!ratio.isFinite || ratio <= 0) return kFeedPlaceholderRatio;
  if (ratio < kFeedRatioMin) return kFeedRatioMin;   // 只有「小于」才夹
  if (ratio > kFeedRatioMax) return kFeedRatioMax;   // 只有「大于」才夹
  return ratio;
}
```

闭区间 `0.75~1.34` ✅，而且源码注释里已经把"写成开区间会把 3:4 竖拍排除掉"这个坑记下来了，与 AD-A11.2 的强调完全同源。`resolveFeedImageAspect` 的三步顺序（实际比例 → clamp → 高度护栏）与 delta 描述一致，注释也写明"护栏放在最后…若先护栏后 clamp，护栏白做" ✅。

### 5.2 ✅ 详情页当前确为 `AspectRatio(1)` + `cover`

```dart
// content_detail_page.dart:451-460
child: AspectRatio(
  aspectRatio: 1,
  child: PageView.builder(
    ...
    child: AppImage.widget(widget.urls[i], fit: BoxFit.cover, thumbWidth: 1080, ...)
```

注释里还写着"详情方图：按手机全宽取缩略图"。✅

装饰标签左下（`Positioned(left, bottom)` + 注释"与右上角的页码角标分处两角、互不遮挡"）、无图分支走"正文下方单独一行小胶囊"—— AD-A11.5 / A11.6 的现状陈述也都 ✅。

### 5.3 ✅ v1.1.6 的图片尺寸列确已存在

- 迁移：`V20260818_1344__add_content_post_image_sizes.sql` → `ALTER TABLE content_posts ADD COLUMN IF NOT EXISTS image_sizes JSONB`
- 实体：`ContentPost.java:66-67` → `@Column(name="image_sizes") private List<ImageSize> imageSizes;`
- 列可空、无回填 → AD-A12.3"存量内容永远无尺寸、占位兜底必做"✅

### 5.4 🔴 但**详情接口不下发 `imageSizes`** —— AD-A12.1 现状下无法实现，§1 的"零接口变更"不成立

| 位置 | 有 `imageSizes` 吗 |
|---|---|
| `FeedItemResponse.java:61` | ✅ 有 |
| `ContentPostCreateRequest.java:42` | ✅ 有（发布时上行） |
| **`ContentDetailResponse.java`** | ❌ **只有 `imageUrls`（:32），没有 imageSizes** |
| **App `ContentDetail`（`content_detail.dart`）** | ❌ **只有 `imageUrls`（:44、:56、:72），没有 imageSizes** |

AD-A12.1 写"尺寸一律取 v1.1.6 AD-5 的尺寸列（与图片数组同序等长），详情页不另测"—— 数据**根本没送到详情页**。要落地必须：

1. `ContentDetailResponse` 加 `List<ImageSize> imageSizes` 字段（**契约变更**）；
2. App `ContentDetail.fromJson` 加解析（`ImageSize.listFromJson` 已存在，可直接复用）。

**连带三处失准**：

- §1 形态判断 1「FR-113 / FR-115 / FR-116 是**纯客户端改造（零接口变更）**」→ FR-113 是双侧 story；
- AD-A22「新增的全部运行时开销是四支 Flyway 迁移与**两处查询**」→ 至少多一处响应体字段；
- §1 的 mermaid 图把 F113 画在"客户端为主（零/近零接口变更）"子图里 → 需要移出或改标注。

**为什么标阻断**：这条直接决定 FR-113 拆成单侧还是双侧 story、要不要排后端工时。按现文拆，前端工程师做到一半会发现拿不到尺寸，只剩两条路 —— 要么临时找后端加字段（打乱排期），要么现场测量（正是 AD-A12.2 明令不采纳的做法）。

**顺带**：`ContentDetailResponse` 里既然要加字段，`SharedPostResponse`（分享落地页）是否同口径，建议一并在 AD 里点名，免得又漏一处。

---

## 6. AD-A15 / AD-A16 —— 无第三方包 ✅ 属实，可行性成立

### 6.1 ✅ `pubspec.yaml` 确无图片查看器类、reorder 类包

我把 `pubspec.yaml` 的 dependencies 段全表核过（含 `dependency_overrides` 与 `dev_dependencies`），关键词 `reorder / photo_view / preview / gallery / zoom / carousel` **零命中**。与图片相关的只有 `image_picker`（选图）、`image`（纯 Dart 解码/剥 EXIF）、`gal`（存相册）、`flutter_svg`、`qr_flutter` —— 都不是查看器。✅

### 6.2 ✅ 项目里确无同款下拉关闭手势与拖拽重排网格

现有灯箱是详情页私有类（`content_detail_page.dart:504-550`），全部能力就三样：

```dart
Scaffold(backgroundColor: Colors.black,
  appBar: AppBar(title: '${_current+1}/${widget.urls.length}'),   // 不是沉浸态
  body: PageView.builder(...
    itemBuilder: GestureDetector(onTap: () => Navigator.pop(),     // 单击关闭
      child: Center(child: InteractiveViewer(                      // 缩放（无双击）
        child: AppImage.widget(urls[i], fit: BoxFit.contain)))))
```

**无下滑关闭、无双击缩放、无飞入飞出过渡、无失败重试**（现状确实只有灰块，AD-A15.6 的描述准确）。另一处 `shared/widgets/case_image_viewer.dart:79`（问诊病例图）同样只是 `InteractiveViewer` —— AD-A14.3 说"不在范围"是有具体对象的，不是空指。✅

发布页也确无拖拽重排网格（全库 `onDoubleTap` / reorder 相关零命中）。✅

### 6.3 ✅ 依赖可行性判断成立

- **AD-A15 四个手势**：Flutter SDK 自带 `InteractiveViewer`（缩放与平移，含上下限）、`GestureDetector` / `RawGestureDetector`（双击、拖拽阈值）、`Hero`（原位飞入飞出）、`PageView.physics`（放大态禁翻页）、`SystemChrome`（沉浸态与恢复）。**不需要引包，结论成立。**
- **AD-A16 拖拽重排**：SDK 只提供 `ReorderableListView`（列表形态），**网格重排要自实现**（`LongPressDraggable` + `DragTarget` + 位移动画）。不引包的结论成立，但"长按抬起→拖到目标→其余让位"这一句话背后的工作量比字面大，建议在工作拆分预览里单独给点数。

### 6.4 🔵 AD-A15 漏了一条已修 bug 的行为，不写清会回归

现灯箱的"点击图片或黑边即关闭"是 **bug 20260701-192** 的修复（代码注释在案："对齐主流看图 App 交互；与翻页/缩放手势不冲突"）。AD-A15 只列了"悬浮关闭 ✕ + 页码胶囊"和四个手势，**没说单击关闭是保留还是取消**。改写时被顺手删掉就是回归。建议在 AD-A15.1 里补一句"单击关闭保留"。

同理 `_openLightbox` 的 `bug 20260727-372`（单图进灯箱后可左右翻页）也是既有修复，AD-A15.2 的手势优先级表隐含保留了翻页，但没点名这条 bug，建议加个引用。

---

## 7. AD-A17 —— `/profile/id-card` ✅ 存在，引用点数量 ✅ 对，但**遗漏两类引用点** 🟠

### 7.1 ✅ 路由与站内跳转数量与文中说法一致

```dart
// petgo_app/lib/core/router/app_router.dart:675
GoRoute(path: '/profile/id-card', builder: (c, s) => const IdCardPage()),
```

站内跳转确为**两处**，都在同一个文件：

- `growth_archive_page.dart:368` → `onOpenIdCard: () => context.push('/profile/id-card')`
- `growth_archive_page.dart:706` → `context.push('/profile/id-card')`

AD-A17.2 说"站内至少两处跳转"✅ 准确。

（`/profile/id-cards/create` 与 `/profile/id-cards/:id` 是多卡子路由，与本条无关，不受迁移影响 —— 但**它们和 `/profile/id-card` 只差一个字母**，迁移时如果只做前缀替换会误伤，建议在 AD 里标一句。）

### 7.2 ✅「潜在的历史通知深链」查证：目前一条都没有

我把通知深链的完整映射查了一遍：`DeepLinkRoutes.pushPayloadToLocation` 处理 `NEW_CONSULT_REQUEST / PET_BIRTHDAY / COMPANION_ANNIVERSARY / MILESTONE_NODE / VET_REPLY / CONSULT_CLOSED / CONTENT_LIKED / CONTENT_COMMENTED`，**没有任何类型落到 id-card**，其它一律兜底到 `/notifications`。

原文用了"潜在的"来措辞，不算硬错；但"**断链是硬失败**"这条结论的主要支撑其实是下面 7.3 的两类，而不是通知深链。建议把论据换掉。

### 7.3 🟠 遗漏的引用点两类 —— 其中一类踩安全红线

**(a) 游客门控靠 `/profile/` 前缀 —— 这是 CLAUDE.md 的安全红线。**

`app_router.dart:122` 定义"未登录游客不可直接进入的受控路由**前缀**（FR-19 门控）"，`:434` 执行 `if (!auth.isLoggedIn && controlled) return '/home';  // 安全规则只升不降：游客不进受控路由`；`:135-144` 还写明"反向做法（改成列举受控子页）**明确禁止**：新增子页忘登记就会对游客敞开"。

`test/profile/visitor_route_gating_test.dart:25` 逐条钉住了 `['/profile/edit', '/profile/id-card', '/profile/health', '/publish']`。

**含义**：新的"Know more about your pet"聚合页与迁移后的 KTP 页**必须仍在 `/profile/` 前缀下**，且 `/profile/id-card` 的 redirect **不能绕过门控重定向的执行顺序**（redirect 与门控 redirect 在同一个 `redirect` 回调里，先后顺序会决定游客带旧深链进来时是落 `/home` 还是先跳到新路径再被弹走）。这落在 CLAUDE.md「安全规则层**只升不降不可绕过**」的红线上，**架构层必须写死，不能留给实现**。AD-A17 一个字没提。

**(b) 埋点屏名与四个测试以字面量为基线。**

以下位置直接写死了 `/profile/id-card` 字面量：

- `test/analytics/v112_events_test.dart:87` —— 埋点用例的路由表
- `test/shared/diary_gating_and_landing_test.dart:59`
- `test/profile/visitor_route_gating_test.dart:25`
- `test/profile/pet_card_share_analytics_test.dart:51`
- `test/profile/timeline_five_class_render_test.dart:67`
- `lib/.../widgets/diary_header.dart:18`（注释里的受控页举例）

AD-A23 只锁了六个**新**事件，没提**屏名 / 路由口径变更**。KTP 页换路径 = PostHog 的 `$screen_name` 换值 = KTP 页的历史留存/漏斗数据断代。这至少要在 AD-A23 或 §7 反向影响里点名（是保持旧屏名、还是接受断代并在埋点平台标注切换点）。

### 7.4 ✅ AD-A17.3 / A17.4 无事实问题

"不占位、不置灰、不出现"是产品约束，非代码事实。非猫狗置灰：`PetType` 三值（CAT/DOG/OTHER）确实存在，可判定；"站内既有『功能对当前项不适用』的统一做法"我没在代码里找到明确的同款组件（grep `不适用 / notSupported / unsupported` 在 profile 域零命中），这句"与既有做法一致"缺证据 🔵 —— 不影响决策，但别当成"有现成组件可复用"来排期。

---

## 8. AD-A19 / AD-A20 —— 分享卡基建与 PawCoin ✅ 数字全对 / 但 AD-A20 漏了一整张表 🟠

### 8.1 ✅ 画布规格逐字属实

```dart
// petgo_app/lib/shared/card_render/card_canvas.dart
static const CardCanvas story  = CardCanvas(size: Size(1080, 1920), radius: 48);  // :27
static const CardCanvas square = CardCanvas(size: Size(1080, 1080), radius: 48);  // :30
```

默认 9:16 也对：`share_card_preview_page.dart:51` → `CardCanvas _canvas = CardCanvas.story;` ✅

### 8.2 ✅ 二维码三条硬要求全部有据

`share_card_template.dart` 里三条都能对上：

- **最小边长**：`CardQr.minExportSide = 140`（:71、:103、:363 三处引用，:376 还写着"这是既有的『导出图里码 ≥140』那条用例当场抓住的" → **有测试守门**）
- **静默区**：":72 四周必须留 4 个码元的静默区（`CardQr.footprintFor`），少了就扫不出来"，实际占位 1.381 倍
- **白色底板**：":149 渐变正是二维码必须带白色底板的原因（AD-15 Rule 4 第 4 条）"

AD-A19.4 说"这三条是可扫底线，不可为视觉让步"—— 与源码注释同源，✅。而且 1:1 画布那侧余量只剩 9px（":363-364"），AD-A19 让年龄卡沿用同一套是对的，但值得在实现时提醒：**年龄卡的 1:1 模板同样只有这点余量**。

### 8.3 ✅ PawCoin 两层结构属实

| 层 | 列 | 出处 |
|---|---|---|
| 全局层 | `share_reward_enabled BOOLEAN DEFAULT true`、`share_reward_monthly_cap BIGINT DEFAULT 0` | `V20260825_0236__pawcoin_config_add_share_reward.sql:20-21` |
| 渠道层（KTP） | `id_card_share_reward BIGINT DEFAULT 0`、`id_card_share_daily_cap INT DEFAULT 0` + `ck_pawcoin_id_card_share CHECK (… >= 0 AND … >= 0)` | `V20260825_0247__pawcoin_config_add_id_card_share.sql:15-21` |

"渠道层两列（单次奖励 + 单日上限）"✅，"与既有 KTP 渠道两列同构"✅，AD-A20.1 准确。

**"跨渠道共用全局月度上限"也有硬证据**：`share_reward_quotas` 建表注释写着"🛡 额度按『所有分享类行为』合一（AC1）：本表**没有渠道列** —— 加了渠道列就变成按渠道各算一份，等于上限乘以渠道数"。AD-A20.2 ✅。

### 8.4 🟠 AD-A20 漏了既有渠道那张**记账表**，§5 也就少了一支迁移

既有 KTP 渠道除了配置表两列，还有一张专表：

```sql
-- V20260825_0248__create_id_card_share_rewards.sql
CREATE TABLE id_card_share_rewards (
    id, pet_profile_id, user_id, card_id, coins,
    share_date DATE NOT NULL,        -- WIB 当地日期，用于日上限判定
    created_at,
    CONSTRAINT uq_id_card_share_rewards_profile UNIQUE (pet_profile_id)   -- 去重键
);
CREATE INDEX ix_id_card_share_rewards_user_date ON id_card_share_rewards (user_id, share_date);
```

它承担**两件配置列做不了的事**：① 发放去重（唯一约束层面幂等，注释明说"不靠先查有没有再插——那是典型的并发双发"）；② 日上限记账（`(user_id, share_date)`，`share_date` 走 **WIB 当地日期**，不是 UTC）。

年龄卡渠道要真发币，同样需要一份渠道记账，而且**它的去重语义还不一样**：

- KTP 的去重键是 `pet_profile_id`（"一个档案只发一次"），因为卡可无限建、按卡去重等于无去重；
- **年龄卡纯客户端生成、不落服务端、无 card_id、也没有"一张卡"的概念**（AD-A18 明确不落库）。那它按什么去重？按 `pet_profile_id`？按天？还是只受日上限约束？

**AD-A20 一句没提，§5 的四支迁移里也没有这一支。** 这是"照着写就会漏"的空洞 —— 实现者要么临时加表（迁移数变五支）、要么干脆不去重（并发双发漏币）。

**建议**：AD-A20 补第 5 条明确去重键与记账载体；§5 迁移表补第 5 支；同时把 AD-A19「年龄卡不落服务端」与"发币需要服务端记账"这对张力显式化 —— 卡不落库，但**领奖动作**必须落库。

### 8.5 🔵 "两列默认 0 = 不发币"属实，但闸门是三串联

`V20260825_0247` 的注释提醒："⚠️ **三个数里任意一个是 0 都不会发**（闸门是串联的），所以配的时候三个都要看" —— 全局 `share_reward_monthly_cap` 默认也是 0。AD-A20.3 只说了渠道两列默认 0，运营照 AD 只开两列仍然一分不发。补一句即可。

### 8.6 ✅ AD-A19.3 对 v1.1.6 AD-15.4 的"范围澄清"逻辑自洽

"年龄卡没有可指向的单条内容"这个前提，与 AD-A18（纯客户端、不落库、无 H5 回流页）自洽，澄清而非推翻的论证方式成立。无事实问题。

---

## 9. AD-A21 —— 一次性引导标记 ✅ 全部属实

| 断言 | 核实结果 |
|---|---|
| 现有一次性引导标记走本地偏好存储 | ✅ 全库唯一一处：`petgo_app/lib/core/storage/prefs.dart:32` `setPushPermissionAsked` / `:43` `setPushPermissionLastGranted`，都是 `SharedPreferences.setBool`，按设备 |
| 对应 v1.1.6 AD-14（推送权限引导） | ✅ 就是推送权限那条，与 delta 的引用一致 |
| `users` 表确无可复用的通用标记机制 | ✅ 逐列核过：`V2__init_auth.sql` 建表 13 列 + 12 支 `ALTER TABLE users`（phone / last_active_at / apple_sub / status / is_system_default_name / deleted_email / deleted_display_name / locale / password_hash / account_type + published_count / signature）。唯一沾边的是 **`onboarding_completed BOOLEAN`** —— 一个专用布尔，不是通用键值机制。全库也无 `user_*_flags` / `user_preferences` 类表 |
| 项目内无现成 coachmark 组件 | ✅ `coachmark / CoachMark / spotlight / Spotlight / 挖孔 / 聚光` 全库零命中，AD-A21.5"需新实现"属实 |

AD-A21.2 的差异化理由（推送引导的判断依据"系统通知开关"本身是设备级状态，故标记按设备；认知性告知按账号）逻辑成立，"两条并存各管各的场景"这个收口方式没有事实问题。✅

### 🔵 唯一提醒：新表的注销口径没写

§4 只说"新增的用户引导标记表**不含任何 PII**"。但 CLAUDE.md 把"注销**级联删除/匿名化**按 D1/D2 落实"列为三类安全攸关节点之一。既有同类行为表的处理是明确的（`content_post_views` 建表注释："注销口径与 `content_likes` 一致（D1/A：账号就地匿名化、行为数据保留），不做级联删除"）。新引导标记表按 `user_id` 外键，注销时是删行还是保留，**架构层该给一句**，否则实现者要么漏做、要么各写各的。

---

## 10. §5「本批次不涉及 CHECK 约束重建」 ✅ 成立

逐支核对四支迁移：

| # | 内容 | 是否触及既有 CHECK |
|---|---|---|
| 1 | `milestone_completions` 加 `celebrated_at TIMESTAMPTZ NULL` + 回填 | ❌ 不触及。既有 `ck_milestone_completions_source` 管的是 `source` 列，与新列无关 |
| 2 | 新建 `comment_likes` | ❌ 新表自带的约束是**新增**，不是重建 |
| 3 | PawCoin 两列 + `>= 0` CHECK | ❌ 按既有 `ck_pawcoin_id_card_share` 同构 **`ADD CONSTRAINT` 新约束**（V20260825_0247 就是这么加的），不动任何旧约束 |
| 4 | 新建引导标记表 | ❌ 新表 |

两条最容易踩的既有 CHECK 也确认不涉及：

- **`ck_health_records_type`**（`V76:16`）—— 决策 A-1 不新增"体检"类型，确实不动。§5 那句"若后续改判要加，必须 `DROP + ADD` 全量重列、值取自当前树里最后一条重建它的迁移"与 CLAUDE.md 的纪律逐字一致，写得对。
- **`ck_notifications_type`**（最后一次重建在 `V97__union_notification_types_two_lines.sql:7-8`，此前 `V72` 也重建过 —— 正是 CLAUDE.md 记的那几次事故）—— 本批次不涉及：PRD 第 129 行明说"本 FR 不动通知"，FR-114 的评论点赞也**没有定义任何通知**。✅

### 🟡 但相邻有一处存量数据问题，§5 没覆盖

AD-A5 把 G-M1 / G-M2 从"打卡"改成"自动"，而 **`pet_milestones.trigger_type` 是建档时物化的行数据**（`V27`：建档即按 `MilestoneCatalog` 全量铺行）。改了目录常量之后：

- 存量宠物的 G-M1/G-M2 行仍是 `USER_CHECKIN`；
- 之后新建档的宠物拿到的是新值。

`ck_pet_milestones_trigger CHECK (trigger_type IN ('SYSTEM_AUTO','USER_CHECKIN','PUSH_PUBLISH'))` **不需要改**（SYSTEM_AUTO 已在集合内），所以 §5 的结论不受影响。但"新老宠物同一 code 的 trigger 不一致"这件事需要一个明确立场，二选一：

1. 补一支回填迁移把存量行改齐；
2. 或明写"**以 code 判定为准，`trigger_type` 不作数**"—— 前端其实已经这么干了（`milestone_list_page.dart:561` 注释："即使后端历史数据里 `triggerType` 仍是 `USER_CHECKIN`，也不渲染打卡入口"）。

另外 `MilestoneCatalogTest` / `MilestoneCatalogI18nTest` / `MilestoneListResponseContractTest` 三个测试很可能钉住了目录内容与契约，改 catalog 会连带改测试 —— 属实现细节，但排期时别漏。

---

## 11. 「不引入任何新依赖 / 新中间件」总判 —— ✅ 成立，但 AD-A22 的开销清单 ❌ 不全

### 11.1 ✅ 逐条 Rule 的依赖可行性

| Rule | 现有依赖够不够 | 结论 |
|---|---|---|
| AD-A15 灯箱四手势 | Flutter SDK：`InteractiveViewer` / `GestureDetector` / `Hero` / `PageView.physics` / `SystemChrome` | ✅ 够，不需引包 |
| AD-A16 拖拽重排网格 | SDK 只有 `ReorderableListView`（列表），网格需 `LongPressDraggable` + `DragTarget` 自实现 | ✅ 不引包成立；工作量未标注 |
| AD-A8 三元组 keyset 跨表聚合排序 | JPQL / native query 都能写，无需新依赖 | ✅ 依赖上成立；**但代价不在依赖，在读路径**（见第 4 条） |
| AD-A21 coachmark 蒙层 | 纯 Flutter 绘制；AD-A21.5 已排除超大 `box-shadow` 叠加做法 | ✅ |
| AD-A18 年龄换算 + 文案池 | 纯 Dart + 既有 l10n | ✅ |
| AD-A19 出图 | 复用既有 `CardRenderPipeline` / `CardCanvas` / `qr_flutter`（已在 pubspec） | ✅ |

**总判：不引新依赖、不引新中间件的护栏在本批次是可守住的。** 这条我没有异议。

### 11.2 ❌ 但 AD-A22 的"全部运行时开销 = 四支 Flyway 迁移与两处查询"低估了

按本次核实，至少还要加上：

1. **详情接口响应体增加 `imageSizes`**（第 5.4 条）—— 契约变更，App 侧模型同步；
2. **年龄卡渠道的记账表**（第 8.4 条）—— 第五支迁移 + 一条写路径；
3. **里程碑接口下发"已完成未庆祝"计数**（AD-A2.2 自己要求的"由里程碑相关接口下发"）—— 也是契约变更，`MilestoneListResponseContractTest` 会挡；
4. **评论热度序的全量聚合读路径**（第 4.2 条）—— 不是"一处查询"那么轻。

建议把 AD-A22 的这句量化改成定性表述（"不新增中间件/依赖/定时任务/异步链路/外部服务调用"），把具体清单交给 §5 与工作拆分，免得一个不准的数字反过来削弱整条护栏的可信度。

---

## 12. 其余零散核实（无问题，供交叉参考）

| 断言 | 结果 |
|---|---|
| AD-A2.6「既有即时庆祝路径与 500/800/1200ms 三次短轮询全部保留」 | ✅ `health_list_page.dart:680` → `for (final ms in const [500, 800, 1200])`，数字逐字对上 |
| AD-A2.3「多条同解锁只弹最高一条」是 1.1.2 既有规则 | ✅ `highestLevelMilestone()`（`health_milestones.dart`）+ `health_list_page.dart:687` 注释"Story 5.2 · AC3" |
| AD-A9「评论作者 id 与帖子作者 id 详情页响应里都已有」 | ✅ `ContentDetailResponse.authorId:22`、`CommentResponse.authorId:22`，且 `CommentSection` 已同时拿到 `postAuthorId: detail.authorId` 与各评论的 authorId → **客户端可比，零接口变更成立** |
| AD-A10.4「既有输入框已有 `replyTarget` 与「@昵称 + ✕」」 | ✅ `comment_composer.dart:89/121/127/132` 全部对上，"四项增量"的定性准确 |
| AD-A13.1「互动栏内嵌正文下方、评论输入框是另一条固定底栏」 | ✅ `content_detail_page.dart:165` 互动栏在滚动内容里，`:190` `CommentComposer` 在 Column 末尾（固定底部），确未合并 |
| AD-A13.4「顶栏『···』是举报入口，分享刻意不上顶栏」 | ✅ `_interactionBar` 注释：":2026-08-14 产品决定：分享让位，顶栏保持现状。照 SH1 实现就是把合规入口做掉" —— 与 delta 引用的决策日期一致 |
| AD-A13.6「双击图片点赞已取消（2026-08-31）」 | ✅ 全库 `onDoubleTap` **零命中** |
| AD-A18.3「生日是建档必填完整日期，无缺失兜底分支」 | ✅ `pet_profile_create_page.dart:65` 表单可提交条件含 `_birthday != null`，`:93` 提交前再判一次 |
| AD-A14.1 灯箱现为详情页私有实现 | ✅ `class _Lightbox`（私有）在 `content_detail_page.dart:504` |
| 🔵 AD-A13.5「图标可见尺寸 18→19px、间距 16→22px」 | ⚠️ **起点数与代码不符**：现状点赞图标 `size: 20`（`like_button.dart:141`）、评论图标 `size: 20`（`content_detail_page.dart:283`），间距 `AppSpacing.lg = 16`。"18→19"的 18 多半抄自 UI 稿而非代码。间距 16 ✅ 对。不影响决策，但"19px"这个目标值若是相对 18 推的，需要产品/设计复核 |

---

## 13. 需要 delta 作者动手的清单（按优先级）

| # | 严重度 | 要改什么 | 对应章节 |
|---|---|---|---|
| 1 | 🔴 | §1 形态判断 1 + mermaid 图 + AD-A12 + AD-A22：承认 **FR-113 需要详情接口下发 `imageSizes`**，FR-113 是双侧 story | 第 5.4 条 |
| 2 | 🔴 | AD-A4.3 升格为独立 Rule：**禁止用后缀跨系列拼 code**，修 `OTHER` 类宠物 VACCINE→G-M3 / DEWORM→G-M4 的错误点亮与错误跳转 | 第 2.2 条 |
| 3 | 🟠 | AD-A4 Prevents 与 AD-A6.3 重写：两个"禁打卡集合"本来就等长；"后端必拒的按钮"现网已不存在；真实 bug 是 M5/M9/G-M1 **无去向** | 第 2.3 条 |
| 4 | 🟠 | AD-A8.5 删除或改写"覆盖排序索引"，改为交代全量聚合代价，并二选一定死（量级上界 / 只对前 N 条热度排序） | 第 4.2 条 |
| 5 | 🟠 | AD-A20 补渠道记账载体与**去重键**（年龄卡无 card_id、无档案唯一性可依）；§5 迁移表补第 5 支 | 第 8.4 条 |
| 6 | 🟠 | AD-A17 补两条遗漏引用点：**`/profile/` 前缀门控**（安全红线，架构层写死）与**埋点屏名/四个测试基线** | 第 7.3 条 |
| 7 | 🟡 | AD-A7.2 把"全库无冗余计数列"收窄为"点赞/评论侧无冗余计数列" | 第 3.2 条 |
| 8 | 🟡 | §5 补 `pet_milestones.trigger_type` 存量行的立场（回填 or 以 code 为准） | 第 10 条 |
| 9 | 🔵 | AD-A15.1 明确"单击关闭保留"（bug 20260701-192 的既有修复） | 第 6.4 条 |
| 10 | 🔵 | §4 补引导标记表的注销口径（D1/D2）；AD-A20.3 补"全局 monthly_cap 默认也是 0" | 第 9、8.5 条 |
| 11 | 🔵 | AD-A13.5 复核图标尺寸起点（现状 20px 非 18px） | 第 12 条 |

---

## 附：本次核实触及的代码位置索引

**后端**
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V27__init_milestones.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V10__init_content_likes.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V76__init_health_records.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V82__alter_users_account_type.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V86__add_column_comments.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V97__union_notification_types_two_lines.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V20260818_1344__add_content_post_image_sizes.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V20260825_0235__create_share_reward_quotas.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V20260825_0236__pawcoin_config_add_share_reward.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V20260825_0247__pawcoin_config_add_id_card_share.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V20260825_0248__create_id_card_share_rewards.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V20260831_1254__init_content_post_views.sql`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/profile/domain/HealthMilestones.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/profile/domain/MilestoneCatalog.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/profile/domain/MilestoneCompletion.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/profile/service/MilestoneCompletionService.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/profile/service/MilestoneAutoCompleteListener.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/content/repository/CommentRepository.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/content/dto/ContentDetailResponse.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/content/dto/FeedItemResponse.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/content/dto/CommentResponse.java`
- `/Users/hexsfile/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/auth/domain/User.java`

**前端**
- `/Users/hexsfile/work/petgo-platform/petgo_app/pubspec.yaml`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/core/router/app_router.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/core/router/deep_link_routes.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/core/storage/prefs.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/shared/card_render/card_canvas.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/shared/widgets/case_image_viewer.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/content/domain/feed_image_layout.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/content/domain/content_detail.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/content/presentation/content_detail_page.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/content/presentation/comment_composer.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/content/presentation/like_button.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/content/presentation/share_card/share_card_template.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/content/presentation/share_card/share_card_preview_page.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/profile/domain/health_milestones.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/profile/presentation/milestone_list_page.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/profile/presentation/health_list_page.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/profile/presentation/growth_archive_page.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/lib/features/profile/presentation/pet_profile_create_page.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/test/profile/visitor_route_gating_test.dart`
- `/Users/hexsfile/work/petgo-platform/petgo_app/test/analytics/v112_events_test.dart`
