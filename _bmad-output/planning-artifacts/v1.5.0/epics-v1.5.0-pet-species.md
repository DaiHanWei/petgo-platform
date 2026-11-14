---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
status: complete
updated: '2026-11-14'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.5.0/宠物物种扩展/1-5-0宠物物种扩展prd.md
  - _bmad-output/planning-artifacts/v1.5.0/architecture-v1.5.0-pet-species-delta.md
  - _bmad-output/planning-artifacts/v1.5.0/宠物物种扩展/ui-1-5-0-pet-species-integrated-v1.html（拆页-App/ 26 屏）
  - _bmad-output/planning-artifacts/v1.5.0/宠物物种扩展/ui-admin-1-5-0-pet-species-integrated-v1.html（拆页-后台/ 6 屏）
  - _bmad-output/planning-artifacts/v1.5.0/decision-log.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
version: v1.5.0
theme: pet-species
---

# petgo-platform V1.5.0 pet-species（宠物物种扩展）- Epic Breakdown

## Overview

V1.5.0「宠物物种扩展」PRD 拆分（FR-205 ~ FR-208、FR-224、AB-42）。会话 ④，分支 `feat/1.5.0-pet-species`（从 `dev_1.5.0` 地基之后切出，全部 story 同一分支、云端单会话串行）。**本线最后合并**（送礼优化、首日与新手任务、健康记录提醒之后），合并后对齐由 Story 4.2 在对齐分支上做。

> 🔴 **口径优先级**：`decision-log.md`（2026-11-13 宠物物种扩展各条）> PRD（变更记录日期最晚的一条为准）> `architecture-v1.5.0-pet-species-delta.md`（D-1 ~ D-20、G-1 ~ G-6）> UI 稿（定稿，拆页）。跨 story 契约以 `CROSS-STORY-DECISIONS.md`「2026-11-14 V1.5.0 六会话共用地基」为准。

## Requirements Inventory

### Functional Requirements

**FR-205 类型与品种清单**
- **FR-205.1** 类型 15 项（猫 / 狗 / 兔 / 仓鼠 / 鸟 / 鸡 / 鱼 / 蜥蜴 / 龟 / 蜜袋鼯 / 蛇 / 鸭 / 青蛙 / 豚鼠 / 其他），顺序固定、「其他」最后；emoji 与印尼语 / 英语名照 PRD 3.1。
- **FR-205.2** 细分类型存 `pet_species`；大类 `pet_type` 由服务端推出（猫 → CAT、狗 → DOG、其余 → OTHER），App 不传大类。
- **FR-205.3** 各类型品种清单照附录 A（含 Campuran / mix），显示全文、「/」两边都是搜索别名。
- **FR-205.4** 「其他」按 12 个物种分组（附录 B），每组末项「<物种> lainnya」；选中则细分类型 = 该物种、品种 = 选项文字（「Musang lainnya」→「Musang」）；手填对不上 → `OTHER`；灵长类不提供选项。
- **FR-205.5** 猫 22 → 39、狗 35 → 43；旧值 Thai Bangkaew / Weimaraner 及一切不在新清单的存量品种按手填值显示，不清洗。
- **FR-205.6** 业务逻辑仍按大类（里程碑、新手任务、身份证物种码、性格测试、首页推荐、内容物种、运营人群、自动评论、礼物适用物种等）；细分类型只用于选择器、显示、AI 提示词。
- **FR-205.7** 所有显示类型名 / emoji 的地方读细分类型，收成一个函数；「其他」组物种显示「Lainnya / 其他」。

**FR-206 选择器搜索**
- **FR-206.1** 建档、编辑档案、身份证建卡统一同一个底部抽屉；身份证原下拉组件删掉，改为叠在「Ubah info」上弹抽屉。
- **FR-206.2** 类型：顶部搜索框；无输入 3 列 × 5 行宫格；有输入切列表；不提供手填；无结果空态引导选「Lainnya」。
- **FR-206.3** 品种：搜索框常驻；无输入按清单顺序；底部固定「Tulis sendiri」/「Pakai "xxx"」（≤60 字），取代手填弹窗；已选打勾；已选手填值时预填搜索框。
- **FR-206.4** 匹配：不分大小写、忽略变音符号、包含即命中；「/」分段单独匹配；「其他」里搜物种名命中整组；三处共用一个匹配函数。

**FR-207 类型可改，90 天一次**
- **FR-207.1** 创建不算修改；第一次修改不受限；之后 90 天内不能再改（WIB 日期，第 91 天可改）；服务端校验，App 置灰只是提示；拒绝时返回下次可改日期。
- **FR-207.2** 编辑页类型可点；选不同类型弹二次确认（清空品种、90 天提示）；保存成功才算修改；未保存离开不算；选相同类型不弹不算。
- **FR-207.3** 冷却期内类型框灰底 + 锁 +「Bisa diubah lagi <日期>」；建档页红字改「(bisa diubah 90 hari sekali)」。
- **FR-207.4** 「其他」组内换物种只算改品种：不校验冷却、不计冷却，只记修改记录。
- **FR-207.5** 大类改变（用户改 / 运营应用户要求代改）：里程碑 / 新手任务换成新大类清单，已完成的、徽章、已发奖励保留不收回，新清单能按事实判断的自动补记、不重复发奖励；健康记录与已生成提醒不取消；身份证 / 护照 / 登机牌已生成的不变；性格测试按新大类；推荐 / 内容 / 人群 / 礼物按新大类即时生效。迁移不换清单。
- **FR-207.6** AI 分诊物种传细分类型中文名（兔 / 仓鼠 / 麝香猫……），仍是 `OTHER` 的传「其他伴侣动物」，猫狗不变。

**FR-208 存量迁移**
- **FR-208.1** 按 Albar 逐只映射 CSV 迁移 `OTHER` 宠物（约 1,734 只 + 51 只猫），只迁 `confidence = HIGH`；品种已有不覆盖；51 只猫大类变 CAT、清单不换。
- **FR-208.2** 写修改记录（来源 = 迁移），不占次数、不发通知；新版 App 上线后执行；映射表延期不阻塞上线。

**FR-224 上线推送**
- **FR-224.1** 4 组每人一条（A 仍是 OTHER / B 被迁移过 / C 猫狗品种为空或手填 / D 无宠物），A → B → C 优先；A / B / C 研发导出互不重叠的用户 ID，D 用后台「没有宠物」条件。
- **FR-224.2** 跳转页 `PET_SPECIES`：1 只 → 编辑页；多只 → 选宠物抽屉（「其他」排最前）；无宠物 → 建档页；旧版 App 点开只打开 App。
- **FR-224.3** 后台跳转下拉加「宠物类型」（地基已加），说明「5 个固定页」改为实际页数。

**AB-42 后台用户详情与代改**
- **AB-42.1** 宠物卡：头像按细分类型 emoji；类型徽标中文名（「其他·麝香猫」）+「大类」；「下次可改」行（日期 + 用户上次修改日期 / 现在可改）。
- **AB-42.2** 「代改类型」：沿用 `confirm-modal`；新类型下拉 27 项分组；原因必填；不占用户次数、不重置冷却；品种清空；写审计与修改记录；大类改变时换里程碑清单；无权限不显示按钮。
- **AB-42.3** 「类型修改记录」展开：时间（WIB）/ 旧 → 新 / 来源 / 操作人 / 原因，倒序；空态「暂无修改记录」。

### NonFunctional Requirements

- **NFR-1 兼容**：旧版 App 不传 `species` 时建档回填、编辑保留、建卡回填；返回体只追加字段，`petType` 语义不变。
- **NFR-2 一致性**：`pet_species` 与 `pet_type` 由 DB CHECK 绑死；大类只由 `PetSpecies.group()` 推出；里程碑 code 只按 `milestone_track` 算。
- **NFR-3 并发**：改类型对宠物行加悲观锁，两台设备同时改不能绕过冷却。
- **NFR-4 隐私**：代改原因不进日志与审计 summary；埋点 `query` 截断 60 字。
- **NFR-5 合并安全**：只追加、不改别人逻辑；合并后对齐单独成 story。

### Additional Requirements（架构）

- 迁移预分配 `V20261115_0400`（pet_profiles 三列）、`_0410`（pet_species_changes）、`_0420`（pet_milestones.retired_at）、`_0430`（id_cards.pet_species）；`_0440`–`_0459` 留给对齐。
- FR-208 迁移与 FR-224 导出是 `scripts/ops/pet-species/` 下的 psql 脚本，不进 Flyway；生产执行前须操作者确认。
- 不新增通知类型、不碰 `ck_notifications_type`。

### UX Design Requirements

- App 26 屏（A 建档类型 5 / B 品种 7 / C 编辑改类型 8 / D 身份证建卡 4 / E 推送落地 1 / F 显示 1）、后台 6 屏（G 用户详情 5 / H 跳转下拉 1），均已定稿拆页。还原度验收 ≥ 95%（L2，本地）。

### FR Coverage Map

| 需求 | Story |
|---|---|
| FR-205.1 / .3 / .4 / .5 | 3.1（清单）、3.2（抽屉） |
| FR-205.2 | 1.1 |
| FR-205.6 | 1.2（里程碑 track）、1.3（其余只加显示字段） |
| FR-205.7 | 1.3（后端下发 species）、3.1（统一显示函数）、4.2（合并后补扫） |
| FR-206.1 ~ .4 | 3.2、3.4 |
| FR-207.1 / .4 / .5 | 1.2 |
| FR-207.2 / .3 | 3.2（建档红字）、3.3 |
| FR-207.6 | 1.3 |
| FR-208 | 1.4 |
| FR-224.1 | 1.4（导出 SQL）、4.1（演练） |
| FR-224.2 | 3.5 |
| FR-224.3 | 2.1、4.2 |
| AB-42 | 2.1 |

## Epic List

### Epic 1: 细分类型的数据与接口（后端）
存下细分类型、推出大类，类型能改且守住 90 天冷却，大类改变时里程碑清单跟着换；所有显示接口带上细分类型；交付存量迁移与推送分组脚本。

### Epic 2: 运营在后台看到并代改宠物类型
用户详情显示细分类型、下次可改、修改记录，运营可代改；跳转说明文案同步。

### Epic 3: 用户在 App 里选到、看到、改对自家宠物的类型
15 个类型与品种清单、带搜索的统一选择抽屉、编辑页改类型与冷却、身份证建卡同一抽屉、推送「宠物类型」落地。

### Epic 4: 联调与合并后对齐
本地 L1 / L2 联调与上线演练；三条线合入 `dev_1.5.0` 之后在对齐分支上补扫显示调用点与 FR-174 匹配。

## Epic 1: 细分类型的数据与接口（后端）

### Story 1.1: 物种字段迁移与建档接口（后端）
As a 新建档案的用户, I want 建档时就能选到兔子、仓鼠等具体类型, so that 我的宠物资料从一开始就准确。
- 迁移 ① `V20261115_0400`：`pet_species` / `milestone_track` / `pet_type_changed_at` + 回填 + 三个 CHECK `[L0][L1]`
- `PetSpecies` 枚举（27 值、`group()`、`pickerType()`、`zhName()`、`parse`）+ 后端与 App 同步守卫的 Java 侧常量 `[L0]`
- `PetProfile` 追加三字段与 `applySpecies` 单一写入口 + 写入守卫测试 `[L0]`
- 建档接受 `species`；旧版只传 `petType` 回填；不一致 422 `[L0][L1]`
- `PetProfileResponse` / `PetListItemResponse` 追加 `species`、`speciesChangeableFrom` `[L0][L1]`

### Story 1.2: 物种修改冷却与里程碑清单切换（后端）
As a 选错了类型的用户, I want 在编辑页改类型（90 天一次）, so that 宠物资料能改对，而里程碑也跟着对得上。
- 迁移 ② `_0410` 修改记录表、③ `_0420` `pet_milestones.retired_at` `[L0][L1]`
- 22 处里程碑 code 计算改读 `milestoneTrack` + 守卫测试；活跃清单读路径 `[L0][L1]`
- PATCH 接 `species`：保留 / 相同 / 组内换 / 改类型 / 冷却 409（`changeableFrom`）/ 悲观锁 / 清品种 `[L0][L1]`
- `MilestoneTrackSwitcher`：退役旧行、复活或新建新行、按事实补记（不发事件不发奖励）、账号级 flag `[L0][L1]`
- 埋点 `pet_type_changed`（AFTER_COMMIT）`[L0]`

### Story 1.3: 物种显示字段下发与AI提示词（后端）
As a 浏览别人宠物、送礼、看卡的用户, I want 到处看到的都是准确的宠物类型, so that 兔子不再显示成「其他」。
- 访客 / 公开主页 / 推荐 / 追更 / 关注推荐 / 内容挂宠 / 送礼目录与展示 / 登机牌 / 订单详情返回体追加 `species`，无 N+1 `[L0][L1]`
- 迁移 ④ `_0430` `id_cards.pet_species`；建卡接 `species`、卡号物种码仍按大类；卡返回体带 `species` `[L0][L1]`
- AI 分诊传细分类型中文名 `[L0]`

### Story 1.4: 物种存量迁移脚本与推送导出SQL（运维脚本）
As a 运营, I want 一键把识别出物种的存量宠物迁好、并拿到三组推送用户清单, so that 上线推送能按组发。
- `migrate-pet-species.sql`：CSV 载入、校验、只迁 HIGH、名字核对、品种不覆盖、不动 track / 冷却、记 MIGRATION、默认 ROLLBACK `[L1]`
- `export-push-groups.sql`：A / B / C 三组互斥 + 自检 `[L1]`
- 脚本集成测试（Testcontainers 灌数据跑 psql 等价 SQL）`[L1]`；README 执行步骤与生产确认 `[L0]`

## Epic 2: 运营在后台看到并代改宠物类型

### Story 2.1: 后台用户详情物种显示与代改（后台）
As a 处理客诉的运营, I want 在用户详情看到宠物的细分类型、修改记录并能代改, so that 用户选错类型时我能帮他改。
- 宠物卡改版（emoji、中文名「其他·麝香猫」、大类、下次可改）`[L0][L1]`
- 代改弹层（27 项分组下拉、原因必填、422 弹层内报错、成功 toast 与卡片刷新）+ 权限码 + 审计 `[L0][L1]`
- 修改记录展开与空态 `[L1]`
- 跳转说明「5 个固定页」→「6 个」三语 `[L0]`

## Epic 3: 用户在 App 里选到、看到、改对自家宠物的类型

### Story 3.1: app物种清单与统一显示函数
As a 养兔子的用户, I want App 各处都显示「Kelinci」和 🐰, so that 我的宠物不再是一只「其他」。
- `pet_species.dart` 清单（15 类型 + 12 组 + 品种 + 别名 + emoji + 双语名）、`foldDiacritics`、匹配函数、手填解析 `[L0]`
- `pet_species_display.dart` 统一显示函数；旧函数保留签名转调 `[L0]`
- 模型加 `species`；§1.2-13 全部显示调用点改传 `species`（含卡面印尼语物种）`[L0][L2]`
- 同步守卫测试（后端枚举、导出 SQL 品种表、清单条数）`[L0]`

### Story 3.2: app物种类型品种选择抽屉
As a 建档的用户, I want 在一个能搜索的抽屉里选类型和品种, so that 很快找到、找不到也能自己填。
- 外壳 + 类型宫格 / 搜索列表 / 空态；品种列表 / 分组 / Tulis sendiri / Pakai / 已选手填 `[L0][L2]`
- `SpeciesField` / `BreedField` 换新抽屉，删 ListTile 版与 AlertDialog；建档页红字；建档提交 `species` `[L0][L1]`
- 埋点 `pet_type_selected` / `pet_breed_selected` / `pet_breed_search_no_result` `[L0]`

### Story 3.3: app编辑档案改物种类型与冷却期
As a 选错类型的用户, I want 在编辑页改类型并知道下次什么时候能再改, so that 改得放心。
- 类型框可改 / 冷却锁定（「Bisa diubah lagi 12 Feb」）`[L0][L2]`
- 改类型确认抽屉、清空品种、未保存恢复、选相同不弹 `[L0]`
- 保存带 `species`；409 冷却 toast + 回滚类型品种 + 锁定；网络失败 `[L0][L1]`

### Story 3.4: app身份证建卡物种选择抽屉
As a 做身份证卡的用户, I want 建卡时用同一个抽屉选类型和品种, so that 和档案页一样好用。
- 删 `IdCardSpeciesDropdown` / `IdCardBreedDropdown`，「Ubah info」上叠弹新抽屉 `[L0][L2]`
- 建卡提交 `species`；预填取档案细分类型；卡号预览仍按大类 `[L0][L1]`

### Story 3.5: app运营跳转物种类型页
As a 收到上线推送的用户, I want 点开就到能改类型的页面, so that 不用自己找。
- `OpsAppPage.petSpecies` + `OpsPetSpeciesAction` + 执行层 0 / 1 / 多只分流、「其他」排前 `[L0][L1][L2]`
- 运营弹窗同样生效 `[L0]`

## Epic 4: 联调与合并后对齐

### Story 4.1: 物种全链路联调（本地）
As a 发版负责人, I want 在本地把建档、改类型、冷却、迁移、推送导出、旧版兼容全跑一遍, so that 上线不出事。
- L1 后端全量 + 迁移脚本 stag 干跑 + 导出自检 `[L1]`
- L2 模拟器逐屏还原度 ≥ 95%、旧版 1.4.2 包兼容、推送落地 3 种分流 `[L2]`

### Story 4.2: 物种合并后显示调用点对齐（对齐分支）
As a 维护者, I want 在三条线合入 dev 之后把它们新增的类型显示与物种匹配接到细分类型上, so that 最后合并不留死角。
- 对齐分支 `feat/1.5.0-pet-species-align`（从 dev 切）合入本线、解冲突（只追加原则）`[L0]`
- 扫 ①②③ 新增显示点补 `species`；FR-174 改读 `pet_species`；后台跳转说明数字 = 实际页数；① 新增的里程碑 code 计算改读 track `[L0][L1]`
- 守卫测试与全量 L0 / L1 `[L0][L1]`

## 待拍板（均给出推荐默认，按默认实现、不阻塞；操作者如有异议在实现前回改 story）

| # | 问题 | 推荐默认 | 影响 story |
|---|---|---|---|
| Q-1 | 大类改变后，旧清单完成过、新清单没有对应项的里程碑徽章，App 是否另给展示位？（PRD AC5「已获得的猫里程碑徽章仍在」） | **数据全保留不删（退役行 + 完成记录 + 时间线 + 分享页都在），里程碑列表只显示新清单**，不另做展示位；有对应项的自动显示为已完成。后续要展示「旧徽章」另开需求 | 1.2 |
| Q-2 | 冷却期被拒时，同一次保存里的其他字段改动是否先存？ | **整次请求不生效**（服务端原子），App 保留其他输入、只把类型 / 品种恢复，用户再点「Simpan」即可保存其他字段（与 UI C7 一致） | 1.2、3.3 |
| Q-3 | 代改权限码名与是否预授 | 码名 `user.pet_type_change`（沿用 `user.*` 命名，不用稿里的 `pet.change_type`）；**不预授**，SUPER_ADMIN 天然有，需要时在角色管理勾选 | 2.1 |
| Q-4 | FR-208 迁移是否补发 `pet_type_changed`（source = migration）埋点？ | **不发**（迁移是 SQL 脚本），迁移量以 `pet_species_changes` 表统计；PRD §4 的 `source` 取值保留 migration 只为口径 | 1.4 |
| Q-5 | 兽医工作台（`vet_*_page.dart`）的宠物 emoji / 类型名是否改细分类型？（不在 UI 稿；`PetIdentityView.species` 已占用为大类） | **本版不改**，兽医端仍按大类显示；AI 分诊已传细分类型 | 1.3、3.1 |
| Q-6 | 只读（READ_ONLY）宠物能否被运营代改？ | **不能**，与 App 编辑同受 `PetWriteGuard` 限制，弹层内报现有只读错误 | 2.1 |
| Q-7 | 推送落地的选宠物抽屉是否列出只读宠物？ | **列出**（与「我的」页一致），选了进编辑页按现有只读态表现 | 3.5 |
| Q-8 | 推送分组导出只看 ACTIVE 宠物吗？ | **只看 ACTIVE**（只读宠物不能编辑，推给他没用）；全部宠物都是只读的用户落到 D 组之外、不发 | 1.4 |
| Q-9 | 「其他」里点「Pakai "xxx"」时，xxx 恰好等于某组的选项或组名，细分类型记什么？ | **记该组物种**（规范化后完全相等才算），否则 `OTHER` | 3.1、3.2 |
| Q-10 | 后台跳转说明的页数：本线写 6，合并超值礼包后应为 7 | 本线写「6」，Story 4.2 合并后改为实际页数（`OpsAppPage.values().length`） | 2.1、4.2 |
