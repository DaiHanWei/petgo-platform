# 规格：V1.3.0 场所表对齐（batch-b1 → 后台 schema）

> 状态：**已拍板，可开工**（2026-09-18）。§3 六项决策：D2 按用户意见改为「默认雅加达 + 预留多城市」，其余按推荐。
> 版本 / 主题：V1.3.0 · batch-b1（App 场所与社交）× admin（后台 UI 重构）
> 分支：从 `dev_1.3.0` 切出 `feat/1.3.0-places-align`，把 `feat/1.3.0-batch-b1-places-social` **合进这个新分支**，在上面对齐，做完合进 `dev_1.3.0`。
> 🔴 **禁止把 `dev_1.3.0` 反向合进任何功能分支**（分支只单向流入 dev）。batch-b1 分支本身保持不动。

---

## 1. 为什么要做

合并 1.3.0 四个功能分支时发现，**后台（ops）和 App（batch-b1）各建了一套场所表**：

| | 后台 ops | App batch-b1 |
|---|---|---|
| 迁移 | `V20260909_1749__init_places.sql`（一个文件建 5 表） | `V20260915_0409` / `0458` / `0656` / `0813` / `0927`（5 个文件建 4 表） |
| 实体 | `com.tailtopia.admin.places.domain.{Place, PlaceComment, PlacePhoto, PlaceReport, PlaceCheckin}` | `com.tailtopia.place.domain.{Place, PlaceComment, PlacePhoto, PlaceReport}` |

两边的表名一样、结构不一样，JPA 实体也同名。合进同一个分支后**能编译，但服务启动失败**：
1. Flyway 会重复建表。b1 用的是 `CREATE TABLE IF NOT EXISTS`，会被跳过；但它后面的 `ADD CONSTRAINT ... CHECK (type IN ...)` 引用的列在后台表里不存在，还是会报错。
2. Hibernate 里有两个叫 `Place` 的实体，启动时实体名冲突。
3. 即使这两处都绕过去了，`ddl-auto=validate` 时 b1 的实体也对不上后台的列。

`CROSS-STORY-DECISIONS.md` 的表归属表写明：五张表**由后台分支定结构，App 分支只读写数据**（架构 delta 契约 X-1 / X-3）。所以本方案**以后台的表结构为准**，改 batch-b1 去适配，后台只做必要的配合。

**时机**：1.3.0 的迁移在 staging（`petgo_stag`）和生产（`petgo`）都**一条没执行过**（2026-09-18 只读核实）。两边迁移文件都还能改，代价只在代码层面。

## 2. 目标与不做的事

- ✅ 只有一套场所表，后台和 App 读写同一批数据。服务能启动，`ddl-auto=validate` 通过。
- ✅ **App 对外接口不变**：`/api/v1/places/**` 的请求和响应字段不变，App 端（Flutter）不需要改。唯一例外见 D4。
- ✅ 后台现有功能不退化：列表、详情抽屉、录入、编辑、下架、合并、删照片、删评论、举报处理。
- ❌ 不重新设计场所功能，不动打卡、护照（`place_checkins` 由后续批次使用，本次不碰）。

## 3. 决策（2026-09-18 已拍板）

| # | 问题 | **拍板结果** | 当时的其他选项 |
|---|---|---|---|
| **D1** | **场所类型用哪套取值**。后台是 4 个：`CAFE / PET_PARK / PET_HOTEL / PET_FRIENDLY_RESTAURANT`；App 是 7 个：`CAFE / RESTAURANT / PARK / MALL / HOTEL / PET_SERVICE / OTHER` | **用 App 的 7 个**（按推荐）。后台迁移注释和 5-1 story 都写了「值域由 App 端 FR-112 定」，App 已经按 7 个做了界面和多语言 | 用后台 4 个（App 要改界面和文案，也不符合 PRD） |
| **D2** | **App 标记的场所没有城市**。后台 `city` 是必填（D-39，后台按城市筛选），App 标记表单没有城市字段，而且规定不接第三方地理服务、不能反查地址 | **默认雅加达，为多城市预留**（用户拍板）：`city` **保持必填**；App 标记时由服务端填 `Jakarta`（与后台录入表单的默认值同一写法）。取城市的逻辑收口在一个 `PlaceCityResolver` 接口里，本版实现只返回配置项 `petgo.places.default-city`（默认 `Jakarta`）；将来开多城市时只换这个实现（按坐标区域判、或 App 传入），数据库和调用方都不用动。运营仍可在后台编辑时改城市 | 城市改成可空、后台显示「未填」；App 表单加城市选择；服务端按坐标粗判 |
| **D3** | **计数怎么算**。后台表里有 5 个计数列（照片 / 评论 / 打卡 / 推荐 / 不推荐），靠后台写操作在同一事务里加减。App 写评论、传照片都不会去更新这些列，后台看到的数字会越来越偏。App 自己用 Redis 计数加数据库回算自愈（AD-9） | **删掉这 5 个计数列，后台改成实时统计**（按推荐）（子表都有索引，后台列表一页 20 行，量级 ≤500 DAU，完全够用）。后台 `recount` 那段 SQL 本来就是实时统计的写法，可以直接复用。App 的 Redis 计数不动 | 让 App 的每个写入路径也去维护这 5 列（两处记账、口径还不一样：App 的态度计数只算审核通过的，后台算全部未删的，迟早对不上） |
| **D4** | **被合并的场所在 App 里怎么显示**。后台支持把 A 合并进 B（`status=MERGED`，`merged_into_id=B`），契约写的是「App 直链据此跳到保留场所」，但 b1 还没实现 | **服务端直接返回保留场所**（按推荐）：详情接口查到 MERGED，就按 `merged_into_id` 返回 B 的详情（响应里的 `token` 是 B 的，App 以它为准）；分享页 `/place/{A}` 301 跳到 `/place/{B}`。App 不用改 | 返回 404「场所不存在」（最简单，但分享出去的旧链接全失效，也违反契约） |
| **D5** | **照片存什么**。后台存 OSS 的 `object_key`，显示时现签 URL；App 存完整的公开 URL | **统一存 `object_key`**（按推荐）。App 上传接口仍然接收 URL，由服务端解析出 key 再存（顺带堵住「客户端可以塞任意外部 URL」这个口子：不是我们桶的 URL 直接 422）；读取时拼公开地址再加去 EXIF 缩略参数，和现在的输出一致 | 后台表加 `url` 列、两种都存（两套口径，不推荐） |
| **D6** | **运营在后台录入的照片算什么审核态** | **直接可见、算首批图、能当分享预览图**（按推荐）（`moderation_status=VISIBLE`、`is_original=true`、`og_eligible=true`）。运营录入是可信来源 | 也走 App 那套异步审核（后台录入会变慢，没有必要） |

## 4. 列对照（以后台表为准）

### 4.1 `places`

| 后台列 | b1 原来叫 | 处理 |
|---|---|---|
| `id` IDENTITY | `id` BIGSERIAL | 等价，不用动 |
| `public_token` | 同 | — |
| `name` VARCHAR(80) | 同 | — |
| `place_type` VARCHAR(32) | `type` VARCHAR(24) | b1 实体改映射到 `place_type`。**按 D1 加 CHECK**（7 值） |
| `tags` JSONB | 同 | 默认值 `'[]'` 用后台的 |
| `description` TEXT | VARCHAR(200) | 用 TEXT。App 接口仍然校验 ≤200 |
| `city` VARCHAR(60) **NOT NULL** | —（没有） | **按 D2：保持必填**；b1 实体加 `city`，标记时由 `PlaceCityResolver` 填（本版恒为 `Jakarta`） |
| `address_text` | 同 | — |
| `lat` / `lng` NUMERIC(9,6) | `latitude` / `longitude` DOUBLE | b1 实体改映射到 `lat` / `lng`，字段类型改 `BigDecimal`。Java 字段名可以保持 `latitude`，这样 JPQL 不用改（见 §6.1） |
| `marked_by_user_id` | `created_by` | b1 实体改映射 |
| `status` ACTIVE / DELISTED / MERGED | ACTIVE / TAKEN_DOWN | b1 的状态枚举**必须改成后台这三个值**（`TAKEN_DOWN` → `DELISTED`）。否则 b1 读到 MERGED 的行时枚举解析会直接报错 |
| `merged_into_id` | — | b1 实体加上（D4 要用） |
| 5 个计数列 + `ck_places_counts` | — | **按 D3 删掉** |
| `created_at` / `updated_at` / `deleted_at` | 前两个同名；b1 没有 `deleted_at` | b1 实体加 `deleted_at`，所有「对用户可见」的查询都补上 `deleted_at IS NULL` |

### 4.2 `place_photos`

| 后台列 | b1 原来 | 处理 |
|---|---|---|
| `object_key` | `url` | 按 D5：b1 改存 key |
| `uploader_user_id` | `uploader_id` | b1 改映射 |
| `created_at` / `deleted_at` | 同 | — |
| — | `moderation_status` VARCHAR(24) DEFAULT 'VISIBLE' | **加列** |
| — | `sort_order` INT DEFAULT 0 | **加列** |
| — | `is_original` BOOLEAN DEFAULT false | **加列** |
| — | `og_eligible` BOOLEAN DEFAULT false | **加列** |
| — | `updated_at` | **加列** |

### 4.3 `place_comments`

| 后台列 | b1 原来 | 处理 |
|---|---|---|
| `author_user_id` | `author_id` | b1 改映射 |
| `body` VARCHAR(500) | VARCHAR(200) | 用 500，App 接口仍然校验 ≤200 |
| `attitude` **NOT NULL**，CHECK 两值 | 可空 | **`DROP NOT NULL`，CHECK 重建为允许 NULL**。App 的态度是可选的；后台实体同步改成可空 |
| — | `moderation_status` / `content_version` / `updated_at` | **加列** |

### 4.4 `place_reports`

| 后台列 | b1 原来 | 处理 |
|---|---|---|
| `reporter_user_id` | `reporter_id` | b1 改映射 |
| `reason_type` VARCHAR(24)，CHECK 7 值 | VARCHAR(16)，复用全站 `ReportReason`（5 值） | 5 值都在后台的 7 值里，没问题 |
| `status` PENDING / DISMISSED / ACTIONED | PENDING | b1 的状态枚举**要包含全部三个值**，否则读到后台已处理的举报会报错 |
| `handled_by` / `handled_at` | — | b1 不映射（只读写 PENDING） |
| 唯一约束 `uq_place_reports_reporter_place` | 同名的唯一索引 | 用后台的。b1 如果按约束名识别「重复举报」，名字是同一个，不用改 |

## 5. 迁移怎么改

- **删除** b1 的 5 个建表迁移：`V20260915_0409__init_places.sql`、`V20260915_0458__add_places_geo_index.sql`、`V20260915_0656__init_place_reports.sql`、`V20260915_0813__init_place_comments.sql`、`V20260915_0927__init_place_photos.sql`。它们从来没有在任何环境执行过，可以直接删；flyway-guard 只拦 main 上已有的文件，不受影响。
- **新增一个**时间戳迁移 `V<创建时刻>__align_places_for_app.sql`，全部是对后台表的 `ALTER`：
  - `places`：（`city` 不动，保持必填，见 D2）删 5 个计数列和 `ck_places_counts`（D3）；加 `ck_places_type` 7 值（D1）；按 b1 的查询补索引：`(created_at DESC, id DESC) WHERE status='ACTIVE' AND deleted_at IS NULL`，以及 `lat`、`lng` 两条部分索引。
  - `place_photos`：加 §4.2 的 5 列；加索引 `(place_id, sort_order, id) WHERE deleted_at IS NULL` 和 `(uploader_user_id)`。
  - `place_comments`：`attitude DROP NOT NULL` + 重建 CHECK；加 §4.3 的 3 列；加索引 `(place_id, created_at DESC, id DESC) WHERE deleted_at IS NULL`、`(place_id, attitude) WHERE deleted_at IS NULL`、`(author_user_id)`。
- **不改**后台的 `V20260909_1749`。它不在 main 上，理论上还能改，但 ops 的复审和云端会话都在 scratch 库上跑过它，改原文件会让这些库的校验和对不上。用 ALTER 对所有人都安全。
- b1 其余迁移（@提及、推荐索引、通知类型约束重建）不受影响，保留。

## 6. 代码改动

### 6.1 App 侧（`com.tailtopia.place`，约 43 个文件，改动集中在实体、仓库和少数服务）

1. **4 个实体改列映射**（按 §4）。Java 字段名尽量不改，只改 `@Column(name=…)`，这样 b1 那 9 处 JPQL 基本不用动。
   - 坐标：字段类型改 `BigDecimal`，同时提供返回 `double` 的 getter 给距离计算用；仓库里 `between :minLat and :maxLat` 这类参数改传 `BigDecimal`。
   - 枚举：`PlaceStatus` 改为 `ACTIVE / DELISTED / MERGED`；`PlaceReport` 的状态改为三值。
2. **可见性过滤**：所有面向用户的场所查询补 `deleted_at IS NULL`（列表、附近、详情、分享页、推荐位、评论和照片的场所存在性检查）。
3. **照片改存 object_key（D5）**：上传接口收到 URL → 用 `AliyunOssClient` 校验是不是公开桶的地址并解析出 key（不是就 422）→ 存 key；读取时 `publicUrl(key)` 再加 `exifStrippedThumbUrl`，**保证输出和现在逐字一致**（先补一个快照测试钉住现在的输出）。
4. **MERGED 跳转（D4）**：详情接口、分享页遇到 MERGED 就取 `merged_into_id` 指向的场所（只跳一层；目标不是 ACTIVE 时按不存在处理）。
5. **合并事件**：监听后台的 `PlaceMergedEvent`（AFTER_COMMIT），删掉两个场所的 Redis 态度计数键，让保留场所立刻按数据库重算。不删的话最多偏差 10 分钟，这一步很便宜，建议做。
6. **标记场所**：`city` 取 `PlaceCityResolver.resolve(lat, lng)`（D2）。本版唯一实现 `DefaultCityResolver` 返回配置项 `petgo.places.default-city`（`application.yml` 默认 `Jakarta`，**不进 env、不进库**）。接口签名带上坐标，是给将来按区域判城市预留的，本版不用。首批照片按现在的逻辑写 `is_original` / `og_eligible` / `moderation_status`。
   - ⚠️ 不要在 `Place` 实体、DTO 或 SQL 里写死 `"Jakarta"`，只能出现在这一个配置项里；后台录入表单的默认值也改成读这个配置项，保证两边写法一致。

### 6.2 后台侧（`com.tailtopia.admin.places`，约 27 个文件）

1. **实体名改掉，避开重名**：给 5 个实体加 `@Entity(name = "AdminPlace")` 这类名字，表名不变。后台只有 1 处 JPQL 引用实体名，跟着改。（反过来改 App 侧要改 9 处 JPQL，所以改后台。）
2. **类型取值（D1）**：`PlaceType` 换成 App 的 7 个值；录入 / 编辑表单的下拉和 4 个语言文件的类型文案同步更新；表单加服务端值域校验（现在是「不做值域校验」，那是因为当时不知道全集）。
3. **城市（D2）**：后台 `city` 仍然必填，不用改。录入表单的默认值从写死的 `Jakarta` 改成读 `petgo.places.default-city`，和 App 标记用同一个来源。App 标记的场所在后台城市筛选里会按 `Jakarta` 出现，运营可以在编辑时改。
4. **计数改实时统计（D3）**：删掉实体里的 5 个计数字段和 `recount(...)`；列表、抽屉、顶部汇总改成子查询实时统计（直接复用 `AdminPlaceService` 里那段 recount SQL 的写法）；删照片 / 删评论不再手工加减。**口径**：照片和评论按「未删」统计，和现在后台一致（后台要看到审核中的内容），推荐 / 不推荐同样按未删统计。
5. **评论态度可空**：`PlaceComment.attitude` 改成可空；删评论、统计、展示都要处理 NULL（显示「未表态」）。
6. **照片新列（D6）**：运营录入的照片写 `VISIBLE / is_original=true / og_eligible=true / sort_order` 按上传顺序。后台照片的显示仍然现签 `object_key`。实体至少要映射 `moderation_status`，后台抽屉里给审核中和被拒的照片打标。
7. 后台的 `place_comments` / `place_photos` 实体只映射自己用得到的列，新加的列都有默认值，写入不受影响。

### 6.3 文档

- `docs/reference/db-schema-reference.md` §8 按最终结构更新。
- `CROSS-STORY-DECISIONS.md` 的表归属那一行补一句：「2026-09-18 对齐：App 需要的列由对齐迁移 ALTER 追加，计数改实时统计；App 标记的城市经 `PlaceCityResolver` 取默认城市（本版 Jakarta，预留多城市）」。
- b1 的 1-1 / 1-3 / 1-7 / 1-9 story 的 Completion Notes 注明建表迁移已被对齐迁移取代。

## 7. 执行顺序

> 为什么要单开对齐分支：对齐改的是后台那套表，batch-b1 分支上没有后台的表和代码，所以只能在「两边代码同时存在」的地方做；
> 又不能把 dev 反向合进 batch-b1，所以从 dev 切一个新分支，把 batch-b1 按正常方向（功能分支 → 集成）合进来。

1. `git switch -c feat/1.3.0-places-align origin/dev_1.3.0`。
2. 在新分支上 `git merge --no-ff origin/feat/1.3.0-batch-b1-places-social`。已知的文字冲突：`LogSanitizer`（两边都加了打码字段，取并集）、`planning-artifacts/v1.3.0/README.md`（保留 dev 一侧）。**合完这一步服务起不来是预期的**（两套场所表），合并提交里写明「对齐见后续提交」。
3. 迁移：删 5 个、加 1 个（§5）。
4. 后台侧改动（§6.2）→ 后台测试通过。
5. App 侧改动（§6.1）→ App 测试通过。
6. 文档（§6.3）。
7. 验证（§8）全部通过后，把 `feat/1.3.0-places-align` 合进 `dev_1.3.0`（`--no-ff`）。batch-b1 的全部提交随之进入 dev，功能分支本身不动。
8. 之后 batch-b1 上如果还有新修复，照常从 batch-b1 合进 dev；与本次对齐冲突的部分在合并时按对齐后的结构处理。

## 8. 验证

**这次必须跑 L1**：改的是表结构，L0 保证不了能启动。

- **L0**：`mvn -B clean package`；`bash scripts/ci/check-flyway-versions.sh origin/main`；`flutter analyze` 和 `flutter test`（App 不改，应该原样通过）；路由撞车静态扫描。
- **L1（本地 scratch 库）**：
  - 从空库跑完全部 Flyway 迁移，`ddl-auto=validate` 启动，`/actuator/health=UP`。
  - 场所相关集成测试全部通过（后台 `AdminPlace*IntegrationTest` 和 App 场所的集成测试）。
  - 端到端：App 标记场所 → 后台列表能看到（城市为 `Jakarta`、计数正确）→ 后台下架 → App 查不到；后台合并 A→B → App 打开 A 返回 B、分享页 `/place/{A}` 跳到 B；App 发带 / 不带态度的评论 → 后台抽屉能看到、计数正确；后台删评论 → App 的态度计数随之更新。
- **需要新增的测试**：
  - App 场所接口的响应快照测试（改动前先录，改动后逐字一致）；
  - 非公开桶的照片 URL 返回 422；
  - MERGED 详情返回保留场所、分享页 301；
  - 后台实时计数的口径；
  - App 标记的场所 `city` = 配置项的值（改配置项后跟着变，证明没有写死）；
  - 两个实体集合同时启动时 validate 通过（L1）。

## 9. 风险

| 风险 | 应对 |
|---|---|
| 两套实体写同一张表，各自的不变量互不知道（比如后台编辑时覆盖了 App 需要的列） | 两边实体只映射自己要写的列；后台编辑 `places` 时不碰审核相关列；集成测试覆盖「App 写 → 后台改 → App 读」 |
| 坐标从 double 改成 NUMERIC(9,6)，精度约 0.11 米 | 对场所足够；距离排序的测试要用真实坐标跑一遍 |
| 照片从 URL 改成 key，App 看到的地址变了 | 先录响应快照，改完逐字对比 |
| 云端会话在 scratch 库上跑过 b1 的旧迁移 | 删掉的迁移只会让那些库报「已应用的迁移找不到」；scratch 库重建即可（重建前先 flush Redis DB0，见 memory） |

## 10. 工作量估计

后台约 0.5 天，App 约 1 天，L1 联调约 0.5 天，**合计 2 天左右**。能在云端跑到 L0；**L1 必须在本地做**。

## 云端执行须知（交给云端会话时一并附上）

- 提示词带版本和主题：「V1.3.0 batch-b1 场所表对齐，按 `_bmad-output/implementation-artifacts/specs/spec-v130-places-schema-alignment.md` 执行」。
- 分支：按 §7 从 `dev_1.3.0` 切 `feat/1.3.0-places-align`，只在这个分支上改；**不要把 dev 合进 batch-b1**；不 push main，不 force push。
- §3 的决策已全部拍板，以本文件为准；实现中遇到本文件没覆盖的取舍，停下来问，不要自己定。
- 新迁移用时间戳版本号，打包一律 `mvn -B clean package`。
- 云端只跑到 L0，Completion Notes 里写明「L1 待本地验收」。

## 执行记录（2026-09-18，本地）

按 §7 在 `feat/1.3.0-places-align` 上执行。原方案之外、执行中发现并一并处理的：

1. **合并 batch-b1 的冲突远多于试合并**：dev 里已有批次 A，与 b1 在评论区 / 内容详情大面积重叠（点赞 × @提及 × ops 回复目标预留）。
   合并提交 `c11d0830` 逐条说明；其中 `Comment.copyWith` 必须带上 `mentions`（否则点一下赞 @ 变纯文字）。
2. **标签值域**：App 按 `List<PlaceTag>` 读，后台原先标签「不做值域校验」—— 运营写进未知标签，App 读这个场所整页 500。
   后台录入 / 编辑改为按 App 的 `PlaceTag` 服务端校验（`admin.err.places.tagUnknown`）；后台测试数据里的编造标签 / 旧类型同步换成正式值。
3. **后台抽屉对 null 态度调 `.name()`**：App 允许不表态，后台打开这类场所抽屉会 500。已加判空；照片 / 评论非 VISIBLE 时打审核标。
4. **D4 扩到写入口**：只改详情的话，App 在合并场所的详情页里发评论 / 补图 / 举报仍用旧 token → 404。
   评论列表、发评论、补图、举报一律经 `resolveForView`，App 不用改；旧的 `findByPublicTokenAndStatus` 已删。
5. **后台删评论 / 合并**不经过 App 的 Redis 态度计数 → 提交后丢键（`PlaceAttitudeCounters#evictAfterCommit`），下次读回库重算。
6. **App 侧 `PlaceReport.status` 改存字符串**：全站 `ReportStatus` 没有后台的 `ACTIONED`，App 读到运营处理过的举报行会枚举解析失败。
7. **灯箱统一**：批次 A（FR-115 重做的 `ImageLightbox`）与 b1（从旧详情页原样抽出的 `PhotoLightbox`）各有一个公共灯箱。
   场所详情改用 `ImageLightbox`（`source=place_detail`），`PhotoLightbox` 及其测试删除。
8. **照片 URL 校验**：D5 顺带堵住「客户端可塞任意外链」—— 非本平台公开桶的地址 422，且在送审之前拒（不白花审核配额）。
