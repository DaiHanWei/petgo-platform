---
title: 内容审核 Story 1 — 阿里云内容安全接入 + 分层词库 + fail-closed 降级
type: spec
status: draft
source:
  - _bmad-output/planning-artifacts/content-moderation-plan-v1.0.1.md（§2 判定标准 / §4 三方配置 / §4.3 降级 / §9 词库）
  - _bmad-output/implementation-artifacts/spec-content-moderation-overview.md（story 1 行 + D-CM5/D-CM8）
relatedStories:
  - spec-content-moderation-overview.md（总览，权威拆解）
  - spec-content-moderation-post-review.md（story 2：消费评分 ≥0.8 路由入队 + 消费降级）
  - spec-content-moderation-comment-review.md（story 3：评论同步拦截 + 消费降级）
  - spec-content-moderation-name-review.md（story 4：复用文本审核）
  - spec-content-moderation-avatar-review.md（story 5：复用图像审核）
flyway: V47__init_moderation_keyword_rules.sql（占位号；CI 落地时按实际合并顺序单调顺延，勿硬编码）
created: 2026-07-08
owner: Dai
communication_language: 中文
---

# 内容审核 Story 1 — 阿里云内容安全接入 + 分层词库 + fail-closed 降级

> 本 story 是**所有风险分级 / 降级的地基**（overview §4 顺序第 1）。把现有 stub `ContentModerationService` 的内部替换为**真实阿里云内容安全**（文本 + 图像），输出 **0–1 风险评分**、**L1 硬拦截判定**、**L1/L2/L3 分层词库**（白名单优先级高于黑名单），并实现 **§4.3 fail-closed 降级**（超时 / 4xx / 5xx / 配额耗尽 / 宕机 → 不放行，转人工队列信号）。
> **调用方 `ContentService` 本 story 不改**（见 §5.1 边界）；评分/降级的**路由消费**由 story 2/3 承接。
> **权威源 = 方案 v1.0.1**；契约冲突以 `CROSS-STORY-DECISIONS.md` 为准。与用户沟通用中文。

---

## 1. 背景与范围

内容审核流程骨架**已接线**（发布校验后、落库前调用；非 PASS 分「失败 / 入队」两支，`ContentService.java:248-266`）。唯一缺口是**审核判定本身仍是 stub**：6 词硬编码子串黑名单 + 图 URL 魔法标记，**无评分、无阈值、无超时、无降级**。

本 story 只做**判定引擎替换 + 能力扩展**，一次只碰 `content` 后端一侧：

**在范围内：**
1. 真实阿里云内容安全 **文本审核**（TextModeration）+ **图像审核**（ImageModeration）客户端，`mode=stub|live` 双模（对齐既有 Gemini/IM 惯例）。
2. **0–1 风险评分** + **L1 硬拦截判定**（命中 L1 黑名单 / 图像高置信违规 → 即时硬拦截；未命中但评分 ≥ 阈值 → RISKY，交由调用方路由人工队列）。
3. **L1/L2/L3 分层词库**（§9）：L1 平台强制黑名单、L2 运营可调、L3 宠物场景白名单；**白名单优先级高于黑名单**（§9.3）。词库落 DB 新表 `moderation_keyword_rules`（V47 占位）。
4. **§4.3 fail-closed 降级**：三方超时 / 4xx / 5xx / 配额耗尽 / 持续宕机 → **绝不自动放行**，返回 `DEGRADED` 结果 + 降级原因，由调用方（story 2/3）转人工队列；含熔断态与告警日志。
5. **印尼语能力实测（D-CM8 前置阻断项）**：`live` 模式下用真实印尼语样本实测阿里云文本审核评分；不足则风险分级降级为「中英可靠 + 印尼语自建 L1/L3 关键词硬匹配兜底」（本 story 词库表天然支撑此兜底）。
6. 扩展 `Verdict` 契约（向后兼容）+ 新增富结果类型 `ModerationOutcome`（verdict + 评分 + 降级原因）。

**不在范围内（明确交接）：**
- 评分 ≥0.8 / 降级结果的**发布流程路由**（落 UNDER_REVIEW、入队、Feed 可见性）→ story 2（帖子）/ story 3（评论）。
- 昵称/宠物名/头像的**异步**送审编排 → story 4/5（复用本 story 的文本/图像审核方法）。
- 人工队列的运营处置 / 优先级 → story 8。
- 关键词库的运营后台 CRUD 界面 → story 8（本 story 只建表 + 种子 + 读取加载，写入靠迁移种子 + 后续管理台）。

---

## 2. 现状基线（带 file:line，勿重复实现已存在部分）

| 现状 | 证据 file:line | 本 story 动作 |
|---|---|---|
| `ContentModerationService` 为 stub：6 词硬编码黑名单子串匹配 | `content/service/ContentModerationService.java:31-32,57-60` | **替换内部**为阿里云 + 词库 |
| 图像审核 = URL 含 `moderation-blocked` 魔法标记 | `ContentModerationService.java:38,62-64` | **替换**为阿里云 ImageModeration |
| `Verdict` 枚举 {PASS, TEXT_BLOCKED, IMAGE_BLOCKED}，同步、无评分、无阈值、无超时 | `ContentModerationService.java:20-25,47-55` | **兼容扩展** + 新增 `ModerationOutcome` |
| 注释「真实接入时仅替换本类内部，调用方不变」 | `ContentModerationService.java:13-16` | 遵循：本 story 不改调用方 |
| 调用方：`verdict != PASS` 分「失败 / 入队」两支 | `ContentService.java:248-266` | **不改**（story 2 承接路由） |
| 人工审核开关 `manual_review_enabled`（默认 false，单行 id=1） | `db/migration/V42__init_admin_settings.sql` | 复用（降级信号供调用方按此开关路由；本 story 不翻开关） |
| 人工审核队列 `manual_review_queue`（PENDING/APPROVED/REJECTED/TIMED_OUT，预建） | `db/migration/V41__init_manual_review_queue.sql` | 复用（本 story 只产出降级信号，不直接入队；入队在 story 2/3） |
| 第三方双模惯例（stub/live + env 注入 + 不入日志） | `application.yml:17-26`（Gemini）、`.env.example`（OSS/Gemini/IM 段） | **照抄该模式**接阿里云内容安全 |
| Flyway 已落到 V46 | `db/migration/V46__add_vet_qualification_strv.sql` | 新增从 **V47 占位**顺延 |

> 关键：现网 stub 除 6 词命中外一律 PASS（无「风险」概念），因此**引入评分后若不改调用方，publish 行为必须保持不变**——见 §5.1 兼容边界。

---

## 3. 目标与非目标

**目标：**
- G1 用真实阿里云内容安全替换文本 + 图像判定，`live` 模式真连、`stub` 模式无凭证可跑（L0/L1）。
- G2 产出 0–1 风险评分 + L1 硬拦截布尔判定，阈值可配（默认 0.8，图像分类阈值 §4.2）。
- G3 L1/L2/L3 分层词库入库，**白名单优先级最高**，命中白名单的词不触发同步硬拦截。
- G4 fail-closed：任何「无法给出明确通过」的三方结果 → `DEGRADED`，绝不自动放行。
- G5 印尼语能力实测并留档；不足则词库兜底路径可用。
- G6 `Verdict` 契约向后兼容，调用方零改动仍可编译且行为不变。

**非目标：**
- 不做发布流程可见性/入队路由（story 2/3）。
- 不引入 MQ / Kafka / 通用缓存层 / 新中间件（护栏）——熔断态用**进程内**轻量状态，不新增中间件。
- 不做关键词库运营 UI（story 8）。
- 不做异步重试编排的账号名/头像侧（story 4/5）；本 story 仅定义**同步**审核方法 + 无状态可复用能力。

---

## 4. 数据与迁移（Flyway 占位 delta）

### 4.1 新表 `moderation_keyword_rules`（V47 占位）

分层词库 + 白名单。**新表**（overview §5：story 1 = V47）。号仅占位，CI 按实际合并顺序单调顺延。

```sql
-- V47__init_moderation_keyword_rules.sql（占位号；勿硬编码，落地时顺延）
-- Story 内容审核-1：L1/L2/L3 分层词库 + 宠物场景白名单（方案 §9）。
-- rule_kind UPPER_SNAKE 落 varchar；白名单优先级最高（§9.3），命中白名单不触发同步硬拦截。
-- 无 length=1 列（避免 Hibernate CHAR(1) → validate 全红）；enabled 用 boolean。
CREATE TABLE moderation_keyword_rules (
    id          BIGSERIAL    PRIMARY KEY,
    rule_kind   VARCHAR(16)  NOT NULL,   -- L1_BLOCK / L2_ADJUSTABLE / L3_WHITELIST
    match_type  VARCHAR(16)  NOT NULL DEFAULT 'SUBSTRING', -- SUBSTRING / REGEX / EXACT
    pattern     VARCHAR(512) NOT NULL,   -- 词或正则（大小写不敏感由应用层保证）
    category    VARCHAR(32)  NOT NULL,   -- DRUGS/GAMBLING/PORN/POLITICS/AD_SPAM/HARASSMENT/WEAPON/PET_SAFE...
    lang        VARCHAR(8)   NOT NULL DEFAULT 'ALL',  -- id / en / zh / ALL
    enabled     BOOLEAN      NOT NULL DEFAULT true,
    note        VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_mkr_rule_kind  CHECK (rule_kind  IN ('L1_BLOCK','L2_ADJUSTABLE','L3_WHITELIST')),
    CONSTRAINT ck_mkr_match_type CHECK (match_type IN ('SUBSTRING','REGEX','EXACT'))
);

CREATE INDEX idx_mkr_kind_enabled ON moderation_keyword_rules (rule_kind, enabled);
CREATE INDEX idx_mkr_lang         ON moderation_keyword_rules (lang);

-- 种子：§9 初稿（L1 违禁/赌博/武器 + L2 引流 + L3 宠物白名单）。
-- 印尼语 L1（毒品/赌博/武器）与 L3 白名单来自方案 §9.1/§9.3；
-- L3 宠物白名单（anjing/gendut/hitam/nakal...）供印尼语兜底与误判防护。
INSERT INTO moderation_keyword_rules (rule_kind, match_type, pattern, category, lang, note) VALUES
  ('L1_BLOCK','SUBSTRING','narkoba','DRUGS','id','毒品总称'),
  ('L1_BLOCK','SUBSTRING','sabu','DRUGS','id','冰毒'),
  ('L1_BLOCK','SUBSTRING','ganja','DRUGS','id','大麻'),
  ('L1_BLOCK','SUBSTRING','judi','GAMBLING','id','赌博总称'),
  ('L1_BLOCK','SUBSTRING','togel','GAMBLING','id','非法数字彩'),
  ('L1_BLOCK','SUBSTRING','bom','WEAPON','id','炸弹'),
  ('L2_ADJUSTABLE','SUBSTRING','wa.me/','AD_SPAM','ALL','WhatsApp 引流链接'),
  ('L2_ADJUSTABLE','REGEX','\b08[0-9]{2}[-\s]?[0-9]{4}[-\s]?[0-9]{4}\b','AD_SPAM','id','印尼手机号（中风险，非硬拦截）'),
  ('L3_WHITELIST','EXACT','anjing','PET_SAFE','id','狗（宠物语境正常词，优先于黑名单）'),
  ('L3_WHITELIST','EXACT','gendut','PET_SAFE','id','胖（高频宠物名）'),
  ('L3_WHITELIST','EXACT','hitam','PET_SAFE','id','黑（黑毛宠物名）'),
  ('L3_WHITELIST','EXACT','nakal','PET_SAFE','id','调皮（宠物名）');
```

> 色情/低俗 L1 词方案 §9.1 要求母语运营配置，本迁移不内置露骨词，留待运营通过后续管理台（story 8）补录；表结构已就位。

### 4.2 无需改动的表
- `admin_settings`（V42）已含 `manual_review_enabled`，本 story **只读不写**（降级信号交调用方按此开关路由）。运营手动「暂停三方 / 强制人工」总闸留 story 8 再决定是否加列，本 story 不加。
- `manual_review_queue`（V41）本 story 不动（入队在 story 2/3）。

> Flyway 冻结纪律：只新起 V47；`ddl-auto=validate` 不变；实体命名 snake_case↔camelCase 由 JPA 桥接。

---

## 5. 后端设计

### 5.1 兼容边界（关键 —— 为何调用方能不改）

现网 `ContentService.java:248-266` 依赖 `Verdict moderate(text, imageUrls)`。本 story：

1. **保留** `Verdict moderate(String text, List<String> imageUrls)` 方法签名与语义**用作编译兼容 shim**：仅在命中 **L1 硬拦截**时返回 `TEXT_BLOCKED`/`IMAGE_BLOCKED`，其余（含 ≥0.8 RISKY、DEGRADED）一律返回 `PASS`。
   - 依据：现网 stub 对「非 6 词」内容本就一律 PASS（无风险概念），故 shim 令 publish 行为**逐字节保持不变**，直到 story 2 改用富接口。**这是有意的过渡态，不是漏放行**：评分/降级的实际拦截由 story 2/3 采纳富接口后才生效。
2. **新增富接口** `ModerationOutcome evaluate(String text, List<String> imageUrls)`（story 2/3 将把调用点从 `moderate` 切到 `evaluate`，并按 verdict/degraded 落 UNDER_REVIEW / 入队）。
3. `Verdict` 枚举**向后兼容扩展**（追加值，不改既有名/序）：
   ```
   enum Verdict { PASS, TEXT_BLOCKED, IMAGE_BLOCKED, RISKY, DEGRADED }
   ```
   既有调用方 switch 带 `default` 分支（`ContentService.java:257`），追加枚举值不破坏编译；且 shim 从不向旧调用方返回新值，行为安全。

> Completion Notes 必须显式记录：「story 1 交付引擎，publish 拦截行为不变；story 2 采纳 `evaluate()` 后 ≥0.8/降级才真正路由入队」。

### 5.2 富结果类型

```java
public record ModerationOutcome(
    Verdict verdict,        // PASS / TEXT_BLOCKED / IMAGE_BLOCKED / RISKY / DEGRADED
    double  riskScore,      // 0.0–1.0（DEGRADED 时为 -1 表示未知；TEXT/IMAGE_BLOCKED 时为 1.0）
    String  topCategory,    // 命中的最高风险类别（DRUGS/PORN/...，无则 null）
    boolean degraded,       // true = fail-closed 触发（超时/4xx/5xx/配额/熔断）
    String  degradeReason   // 降级原因枚举串（TIMEOUT/HTTP_4XX/HTTP_5XX/QUOTA/CIRCUIT_OPEN），非降级为 null
) {}
```

判定语义：
- **命中 L1 黑名单**（且未命中白名单）→ `TEXT_BLOCKED`，riskScore=1.0，即时硬拦截（不入队，publish 失败——即时判定不进挂起态，D-CM2）。
- **图像命中高置信违规**（§4.2 阈值：色情 ≥0.85 / 暴力 ≥0.80 / 违禁品 ≥0.75）→ `IMAGE_BLOCKED`，硬拦截。
- **未命中 L1，评分 ≥ 阈值（默认 0.8）** → `RISKY`（交调用方转人工队列，story 2/3 消费）。
- **评分 < 阈值** → `PASS`。
- **三方无法给出明确结论** → `DEGRADED`（fail-closed，见 §5.5），degraded=true。

### 5.3 服务分层与接口

```
ContentModerationService（门面，@Service，签名对调用方稳定）
 ├─ evaluate(text, imageUrls) : ModerationOutcome        // 新富接口
 ├─ moderate(text, imageUrls) : Verdict                   // 兼容 shim（§5.1）
 ├─ KeywordRuleEngine        // 词库匹配：白名单优先 > L1 黑名单 > L2 中风险加权
 └─ ContentSafetyClient（接口）
      ├─ StubContentSafetyClient   // mode=stub：无凭证，规则化打分（可测拦截/降级路径）
      └─ AliyunContentSafetyClient // mode=live：真连阿里云 TextModeration/ImageModeration
```

**`ContentSafetyClient` 接口**（隔离三方，便于 stub/live 与降级封装）：
```java
interface ContentSafetyClient {
    TextScore  scanText(String text);          // 抛 ModerationDegradedException 表降级
    ImageScore scanImage(String imageUrl);     // 同上
}
```
- `TextScore { double riskScore; String topLabel; }`、`ImageScore { Map<String,Double> labelConfidence; }`（色情/暴力/违禁品分类置信）。
- 阿里云新版 **内容安全 2022（green20220302）** `TextModerationPlus` / `ImageModeration`：返回 `labels` + `confidence`（0–100），客户端归一化到 0–1。国际站文本走 `ap-southeast-1`（新加坡）Region，覆盖印尼语见 §10。

### 5.4 词库优先级（§9.3 白名单优先）

`KeywordRuleEngine.classify(text)` 顺序：
1. 大小写归一（`toLowerCase(Locale.ROOT)`）。
2. **先查 L3 白名单**：命中的词段**从硬拦截判定中豁免**（不因其外观触发 L1）。§9.3「anjing = 狗」在宠物语境豁免。
3. L1 黑名单（SUBSTRING/EXACT/REGEX，`enabled=true`）：命中且未被白名单豁免 → L1 硬拦截。
4. L2 中风险（引流/骚扰/手机号正则）：命中不硬拦截，作为**评分加权项**并入三方评分（提升 riskScore，可能触发 RISKY）。
5. 词库从 DB 加载，进程内缓存（`@PostConstruct` 载入 + 定时/手动刷新；**不引入外部缓存中间件**，用普通 `volatile` 引用替换，护栏合规）。

> 印尼语兜底（D-CM8）：若 live 实测印尼语评分不可靠，则印尼语内容的 RISKY 判定退化为「L1/L3 词库硬匹配 + L2 加权」，不依赖三方评分——词库表已支撑，无需改结构。

### 5.5 fail-closed 降级（§4.3）

| 失败场景 | 客户端行为 | Outcome |
|---|---|---|
| 单次超时（SLA：文本 ≤1s，帖子整体 ≤2s） | 抛 `ModerationDegradedException(TIMEOUT)` | `DEGRADED` degradeReason=TIMEOUT |
| 4xx（含鉴权/参数） | 记录错误码（不记原文/密钥）→ 抛 `HTTP_4XX` | `DEGRADED` |
| 5xx | 抛 `HTTP_5XX` | `DEGRADED` |
| 配额耗尽 / 限流 | 抛 `QUOTA` + **告警日志**（WARN，无 PII/令牌） | `DEGRADED` |
| 持续宕机 | **进程内熔断器**：滑动窗口失败率超阈 → 打开熔断，窗口期内直接 `CIRCUIT_OPEN` 短路（不打三方），半开探测恢复 | `DEGRADED` degradeReason=CIRCUIT_OPEN |
| 异步任务失败（story 4/5 用） | 客户端只保证同步；重试 ≥3 次指数退避的编排留 story 4/5 | —（本 story 提供可重入的同步方法） |

- **核心不变量**：`evaluate` 在任何三方异常下**绝不返回 PASS**——异常统一映射为 `DEGRADED`，由调用方（story 2/3）按 fail-closed 转人工队列。
- 熔断器：自研极简计数器（`AtomicInteger` 滑窗 + 时间戳），**不引入 Resilience4j/Hystrix 等新依赖**（护栏：不加中间件/新框架；若团队已有 spring-retry/resilience 依赖则可用，默认自研轻量实现）。
- 超时用 `HttpClient`/SDK 超时参数 + 无阻塞回退，不新增线程池外的调度中间件。

### 5.6 配置（env 注入，绝不入库/入日志）

`application.yml`（照抄 Gemini 段模式，`app.moderation.*`）：
```yaml
app:
  moderation:
    mode: ${MODERATION_MODE:stub}                 # stub（默认，无凭证）| live
    risk-threshold: ${MODERATION_RISK_THRESHOLD:0.8}
    text-timeout-ms: ${MODERATION_TEXT_TIMEOUT_MS:1000}
    image-timeout-ms: ${MODERATION_IMAGE_TIMEOUT_MS:2000}
    aliyun:
      region: ${MODERATION_ALIYUN_REGION:ap-southeast-1}   # 国际站新加坡，覆盖印尼语
      endpoint: ${MODERATION_ALIYUN_ENDPOINT:green-cip.ap-southeast-1.aliyuncs.com}
      access-key-id: ${MODERATION_ALIYUN_AK_ID:${ALIYUN_ACCESS_KEY_ID:}}      # 可复用 OSS 的 AK
      access-key-secret: ${MODERATION_ALIYUN_AK_SECRET:${ALIYUN_ACCESS_KEY_SECRET:}}
    image-threshold:
      porn: ${MODERATION_IMG_PORN:0.85}
      violence: ${MODERATION_IMG_VIOLENCE:0.80}
      contraband: ${MODERATION_IMG_CONTRABAND:0.75}
```
`.env.example` 追加占位段（留空，L0/L1 用 stub）：
```
# --- 内容审核 (内容审核 Story 1) 阿里云内容安全 green20220302 ---
# mode=stub（默认）走打桩客户端，无凭证即可启动并验评分/降级状态机（L0/L1）；
# mode=live 才打真实阿里云内容安全（L2，需真实 AK + 印尼语实测）。AK/Secret 绝不入库/不落日志。
MODERATION_MODE=stub
MODERATION_ALIYUN_REGION=ap-southeast-1
# MODERATION_ALIYUN_AK_ID=       # 留空则复用 ALIYUN_ACCESS_KEY_ID
# MODERATION_ALIYUN_AK_SECRET=   # 留空则复用 ALIYUN_ACCESS_KEY_SECRET
# MODERATION_RISK_THRESHOLD=0.8
```

### 5.7 日志与错误（护栏）
- 审核决策日志：只记 `verdict`/`riskScore`/`topCategory`/`degradeReason`/内容 id + 长度，**严禁记原文、图 URL 签名、AK/Secret**（方案 §5.5：审核证据链另行按访问控制存储，不在业务 JSON 日志）。
- 降级/配额告警：WARN 级 JSON，字段脱敏。
- 对外错误仍走 RFC 9457 ProblemDetail（本 story 不新增对外端点，硬拦截文案沿用 `AppException.contentTextBlocked/contentImageBlocked`）。

---

## 6. 前端设计

**无（仅间接）。** 本 story 纯后端引擎替换，App 无新增/改动界面。评分/降级带来的用户可见变化（挂起态、"仅作者可见"、重试提示时机）属 story 2/3 的前端范围。本 story 交付后，现网 App 发布/评论体验**保持不变**（因 §5.1 兼容 shim）。

---

## 7. 验收标准 AC（每条标 L0/L1/L2 + 所需环境）

> 层级定义沿用 CLAUDE.md：L0 静态（无 DB/凭证）；L1 集成（Docker PG+Redis + Flyway validate）；L2 端到端（真阿里云凭证 / 印尼语实测）。**三方真连一律 L2。**

| # | 验收标准 | 层级 | 环境 |
|---|---|---|---|
| AC1 | `mvn -B package` 通过；`ContentModerationService` 保留 `Verdict moderate(text,imageUrls)` 签名，`Verdict` 追加 `RISKY`/`DEGRADED` 后既有调用方 `ContentService` 零改动编译通过 | **L0** | 无 DB/凭证 |
| AC2 | 单测：`moderate()` 兼容 shim —— 命中 L1 词返回 TEXT_BLOCKED，含违规图像标记返回 IMAGE_BLOCKED，其余（含 stub 高分/降级）返回 PASS，证明 publish 行为不变 | **L0** | 无 |
| AC3 | 单测：`evaluate()` 富语义 —— L1 命中→TEXT_BLOCKED(1.0)；评分≥阈值未命中 L1→RISKY；评分<阈值→PASS；stub 可触发全部分支 | **L0** | 无 |
| AC4 | 单测：白名单优先级 —— 文本同时含 L3 白名单词（如 `anjing`）与其黑名单外观时，不触发 L1 硬拦截（§9.3） | **L0** | 无 |
| AC5 | 单测：fail-closed —— stub 注入超时/4xx/5xx/配额/熔断各场景，`evaluate` 返回 `DEGRADED`（degraded=true，reason 正确），**绝不返回 PASS** | **L0** | 无 |
| AC6 | 单测：熔断器 —— 连续失败超阈打开熔断，窗口期内短路返回 CIRCUIT_OPEN 不打三方，半开探测恢复 | **L0** | 无 |
| AC7 | Flyway `V47` 迁移在真库 apply 成功，`ddl-auto=validate` 绿；`moderation_keyword_rules` 建表 + 种子 + 索引正确；无 CHAR(1) 陷阱 | **L1** | Docker PG + Flyway |
| AC8 | 集成：`KeywordRuleEngine` 从库加载词库（L1/L2/L3），命中判定与白名单豁免符合 §5.4 顺序；改表 enabled=false 刷新后不再命中 | **L1** | Docker PG（Redis 起） |
| AC9 | `mode=live` 真连阿里云：正常印尼语 + 英语正常文本→PASS/低分；已知违规文本→高分/L1 命中；违规图像→对应分类置信超阈拦截 | **L2** | 真实阿里云 AK + 网络 |
| AC10 | **印尼语能力实测（D-CM8）**：用 ≥1 组印尼语违规样本（毒品/赌博/色情/引流）+ 正常宠物文本，实测阿里云评分区分度；结论（可靠/需词库兜底）写入 Completion Notes；若不足，验证印尼语走词库硬匹配兜底仍能拦 L1 | **L2** | 真实阿里云 AK + 印尼语样本 |
| AC11 | live 降级实测：制造超时/无效 AK（4xx），确认返回 DEGRADED 且**未放行**，告警日志字段无 PII/AK/原文 | **L2** | 真实/伪凭证 |
| AC12 | 日志审计：抓取审核路径日志，确认无原文、无签名 URL、无 AK/Secret（护栏 §5.7） | **L1/L2** | L1 可查决策日志字段；L2 查 live 告警 |

**AC 总数：12 条**（L0×6 / L1×3（AC7/8/12-L1 面）/ L2×4（AC9/10/11/12-L2 面））。

---

## 8. 依赖与契约

- **上游依赖：** 阿里云内容安全（green20220302，国际站 ap-southeast-1）；真实 AK 可复用 OSS 段 `ALIYUN_ACCESS_KEY_*` 或独立 `MODERATION_ALIYUN_AK_*`（AK 需在阿里云开通「内容安全」产品权限）。
- **对下游契约（story 2/3/4/5）：**
  - `ModerationOutcome evaluate(text, imageUrls)` 稳定：调用方按 `verdict`（PASS/RISKY/DEGRADED/*_BLOCKED）+ `degraded` 决定放行 / 硬拦截失败 / 转人工队列。
  - `RISKY` 与 `DEGRADED` 都要求调用方**转人工队列**（story 2 帖子入 `manual_review_queue`；story 3 评论仅降级场景入队）；`*_BLOCKED` 为即时硬拦截（publish 失败，不入队）。
  - 文本审核方法可被 story 4（昵称/宠物名）复用；图像审核方法可被 story 5（头像）复用——本 story 保证方法**无状态、可重入**。
- **决策绑定：** D-CM2（通过前仅作者可见，即时判定不进挂起态）、D-CM5（fail-closed）、D-CM8（印尼语前置阻断）。方案 §4.1（词库分层）、§4.2（图像阈值）、§4.3（降级）、§9（词库初稿）。
- **护栏：** 无 MQ/新中间件；`validate`；env 凭证不入库/不入日志；Flyway 从 V47 顺延；length=1 列不建 VARCHAR(1)；错误 RFC 9457。

---

## 9. 云端（headless）执行须知

- ✅ **云端可做（L0 全绿）**：`mvn -B package`、全部单测（AC1–AC6，stub 模式无凭证）、`flutter analyze`（前端无改动，仅确认不回归）。
- ⚠️ **L1（AC7/AC8/AC12-L1）**：需 Docker PG（Redis 起）跑 Flyway validate + 词库加载集成。云沙箱不保证 Docker daemon → **默认留本地/CI**，云端只到 L0，Completion Notes 标「L1 待本地/CI」。schema 契约（含 V47 无 CHAR(1)）以 CI/L1 绿为准。
- ❌ **L2 必须回本地（AC9/10/11/12-L2）**：真实阿里云 AK + 网络 + 印尼语样本。**AC10 印尼语实测是 D-CM8 前置阻断项**——在 live 联调前先单独 spike 一组印尼语样本确认评分能力，据此定「三方评分 vs 词库兜底」的最终分级方案，再决定 story 2/3 的阈值路由。
- 云端交付物：绿灯的引擎 + stub 全分支单测 + V47 迁移 + 配置接线；live 部分在 Completion Notes 明确「待本地 L2」。

---

## 10. 风险与待确认

1. **【首要·D-CM8 前置阻断】阿里云内容安全对印尼语（Bahasa Indonesia）文本的评分区分度未证实。** 若 live 实测（AC10）表明印尼语评分不可靠 → 印尼语风险分级**退化为词库硬匹配兜底**（L1/L3 词库已支撑，评分仅中英可靠）。此结论直接影响 story 2/3 的阈值路由设计，须在本 story L2 阶段先行拍板并写入 Completion Notes / 回填方案 §7 待确认 #12。
2. **阿里云内容安全 SDK/Region 选型**：新版 green20220302（内容安全 2022）vs 旧版 green；国际站 Region（ap-southeast-1）对印尼语与图像分类的 label 体系需以实际 API 返回为准，`ImageScore` 的分类→阈值映射（色情/暴力/违禁品）落地时按真实 label 校准。
3. **AK 权限与配额**：复用 OSS 的 AK 需额外开通「内容安全」产品权限；配额耗尽会大面积触发 DEGRADED → 人工队列积压，需 story 8 的运营告警/暂停闸配合（本 story 只产出降级信号 + 告警日志）。
4. **图像阈值回归**（方案 §7 #3）：正常宠物照片误判风险，上线前需历史样本回归；本 story 阈值可配（§5.6）为回归留口，但样本回归本身在 story 5/运营侧。
5. **兼容 shim 的过渡语义**：§5.1 令 story 1 交付期间「评分/降级不实际拦截 publish」。须在 Completion Notes 显著标注，避免误判为「已上线即生效」——真正生效以 story 2 采纳 `evaluate()` 为准。
6. **色情 L1 词库缺母语初稿**：§9.1 露骨词待母语运营配置，本迁移未内置；live 模式下色情主要靠三方评分 + 图像审核兜底，纯文本色情印尼语在词库补录前可能漏报（story 8 管理台补录）。
7. **熔断器自研 vs 依赖**：为守「不加中间件」护栏采用进程内极简熔断；若项目已有 spring-retry 等依赖可替换，需在实现时确认 pom 现状（L0 阶段核查）。
