---
title: 'Story 5.5 增量：腾讯 IM live 真实接入 + 按需登录控 MAU'
type: 'feature'
created: '2026-06-15'
status: 'ready-for-dev'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/5-5-腾讯-im-会话与图文对话界面.md'
  - '{project-root}/_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 5.5 的 IM 只有 stub（`ImConfig` 的 live 分支启动即抛错），无法真实聊天；且腾讯 IM 按「当月成功 SDK login 的去重用户」计 MAU（国际站 Trial 免费额度小，超出 $0.05/MAU/月），若全体 App 用户都登录 IM 会失控。

**Approach:** 实现 `LiveTencentImClient`（TLSSigAPIv2 签 UserSig + REST 建号/系统消息）并按 `petgo.im.mode=live` 装配；按「login 即 MAU」的本质做按需登录——兽医建号即 REST 导入 IM、App 上线即 login；用户默认不 login，仅在「进行中咨询」时由后端硬门控签发 UserSig 才能 login。前端集成腾讯 IM Flutter SDK 替换占位聊天面，双端 C2C 图文实时收发。

## Boundaries & Constraints

**Always:**
- SecretKey 仅后端 env，绝不下发客户端 / 绝不入日志；UserSig 短时（`user-sig-ttl-seconds`）。
- MAU 控制点 = 「谁 / 何时调 SDK login」：兽医上线 login、用户仅进行中咨询 login。**账号导入不计 MAU**，故可对用户提前导入以便系统消息落地，但绝不替用户 login。
- `/api/v1/im/usersig` 用户态硬门控：非 VET 角色须有 `IN_PROGRESS`/`PENDING_CLOSE` 会话才签发，否则 403；VET 角色恒签。
- 后端不持 IM 长连接、不中转聊天媒体（媒体留 IM，不落 OSS / 后端 / 日志）；实时收发由 Flutter SDK 直连。
- IM 账号映射沿用 `u_<userId>` / `v_<vetId>`（`ImAccountMapper`），禁散落。
- stub/live 经 `petgo.im.mode` 切换；前端 mock 模式（`PETGO_MOCK`）不触真实 SDK。
- REST 域名 / 数据中心走配置（选德国 / 欧洲，与后端同区）。

**Ask First:**
- 会话模型选 **C2C 单聊**（默认，一对一咨询）；若改 Group 需重做建会话 / 成员管理 → 停下确认。
- 引入新 Flutter 依赖 `tencent_cloud_chat_sdk`（版本 / 体积 / 原生权限）→ 加依赖前停下确认。
- 若 Trial MAU 免费额度不足 ≤500 DAU 演示需升付费档（涉及费用）→ 停下确认。

**Never:**
- 不引入 MQ / 通用缓存 / 新中间件；状态迁移仍用 DB CAS。
- 不做会话结束 / 评分（5.6）、封禁中断（5.7）、IM→OSS 存档（5.6/Epic2）、独立推送系统（Epic6）。
- 不做用户↔用户聊天（V1 仅用户↔兽医）；不做视频（F4，仅文字 + 图片）。
- 不在前端硬编码 SecretKey 或自签 UserSig；不为省 MAU 让兽医改成仅咨询中 login（已决策上线即 login）。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 用户取 UserSig（有进行中咨询） | `GET /im/usersig`，role=USER，存在 IN_PROGRESS/PENDING_CLOSE 会话 | 200 真实 UserSig（无 `stub-` 前缀），`imUserId=u_<id>` | — |
| 用户取 UserSig（无活跃咨询） | role=USER，无 IN_PROGRESS/PENDING_CLOSE | 403 ProblemDetail | 不签发，锁死无关用户吃 MAU |
| 兽医取 UserSig | role=VET | 200 真实 UserSig，`imUserId=v_<id>` | 恒签 |
| 未登录取 UserSig | 无 / 损坏 JWT | 401 ProblemDetail | unauthorized |
| 兽医建号 | `AdminVetService.create` 成功 | REST 导入 `v_<vetId>`（幂等，不计 MAU） | 导入失败仅记非敏感日志，**不阻断建号**（首次 usersig/login 前可再幂等导入） |
| 接单建会话 | accept CAS 成功 | ensureAccount `u_<userId>` + 落 `im_conversation_id`(C2C) + 发系统消息「兽医已接受你的问诊」 | IM 失败不回滚已成接单，记日志、系统消息可后补 |
| 用户进入进行中会话 | 前端 status=IN_PROGRESS | 取 UserSig → SDK login → 打开 C2C（peer=`v_<vetId>`），离开/结束 logout | 取 sig 403/网络失败 → 提示重试，不崩 |

</frozen-after-approval>

## Code Map

- `petgo-backend/.../shared/im/TencentImClient.java` — 接口，新增 `ensureAccount(imUserId, displayName)`
- `petgo-backend/.../shared/im/LiveTencentImClient.java` — **新增**：TLSSigAPIv2 签 UserSig + REST 建号/系统消息（纯 JDK：`Mac`/`Deflater`/`Base64` + Spring `RestClient`）
- `petgo-backend/.../shared/im/StubTencentImClient.java` — 补 `ensureAccount` 空实现（记非敏感日志）
- `petgo-backend/.../shared/im/ImConfig.java` — live 分支用 `LiveTencentImClient` 替换 `throw`
- `petgo-backend/.../shared/im/ImProperties.java` — 新增 `restBaseUrl`、`adminIdentifier`
- `petgo-backend/.../shared/im/web/ImUserSigController.java` — 注入会话查询，加用户态闸门（非 VET 须有活跃会话否则 403）
- `petgo-backend/.../consult/repository/ConsultSessionRepository.java` — 复用 `findFirstByUserIdAndStatusInOrderByCreatedAtDesc(userId, [IN_PROGRESS,PENDING_CLOSE])`
- `petgo-backend/.../admin/service/AdminVetService.java` — `create()` 后 hook `ensureAccount(vetImId, displayName)`
- `petgo-backend/.../consult/service/ConsultAcceptService.java` — createConversation 前 `ensureAccount(userImId)`（既有调用点）
- `petgo-backend/.../shared/security/SecurityConfig.java` — 显式 `/api/v1/im/usersig` authenticated
- `petgo-backend/src/main/resources/application.yml` + `.env.example` + `.env` — 加 `IM_REST_BASE_URL`、`IM_ADMIN_IDENTIFIER`
- `petgo_app/pubspec.yaml` — 加 `tencent_cloud_chat_sdk`
- `petgo_app/lib/core/im/im_service.dart` — **新增**：SDK 封装（init/login/logout/send/监听）+ Riverpod provider，UserSig 取自 `/im/usersig`，mock 下空转
- `petgo_app/lib/features/consult/presentation/im_chat_placeholder.dart` — 占位 → 真实 IM 收发组件（保留视觉，文字 + 图片）
- `petgo_app/lib/features/consult/presentation/consult_conversation_page.dart` — IN_PROGRESS 时 login + 传真实 `imConversationId`/peer，离开 logout
- `petgo_app/lib/features/vet/presentation/vet_me_page.dart` + `data/vet_repository.dart` — 上线 login IM / 下线 logout IM
- `petgo_app/lib/features/vet/presentation/vet_conversation_page.dart` — 替换占位为真实组件，AI 卡 + 辅助面板不动

## Tasks & Acceptance

**Execution:**
- [ ] `shared/im/TencentImClient.java` -- 加 `ensureAccount(imUserId, displayName)` 接口方法 -- 兽医/用户按需建号
- [ ] `shared/im/LiveTencentImClient.java` -- 新增 live 实现（UserSig 签名 + REST 建号/系统消息/删媒体/回调校验）-- 真实 IM 接入主体
- [ ] `shared/im/StubTencentImClient.java` -- 补 `ensureAccount` 桩 -- 保持 stub L0 绿
- [ ] `shared/im/ImConfig.java` + `ImProperties.java` -- live 装配 `LiveTencentImClient` + 加 restBaseUrl/adminIdentifier -- 去掉抛错
- [ ] `shared/im/web/ImUserSigController.java` -- 注入会话查询加用户态 403 闸门 -- MAU 硬门控
- [ ] `admin/service/AdminVetService.java` + `consult/service/ConsultAcceptService.java` -- 建号 hook -- 兽医建号即导入 / 接单导入用户
- [ ] `shared/security/SecurityConfig.java` -- /im/usersig 显式 authenticated -- 鉴权收口
- [ ] `application.yml`/`.env.example`/`.env` -- 加 REST 域名 + admin 标识 -- live 配置
- [ ] `test/.../shared/im/LiveTencentImClientTest.java` + `ImUserSigGateTest`(或 controller 切片) -- 单测 UserSig 签名确定性（注入固定时间）+ 闸门 403/恒签矩阵 -- 覆盖 I/O Matrix
- [ ] `petgo_app/pubspec.yaml` + `core/im/im_service.dart` -- 加 SDK + 封装 provider（mock 空转）-- 前端 IM 能力
- [ ] `consult/.../im_chat_placeholder.dart` -- 占位换真实收发（文字 + 图片，保留视觉）-- 双端共用聊天核心
- [ ] `consult/.../consult_conversation_page.dart` + `vet/.../vet_conversation_page.dart` -- 接入真实会话 + 传 peer，AI 卡保留 -- 双端对话壳
- [ ] `vet/.../vet_me_page.dart` + `vet_repository.dart` -- 上线 login / 下线 logout IM -- 兽医按需登录

**Acceptance Criteria:**
- Given role=USER 且有 IN_PROGRESS 会话，when `GET /api/v1/im/usersig`，then 返回真实 UserSig（无 `stub-` 前缀）。
- Given role=USER 且无活跃会话，when `GET /api/v1/im/usersig`，then 403，不签发。
- Given `IM_MODE=live`，when 应用启动，then 装配 `LiveTencentImClient` 且不抛错。
- Given 新建兽医账号，when `AdminVetService.create` 成功，then 幂等 REST 导入 `v_<vetId>`，导入失败不阻断建号。
- Given 兽医接单成功，when 建会话，then 用户 IM 账号已 ensure、`im_conversation_id` 落库、系统消息发出。
- Given 前端 mock 模式，when 进入会话，then 不调用真实 SDK、不取真实 UserSig（占位/演示路径仍可跑）。

## Design Notes

- **UserSig 签名（TLSSigAPIv2）**：content = `TLS.identifier|sdkappid|expire|time|base64(sig)`；`sig = HMAC-SHA256(secretKey, "TLS.identifier:...\nTLS.sdkappid:...\nTLS.time:...\nTLS.expire:...\n")`，整体 JSON → `Deflater`(zlib) → base64url。纯 JDK，无需引腾讯库。单测注入固定 `time` 即可断言确定性输出（L0）。
- **C2C 会话 id 约定**：`createConversation` 返回 `c2c-u_<userId>-v_<vetId>` 落 `im_conversation_id`；C2C 无需服务端建会话，客户端按对端 userID 开会话（用户 peer=`v_<vetId>`，兽医 peer=`u_<userId>`，从 session view 的 role/对端字段推导）。
- **建号 vs MAU**：`ensureAccount`（REST `account_import`，幂等）不计 MAU；只有客户端 `TIM.login` 计 MAU。系统消息要求目标账号存在，故 accept 时先 ensure 用户账号，但绝不替用户 login。
- **前端 SDK 封装**：`im_service.dart` 暴露 `loginIfNeeded()/logout()/sendText/sendImage/onMessages`，内部判 `PETGO_MOCK`——mock 时全部空转返回本地占位，真机 live 时走 SDK，使 widget 测试无需真实 SDK（L0 绿）。
- **导入失败不阻断**：建号/接单的 IM 调用失败只记非敏感日志，不回滚业务（接单 CAS 已成）；兽医首次取 usersig/login 前可再幂等导入兜底。

## Verification

**Commands:**
- `cd petgo-backend && ./mvnw -B -q package` -- expected: 编译 + 单测通过（含 LiveTencentImClient 签名确定性、usersig 闸门矩阵）
- `cd petgo_app && flutter analyze` -- expected: No issues found
- `cd petgo_app && flutter test` -- expected: 全绿（im_service mock 空转，widget 不触真实 SDK）

**Manual checks (L1，本地 Docker pg+redis):**
- 接单 WAITING→IN_PROGRESS + `im_conversation_id` 落库；role=USER 无活跃会话取 usersig 得 403、有会话得 200；role=VET 恒 200。

**Manual checks (L2，本地真机 + 真实 SDKAppID/SecretKey，数据中心德国/欧洲):**
- 兽医上线 login、用户进咨询 login；双端 C2C 收发文字/图片；切 Tab 后台保连消息连续；系统消息「兽医已接受」用户侧可见；腾讯控制台 MAU 仅随实际咨询用户增长（无关用户不计）。

## 云端执行须知（headless，L0-only —— 给云端 dev agent）

> 你是云端 session，无本对话上下文；本节即交接全部要点。

- **凭证**：腾讯 IM 真实 SDKAppID(`20043419`)/SecretKey 已在**本地** `petgo-backend/.env`（gitignored，云端取不到也**不需要**）。云端一律 `IM_MODE=stub` 启动跑 L0；真实凭证只用于本地 L2。
- **云端只跑 L0 且必须绿**：`cd petgo-backend && ./mvnw -B -q package`；`cd petgo_app && flutter analyze && flutter test`。
- **L0 真验的核心**：`LiveTencentImClient` 的 **UserSig 签名是确定性的**——单测注入固定 `time`，断言 HMAC-SHA256 + zlib + base64url 输出稳定，这块在云端就是真验、非占位。`/im/usersig` 用户态闸门（403/恒签矩阵）亦 L0 可验。
- **L1/L2 待本地**（云端做不到，代码写全 + 标注即可）：真实 IM REST 建号/系统消息、真机双端 C2C 收发文字/图片、切 Tab 后台保连、兽医上线 login、控制台 MAU 观测。
- **护栏复述**：SecretKey 绝不下发/入日志；UserSig 短时；后端不持长连接 / 不中转聊天媒体；禁 MQ/缓存/新中间件；状态迁移用既有 DB CAS（**勿动 5.3 抢单逻辑**）；前端 `PETGO_MOCK` 路径下 `im_service` 空转、不触真实 SDK（保 widget 测试绿）。
- **Flyway**：本增量**无新表 / 无新列**（复用既有 `consult_sessions`/`vet_accounts`），勿新增迁移；如确需，按决策 E2 顺延新号、勿照搬示例号。
- **新依赖需停（Ask First）**：`pubspec.yaml` 加 `tencent_cloud_chat_sdk`——若版本无法解析或 `flutter pub get` 失败，**先以接口/封装占位 `im_service` 让 analyze/test 绿**，在 Completion Notes 标注 SDK 集成待本地，勿因拉不到包卡死整批。
- **完工**：更新本文件 File List + Completion Notes（记关键决策与「L1/L2 待本地」）；并在 sprint-status 的 `5-5-腾讯-im-会话与图文对话界面` 备注补「live 增量 L0 绿」。
