---
title: "TailTopia 宠物 App V1.3.4 PRD——KTP 样式迭代"
status: handed-off
created: 2026-10-12
updated: 2026-10-12
---

# PRD：TailTopia 宠物 App V1.3.4——KTP 样式迭代

## 0. 文档说明

本 PRD 为迭代版本文档，承接主 PRD `1-3-4prd.md` §0 中列为「由其他负责人另行承载」的 **「KTP 样式更新」**。不重复 V1.0.0 已写明的产品愿景与术语。

**设计来源：** Albar《ID Card Wireframe and Card Design》（2026-10-01，飞书 wiki `Y7YJwLQ83iNhJrkGi25jeko8p54`；设计资源目录见原文）。设计图与两张流程图已下载至 `134KTP样式图片/`，下文以文件名引用。

**编号：** 2026-10-12 核两仓——文档仓主序列最大号 **FR-141**（`1-3-4关注与通知prd.md`）；工程仓 FR-142 ~ 152 全部出自 `_bmad-output/planning-artifacts/v1.3.0/` 下 shop-v2 废弃草稿，不属主序列。故本文档取 **FR-142**，按页面拆为 FR-142A ~ F。

**KTP 功能沿革（理解现状用）：**

| 版本 | 内容 |
|---|---|
| V1.1.0 FR-49 ~ 49D | 原始定义：一宠一号、三种样式在详情页切换、付费下载 HD |
| V1.1.0 Story 6-7 / 6-8（返工） | 改为**卡快照**模型：每次建卡 = 一张冻结快照 + 独立编号 + 独立 HD 解锁；建卡即绑定卡种（KTP / PASSPORT / STUDENT 三选一） |
| `spec-ktp-pet-idcode-numbering`（V95） | 身份码 / 护照号按 `2.数据算法/宠物身份码护照编码规则.md` 生成 |
| V1.2.0 FR-96 | KTP 分享奖励 |
| V1.3.2 FR-120 | 聚合页新增护照、登机牌入口（五项） |

> V1.1.0 PRD 中「一宠一号」「详情页三风格切换」「编辑仅作用于预览会话」等描述已被 Story 6-7 / 6-8 推翻，**以代码现状（§2）为准**，本文档不回改 V1.1.0 原文。

**卡面设计资源：** 新增 5 款卡面的正式素材在飞书网盘 `SBA6fLfE6ls4QcdreaFjVVlQphh`（`Driving/` = SIM、`Permit/` = 居住证、`Student/01` = Ceria、`Student/02` = Bintang、`Student/03` = Malam；每套含无字底图、Logo、爪印签章、居住证抬头 Label，以及 Font Note），已下载至 `134KTP样式图片/design-assets/`。卡面字体统一 **Rubik**（标题 Bold / ExtraBold + Italic，标签 Regular / Medium，值 Bold）。研发用底图 + 按 §3.4 字段与 §3.5 编号格式叠字渲染，**不要使用概念图上的示例号码与字段**。

**UI 稿状态（2026-10-12 拍板）：** Albar 的**页面设计图仅作参考**，用于说明交互意图与信息结构；具体页面样式按既有 UI/UX 设计流程重新绘制（HTML 稿 → 对齐代码现状 → 审阅授权后交付研发）。下文「设计参考」指这些图，**不作为视觉验收标准**。新增卡面（SIM / 居住证 / 新增学生卡配色）的设计概念图同样只定方向，正式底图在 UI 流程中产出。

| 设计参考图 | 对应 |
|---|---|
| `flow-create-card.jpg` / `flow-payment.jpg` | 建卡流程、付费流程 |
| `01-know-your-pet.png` | Know Your Pet 聚合页（FR-142A） |
| `02-card-list-default.png` / `03-card-list-empty.png` / `04-card-list-created.png` | 身份证列表页两个 Tab（FR-142B） |
| `05-create-ktp.png` / `06-create-no-internet.png` | 建卡页（FR-142C） |
| `07-form-ktp.png` ~ `11-form-pelajar.png` | 五套 Ubah info 表单（FR-142D） |
| `12-card-locked.png` / `13-card-unlocked.png` | 卡详情页，无变更（FR-142F） |
| `14-pay-from-create-no-internet.png` / `15-pay-unlock-no-internet.png` | 付费抽屉断网态（FR-142F） |
| `16-style-sim.png` ~ `20-style-pelajar-03-dark.png` | 新卡面设计概念（FR-142B） |

> **2026-10-12 已交研发。** 开放问题已清零；唯一剩余的【建议】为 §3.5「存量已解锁卡 `hd_unlocked_at` 回填口径」，由技术评审确认。UI 稿：`ui-ktp-1-3-4-integrated-v1.html`（整合稿即交付版本，不拆页）。

> ### 📝 草稿阶段标记约定
>
> 带 **【建议】** 标记的段落是设计稿未覆盖、由 PM 补的规则，尚未过审，批准后转为正文并去除标记。

---

## 1. 版本目标

1. **把「能做哪些卡」摆到台面上**——样式选择从建卡页内的三个小 Tab 提到列表页，以卡面缩略图网格展示全部 8 款，用户进来先看到能拿到什么。
2. **卡种从 3 款扩到 8 款**——新增 SIM、居住证（Kartu Izin Tinggal）及学生卡多款配色，给老用户再建一张卡的理由。
3. **每种卡只问它需要的信息**——由一套通用表单改为 5 套按卡种裁剪的表单。
4. **提高 HD 解锁的紧迫感**——「HD」标签逻辑反转：未解锁的卡挂「Buka HD」，已解锁的卡不挂标签。
5. **聚合页改为整宽列表**——为后续新增入口留位，副标题一行显示完整。

**核心指标：** KTP 建卡数（人均 / 老用户复建率）· 建卡 → HD 解锁转化率 · 各卡种建卡分布 · 「Buka HD」标签点击 → 付费转化

---

## 2. 现状（2026-10-12 核实代码 `petgo-platform`）

| 项 | 现状 | 代码位置 |
|---|---|---|
| 聚合页 | 两两并排的横向矮卡，落单一张整宽（2+2+1），共五项：KTP / 年龄 / 护照 / Tailsonality / 登机牌 | `petgo_app/lib/features/profile/presentation/pet_insights_page.dart` `insightRows()` |
| 身份证列表 | 单页列表（无 Tab），已建卡倒序；空态为居中「+ Kartu Baru」按钮；有卡时列表顶部一条紫浅底「Kartu Baru」入口行（`AppColors.mint` 实为品牌紫） | `id_card_page.dart` |
| HD 标签 | **已解锁**的卡显示「HD」徽标，未解锁无标签 | `id_card_page.dart:169` |
| 建卡页 | 顶部 **KTP / Paspor / Pelajar 三 Tab** 切换，在哪个 Tab 点 Create 就建哪种卡 | `id_card_create_page.dart:55,221` |
| 卡种枚举 | `KTP` / `PASSPORT` / `STUDENT`，后端 `@Pattern` 白名单 | `CreateIdCardRequest.java:33`；`id_cards.card_type`（V93） |
| 卡快照字段 | name / pet_type / breed / birthday / gender / avatar_url / intro / birth_city / address / occupation / marital_status / school / faculty | V91 / V95 / V96 / V100 |
| 编号 | 每张卡建卡时同时生成：**身份码** `card_no`（`TT+DDMMYY+SP+XXXX`，14 位）、**护照号** `passport_no`（`TT+SP+P+YY+XXXXX`，12 位）、**legacy 流水号** `serial_id` | `CardNumberService.java`；`IdCardService.java:81-83` |
| 卡面取号 | KTP 显示 `card_no`；护照卡显示 `passport_no`；**学生卡卡面不显示任何编号**（spec 明确「学生卡面取消证号展示」）；列表页一律显示 `card_no` | `ktp_fields.dart` / `passport_card.dart:59` / `student_card.dart:14` / `id_card_page.dart:119` |
| HD 解锁 | 粒度 = **单张卡**（`id_cards.hd_unlocked`）；价格读 `pricing_config.id_hd_download_price`（后台可配，下限 100） | V92；`IdCardHdPricingResponse.java` |
| 支付 | PawCoin / QRIS，付费抽屉含断网「Gagal memuat biaya · Coba lagi」 | `hd_paywall_sheet.dart` |

---

## 3. 功能与需求

### 3.1 FR-142A：Know Your Pet 聚合页改整宽列表

**设计参考（`01-know-your-pet.png`）：** 两两并排改为**单列整宽卡片列表**，每行一项：图标 + 标题 + 副标题。

| 顺序 | 标题 | 副标题（参考图文案） |
|---|---|---|
| 1 | Kartu Identitas | Yuk, buat KTP Kerajaan Hewan! |
| 2 | Umur Hewanmu | Berapa umur hewanmu versi manusia? |
| 3 | Jejak Anabul（参考图写作 Paspor Hewan，不采用） | Kumpulin cap dari tempatmu |
| 4 | Tailsonality | Cari tahu kepribadian hewanmu |
| 5 | Boarding Pass | Kartu dari tiap tempat yang kamu datangi |

**理由：** 为后续新增入口留位；副标题一行完整显示，不再挤在半宽小卡里折行。

**规则：**
- 顺序以参考图为准，与代码现状（KTP / 年龄 / 护照 / Tailsonality / 登机牌）一致，只改布局不改顺序。
- 现有行为全部保留：年龄卡对不适用物种置灰且不可点（`ageCardUnavailableForSpecies`）；Tailsonality 的 NEW 徽标与 coachmark 锚点保留。
- **文案不动（2026-10-12 拍板）：** 五项的标题、副标题全部沿用现有 i18n。逐项核对，参考图与现有文案仅护照入口标题不同（参考图「Paspor Hewan」/ 现有「Jejak Anabul」），**保留「Jejak Anabul」**。本条只改布局。
- 改动集中在 `insightRows()`：由 2+2+1 改为单列，后续新增入口只需往列表追加。

### 3.2 FR-142B：身份证列表页——双 Tab + 8 款样式

**设计参考（`02` / `03` / `04`）：** 页面顶部新增两个 Tab：**ID Card**（样式选择）与 **Created**（已建的卡）。

**理由：** 让用户一进来就看到自己能拿到什么卡，而不是先进建卡页再在三个小 Tab 里发现。

**Tab 命名（2026-10-12 拍板）：** 印尼语「**Pilih Kartu**」（样式选择，下文仍称 ID Card Tab）/「**Kartuku**」（已建的卡，下文仍称 Created Tab），en / zh 同步翻译。

#### ID Card Tab（样式选择）

- 标题句（提议「Pilih dan buat kartu identitas untuk hewanmu!」），下方 **2 列网格**，每格 = 卡面缩略图 + 卡种名称
- **分两组展示（2026-10-12 拍板）：**「Kartu Identitas」（KTP / Paspor / Kartu Izin Tinggal / SIM）与「Kartu Pelajar」（Bintang / Malam / Ceria / Klasik），组标题在网格上方
- **新卡 `new` 角标（2026-10-12 拍板）：** 5 款新卡（居住证 / SIM / Bintang / Malam / Ceria）右上角挂红色 `new` 角标，复用聚合页 `insightNewBadge` 样式。上线 30 天后统一下线（2026-10-12 确认；前端按版本常量控制，不做后台配置）
- **共 8 款**（按列表图）：

| # | 卡种名称 | 卡种 | 状态 | 设计概念 |
|---|---|---|---|---|
| 1 | KTP | KTP | 现有 | 不变 |
| 2 | Paspor | PASSPORT | 现有 | 不变 |
| 3 | Kartu Izin Tinggal | KITAS | 新 | 仿印尼居住证，TailTopia 紫配色（`17-style-izin-tinggal.png`） |
| 4 | SIM | SIM | 新 | 仿印尼驾照，去掉车型代码、改为字母 T（`16-style-sim.png`） |
| 5 | Pelajar Bintang | STUDENT | 新配色 | 黄色星星边框，简洁明亮（`18-style-pelajar-02.png`） |
| 6 | Pelajar Malam | STUDENT | 新配色 | 反色深色版，「暗黑模式」感（`20-style-pelajar-03-dark.png`） |
| 7 | Pelajar Ceria | STUDENT | 新配色 | 彩色几何形状背景，玩具感（`19-style-pelajar-01.png`） |
| 8 | Pelajar Klasik | STUDENT | 现有 | 现有紫色学生卡 |

  > 学生卡 4 款命名（2026-10-12 定）：参考图里前两款叫「Tipe 1 / Tipe 2」、后两款名字被截断。按卡面风格统一起名，避免「Tipe 1~4」这种看不出区别的编号：**Bintang**（星星）= 黄色星星边框；**Malam**（夜晚）= 深色反色；**Ceria**（活泼）= 彩色几何玩具感；**Klasik**（经典）= 现有紫色款。英文 / 中文版名称在 i18n 中对应翻译。

- 点任一格 → 进入建卡页（FR-142C），卡面即该样式
- 网格缩略图用**静态示例图**（设计提供），不按当前宠物实时渲染——8 张实时渲染卡面的开销与加载不值得

**学生卡多配色的数据建模（2026-10-12 拍板）：** 4 款学生卡字段完全相同（同一套 Pelajar 表单），只有底图不同。`card_type` 共 5 个值：`KTP` / `PASSPORT` / `KITAS`（居住证）/ `SIM` / `STUDENT`；学生卡另用新列 `card_variant` 区分配色：`BINTANG` / `MALAM` / `CERIA` / `KLASIK`（非学生卡为 NULL）。存量 STUDENT 卡迁移回填为 `KLASIK`，展示零变化。以后新增学生卡配色只加 `card_variant` 值，不动 `card_type`。（`KITAS` / 各配色的枚举值名为建议命名，研发可调整）

#### Created Tab（已建的卡）

- 顶部说明：「Each card is a saved snapshot with its own serial number. Old cards stay here to view and download」
- 列表项：统一的紫浅底证件图标（线性 SVG，取代现状 🪪 emoji）+ 宠物名 + 「{卡种} · No. {编号}」+「Dibuat {日期}」；倒序。**不按卡生成迷你卡面缩略图**（2026-10-12 拍板：原定迷你卡面方案取消，按标准样式展示）
- **「Buka HD」标签——逻辑反转：**
  - 现状：已解锁挂「HD」
  - 改为：**未解锁**的卡挂紫色「Buka HD」标签；**已解锁**的卡不挂标签
  - 理由：现状标签对用户没有任何催促作用；反过来，标签成了「这张还没解锁」的提示和行动号召
- 点列表项 → 卡详情页（FR-142F）
- **点「Buka HD」标签 → 直接弹付费抽屉**（2026-10-12 拍板），不进详情页；标签热区 ≥44px，与整行点击区分开。关闭抽屉 → 留在列表；付费成功 → 留在列表，该卡标签消失 + toast `idCardHdUnlockedToast`
- **加载失败（2026-10-12 拍板）：** 沿用 `idCardLoadError` 文案，**下方补一个「Coba lagi」按钮**（`commonRetry`）重新拉取列表——现状只有文字、无法重试
- **空态（`03`）：**「No ID Card Created」+ 说明 + **「Buat Kartu Baru」主按钮**（品牌紫，带文字）→ 切到 ID Card Tab
- **有卡时：** 底部同一个「Buat Kartu Baru」带文字主按钮（→ 切到 ID Card Tab），取代现状列表顶部的「Kartu Baru」入口行——统一按钮、增强建卡 CTA

**默认 Tab（2026-10-12 拍板）：** 从聚合页进入列表页，**无论有没有卡，一律停在 ID Card Tab**（先看样式）。从新卡详情页返回列表时同样停在 ID Card Tab；用户切到 Created Tab 即可看到刚建的卡。

### 3.3 FR-142C：建卡页（Buat Kartu Identitas）

**设计参考（`05` / `06`）：**
- **去掉顶部样式切换 Tab**（现状 KTP / Paspor / Pelajar 三 Tab → 无）。**样式只能在列表页选**（2026-10-12 拍板）；想换样式须返回列表页重选
- 页面 = 所选样式的卡面预览（带预览水印）+「Ubah info」+「Ganti Foto」两个次按钮 +「Buat Kartu」主按钮
- 卡面预览默认按当前宠物档案信息填充（沿用现状）
- **断网 / 建卡失败（2026-10-12 拍板：沿用现状）：** 卡面预览照常显示（本地渲染，不依赖网络），点「Buat Kartu」失败时底部 SnackBar `idCardCreateError`。**不采用**参考图 `06` 的灰色占位

**规则：**
- 点「Ubah info」→ 该卡种对应的表单（FR-142D），点「Selesai」回到建卡页，卡面即时刷新
- 点「Ganti Foto」→ 沿用现有换照片逻辑
- 点「Buat Kartu」→ 建卡（冻结快照 + 取号）→ 弹出付费抽屉（FR-142F）
- 建卡请求带上卡种与 `card_variant`（学生卡必填），后端白名单同步扩充

### 3.4 FR-142D：五套 Ubah info 表单

**设计参考（`07` ~ `11`）：** 由一套通用表单改为**每类卡一套**。部分字段已按档案预填，可直接改。

**形态（2026-10-12 拍板）：保持底部抽屉**（沿用现状 `id_card_create_page.dart:263` 的 `showModalBottomSheet`），不采用参考图的整页形态。字段多的表单（KTP 9 项）在抽屉内滚动，「Selesai」吸底。

**字段口径（2026-10-12 拍板）：表单只采集卡面上印出来的字段**，卡面没有的一律不出现。唯一例外是**编号所需字段**：身份码 / 护照号编码了物种与性别，卡面不展示这两项的卡种（Paspor、SIM 缺物种，Pelajar 缺性别）仍须让用户补填。卡面上的固定值（如 KTP 的 Kewarganegaraan「INDONESIA」、Berlaku Hingga「SEUMUR HIDUP」）不进表单。

**物种 / 品种选择（2026-10-12 拍板）：** 在输入框下方**直接展开下拉菜单**选择，**不再另弹底部抽屉**（现状 `SpeciesField` / `BreedField` 点击后各弹一个 `showModalBottomSheet`，叠在表单抽屉之上成了抽屉套抽屉）。品种列表较长时下拉菜单内滚动，末项「Lainnya」展开为输入框。
- ⚠️ `SpeciesField` / `BreedField` 是共享组件（宠物档案表单也在用），【建议】只在身份证表单里改为下拉菜单，档案表单保持现状，避免牵动档案流程

> 2026-10-12 拍板：参考图中 Paspor / SIM 缺「物种」、Pelajar 缺「性别」——这两项是编号的组成部分，**补进对应表单，由用户建卡时自己填写**。下表已按此补齐（标 ★）。

| 字段 | KTP | Paspor | Kartu Izin Tinggal | SIM | Pelajar（×4 款共用） | 快照列 |
|---|:-:|:-:|:-:|:-:|:-:|---|
| Nama Hewan（名字） | 必填 | 必填 | 必填 | 必填 | 必填 | `name` |
| Jenis Hewan（物种，下拉） | 必填 | 必填 ★ | 必填 | 必填 ★ | 必填 | `pet_type` |
| Ras（品种，下拉） | 必填 | — | 必填 | — | — | `breed` |
| Jenis Kelamin（性别：Jantan / Betina / Tidak Diketahui） | 必填 | 必填 | 必填 | 必填 | 必填 ★ | `gender` |
| Tanggal Lahir（生日） | 必填 | 必填 | 必填 | 必填 | 必填 | `birthday` |
| Kota Kelahiran（出生城市） | 选填 | 选填 | — | 选填 | — | `birth_city` |
| Alamat（地址） | 选填 | — | — | 选填 | — | `address` |
| Pekerjaan（职业） | 选填 | — | — | 选填 | — | `occupation` |
| Status Perkawinan（婚姻状态） | 选填 | — | — | — | — | `marital_status` |
| Sekolah（学校） | — | — | — | — | 选填 | `school` |
| Fakultas（院系） | — | — | — | — | 选填 | `faculty` |

**规则：**
- 全部字段复用现有快照列，**无需新增列**
- **去掉「一句话介绍」（`intro`）（2026-10-12 拍板）：** 现状建卡表单可填并存入快照，但没有任何卡面显示它。五套新表单均不含此项，新建卡不再写入 `intro`（存 NULL）。宠物档案自身的一句话介绍不受影响；`id_cards.intro` 列与存量数据保留不删，无可见变化
- 选填字段留空 → 卡面渲染趣味默认值（沿用 V96 / V100 口径）
- 设计稿中「Jenis hewan」大小写、「OPSIIONAL」等笔误以 i18n 正式文案为准

**必填校验与默认值（2026-10-12 拍板）：** 必填项 = 名字 / 物种 / 性别 / 生日（KTP、居住证另加品种为现状口径不变）。沿用现状生日的拦截方式扩展到物种、性别：
- 表单抽屉内未填的必填项下方红字提示（生日沿用 `idCardBirthdayRequired`；物种、性别新增文案，提议「Jenis hewan wajib diisi」「Jenis kelamin wajib dipilih」）
- 「Selesai」不拦截、始终可点（现状）；拦截放在建卡页：任一必填缺失 → 「Buat Kartu」置灰，按钮上方红字说明缺什么
- **性别默认值**：宠物档案有性别则预选；档案未填则三个 chip 都不选，必须用户手选。物种同理（档案有则预选，无则显示「Pilih jenis」）

**物种 / 性别为什么五套表单都必须有：** 身份码 `card_no` 由**生日 + 性别 + 物种**拼出，护照号 `passport_no` 含物种。两项都按档案预填，用户可改；卡面不展示该字段的卡种（如 Paspor 卡面无物种位）也照样采集，只用于拼号。

### 3.5 FR-142E：编号规则

#### 拍板结论（2026-10-12）

1. **学生卡按当前代码实现：** 与其他卡种一样，建卡时生成身份码 `card_no`（`TT+DDMMYY+SP+XXXX`，14 位），列表页显示该号。**不采用**设计稿学生卡概念里「无 TT 前缀、`DDMMYYXXXXXX`」的格式。
2. **已解锁学生卡的编号共享：** 用户之前**已建过学生卡并已解锁 HD**的，之后再建其他样式的卡，**且新卡填写的生日、性别、物种与该学生卡完全相同**，则**沿用该学生卡已解锁的编号**，不再重新取号；三项任一不同 → 照常取新号（2026-10-12 拍板）。
   - **新卡可以是任意款式**（2026-10-12 拍板）：8 款中任一款都适用，**包括学生卡的其他配色，以及再建一张同款学生卡**。沿用来源只看「已解锁的学生卡」，KTP 等其他卡种解锁后不作为编号来源
   - **只共用编号，不共用解锁**（2026-10-12 拍板）：沿用编号的新卡仍是**未解锁**状态，带水印、挂「Buka HD」，要 HD 须单独付费——每张卡独立解锁的现状不变

#### 号码回收核查结论

**学生卡编号不涉及号码回收。**

- `card_no` / `passport_no`：由计数器表 `id_card_no_counters` / `passport_no_counters` 单调递增发号，**没有任何回收路径**（`CardNumberService.java`）
- `serial_id`：有回收池（`pet_serial_pool`，删除宠物档案时把号放回池），**但被卡快照持有的号永不回池**——`release()` 与 `allocate()` 都显式排除 `id_cards` 里已有的号（`SerialAllocationService.java`，2026-07-27 曾因此出过建卡恒 500 事故后加的防护）
- 卡快照本身**不会被删除**：删档时未付费卡只打标隐藏（`profile_deleted_at`，V108），付费卡永远可见
- 因此规则 2 的「沿用编号」不会撞上回收号

#### 规则 2 落地时须处理的技术约束

- **共用范围（2026-10-12 拍板）：** 身份码 `card_no` 与护照号 `passport_no` **都共用**——新卡直接复制该学生卡的两个号，不再调用发号器
- **唯一约束调整（2026-10-12 拍板）：** `id_cards.card_no` 与 `id_cards.passport_no` 各有全局 `UNIQUE` 约束（V95，`uq_id_cards_card_no` / `uq_id_cards_passport_no`），共用编号会让建卡失败。两条都改为「**同一用户内可重复、跨用户不可重复**」——同一个号只能属于一个用户，保留发号出错时的兜底。实现方式（如排他约束、或校验 + 触发器）由研发定
- **`serial_id` 不共用（2026-10-12 拍板）：** legacy 流水号仍每卡新取（`uq_id_cards_serial` 不动）。
  - **新增卡种（SIM / 居住证 / Bintang / Malam / Ceria）卡面不使用 `serial_id`**：卡面上若设计了装饰性号码，一律由 `card_no` / `passport_no` 派生，保证共用编号时整张卡的号都一致
  - **现有护照卡保持原样**：其 Reg No. / NIKIM 仍由 `serial_id` 派生（`passport_card.dart:52`），不改老卡面。已知结果：共用编号的护照卡，护照号与学生卡一致，Reg No. / NIKIM 不一致——接受
  - KTP、Klasik 学生卡维持现状
- **共用条件（2026-10-12 拍板）：** 身份码编码了生日 / 性别 / 物种，护照号编码了物种。只有新卡这三项与学生卡**完全相同**才沿用，保证共用的号与新卡面信息永远一致。比对的是**两张卡的快照值**（`birthday` / `gender` / `pet_type`），不是宠物档案当前值
- **性别「未知」也是一个值：** 学生卡快照里性别为 UNKNOWN 的，新卡也须选 Tidak Diketahui 才算一致
- **多张已解锁学生卡都满足条件时（2026-10-12 拍板）：** 取**最早解锁**的那张的号——第一个解锁的号即固定编号，之后再解锁多少张学生卡都不变。注意卡快照只挂 `user_id`、不挂宠物档案，「同一只宠物」在系统里就是靠生日 / 性别 / 物种三项一致来认定的。「解锁时间」= HD 支付到账、`hd_unlocked` 翻为 true 的时刻，不是建卡时间。
  - ⚠️ **现状没有这个时间**：`id_cards` 只有 `hd_unlocked` 布尔值；`id_card_hd_purchases.purchased_at` 是**下单**时间（QRIS 下单即建行、超时重开会再建一行，见 V92 注释），不能当解锁时间
  - 须新增 `id_cards.hd_unlocked_at`，到账翻转 `hd_unlocked` 时同时写入
  - 存量已解锁卡回填【建议】：取该卡最早一条有支付记录的 `purchased_at`；V92 回填生成的老卡取其 `created_at`（V92 已把它设为原购买时间）。存量回填只影响「哪张学生卡最早解锁」的判定，误差可接受
- **V95 之前的旧学生卡不参与共用：** 2026-07-28 新编码上线前建的卡 `card_no` / `passport_no` 为空、卡面走 legacy 拼号，无号可沿用 → 新卡照常取新号
- **判定时机与提示（2026-10-12 拍板）：** 在「Buat Kartu」建卡请求时由后端判定；**建卡前后都不提示用户**「将沿用 / 已沿用编号」，前端无需预查接口。建卡页预览卡面上的编号沿用现状的预览占位口径，建卡成功后以后端返回的号为准

#### 新卡种的编号展示

设计稿给 SIM / 居住证的格式，拆开看都是**同一个身份码换了排版**，不需要新的发号器：

| 卡种 | 设计稿格式 | 与 `card_no`（`TT DDMMYY SP XXXX`）的关系 |
|---|---|---|
| SIM | `TTDDMM-YYYY-SPXXXX`（例 `TT1303-2026-020110`） | 年份展开为 4 位并加分隔符 |
| Kartu Izin Tinggal | `TT-DDMMYYSPXXXX`（例 `TT-030326020110`） | TT 后加分隔符 |
| 学生卡 | 按拍板沿用 `card_no` 原样 | — |

**展示规则（2026-10-12 拍板）：** SIM / 居住证**卡面**按上表格式化显示 `card_no`（含 HD 下载图与分享图）；**Created 列表页与付费抽屉的编号**一律显示 `card_no` 原样，8 款卡格式统一。格式化只是展示层排版，不新增存储字段。

**学生卡卡面显示编号（2026-10-12 拍板）：** 现状学生卡卡面**不显示编号**（代码注释「学生卡面取消证号展示」）。本版本起 4 款学生卡卡面都显示身份码 `card_no`（编号位标签沿用参考图「Nomor Induk Anabul」）。
- **含现有紫色款（Klasik）**：该款卡面组件 `student_card.dart` 须补编号位，存量学生卡随之显示编号
- **V95 前的旧学生卡（`card_no` 为空）卡面不显示编号（2026-10-12 拍板）：** 编号位整体隐藏（标签「Nomor Induk Anabul」一并不显示），卡面维持现状无编号的样子。Created 列表页仍按现状显示 `serial_id` 补零（`id_card_page.dart:119`），不改。不为旧卡补发身份码

### 3.6 FR-142F：卡详情页与付费——仅流程调整

**卡详情页（`12` / `13`）：无视觉变更。** 未解锁：「Bagikan」+「Buka HD」；已解锁：「✓ HD」行 +「Bagikan」+「Unduh HD」。页面预览**无论是否解锁都带水印**（代码现状：水印叠在导出区域外），只有导出的 HD 图无水印——沿用。底部免责声明「For Entertainment Only…」保留。

**按钮命名统一：** 以标签和按钮为准，**统一为「Buka HD」**。注意：卡详情页按钮的**现有文案就是「Buka unduhan HD」**（`idCardUnlockHd`），设计文档写的「Buka unduhan」源自线上现状，不是笔误——本版本是一次**文案改动**，`idCardUnlockHd` 改为「Buka HD」（en / zh 同步）。

**付费流程（`flow-payment.jpg`）：**
- 入口 1：建卡页点「Buat Kartu」→ 建卡成功 → 付费抽屉上滑
- 入口 2：卡详情页点「Buka HD」→ 付费抽屉上滑
- 入口 3：Created 列表点「Buka HD」标签 → 付费抽屉上滑（落点见 §3.2）
- 抽屉内容不变：卡号预览条「{名字} · No. {编号}」+「Pratinjau · Resolusi rendah + watermark」、「Unduh Versi HD Tanpa Watermark」、PawCoin / QRIS 二选一、「Bayar & Unduh HD」
- **从建卡页进入（入口 1）（2026-10-12 拍板）：** 关闭抽屉（不付费）→ **跳到这张新卡的详情页**（未解锁：带水印 + Bagikan / Buka HD），不再留在建卡页、也不跳列表；付费成功 → 同样跳到新卡详情页，已解锁（Bagikan / Unduh HD）。从详情页返回 → 列表页
  - 代码现状是关掉后 `context.pop()` 回列表（`id_card_create_page.dart`），须改为以新卡 id 替换当前页进入详情（建卡页出栈，避免返回时又回到已建过卡的建卡页、重复建卡）
- **从卡详情页进入（入口 2）：** 关闭抽屉 → 留在卡详情页不跳转；付费成功 → 留在卡详情页，按钮由「Buka HD」变为「Unduh HD」（参考图 `13`）
- 选 QRIS → 「Waiting for payment…」二维码页，可「Cancel Payment」（沿用现状）
- **断网（`14` / `15`）：** 价格位显示「Gagal memuat biaya · Coba lagi」，支付方式价格为「…」，「Bayar & Unduh HD」置灰（沿用现状）
- **价格：** 设计稿示例 Rp1.000 仅为示意。价格一律读后台 `pricing_config.id_hd_download_price`，**客户端不得硬编码**。**8 款卡全部同价**（2026-10-12 拍板），沿用单一 `id_hd_download_price`，不新增按卡种定价的配置

### 3.7 验收标准

- [ ] 聚合页五项单列整宽展示，顺序与 §3.1 一致；年龄卡置灰、Tailsonality NEW 徽标与 coachmark 行为不变
- [ ] 列表页有 ID Card / Created 两个 Tab；ID Card Tab 展示 8 款样式缩略图，点任一款进入对应样式的建卡页
- [ ] Created Tab：未解锁卡挂「Buka HD」，已解锁卡不挂标签；空态与有卡态均为带文字的「Buat Kartu Baru」主按钮
- [ ] 建卡页无样式切换；Ubah info 按卡种弹出 §3.4 对应表单，字段必填 / 选填与表一致
- [ ] SIM、居住证、学生卡新配色可正常建卡、预览带水印、付费解锁、下载 HD、分享
- [ ] 现存 KTP / PASSPORT / STUDENT 卡在 Created Tab 正常显示；卡面除「V95 后建的学生卡补显编号」外零变化，V95 前旧学生卡卡面仍无编号
- [ ] SIM / 居住证卡面编号按 §3.5 格式化，Created 列表与付费抽屉显示原样 `card_no`
- [ ] 新建卡不再采集、不再写入一句话介绍（`intro`）
- [ ] 沿用编号的新卡为未解锁状态，需单独付费解锁 HD
- [ ] 已解锁学生卡的用户新建任意款式的卡（含其他学生卡配色与同款学生卡），且新卡生日 / 性别 / 物种与该学生卡完全相同 → 新卡身份码与护照号均与该学生卡相同；三项任一不同、或学生卡未解锁、或学生卡为 V95 前旧卡 → 照常取新号
- [ ] Ubah info 为底部抽屉，长表单在抽屉内滚动
- [ ] 付费抽屉：从建卡页进入时，关闭或付费成功都跳到新卡详情页，返回回到列表页（不回建卡页）；从卡详情页进入时留在详情页，付费成功后按钮变「Unduh HD」
- [ ] 从聚合页进入列表页，无论有没有卡都停在 ID Card Tab
- [ ] 断网：建卡页卡面照常显示、建卡失败弹 SnackBar；付费抽屉价格加载失败态按 UI 稿呈现
- [ ] Kartuku Tab 加载失败时有「Coba lagi」按钮，点击重新加载
- [ ] 物种 / 性别 / 生日任一缺失：表单内红字提示，建卡页「Buat Kartu」置灰并说明原因；档案无性别时性别不预选
- [ ] 价格从后台读取，改价后新发起的支付按新价

### 3.8 影响面

| 端 | 改动 |
|---|---|
| App | `pet_insights_page.dart` 布局；`id_card_page.dart` 双 Tab + 网格 + 标签反转 + CTA；`id_card_create_page.dart` 去 Tab + 分卡种表单；新增 SIM / 居住证 / 3 款学生卡卡面组件与底图；付费抽屉关闭后跳转 |
| 后端 | `card_type` 白名单扩充 + 新增 `card_variant` 列（存量学生卡回填 `KLASIK`）；`IdCardService.createCard` 增加「已解锁学生卡 + 生日 / 性别 / 物种一致 → 沿用编号」分支；`uq_id_cards_card_no` / `uq_id_cards_passport_no` 约束调整；新增 `id_cards.hd_unlocked_at`（解锁时写入 + 存量回填）；五套表单的物种 / 性别必填校验 |
| i18n | 8 个卡种名称、Tab 名、Created 说明、Buka HD、空态文案、表单新标签（en / id / zh） |
| 分享 / 分享奖励 | 新卡种复用现有 KTP 分享与 FR-96 奖励。**奖励口径不变（2026-10-12 拍板）：** 8 款卡合计每个宠物档案只奖励一次——代码现状已是如此（`id_card_share_rewards` 去重键为 `UNIQUE(pet_profile_id)`，与卡、卡种无关），**无需改动**，研发勿按卡种拆分 |
| 后台 | 用户购买记录（`AdminUserPurchasesQuery`）展示卡种时需识别新值 |

---

## 4. 数据埋点需求

| 事件 | 触发 | 属性 |
|---|---|---|
| `id_card_tab_view` | 切换 / 进入列表页某 Tab | `tab`（style / created）、`has_cards` |
| `id_card_style_click` | ID Card Tab 点样式格 | `card_type`、`card_variant` |
| `id_card_form_open` | 点 Ubah info | `card_type` |
| `id_card_create` | 建卡成功 | `card_type`、`card_variant`、`number_reused`（是否沿用学生卡编号） |
| `id_card_buka_hd_click` | 点 Created 列表的「Buka HD」标签或详情页按钮 | `card_type`、`entry`（list_badge / detail） |
| `id_card_paywall_close` | 付费抽屉未付款关闭 | `card_type`、`entry`（create / detail） |

已有的建卡、付费成功、分享事件沿用，补 `card_type` / `card_variant` 属性。具体落入 `3.数据埋点/` 埋点方案时与现有命名对齐。

---

## 5. 开放问题

| # | 问题 | 结论 / 【建议】 |
|---|---|---|
| ~~Q1~~ | ~~编号共享是否包含护照号~~ | **已拍板（10-12）：护照号也共用** |
| ~~Q2~~ | ~~表单缺物种 / 性别时拼号用什么值~~ | **已拍板（10-12）：补进表单，用户建卡时自己填** |
| ~~Q3~~ | ~~沿用旧号后号码与卡面信息不一致~~ | **已拍板（10-12）：生日、性别、物种三项完全相同才沿用旧号** |
| ~~Q4~~ | ~~学生卡卡面是否显示编号~~ | **已拍板（10-12）：显示** |
| ~~Q5~~ | ~~第 7、8 款学生卡名称~~ | **已定（10-12）：4 款统一命名 Bintang / Malam / Ceria / Klasik** |
| ~~Q6~~ | ~~新卡种价格~~ | **已拍板（10-12）：8 款全部同价** |
| ~~Q7~~ | ~~一句话介绍（`intro`）是否保留~~ | **已拍板（10-12）：不保留**，见 §3.4 |
| ~~Q8~~ | ~~「其他样式」包括哪些~~ | **已拍板（10-12）：任意款式都沿用**，含学生卡其他配色及同款学生卡 |
| ~~Q9~~ | ~~沿用编号的新卡 HD 是否随之解锁~~ | **已拍板（10-12）：不解锁**，需单独付费 |

---

## 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-12 | 初稿：整合 Albar 设计稿；拍板 8 款卡种（按列表图）、学生卡编号按代码现状 + 已解锁学生卡编号共享、按钮统一「Buka HD」、样式只在列表页选；完成号码回收核查 |
| 2026-10-12 | 页面设计图降为参考，样式走 UI/UX 流程；编号共用扩至护照号；Paspor / SIM 表单补物种、Pelajar 表单补性别（用户填写）；Q1、Q2 关闭 |
| 2026-10-12 | 编号共用加条件：新卡生日 / 性别 / 物种与学生卡完全相同才沿用；补 V95 前旧卡不参与、UNKNOWN 性别比对口径；Q3 关闭 |
| 2026-10-12 | 学生卡卡面显示编号（含现有紫色款）；4 款学生卡命名 Bintang / Malam / Ceria / Klasik；8 款同价；Q4 ~ Q6 关闭 |
| 2026-10-12 | 建卡表单去掉一句话介绍（`intro`）；Q7 关闭，开放问题清零 |
| 2026-10-12 | 逐条过审 10 条【建议】：聚合页文案不动（保留 Jejak Anabul）；列表页一律默认 ID Card Tab；SIM / 居住证卡面格式化编号、列表页原样；分享奖励口径不变；学生卡用 `card_variant` 区分配色；唯一约束改为同用户内可重复；多张学生卡取最早解锁（新增 `hd_unlocked_at`）；沿用编号不提示用户；新卡种不用 `serial_id`；V95 前旧学生卡卡面不显示编号 |
| 2026-10-12 | 终检：补 KITAS / SIM 卡种值；付费抽屉按入口区分落点（详情页进入留在详情页）；验收标准补默认 Tab、学生卡编号、SIM / 居住证格式化、去 intro；残留「按设计稿」改为参考图 / UI 稿；新增 Q8、Q9 |
| 2026-10-12 | Q8：任意款式（含学生卡各配色、同款）都可沿用编号；Q9：沿用编号不连带解锁 HD；开放问题清零 |
| 2026-10-12 | UI 清单稿核对：Ubah info 保持底部抽屉；建卡后关闭 / 完成付费抽屉 → 跳新卡详情页（原定落 Created Tab 作废） |
| 2026-10-12 | UI 结构稿核对代码后更正：§2 列表页现状描述；「Buka unduhan HD」为现有文案而非笔误，本版本改为「Buka HD」；补「详情页预览恒带水印」 |
| 2026-10-12 | UI 结构稿 4 问拍板：Tab 叫 Pilih Kartu / Kartuku；列表加载失败补「Coba lagi」按钮；建卡断网沿用现状（不做灰色占位）；物种 / 性别必填沿用生日的拦截方式，档案无值时不预选 |
| 2026-10-12 | 表单字段口径：只采集卡面字段，编号所需的物种 / 性别为唯一例外（维持补填）；物种 / 品种改为输入框下方下拉菜单，不再弹底部抽屉 |
| 2026-10-12 | UI 视觉稿 3 问拍板：样式网格分两组 + 新卡 new 角标（B1b）；列表行首改迷你卡面（B3b）；列表「Buka HD」标签直接弹付费抽屉、停留在列表 |
| 2026-10-12 | 核对稿审阅：列表行首迷你卡面取消，改回标准证件图标；new 角标 30 天下线确认；补卡面设计资源出处（网盘素材 + Rubik 字体） |
| 2026-10-12 | 交研发：status 改为 handed-off |
