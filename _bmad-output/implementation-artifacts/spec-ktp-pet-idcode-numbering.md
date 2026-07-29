---
title: 'KTP宠物身份码+护照号按新编码规则生成'
type: 'feature'
created: '2026-07-28'
status: 'done'
context: []
baseline_commit: '96cfed107c315488dc4dd2a6031529dd4b89838e'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 身份证卡号目前是纯数字流水号（前端各卡面自行拼 `3276+生日+serial` / `A+serial`），不符合《宠物身份码护照编码规则》文档；且全系统无性别字段，新规则的日期段需要性别加码。

**Approach:** 后端建卡时按规则生成并落库两个号——**身份码** `TT+DDMMYY+SP+XXXX`（母+50/公+10/未知+00；SP：狗01/猫02/其他00；XXXX=同一登记日+同物种顺序号从0001起）与**护照号** `TT+SP+P+YY+XXXXX`（YY=签发年后两位，XXXXX=当年顺序号从00001起）。建卡表单加性别选择（默认未知）、生日改必填。**旧卡号保留不动，仅新建卡用新规则**；学生卡面取消显示编号。

## Boundaries & Constraints

**Always:**
- 号码由后端生成落库，前端只展示；旧卡（`cardNo`=null）继续走旧的前端拼号展示。
- 新卡仍照旧分配 legacy `serial_id`（号池/冻结守卫/HD 购买链路不动）。
- 卡面性别联动：公→JANTAN、母→BETINA、未知→`-`；护照面 Sex 同理（J/B/`-`）。
- 登记日/签发年按 **Asia/Jakarta (WIB)** 计。
- Flyway 新迁移号顺延（当前最高 V94→用 V95），小整数列用 INT 不用 SMALLINT。

**Ask First:** 身份码顺序号单日单物种超 9999、护照号单年超 99999 的处理若需要超出「直接 500 报错」的方案。

**Never:** 不改旧卡已发号码；不动 `SerialAllocationService` 号池逻辑；号码不作分享/深链/资源定位键（仅展示）；不引入 MQ/缓存中间件。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 建卡happy | name+生日2024-03-10+母猫，当日(WIB)该物种第2张 | `cardNo=TT600324020002`（10+50=60），`passportNo=TT02P26xxxxx`（当年顺序） | N/A |
| 公狗 | 生日03日、公、狗 | 日期段日=13（3+10），SP=01 | N/A |
| 未知性别 | 生日10日、未选性别 | 日=10（+00），卡面 Jenis Kelamin 显示 `-` | N/A |
| petType空 | 未选物种 | SP=00（按其他） | N/A |
| 生日缺失 | 请求无 birthday | 后端 400；前端表单未填生日禁用创建按钮 | ProblemDetail 400 |
| 身份码撞号 | 不同登记日同生日同性别同物种同序号 | 顺序号继续递增直到不撞（UNIQUE 兜底） | 重试≤上限后 500 |
| 旧卡展示 | 卡 `cardNo`=null | KTP面/护照面维持旧拼号展示 | N/A |
| 学生卡面 | 任意卡 | 不再显示证号 | N/A |
| 并发建卡 | 同日同物种并发 | 计数器行锁串行化，序号不重不漏 | N/A |

</frozen-after-approval>

## Code Map

**后端（petgo-backend）**
- `src/main/resources/db/migration/V95__add_id_card_numbers.sql` -- 新迁移：`id_cards` 加 `card_no`/`passport_no`/`gender` 列 + UNIQUE；两张计数器表
- `profile/domain/IdCard.java` -- 实体加 cardNo/passportNo/gender；snapshot 工厂签名扩展
- `profile/service/IdCardService.java` -- createCard 调新号生成
- `profile/service/CardNumberService.java`（新）-- 身份码/护照号生成：计数器 upsert + 撞号递增
- `profile/dto/CreateIdCardRequest.java` -- birthday 改 @NotNull，加 gender
- `profile/dto/IdCardResponse.java` -- 加 cardNo/passportNo/gender

**前端（petgo_app）**
- `lib/features/profile/domain/id_card.dart` -- IdCard/IdCardData/CreateIdCardRequest 加 cardNo/passportNo/gender
- `lib/features/profile/presentation/id_card_create_page.dart` -- 表单加性别选择（默认未知）、生日必填校验、创建请求带 gender
- `lib/features/profile/presentation/id_card/ktp_fields.dart` -- NIK 优先 `cardNo`；Jenis Kelamin 按 gender 联动
- `lib/features/profile/presentation/id_card/passport_card.dart` -- 护照号优先 `passportNo`；Sex 按 gender 联动
- `lib/features/profile/presentation/id_card/student_card.dart` -- 移除证号展示

## Tasks & Acceptance

**Execution:**
- [x] `V95__add_id_card_numbers.sql` -- id_cards 加 `card_no VARCHAR(14) UNIQUE`、`passport_no VARCHAR(12) UNIQUE`、`gender VARCHAR(8)`；建 `id_card_no_counters(reg_date DATE, species VARCHAR(2), next_seq INT, PK(reg_date,species))` 与 `passport_no_counters(issue_year INT PK, next_seq INT)` -- 旧卡三列为 NULL 即「保留旧号」
- [x] `CardNumberService.java` -- `INSERT..ON CONFLICT DO UPDATE..RETURNING next_seq` 原子取号；身份码拼装+撞号时继续取号（上限 20 次）；护照号同 WIB 年取号 -- 行锁天然串行，无需 advisory 锁
- [x] `IdCard.java` + `IdCardService.createCard` + 两个 DTO -- 贯通 gender 入参与两号落库、响应外露
- [x] 后端测试：`CardNumberServiceTest`（编码纯逻辑：+50/+10/+00、SP 映射、补零）；集成测试补建卡断言 cardNo/passportNo 格式与同日递增
- [x] `id_card.dart` -- 模型三字段 + toJson/fromJson + toIdCardData 透传
- [x] `id_card_create_page.dart` -- _EditInfoSheet 加性别三选（Jantan/Betina/未知，默认未知）；生日未填时创建按钮禁用；预览号 `TT+DDMMYY+SP+0000` 占位
- [x] `ktp_fields.dart` / `passport_card.dart` / `student_card.dart` -- 展示切换（cardNo 优先、gender 联动、学生卡去号）
- [x] 前端测试：ktp_fields 纯函数（新旧卡展示分支、性别联动）；ARB 新键（性别选项文案）en/id 双写 + gen-l10n

**Acceptance Criteria:**
- Given 新建卡（含性别生日），when 创建成功，then 列表/详情 KTP 面显示 `TT` 开头 14 位身份码、护照面显示 12 位护照号、学生卡面无证号
- Given 存量旧卡，when 查看详情，then 三种卡面展示与改动前完全一致（学生卡去号除外）
- Given 同一 WIB 日同物种连建两卡，then 身份码末四位递增（0001→0002）
- L0 全绿：`mvn -B test` 相关模块 + `flutter analyze` + `flutter test`

## Spec Change Log

## Design Notes

- **撞号成因**：身份码日期段=生日、顺序号却按登记日计——不同登记日的同生日同性别同物种宠物可能拼出同号。处理：UNIQUE 约束 + 生成时循环取下一个序号，保住「当日第N只」常态语义。
- 计数器表 upsert 单语句原子（`ON CONFLICT DO UPDATE ... RETURNING`），并发靠行锁；与 legacy serial 的 advisory 锁互不相干。
- 前端预览（未落库）没有序号，占位 `0000`；创建成功后以后端返回的 cardNo 为准。
- 性别 wire 值 `MALE/FEMALE/UNKNOWN`（UPPER_SNAKE 惯例），null 视同 UNKNOWN。

## Verification

**Commands:**
- `cd petgo-backend && mvn -B test -Dtest='*IdCard*,*CardNumber*,*Serial*'` -- expected: 全绿
- `cd petgo_app && flutter analyze && flutter test test/profile/` -- expected: 零警告全绿
- 本地 L1（可选，scratch 库）：建卡两次断言序号递增；stag 部署后模拟器实拍三卡面

**Manual checks (if no CLI):**
- 模拟器：新建卡（母猫、生日 2024-03-10）→ KTP 面 NIK 形如 `TT600324020001`；切护照面/学生卡面核对

## Suggested Review Order

**取号核心（后端）**

- 入口：身份码取号——WIB 登记日+物种计数器、撞号续取至 9999（评审后去掉固定 20 上限防恒 500）
  [`CardNumberService.java:49`](../../petgo-backend/src/main/java/com/tailtopia/profile/service/CardNumberService.java#L49)

- 护照号按 WIB 年取号，构造即唯一无需撞号循环
  [`CardNumberService.java:67`](../../petgo-backend/src/main/java/com/tailtopia/profile/service/CardNumberService.java#L67)

- 拼装纯函数（母+50/公+10/未知+0、SP 映射、补零）——单测锚点
  [`CardNumberService.java:84`](../../petgo-backend/src/main/java/com/tailtopia/profile/service/CardNumberService.java#L84)

- 单语句 `ON CONFLICT..RETURNING` 原子取号，行锁串行（advisory 锁之外的第二层其实已被全局串行覆盖）
  [`CardNumberService.java:118`](../../petgo-backend/src/main/java/com/tailtopia/profile/service/CardNumberService.java#L118)

**schema 与贯通（后端）**

- V95：三可空列（旧卡=NULL 即保留旧号）+ UNIQUE 兜底 + 两张计数器表（INT 非 SMALLINT）
  [`V95__add_id_card_numbers.sql:10`](../../petgo-backend/src/main/resources/db/migration/V95__add_id_card_numbers.sql#L10)

- createCard：legacy serial 照旧 → 两号生成 → 同事务落快照
  [`IdCardService.java:67`](../../petgo-backend/src/main/java/com/tailtopia/profile/service/IdCardService.java#L67)

- 校验统一 Bean Validation 422：生日必填且禁未来、gender 白名单 @Pattern（评审后从服务层 400 收编）
  [`CreateIdCardRequest.java:21`](../../petgo-backend/src/main/java/com/tailtopia/profile/dto/CreateIdCardRequest.java#L21)

- 快照实体三字段（仅展示，不作定位键）
  [`IdCard.java:78`](../../petgo-backend/src/main/java/com/tailtopia/profile/domain/IdCard.java#L78)

**卡面展示（前端）**

- KTP 面：cardNo 优先、旧卡走原拼号零变化；Jenis Kelamin 按 gender 联动
  [`ktp_fields.dart:136`](../../petgo_app/lib/features/profile/presentation/id_card/ktp_fields.dart#L136)

- 预览占位号纯函数（末四位 0000，创建后以后端为准）
  [`ktp_fields.dart:111`](../../petgo_app/lib/features/profile/presentation/id_card/ktp_fields.dart#L111)

- 护照面：passportNo 优先 + Sex J/B/`-` 联动，旧卡回落逐字符同旧
  [`passport_card.dart:56`](../../petgo_app/lib/features/profile/presentation/id_card/passport_card.dart#L56)

- 学生卡面：证号整体移除（字段/渲染/布局常量一并清）
  [`student_card.dart:13`](../../petgo_app/lib/features/profile/presentation/id_card/student_card.dart#L13)

**建卡表单（前端）**

- 缺生日主页面红字提示（评审补：禁用按钮必须给原因，防「死控件」）
  [`id_card_create_page.dart:141`](../../petgo_app/lib/features/profile/presentation/id_card_create_page.dart#L141)

- 性别三选 chip（默认未知）+ 生日必填标记
  [`id_card_create_page.dart:539`](../../petgo_app/lib/features/profile/presentation/id_card_create_page.dart#L539)

**外围**

- spec 示例精确断言 TT600324020002
  [`CardNumberServiceTest.java:17`](../../petgo-backend/src/test/java/com/tailtopia/profile/service/CardNumberServiceTest.java#L17)

- L1 集成用例（格式/递增/422/未来生日拒绝）——待本地 scratch 库跑
  [`IdCardEndpointIntegrationTest.java:134`](../../petgo-backend/src/test/java/com/tailtopia/profile/web/IdCardEndpointIntegrationTest.java#L134)

- 前端展示分支/联动/占位号测试与 ARB en/id 双写
  [`ktp_fields_test.dart:1`](../../petgo_app/test/profile/ktp_fields_test.dart#L1)
