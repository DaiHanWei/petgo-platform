---
title: 'AB-22 后台「App 版本配置」卡片'
type: 'feature'
created: '2026-10-14'
status: 'done'
baseline_commit: '50bb5679'
context:
  - '{project-root}/_bmad-output/planning-artifacts/v1.3.4/决策日志-app.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** FR-124 已把版本配置搬进 `app_version_config` 单行表，App 已接线，但后台没有任何页面能改它，每次发版都要开发手写 SQL；PRD 要求 AB-22 与 FR-124 同批发版。

**Approach:** 在现有「运营配置」页（`/admin/config`）新增一张「App 版本配置」卡片，五个字段可查看 / 编辑，保存走 app 主题已有的 `AppVersionConfigService.update` 校验，后台补变更日志 + 审计；「最低支持版本」变更须再输一遍新版本号确认。后台 PRD 与 UI 稿不在仓库，按现有配置卡片风格自行设计（用户 2026-10-14 拍板）。

## Boundaries & Constraints

**Always:**
- 权限复用 `config.view`（看）/ `config.edit`（改），不新增权限码（同客服联系方式卡先例）。
- 校验唯一口径 = `AppVersionConfigService.update` 的四个错误码；后台不重复写校验逻辑，只新增「确认值不符」一个码。
- 无变化保存 → 不写库、不写日志、不审计（同其它配置卡）。
- 新增文案四份 bundle 齐全（基线 / en / zh_CN / id）。
- Flyway 新迁移用时间戳号；`ck_config_change_type` DROP + ADD 全量重列，值取当前树最后一条重建它的迁移（`V20260916_0925`）再加 `APP_VERSION`。

**Ask First:** 需要改 App 端接口、`AppVersionConfigService` 已有校验语义或新增权限码时。

**Never:** 不做 AB-30 / AB-31；不改 `/api/v1/app-version` 契约；不加缓存；不改已应用迁移。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error Handling |
|---|---|---|---|
| 改最新版本号 | iOS/Android latest 1.3.3→1.3.4 | 保存成功 toast；GET /api/v1/app-version 立即返回新值；每个变更字段一条 `config_change_logs`(APP_VERSION) + 一条审计 `CONFIG_UPDATE_APP_VERSION` | — |
| 未改任何值 | 原样提交 | 成功提示，不写库 / 日志 / 审计 | — |
| 版本格式错 | `1.3`、`01.2.3` | 422 卡内报错，对应输入框标红 | `admin.err.config.version.format` |
| 最低 > 最新 | min 1.4.0，latest 1.3.4 | 422 | `version.min-gt-latest` |
| 上调最低但链接空 | min 1.1.0，任一 store url 空 | 422 | `store-url.blank` |
| 链接非 https | `http://…` | 422 | `store-url.scheme` |
| 改最低未确认 / 确认不符 | min 改了，`minConfirm` 空或 ≠ 新值 | 422，不写库 | 新码 `admin.err.config.version.min-confirm` |
| 只读账号 | 仅 `config.view` | 卡片只读；POST 403 | — |
| 商店链接 512 字符 | 合法长 https 链接 | 保存成功，变更日志完整记录 | — |

</frozen-after-approval>

## Code Map

- `petgo-backend/src/main/java/com/tailtopia/admin/config/web/AdminConfigController.java` -- 卡片 GET 填充 (`populateCards`)、`ERROR_FIELDS`、POST 端点范式（support-contact L242-262）
- `.../admin/config/service/AdminConfigService.java` -- `diff` / `commit` / `require` 范式（L464-527）
- `.../config/service/AppVersionConfigService.java` + `AppVersionConfigUpdate.java` -- 已有校验与写入（app 主题）
- `.../config/domain/ConfigChangeLog.java` -- `ConfigType` 枚举
- `petgo-backend/src/main/resources/templates/admin/config.html`、`fragments/config-card-support.html`、`fragments/tpl-d-config-card.html` -- 卡片模板范式
- `petgo-backend/src/main/resources/static/admin/admin-core.js` -- `data-confirm-diff` 确认框
- `petgo-backend/src/main/resources/i18n/messages{,_en,_zh_CN,_id}.properties`
- `petgo-backend/src/test/java/com/tailtopia/admin/config/AdminConfigTemplateDIntegrationTest.java` -- 集成测试范式

## Tasks & Acceptance

**Execution:**
- [x] `db/migration/V<创建时刻>__config_change_log_app_version.sql` -- `config_change_logs.old_value/new_value` 加宽到 VARCHAR(512)；重建 `ck_config_change_type` 为 6 值（原 5 + `APP_VERSION`） -- 商店链接最长 512，原 64 会让写日志 500
- [x] `ConfigChangeLog.java` -- `ConfigType` 加 `APP_VERSION`，列长注解同步 512
- [x] `AdminConfigService.java` -- 新增 `updateAppVersion(AppVersionConfigUpdate cmd, String minConfirm, long adminId)`：读当前行 → 逐字段 diff（值先 trim）→ 无变化返回 → min 变化且 `minConfirm.trim()` ≠ 新 min 即抛新码 → 调 `AppVersionConfigService.update` → `commit(logs, adminId, "APP_VERSION", "app_version_config")`；同一事务
- [x] `AdminConfigController.java` -- `populateCards` 加 `appVersion`；`ERROR_FIELDS` 加 `appVersion` 映射五个码到对应字段（min-confirm → `minConfirm`）；`POST /admin/config/app-version`（`EDIT_AUTH`，htmx + PRG 两路同 support-contact）
- [x] `templates/admin/fragments/config-card-app-version.html` + `config.html` -- 卡片放在客服联系方式卡之后：iOS 最新版本、Android 最新版本、最低支持版本、iOS 商店链接、Android 商店链接；最低支持版本旁高危说明；「再次输入最低支持版本」输入框仅在最低版本被改动时显示（JS 比较初值），服务端仍以 422 兜底；`confirmDiff=true`
- [x] 四份 `messages*.properties` -- 卡片标题 / 字段 / 说明 / 按钮 / 保存 toast / `admin.err.config.version.min-confirm`
- [x] `src/test/.../admin/config/AdminAppVersionConfigIntegrationTest.java` -- 覆盖 I/O 矩阵全部行 + htmx 成功头 + PRG 重定向 + 审计 / 日志计数；`snapshot()/restore()` 还原单行表

**Acceptance Criteria:**
- Given 超管登录, when 打开 `/admin/config`, then 看到「App 版本配置」卡片，显示表内当前五个值。
- Given 运营把最低支持版本从 1.0.0 改为 1.1.0 且两链接已填, when 确认框输入 1.1.0 并保存, then 保存成功且 App 冷启动收到强制更新判定所需的新值。
- Given 保存成功, when 查看后台审计日志, then 有一条 `CONFIG_UPDATE_APP_VERSION`，摘要为改动字段数。

## Design Notes

- 不另建 Service：后台的 diff / 日志 / 审计放 `AdminConfigService`，校验与写入委托 `AppVersionConfigService.update`（AD-1 / AD-10 分工）。`update` 自身不判无变化，所以无变化判断必须在调用它之前。
- 「再输一遍」用服务端确认字段而不是 `window.prompt`：可测、可审计、不怕绕过前端。
- 「最低支持版本」只在改动时要求确认，降回 1.0.0 也算改动、同样要确认（防误操作）。

## Verification

**Commands:**
- `cd petgo-backend && mvn -B clean package -DskipTests` -- expected: BUILD SUCCESS
- `cd petgo-backend && mvn -B test -Dtest='AppVersionConfig*Test,AdminConfigServiceTest,AdminMessagesExternalizedTest'` -- expected: 全绿（L0）
- `bash scripts/ci/check-flyway-versions.sh origin/main` -- expected: 通过
- L1：`AdminAppVersionConfigIntegrationTest`、`AdminConfigTemplateDIntegrationTest` 本地 Docker 跑绿

**Manual checks:**
- 本地起后台打开 `/admin/config`：卡片样式与相邻卡片一致；改最低版本时确认框出现；三语切换无裸 key。

## Suggested Review Order

**保存流程（入口）**

- 后台 diff / 确认值 / 委托 app 主题校验写入 / 日志 + 审计，全在这一个方法
  [`AdminConfigService.java:536`](../../../petgo-backend/src/main/java/com/tailtopia/admin/config/service/AdminConfigService.java#L536)

- POST 端点：htmx 局部换卡 + 非 htmx PRG 两路
  [`AdminConfigController.java:286`](../../../petgo-backend/src/main/java/com/tailtopia/admin/config/web/AdminConfigController.java#L286)

- 错误码 → 标红字段映射（min-gt-latest 标三个版本框）
  [`AdminConfigController.java:91`](../../../petgo-backend/src/main/java/com/tailtopia/admin/config/web/AdminConfigController.java#L91)

**数据库**

- 变更日志两列加宽 512 + 约束全量重列 6 值
  [`V20261014_2304__config_change_log_app_version.sql:1`](../../../petgo-backend/src/main/resources/db/migration/V20261014_2304__config_change_log_app_version.sql#L1)

- 枚举加 APP_VERSION，列长注解同步
  [`ConfigChangeLog.java:26`](../../../petgo-backend/src/main/java/com/tailtopia/config/domain/ConfigChangeLog.java#L26)

**页面与交互**

- 卡片五字段 + 慎改说明 + 确认框
  [`config-card-app-version.html:12`](../../../petgo-backend/src/main/resources/templates/admin/fragments/config-card-app-version.html#L12)

- 确认框只在最低版本改动时展开；页面过期回 422 时强制展开
  [`admin-core.js:1012`](../../../petgo-backend/src/main/resources/static/admin/admin-core.js#L1012)

- 卡片挂到运营配置页客服卡之后
  [`config.html:37`](../../../petgo-backend/src/main/resources/templates/admin/config.html#L37)

**测试**

- I/O 矩阵全部 9 行 + htmx / PRG / 审计计数
  [`AdminAppVersionConfigIntegrationTest.java:1`](../../../petgo-backend/src/test/java/com/tailtopia/admin/config/AdminAppVersionConfigIntegrationTest.java#L1)
