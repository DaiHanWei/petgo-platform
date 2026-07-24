# Story 6-8 · 身份证卡种扩展：Pet Passport / Student Card

> **来源**：bug `20260721-330`「宠物身份证后面的 passport 和 student 还没做完」。
> **定位**：在 Story 6-7（身份证多卡快照 + 独立建卡器）之上，新增**卡种维度**——同一套"建卡快照 + HD 购买"机制，扩出 **KTP / PASSPORT / STUDENT** 三种可视卡面。
> **状态**：ready-for-dev（资产已入库，见 §2）。
> **前置**：6-7 已落地（`id_cards` 表 = V91、`id_card_hd_purchases` per-card = V92；`IdCard` 实体 / `IdCardService` / 独立建卡器 / `ktp_card.dart` 渲染范式均在）。

---

## 1. 范围与不做

**做**：
- `id_cards` 加 `card_type`（KTP / PASSPORT / STUDENT），建卡器选卡种。
- 两个新卡面 widget：`passport_card.dart`、`student_card.dart`——照 `ktp_card.dart` 的**底图 + 定位文字**范式 1:1 还原设计稿。
- 卡详情页按 `card_type` 分发到对应 widget；HD 导出复用现有链路（HD 购买绑 `card_id`，与卡种无关）。

**不做**：
- 不改 HD 付费/购买链路（6-7 已通，按 `card_id` 幂等，卡种透明）。
- 不做背面（背面早于 0717 已下线，见记忆）。
- 不引入新号池：三种卡都走现有 `SerialAllocationService.allocate()` 同一 `serial_id`。

---

## 2. 设计资产（已入库）

运行时资产（已进 `petgo_app/assets/`，pubspec 已声明 `assets/passport/`、`assets/student/`）：

| 卡种 | 底图（纯底纹，无文字） | Logo | 印章 |
|---|---|---|---|
| passport | `assets/passport/passport_front_bg.png`（995×774，绿波纹 + 底部 MRZ 灰条） | `assets/passport/passport_logo.png`（110×110） | 无 |
| student | `assets/student/student_front_bg.png`（994×600，紫头条 + 爪印水印 + 紫底线） | `assets/student/student_logo.png` | `assets/student/student_stamp.png`（199×262 圆章） |

> passport 与 student 的 Logo 字节相同（与 KTP 的 `ktp_logo.png` 不同）。

**反解坐标参考**（成图，**不打包**）：`docs/design/id-cards/passport-mockup.png`、`student-mockup.png` + `*-FONT.txt`。
- 方法同 KTP（见 `ktp_card.dart` 头注）：成图 vs `*_front_bg.png` 做**像素差分**得各叠加元素 bbox；字号由 cap height 定、字距由实测字宽反推。**勿凭手感调坐标**。
- 画布 = 底图 2×：passport `Size(1990, 1548)`、student `Size(1988, 1200)`。

**字体**：Rubik（`Rubik-VariableFont_wght.ttf` 已打包，覆盖 Regular/Medium/SemiBold/Bold 字重轴）。
- ⚠️ **斜体缺口**：副标题「PET PASSPORT」「STUDENT CARD」为斜体，变量字体只有字重轴无斜体文件。二选一：① 补 `Rubik-Italic-VariableFont_wght.ttf` 入库并在 pubspec 声明 `style: italic`；② 用 `fontStyle: FontStyle.italic` 合成斜体（视觉略差，可接受）。建议 ①。

---

## 3. 数据模型 — Flyway **V93**（当前最新 V92，顺延）

```sql
-- Story 6-8：身份证卡种维度。存量卡（6-7 建的）默认 KTP。ddl-auto=validate。
ALTER TABLE id_cards ADD COLUMN card_type VARCHAR(16) NOT NULL DEFAULT 'KTP';
```

- 号段纪律（决策 E2）：V91/V92 已冻结，本次**新起 ALTER**（V93），勿改旧文件。
- 枚举 `CardType { KTP, PASSPORT, STUDENT }`（UPPER_SNAKE 落库）。
- **无需新增字段列**：passport/student 展示字段全部由现有快照字段（`name`/`pet_type`/`breed`/`birthday`）+ `serial_id` + `created_at` **派生或静态**（见 §4）；缺失项走"趣味默认"（KTP 已有此范式，如 alamat/pekerjaan）。

---

## 4. 卡面字段来源（关键：多为静态/派生，几乎不新增采集）

### 4.1 PASSPORT（成图见 passport-mockup.png）
| 卡面字段 | 来源 |
|---|---|
| 标题 PASPOR HEWAN PELIHARAAN / PET PASSPORT | 静态 |
| Jenis/Type | 静态 `P` |
| Kode Negara/Country Code | 静态 `IDN` |
| No.Paspor/Passport.No | 派生：`A` + serial 后 6 位（如 `A 000123`） |
| Name | 快照 `name` |
| Kelamin/Sex | **趣味默认**（无档案性别）→ `L/M`（可后续加档案性别字段，本期默认） |
| Kewarganegaraan/Nationality | 静态 `INDONESIA` |
| Tgl.Lahir/Date of Birth | 快照 `birthday` → `DD MMM YYYY` |
| Tempat Lahir/Place of Birth | **趣味默认**（如 `BANDUNG`）或从档案地区派生（本期默认） |
| Tgl.Pengeluaran/Date of Issue | `created_at` → `DD MMM YYYY` |
| Tgl.Habis Berlaku/Date of Expiry | `created_at + 5 年` |
| Reg.No | 派生：serial 后 9 位 |
| Kantor/Issuing Office | 静态 `TAILTOPIA` |
| NIKIM | serial 全 12 位 |
| 底部 MRZ 双行 | 生成：`P<IDN` + name + serial 拼 MRZ 风格 `<<<`（装饰性，字符集固定，非真 ICAO 校验） |
| 宠物照片 | 快照 `avatar_key` 签名 URL（同 KTP） |

### 4.2 STUDENT（成图见 student-mockup.png）— **零新增字段**
| 卡面字段 | 来源 |
|---|---|
| 标题 TAILTOPIA ACADEMY / STUDENT CARD | 静态 |
| 学生证号（大号 16 位） | serial（格式同 KTP NIK：`3276…`） |
| Name | 快照 `name` |
| Date of Birth | 快照 `birthday` → `DD-MM-YYYY` |
| Species | 快照 `pet_type` 本地化（CAT→`KUCING` / DOG→`ANJING`…） |
| 宠物照片 | 快照 `avatar_key` 签名 URL |
| 圆章 | 静态 `student_stamp.png` |

> **结论**：本期不新增任何采集字段；Sex/Place-of-Birth 走默认。若后续要真实性别/出生地，另起增量（加 `id_cards.sex`/`birthplace`，新 ALTER）。

---

## 5. 后端改动

- **枚举** `profile/domain/CardType.java`：`KTP, PASSPORT, STUDENT`。
- **实体** `IdCard`：加 `cardType` 字段（`@Enumerated(STRING)`，`@Column(name="card_type")`）；工厂 `IdCard.snapshot(...)` 增 `cardType` 参（默认 KTP 保兼容）。
- **DTO**：
  - `CreateIdCardRequest` 加 `cardType`（枚举字符串，缺省 KTP；服务端校验合法枚举）。
  - `IdCardResponse` 加 `cardType` + **按卡种下发派生显示字段**（见 §4；服务端算好静态/派生值一并返回，前端不重复算业务逻辑，只负责排版）。建议 response 分：公共快照字段 + `Map<String,String> displayFields`（卡种专属，服务端填）。
- **Service** `IdCardService.createCard(...)`：透传 `cardType` 落库；`getMyCard` 按卡种组装 `displayFields`（passport 的 No/Reg/日期/MRZ、student 的证号/species 本地化）。
- 归属校验 / 不可枚举 token / 红色态无关 均不变（沿用 6-7）。
- **护栏**：serial 同池 `allocate()`；`validate` 不 update；日志不落 PII（name/birthday 明文）。

---

## 6. 前端改动

- **建卡器**（独立建卡流程）：顶部加**卡种选择**（KTP / Passport / Student 三选一，默认 KTP）。选卡种影响：① 提交带 `cardType`；② 预览渲对应卡面。字段输入沿用 6-7（name/petType/breed/birthday/avatar），passport/student 不额外要用户填（派生/默认）。
- **卡面 widget**（照 `ktp_card.dart` 范式）：
  - `id_card/passport_card.dart`：`kPassportCanvas = Size(1990,1548)`；`PassportFields`（§4.1 字段）；`_PassportLayout`（反解坐标，abstract final 常量）；Stack = `passport_front_bg` + logo + 定位 Text（Rubik）+ 照片框 + MRZ 双行。
  - `id_card/student_card.dart`：`kStudentCanvas = Size(1988,1200)`；`StudentFields`（§4.2）；`_StudentLayout`；Stack = `student_front_bg` + logo + 大号证号 + 3 字段 + 照片框 + `student_stamp`。
  - 都用 `FittedBox` 缩放到可用宽度；导出走同尺寸 `RepaintBoundary`（HD 复用现有导出器，仅换 canvas/widget）。
- **卡详情页** `id_card_detail_page.dart`：按 `response.cardType` switch 渲染 `KtpCard/PassportCard/StudentCard`；HD paywall/导出入口不变。
- **i18n**：卡面**静态标签**是印尼语原文照搬设计稿（非 App 双语串，属证件版式，直接硬编码在卡 widget 内，与 KTP 一致——KTP 卡面标签也是硬编码印尼语）。Species 本地化映射在卡 widget 内做。

---

## 7. 验收分层

- **L0**（云端可跑）：`mvn -B package`（枚举/实体/DTO/迁移编译 + 单测）；`flutter analyze` + `flutter test`（widget 构造 + golden 可选）；`flutter build apk --debug`。
- **L1**（本地 Docker）：起后端，建 3 种卡各一，`GET /me/id-cards/{id}` 返回正确 `cardType` + `displayFields`；Flyway V93 在含存量卡的库上 apply 后旧卡 `card_type='KTP'`。
- **L2**（本地真机/模拟器视觉）：三种卡面与设计稿 1:1 比对（坐标反解精度）；HD 导出像素精度；建卡器切卡种预览正确。**必须 teleport 回本地**（headless 不能视觉验收）。

---

## 8. 待决 / 提示

1. **斜体字体**（§2）：建议补 Rubik-Italic 入库。
2. **Sex / Place-of-Birth**（passport）：本期默认值；要真实值则另起增量加档案字段。
3. **MRZ 行**：装饰性（固定 `<` 填充 + name/serial），非真 ICAO 校验——确认产品接受"仿真"而非合规 MRZ。
4. **反解坐标**是本 story 的主要工作量与精度风险点：务必用 `docs/design/id-cards/*-mockup.png` 差分，勿手调；每个卡面留一张 golden 供回归。
5. 号池共享：passport/student 与 KTP、`pet_profiles` 共用 serial 空间，全局不撞（6-7 §3.1 已述）。

---

## 附：改动文件清单（预估）

**后端**：`V93__add_card_type_to_id_cards.sql`、`CardType.java`、`IdCard.java`、`CreateIdCardRequest.java`、`IdCardResponse.java`、`IdCardService.java`（+ 对应测试）。
**前端**：`pubspec.yaml`（已改，声明资产）、`id_card/passport_card.dart`（新）、`id_card/student_card.dart`（新）、建卡器页（加卡种选择）、`id_card_detail_page.dart`（按类型分发）、`assets/passport/*` `assets/student/*`（已入库）。
