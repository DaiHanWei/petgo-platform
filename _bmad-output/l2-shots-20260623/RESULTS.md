# L2 验收结果 — 2026-06-23（安卓模拟器 × 生产 api.tailtopia.id）

- 设备：`Pixel_9_Pro_API_36`（emulator-5554，1280×2856，Google Play 镜像，账号 shawnliugj@gmail.com）
- 包：`test/l2-acceptance-20260623` 分支 debug APK（已删全部 mock），`PETGO_API_BASE_URL=https://api.tailtopia.id`，`PETGO_DEV_STUB_LOGIN=false`
- 后端：prod profile，`/actuator/health` = UP

## 结果矩阵（本轮）

| TC | 模块 | 结果 | 说明 / 印证 |
|---|---|---|---|
| 环境 | — | ✅ | 删 mock 包直连生产装机成功 |
| AUTH-03 | 鉴权 | ✅ | 游客首页渲染真实数据；`GET /api/v1/content-posts` 200（真作者/真 OSS 图）；`/api/v1/feed` 401 RFC9457（需登录，符合预期） |
| FEED-01 | 内容 | ✅ | 真实帖流、封面、Story 标渲染正常 |
| AUTH-01 | 鉴权 | ✅ | **真 Google 登录跑通**。重打包传 `GOOGLE_SERVER_CLIENT_ID=952015467016-3q9vb0ro18...`(Web client，项目 tailtopia-500108)后：账号选择器「continue to TailTopia」→ 选 shawnliugj@gmail.com → 进登录态(右上变通知铃)。后端印证：Me 页加载 `/api/v1/me` 真实用户 Shawn Liu/shawnliugj@gmail.com + 真 Google 头像 |
| ME-01 | 我的 | ✅ | Me 页真实用户卡(头像/昵称/邮箱)+「Create a profile for your pet」提示(该用户无宠物档案)+ MY POSTS 区 |
| PET-02 | 建档 | ✅ | 建档表单 PET TYPE 带「cannot be changed after creation」锁定提示 |
| PET-03 | 建档 | ✅ | 类型(Cat/Dog/Other)、品种(Kampung/Golden Retriever/Labrador…)底部选择器可选可联动(选 Dog→BREED 由「Select type first」变「Select breed」) |
| PET-01 | 建档 | ✅ | 填 L2TestMomog/Dog/Labrador/2026-06-01 提交 → 跳护照页。**DB 硬印证**：pet_profiles 新行 id=2,pet_type=DOG,breed=Labrador,birthday=2026-06-01,owner_id=22 |
| PET-04 | 建档 | ✅ | 护照卡(头像/名/品种/0y0m)+ 成就进度 1/30 + Happy Moments/Consultations/Milestone 计数(后端计算)+ Timeline |
| PET-05 | 建档 | ✅ | 护照页 Timeline/Calendar 切换、Achievements 1/30、Milestone 计数=1(里程碑数据) |
| PET-06 / G6 | 护栏 | ✅ | card_token=`09patI4c8nCJExaltdCbxn`(22位随机,非自增 id)→ 对外标识不可枚举 |

### 首轮 AUTH-01 阻塞复盘（已解决）
首次失败仅因打包漏传 `GOOGLE_SERVER_CLIENT_ID`。OAuth 三件套其实早已就位：Web client `...3q9vb0ro18...`(凭证在 `~/Downloads/client_secret_...json`)、后端 `GOOGLE_OAUTH_CLIENT_ID` 已=此 Web client(audience 本就对齐，无需改后端)、用户昨天已建 Android client(包 com.tailtopia.app)。

| FEED-02 | 内容 | 🔵 部分 | Golden Bro 帖详情顶部图带「1/3」角标=多图 pager + N/M 计数存在；横滑/全屏缩放未及验(被会话掉线打断) |

### 🔴→✅ 关键缺陷 — 会话 15min 掉线（bug#2，根因已定+已修+L0验证）
**现象**：真 Google 登录后 access(900s)过期，下一次受保护接口调用触发的静默 refresh 失败 → 清 token 落游客（每 ~15min 被踢）。
**根因**（A/B curl 实证）：`AuthInterceptor.onRequest` 给所有请求加 `Authorization: Bearer <access>`，**含 `/auth/refresh`**；access 过期后 refresh 请求带过期 Bearer 打到 permitAll 端点，后端鉴权过滤器在端点逻辑前 401 拒（refresh token 从不轮换）。同 token 同端点：带过期 Bearer→401、不带→200。触发点是受保护接口(/me、pet-profile)，公开端点(/content-posts)不触发。
**修复**：`auth_interceptor.dart` onRequest 对 `skipRefresh` 的 auth-self 请求(refresh/logout)跳过 Authorization 头。
**验证**：analyze 净 + 回归测试 + 全量 321 测试过；端到端(过期后冷启动保持登录)验证中。详见 [[petgo-session-persistence-bug]]。

### ⚠️ 观察项（待真机确认）
- **跨屏内容串扰/重影**：Me「我的发布」空态文案(「MY POSTS / You haven't posted anything yet」)串到了 Home feed 首卡 + Me 网格，跨导航稳定复现(疑 RepaintBoundary/图层复用，待真机定性)。
- **网格单元重影/叠字**：Home 首卡 + Me「MY POSTS」左格出现页面其它控件的半透明残影叠加，2s 后不消散。形态像**模拟器 GPU 帧缓冲复用伪影**，非真机必现；需真机/iOS 复核才能定性。
- 登录失败仅一闪 snackbar 无重试（首轮观察，前端待办）。

## AUTH-01 阻塞根因（待你配 Google Cloud）

Android `google_sign_in` 7.x 走 Credential Manager，真登录需要：

1. **Android OAuth client**（项目 `952015467016`）：
   - 包名：`com.tailtopia.app`
   - debug SHA-1：`D0:07:DC:96:D1:4D:7F:B1:AA:2E:AB:0C:78:42:A0:B8:B1:A0:8E:96`
   - debug SHA-256：`F2:84:8C:67:F0:8F:79:7D:C1:A1:CC:C4:1A:DF:D2:77:5A:FC:3B:AD:4F:6C:D4:D7:8C:EC:AA:D5:67:BF:46:90`
2. **Web OAuth client**：其 id 作为 app 构建的 `--dart-define=GOOGLE_SERVER_CLIENT_ID=<web client id>`。Android idToken 的 `aud` = 此 Web client。
3. **后端 audience 对齐**：`NimbusGoogleTokenVerifier` 校验 `aud.contains(petgo.auth.google.client-id)`，当前 env `GOOGLE_OAUTH_CLIENT_ID=952015467016-3q9vb0ro18...`（iOS client）。需改成上面的 **Web client id**（动生产 env → 重启 petgo-server）。

> ⚠️ UX 观察项：登录失败仅一闪而过的 snackbar，无重试/原因说明 —— 可作前端待办。

配好后把 Web client id 给我，我用 `GOOGLE_SERVER_CLIENT_ID=<id>` 重打包继续跑登录态用例。
