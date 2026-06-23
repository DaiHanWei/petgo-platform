# 任务 Handoff — 腾讯 IM SDK 集成 + 真机 IM L2 验收

> 自包含交接(新 session 无前序上下文)。目标:打通**兽医↔用户实时聊天**并 L2 真机验收。
> 当前分支 `test/l2-acceptance-20260623`(从最新 main 切;已删全部 mock 子系统;已修「会话 15min 掉线」bug)。

## 现状(均已核实)

**后端 IM 基本完备,勿重造:**
- `petgo-backend/.../shared/im/LiveTencentImClient.java`:纯 JDK 完成 UserSig 签名(TLSSigAPIv2 / HMAC-SHA256)+ REST,**无需后端引腾讯 SDK**。
- `GET /api/v1/im/usersig`(Story 5.5):已鉴权;**MAU 闸门**——非 VET 用户须有进行中会话否则 403(`ImUserSigController`)。
- 配置 `app.im`(application.yml):`mode`/`sdk-app-id`/`secret-key`/`user-sig-ttl-seconds`/`callback-token`。
- 生产 `~/.env.petgo`(主机 62.146.239.156):当前 **`IM_MODE=stub`**,`TENCENT_IM_SDK_APP_ID=20043419`,`TENCENT_IM_SECRET_KEY` 已配,`TENCENT_IM_CALLBACK_TOKEN` 空。
- 护栏:SecretKey 仅后端持有,**绝不下发客户端 / 不入日志**;UserSig 短时。

**缺口在 Flutter 客户端:**
- `petgo_app/lib/core/im/im_service.dart`:`LiveImService` 是骨架——能经 `ApiPaths.imUserSig` 取 UserSig,但 **5 处 `TODO(L2)` 未接 SDK**:`loginIfNeeded`(SDK login)/`sendText`/`sendImage`/`onMessages`(监听 onRecvNewMessage→ImMessage 流)/`logout`。
- `imServiceProvider` 已恒返 `LiveImService`(MockImService 已随 mock 删除)。
- `pubspec.yaml` **无** `tencent_cloud_chat_sdk` 依赖。

**环境:**
- 生产 `vet_accounts` 表为空(无兽医账号);兽医走账密登录(登录页有「Vet sign-in」)。
- 媒体留 IM 不落 OSS/后端(见 sendImage 注释)。

## 目标(L2 done 判据)

1. `pubspec` 加 `tencent_cloud_chat_sdk` + iOS/Android 原生配置(权限/最低版本)。
2. 实现 `LiveImService` 5 个 TODO:用后端取的 UserSig 做 SDK `login`,接 `sendText/sendImage`,`onMessages` 桥接 `addAdvancedMsgListener.onRecvNewMessage → ImMessage` 流,`logout`。**前端不自签 UserSig、不硬编码 SecretKey。**
3. 后端切 `IM_MODE=live`(**动生产 env + 重启 petgo-server 前先跟用户确认**;或起独立测试实例同理)。
4. 建一个兽医账号(写 prod `vet_accounts`,bcrypt 密码),把用户名/密码交用户。
5. **L2 真机验收**:真机登录兽医 + 模拟器(用户 shawnliugj@gmail.com)发起问诊会话(`consult_sessions`)→ 双向收发消息验通(**CON-02**)→ 结束后评分落库(**CON-03**,`consult_ratings`)。

## 执行须知

- **L2 任务**:需真机 + 模拟器 + 真腾讯 IM,云端 headless 只到 L0(`flutter analyze`/`flutter test`、`mvn -B package`)。
- 真 Google 登录构建参数(用户侧 app):
  ```
  flutter build apk --debug \
    --dart-define=PETGO_API_BASE_URL=https://api.tailtopia.id \
    --dart-define=PETGO_DEV_STUB_LOGIN=false \
    --dart-define=GOOGLE_SERVER_CLIENT_ID=952015467016-3q9vb0ro18fnecl9gpnrddbfj9snqer0.apps.googleusercontent.com
  ```
- adb 驱动模拟器可靠;flutter run 才能抓 `I/flutter` 日志(adb install 包 logcat 抓不到 debugPrint)。
- 生产主机只读核查可直接做,**改容器/env/profile 前先跟用户确认**。
- 相关记忆(新 session 的 MEMORY.md 会自动载):IM/Gemini/L2 验收约定/生产部署/mock 已删 等。
- L2 验收口径与首轮结果:`docs/L2-acceptance-emulator-real-backend.md`、`_bmad-output/l2-shots-20260623/RESULTS.md`。
