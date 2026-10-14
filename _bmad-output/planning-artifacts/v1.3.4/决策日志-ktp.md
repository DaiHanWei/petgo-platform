# V1.3.4 ktp 主题决策日志

> 本主题拆 epic / story 过程中的产品决定。**与 PRD / UI 稿冲突时以本文件为准。**
> 功能分支 `feat/1.3.4-ktp-revamp`（自 `dev_1.3.4` 拉出）。
> 输入文档：`PRD-v1.3.4-ktp.md`（原名 `KTP样式迭代.md`，FR-142A ~ F）· `ui-v1.3.4-ktp.html`（原名 `ui-ktp-1-3-4-integrated-v1.html`）· 设计素材 `ktp-assets/`（原 `134KTP样式图片/design-assets/` 与卡面概念图 16 ~ 20；`app-assets/` 与 App 现有素材逐字节相同，未入库）。

## 2026-10-12 · 拆 epic 前拍板（操作者 Hex）

| # | 议题 | 决定 | 覆盖/订正了什么 |
|---|---|---|---|
| D-1 | 本主题范围 | **App + 后端 + 后台「用户购买记录」识别新卡种**（唯一一处后台改动，不另开后台主题） | — |
| D-2 | 「Pilih Kartu」8 款缩略图来源 | **开发用自己的卡面组件渲染导出**（示例宠物数据），作为静态图放进 App；设计日后可替换 | PRD §3.2「静态示例图（设计提供）」——设计未提供 |
| D-3 | 新卡 `new` 角标 30 天下线 | **代码写死一个截止日期常量**，列为发版检查项，发版时填「上线日 + 30 天」；过期后所有用户都不显示 | PRD §3.2「前端按版本常量控制」的具体口径 |
| D-4 | Klasik 学生卡新增编号位的位置 | **按 UI 稿 F8**（字段区下方） | UI 稿 F8「具体位置第三步定」 |
| D-5 | 护照卡签发日 / 到期日用的是「渲染当天」（现有 bug，`passport_card.dart:55` `DateTime.now()`） | **顺手修**：签发日 = 建卡日、到期日 = 建卡日 + 5 年；存量护照卡日期随之变为各自建卡日。SIM 签发日同样取建卡日 | PRD §3.5「现有护照卡保持原样」——仅此一处例外 |
| D-6 | 后台「用户购买记录」卡种显示 | 显示「{卡种名} · No. {身份码} · {宠物名}」，8 款各显示自己的名称；无身份码的 V95 前老卡显示流水号补零（与 App 列表口径一致）。现状所有卡都显示成「KTP #流水号」 | PRD §3.8「后台识别新值」的具体口径 |
| D-7 | 分享埋点 `id_card_share_tapped.card_style`（现为 0/1/2） | **保留** `card_style`：KTP 0、护照 1、学生卡（4 款）2、SIM 3、居住证 4；**另加** `card_type`、`card_variant` | PRD §4「已有分享事件补 card_type / card_variant」 |
| D-8 | `hd_unlocked_at` 存量回填口径（PRD §3.5【建议】，技术评审） | PawCoin 取扣币时间（`purchased_at`）；QRIS 取支付意图**到账**时间；都没有（V92 回填老卡）取 `created_at`。不用 QRIS 的下单时间 | 订正 PRD【建议】「取最早一条有支付记录的 `purchased_at`」 |
| D-9 | 建卡接口物种 / 性别改必填对旧版 App 的影响 | **接受**：仅「档案未填物种」的极少数旧版用户建卡会 422 | — |
| D-10 | 架构增量 | `architecture-v1.3.4-ktp-delta.md` AD-1 ~ AD-14 同意 | — |
| D-11 | 新增文案英文 | 按 step 1 汇报的拟稿采用，唯一改动：Tab「Pilih Kartu」英文为 **Create new**（不是 Choose a Card）；「Kartuku」= My Cards。后台卡种名配中文 | PRD「en / zh 同步翻译」——App 只有 en / id |
| D-12 | 旧版 App 建学生卡不带 `cardVariant` | **按 KLASIK 处理**（旧版只有紫色一款），不 422 | 订正 epics Story 1.1「学生卡缺 cardVariant → 422」（与 NFR4 冲突） |
| D-13 | 新埋点命名 | **改成项目规范的过去式**：`id_card_tab_viewed` · `id_card_style_tapped` · `id_card_form_opened` · `id_card_created` · `id_card_buka_hd_tapped` · `id_card_paywall_dismissed`（属性不变） | PRD §4 事件名（`_view` / `_click` / `_open` / `_close` / `id_card_create`） |
| D-14 | 从卡详情页返回列表停在哪个 Tab | **从哪个 Tab 进就回哪个**：建卡后进详情 → 返回 Pilih Kartu；从 Kartuku 点卡进详情 → 返回 Kartuku。从聚合页等外部入口进列表仍默认 Pilih Kartu | PRD §3.2「从新卡详情页返回同样停在 ID Card Tab」——仅限建卡路径 |
| D-15 | 建卡后付费失败 / QRIS 未付关闭 | **一律跳新卡详情**（失败先提示），建卡页出栈 | 补 PRD §3.6 只写了「关闭 / 成功」 |
| D-16 | 付费成功后自动弹「保存 / 分享」选单 | **两个入口都自动弹**（详情页现状保留，建卡页付费成功落到详情后同样弹） | — |
| D-17 | KTP / 居住证品种 | **必填**（按 PRD 字段表）；UI 稿「RAS」补 `*` | UI 稿 D1 / D3「RAS」无星号；PRD「现状口径不变」的说法不准确 |
| D-18 | 居住证 Logo | **用 Permit 素材包自带的 Logo** | UI 稿 F3 引用的 `ktp_logo.png` |
| D-19 | 编号沿用比对时快照物种为空的老学生卡 | **严格比对**：空 ≠ OTHER，不沿用 | — |

## 2026-10-13 · 开发后拍板（操作者）

| # | 议题 | 决定 | 覆盖/订正了什么 |
|---|---|---|---|
| D-20 | 建卡必填（物种 / 性别、KTP / 居住证品种）对旧版 App 的影响 | **新增建卡接口 V2** `POST /api/v1/pet-profiles/me/id-cards/v2`，新版 App 调用，必填在服务端强制；**旧接口** `POST /me/id-cards` 留给 1.3.3 及更早 App，保持原宽松口径（物种 / 性别 / 品种可不填，性别不填按 UNKNOWN）。两接口共用同一套建卡逻辑（卡种配色、intro 不落库、编号归属、编号沿用）。发版须先后端再 App | 订正 D-9（旧版用户不再因必填 422）、AD-4 / Story 1.1「必填放在 DTO」 |
| D-21 | 2-2 迁移自检报错是否输出号码 | 按开发实现：只列类别、冲突数量与涉及 user_id，不输出号码 | Story 2.2 AC1.1 |
| D-22 | 0649 上线后不可回滚旧 jar 建卡 | 接受，基本不回滚 | — |

## 2026-10-12 · story 生成时的技术裁定（dev agent 按惯例定，操作者已知悉）

| # | 议题 | 裁定 |
|---|---|---|
| T-1 | SIM 编号 4 位年份 | 取卡快照生日的年份（`card_no` 的年段即生日年），格式化函数入参带 `birthday` |
| T-2 | SIM / 居住证预览时无 `card_no`（生日未填） | 编号位留空，不拿 serial 补位 |
| T-3 | 新卡面文字大小写 | 按 UI 稿（首字母大写），不跟 KTP 全大写 |
| T-4 | 卡面日期时区 | 与列表「Dibuat」一致（手机本地日期） |
| T-5 | 三款新学生卡布局 | 一个组件 + 每款一份坐标（UI 稿 Bintang 照片在右，与另两款不同） |
| T-6 | 长文本溢出 | 院系最多两行；名字、学校单行，超宽缩字号 |
| T-7 | `idCardUnlockHd` 改值（Buka HD）时机 | 提前到 Story 1.6（标签最先用到），1.8 不再重复 |
| T-8 | `id_card_paywall_dismissed` 触发 | 仅付费抽屉未选支付方式就关闭时报；选 QRIS 后关二维码未付不报；列表入口 `entry = list_badge` |
| T-9 | 列表加载失败时 `id_card_tab_viewed.has_cards` | 省略该属性 |
| T-10 | new 角标截止常量开发期取值 | 先填一个远期占位日期，Completion Notes 标「发版改为上线日 + 30 天」 |
| T-11 | 缩略图示例数据 | 不放宠物照片（头像位用卡面自带占位） |
| T-12 | 埋点 `card_type` / `card_variant` 取值 | 用接口 wire 值（大写） |
| T-13 | 网格标题句位置 | 第一个组标题上方（UI 稿 B1 未画） |
| T-14 | 建卡页缺项提示 | 统一「Lengkapi {项} dulu」/「Fill in {fields} first」，项名 id：nama hewan · jenis hewan · ras · jenis kelamin · tanggal lahir；en：pet name · species · breed · gender · date of birth；多项用逗号 + 末项「dan / and」 |
| T-15 | 表单内红字范围 | 只按 PRD 三项（生日 / 物种 / 性别）；名字、品种缺失只在建卡页提示 |
| T-16 | 建卡请求字段 | 只提交本卡款表单内的字段，其余存空 |
| T-17 | 跨用户号冲突（理论上不应出现） | 500 级系统错误 + 记日志，整笔回滚 |
| T-18 | 宠物档案已删的已解锁学生卡 | 仍可作为沿用来源（PRD「三项一致即同一只宠物」） |
| T-19 | 解锁到账与建卡并发 | 以建卡事务时刻已提交的状态为准 |
| T-20 | `ktp_unlock_failed` 与旧版单卡端点 | 不补卡种属性（PRD 只点名成功 / 建卡 / 分享事件） |
| T-21 | QRIS 未付关闭二维码（列表入口） | 只刷新列表，不提示 |
| T-22 | 三款新学生卡 Logo 源图偏小（83–94px） | 先用现图；已请操作者向设计要 2 倍图，同名替换即可 |
