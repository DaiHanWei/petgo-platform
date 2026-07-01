# Bug 系统第一期 · 手动配置指南（Epic 1 + Story 2.1）

本文件覆盖**需在 Lark 里手动完成**的部分：建表、配视图、写提报规范、定稿定位映射、配置只读应用。
脚本侧（拉取/分析）见同目录 `pull_bugs.py` 与 `.claude/commands/bug-analyze.md`。

> 范围：本期**只读**。不申请写权限、不写回线上文档。写回是第二期（Epic 3）。

---

## 0. 快速上手（脚本侧）

```bash
cd scripts/bug-system
cp config.example.toml config.toml      # 填真实字段 ID（建表后，见 §1.5）
cp .env.example .env                     # 填 App 凭证（见 §4）
python3 -m unittest discover -s tests    # 离线自测，应全绿
python3 pull_bugs.py                      # 拉取 → data/bugs.json / bugs.md / attachments/
python3 clean_data.py --yes              # 用完即删本地 PII（NFR9）
```

> `.env` / `config.toml` / `data/` 已在仓库根 `.gitignore` 忽略，绝不入库。
> 本目录另有一份 `.gitignore`（双保险）；因本机全局 gitignore 忽略所有 `.gitignore` 文件，
> 如需让它入库须 `git add -f scripts/bug-system/.gitignore`（不入库也无妨，根规则已兜底）。

---

## 1. Story 1.1 — 在 Lark 建 Bug 表与字段

新建一张多维表格（Bitable），按下列四组建字段。字段名用**英文**，类型对齐。

### A 组：基础标识（系统自动 / 提报生成）

| 字段名 | Lark 类型 | 说明 |
|--------|-----------|------|
| Bug ID | 自动编号 | 人读展示号；**非**反向同步主键 |
| Title | 文本 | 一句话概括（提报人填）|
| Created Time | 创建时间 | 自动 |
| Reporter | 创建人 | 自动 |

> `record_id` 是 Lark 内建记录主键，无需建字段；脚本自动带出，所有关联以它为准（FR8）。

### B 组：问题描述与分类（提报人填写）

| 字段名 | Lark 类型 | 选项 |
|--------|-----------|------|
| Steps to Reproduce | 多行文本 | — |
| Actual Result | 文本 | — |
| Expected Result | 文本 | — |
| Screenshots | 附件 | 图片 |
| Recording | 附件 | 视频 |
| Reported On | 多选 | App / Admin Backend |
| Platform | 多选 | iOS / Android |
| Module | 单选 | Home / Growth / Consult / Me / Other |
| Severity | 单选 | P0 / P1 / P2 / P3 / P4 |
| Bug Type | 单选 | Functional / UI / Performance / Crash / Suggestion |

### C 组：处理过程（工程师 / Claude 填）

| 字段名 | Lark 类型 | 选项 |
|--------|-----------|------|
| Status | 单选 | Open / In Progress / Fixed - Pending Verification / Verified / Reopened / Won't Fix / Duplicate / Accepted / Planned / Declined |
| Assignee | 人员 | — |
| Affected Service | 多选 | App / Backend / Admin H5 |
| Root Cause / Code Location | 多行文本 | — |
| Dev Notes | 多行文本 | — |
| Fixed in Version | 文本 | — |
| Duplicate Of | 关联记录 / 文本 | — |
| Confidence | 单选 | 高 / 中 / 低 |
| Updated by Engineer | 修改时间 | — |

### D 组：交付与验收

| 字段名 | Lark 类型 | 选项 |
|--------|-----------|------|
| Artifact Type | 单选 | Android APK / iOS TestFlight / iOS Ad-hoc IPA / Other |
| Artifact Link | 附件 / 组织内长期链接 | — |
| Build Version | 文本 | — |
| Verification Result | 单选 | Pending / Pass / Fail |
| Verification Notes | 多行文本（追加式）| — |
| Verifier | 人员 | — |

> 版本字段语义：`Fixed in Version` = 代码层修复版本；`Build Version` = 实际交付构建号，二者可不同。

### 1.5 建表后：把真实字段 ID 填进 config.toml

本期只读拉取按**字段名**取值，不依赖字段 ID；但 `config.toml` 的 `[field_ids]` 集中维护字段名↔ID
（NFR6，给第二期写回白名单 FR9 预留）。建表后从 Lark「字段管理 / 字段属性」拷真实 ID，替换占位的 `FLD_*`。
若字段名与本指南不同，改 `config.toml` 顶部的关键字段名（`bug_id_field` 等）与 `[field_types]` 即可，**无需改 .py**。

---

## 2. Story 1.2 — 配置流转视图（§3.3）

建 5 个视图，筛选条件如下：

| 视图 | 筛选 |
|------|------|
| 待处理 | Status = Open |
| 处理中 | Status = In Progress |
| 待验收 | Status = Fixed - Pending Verification |
| 已关闭 | Status ∈ {Verified, Won't Fix, Duplicate, Declined} |
| 全部 | 默认表格视图，无筛选 |

> 「已关闭」非绝对终态：Verified 可重开（FR16）、Won't Fix / Duplicate 可逆（FR14）。

---

## 3. Story 1.3 — 提报规范 + 定位映射定稿

### 3.1 提报人填写要求

- **Title**：一句话能让人懂是什么问题（不是「有 bug」）。
- **Steps to Reproduce**：分点写，从打开 App 起逐步到出问题；缺它会被标「信息不足」。
- **Actual Result / Expected Result**：分别写「实际发生」与「本应如何」。
- **Screenshots**：尽量带图；缺图会被标「信息不足」。涉及用户/宠物隐私的图请知悉本地会留存（NFR9）。
- **Recording**：录屏供人工看（AI 不解析视频）。
- **Severity**：P0 最严重（崩溃/不可用），P4 最轻（建议级体验）。
- **Bug Type = Suggestion** 的条目走轻量子流转（Open → Accepted/Planned/Declined），不进修复/验收闭环。

### 3.2 Platform 约定

- **通用 Bug**（两端都有 / 与端无关）：Platform **留空**。
- **单端 Bug**：只勾对应端（iOS 或 Android）。分析时单端会重点查该平台分支。

### 3.3 Reported On vs Affected Service（别混）

- **Reported On**（提报人填，B 组）：用户**在哪个端遇到**（App / Admin Backend）——用户视角。
- **Affected Service**（AI / 工程师填，C 组）：**实际涉及哪个服务**（App / Backend / Admin H5）——定位结论。
- 二者常不同：用户在 App 遇到的问题，根因可能在 Backend。

### 3.4 服务 → 仓库映射（§4.3 定稿，FR6 定位核心输入）

| Affected Service | 代码位置 |
|------------------|----------|
| App | `petgo_app/`（Flutter 客户端，单库构建 Android + iOS）|
| Backend | `petgo-backend/`（Spring Boot 后端）|
| Admin H5 | `petgo-backend/` 内 `com.tailtopia.admin` slice（Thymeleaf 后台管理 H5）|

### 3.5 Module 取值（对照真实产品模块定稿）

| Module | 对应 |
|--------|------|
| Home | App 首页 / 内容流 |
| Growth | 成长档案 / 里程碑 |
| Consult | 问诊 / 兽医咨询 / IM |
| Me | 我的 / 账号 / 设置 |
| Other | 不属以上或跨模块（会被标「信息不足」提示补充）|

---

## 4. Story 2.1 — 配置 Lark 企业自建应用（**只读**）

1. Lark 开放平台 → 开发者后台 → 创建**企业自建应用**，记下 **App ID / App Secret**。
2. 权限管理，**仅申请**：
   - `bitable:app:readonly`（多维表格只读）
   - 媒体/云文档下载相关只读权限（截图附件下载）
   - **本期不申请任何写权限**（NFR4）；第三期同一应用再升级读写（NFR10）。
3. 把应用作为**可读协作者**加入 Bug 多维表格（否则读不到记录）。
4. 取表格标识：表格 URL `…/base/<app_token>?table=<table_id>…`，分别填入 `.env` 的 `APP_TOKEN` / `TABLE_ID`。
5. 复制 `.env.example` 为 `.env`，填 `APP_ID / APP_SECRET / APP_TOKEN / TABLE_ID`。

> 海外版/国内版：`config.toml` 的 `region` 切 `cn`(open.feishu.cn) 或 `global`(open.larksuite.com)（NFR5）。

---

## 5. 留存与清理（NFR9）

- 拉取产物 `data/`（尤其含真实用户/宠物健康信息的截图）属真实隐私面。
- **用完即删**：分析完成后跑 `python3 clean_data.py --yes` 清空本地副本。
- 不二次外传；多机各存全量副本须各自及时清理。
