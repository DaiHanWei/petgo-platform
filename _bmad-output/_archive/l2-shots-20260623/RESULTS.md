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

| FEED-02 | 内容 | ✅ | 多图详情 pager 横滑（角标 1/3→2/3 换图）+ N/M 计数。全屏缩放未单独验 |
| FEED-03 | 内容 | ✅ | 登录态发评论成功。DB 印证：comments id=2,post_id=94,author_id=22,body="L2-test-comment-halo" |
| PUB-01 | 内容 | ✅ | 发布编辑页（分类 Daily/Knowledge/Growth、Photos max9、文本 0/1000、Post）。发纯文本帖成功 |
| PUB-02 | 内容 | ✅(done态) | 「Posted successfully🎉」结果页 + 预览卡 + View in Feed/Back to Home。DB：content_posts id=210,type=DAILY,**status=PUBLISHED**(直发)。reviewing/rejected 态未触发 |
| CON-01 | 问诊 | ✅ | Consult 双卡(Ask AI Triage / Chat with vet)+ My consultations 真实历史 |
| TRI-01 | 分诊 | ✅ | 上传页：虚线加图框(max3)、症状 0/500、免责声明、Analyze now |
| TRI-02/03 | 分诊 | ✅ | **真打 Gemini live** 跑通：返回结构化绿色结果(SYMPTOM SUMMARY/HOME CARE/免责/Save to health notes/Still want to consult a vet/Done)。DB：triage_tasks id=3 status=DONE danger_level=GREEN response_locale=en |
| TRI-05 | 分诊 | ✅ | 超时整页 P-21b(「Analysis is taking longer than usual」+ Resubmit)正确呈现 |
| TRI-06 | 分诊 | ⚠️ | 危急症状 Gemini 连续 503 ServiceUnavailable→后端 retry4 次 FAILED；客户端 **fail-closed 正确**(不误判「安全」),但仅显示超时/Resubmit,未显式软引导兽医。属外部瞬时(非 app bug) |
| TRI-04 | 分诊/护栏 | ✅ | **红色态硬过**(task id=8 DONE danger_level=RED,巧克力中毒急症)。红底警告页「Segera bawa <宠物名> ke rumah sakit hewan」+ 急救三步,Gemini 正确判 RED |
| G1 红色态零变现 | 护栏 | ✅ | 红色结果页**无任何付费/转化/升级入口**,唯一动作「Saya mengerti」 |
| G2 只升不降不可绕过 | 护栏 | ✅ | 措辞不软化;「Saya mengerti」按钮**读完警告才激活**(Tombol aktif setelah membaca peringatan)防秒跳;全程交棒线下急诊 |

> **Gemini 503 真相(深查)**:gemini-2.5-flash **间歇性**过载(连测 3 次=200/503/200,~1/3 撞 503「high demand」UNAVAILABLE),**非费用/网络/key 问题**(flash-lite/flash-latest 同 key 均 200)。后端 4 次重试间隔太近(~2s/14s 内)赶上尖峰即 4 连挂。**建议 story**:重试退避拉长+jitter；503 时 fallback gemini-2.5-flash-lite。本次重试 1 次即拿到 RED。i18n 小观察:response_locale 跟设备 locale(en)非应用内语言(id)。
| ME-03 | 我的 | ✅ | 设置页(通知/隐私开关、语言、版本 Build 100、登出、注销入口) |
| ME-02 | 我的 | ✅ | 语言切 Bahasa Indonesia 文案实时变(Language→Bahasa、tab→Semua/Harian/Tumbuh/Edukasi、nav→Beranda/Konsultasi/Saya)；**冷重启后仍印尼语=持久化生效** |
| CON-02 | 问诊 | ⏭️ | 兽医会话:无兽医在线态渲染正确(「Belum ada dokter hewan online」08-23 工作日 + 降级引导 AI triage)。IM_MODE=stub + prod 兽医账号=0 → 实时收发跳过 |
| CON-03 | 问诊 | ⏭️ | 评分依赖完成的兽医会话,无法触达 |
| VET-01~03 | 兽医 | ⏭️ | prod `vet_accounts`=0、无兽医角色可登录 → 工作台/在线状态/请求富化全跳过(环境限制非 app 问题) |
| DEL-01 | 注销/护栏 | ✅ | Hapus akun 页:删除清单 5 项(宠物档案/成长里程碑/帖子评论/问诊历史/Google数据)+ **30天宽限可登录撤销** + **邮箱确认守卫**。守卫实测:输错误邮箱删除按钮恒 enabled=false(非破坏验证) |
| DEL-02 / G3 | 注销/护栏 | ⏸️ 暂缓 | 真删会级联删 shawnliugj(IM 测试还要用该账号)+ 破坏性 → 暂不执行。后端级联删除/匿名化(D1/D2)待用专用测试账号验 |

| NOTIF-01 | 通知 | ⚠️ | 通知页+空态正确(「Belum ada notifikasi」);列表空(无他人互动)→deep-link 无从点;真推送 FCM 属 §6 |
| MED-01 | 媒体 | ✅ | 相册选图(规范申请媒体权限)→处理→预签名上传→OSS 真存。DB content_posts id=211 image_urls=OSS真链;curl 该对象 HTTP200 image/jpeg 317KB |
| MED-02 | 媒体 | ✅ | OSS 图片处理缩略图:w_240→23.6KB、w_1080→211KB(原图317KB)→取缩略非原图 |
| G5 日志无PII | 护栏 | ✅ | 后端日志近30min grep 症状/邮箱/JWT/Bearer/OSS签名/refreshToken/idToken = 0 命中 |
| G4 RFC9457 | 护栏 | ✅ | 多处 401(/feed、/auth/refresh)响应体 type/title/status/detail/instance 齐,不外泄堆栈 |

> **IM 实时会话(CON-02 + 真机兽医测)阻塞根因**:app **未集成腾讯 IM SDK**(pubspec 无 `tencent_cloud_chat_sdk`,`LiveImService` 5 处 TODO 仅骨架)+ 后端 IM_MODE=stub + prod 无兽医账号。真机↔模拟器 IM 测试需先做 SDK 集成(开发任务),非仅配账号。用户计划:其余测完后再定(集成后测 / 或 IM 整体留后续)。

### 🔴→✅ 关键缺陷 — 会话 15min 掉线（bug#2，根因已定+已修+L0验证）
**现象**：真 Google 登录后 access(900s)过期，下一次受保护接口调用触发的静默 refresh 失败 → 清 token 落游客（每 ~15min 被踢）。
**根因**（A/B curl 实证）：`AuthInterceptor.onRequest` 给所有请求加 `Authorization: Bearer <access>`，**含 `/auth/refresh`**；access 过期后 refresh 请求带过期 Bearer 打到 permitAll 端点，后端鉴权过滤器在端点逻辑前 401 拒（refresh token 从不轮换）。同 token 同端点：带过期 Bearer→401、不带→200。触发点是受保护接口(/me、pet-profile)，公开端点(/content-posts)不触发。
**修复**：`auth_interceptor.dart` onRequest 对 `skipRefresh` 的 auth-self 请求(refresh/logout)跳过 Authorization 头。
**验证**：analyze 净 + 回归测试 + 全量 321 测试过；端到端(过期后冷启动保持登录)验证中。详见 [[petgo-session-persistence-bug]]。

### ⚠️ 观察项（待真机确认）
- **跨屏内容串扰/重影**：Me「我的发布」空态文案(「MY POSTS / You haven't posted anything yet」)串到了 Home feed 首卡 + Me 网格，跨导航稳定复现(疑 RepaintBoundary/图层复用，待真机定性)。
- **网格单元重影/叠字**：Home 首卡 + Me「MY POSTS」左格出现页面其它控件的半透明残影叠加，2s 后不消散。形态像**模拟器 GPU 帧缓冲复用伪影**，非真机必现；需真机/iOS 复核才能定性。
- 登录失败仅一闪 snackbar 无重试（首轮观察，前端待办）。

## 参考：真 Google 登录构建参数（AUTH-01 已解决）

```bash
flutter build apk --debug \
  --dart-define=PETGO_API_BASE_URL=https://api.tailtopia.id \
  --dart-define=PETGO_DEV_STUB_LOGIN=false \
  --dart-define=GOOGLE_SERVER_CLIENT_ID=952015467016-3q9vb0ro18fnecl9gpnrddbfj9snqer0.apps.googleusercontent.com
```
debug SHA-1 `D0:07:DC:96:D1:4D:7F:B1:AA:2E:AB:0C:78:42:A0:B8:B1:A0:8E:96` 已注册 Android client(com.tailtopia.app);后端 GOOGLE_OAUTH_CLIENT_ID 已=该 Web client,audience 对齐。

---

## ✅ IM SDK 集成完成 + 后端切 live + 兽医账号(2026-06-23,本次 session)

> 上面 CON-02「IM 未集成」阻塞已解除。客户端集成腾讯 IM + 后端 live + 建号 + 链路冒烟全过;仅剩双端真机视觉验收(用户自行手动跑)。

### 客户端(L0 全绿,未 commit)
- `pubspec`:加 `tencent_cloud_chat_sdk ^9.0.7652+1`。
- `LiveImService`(`core/im/im_service.dart`):5 TODO 全实现 —— 惰性 `initSDK`+注册 advanced 监听 → 后端 UserSig `login`(幂等);`sendText`/`sendImage`(C2C,媒体留 IM);`onMessages` 按对端 `u_/v_` 过滤入站流;`logout`;补 `loadHistory`。**前端不自签 UserSig / 不碰 SecretKey。**
- `ImChatPlaceholder`:demo→真实 IM 驱动(订阅流+乐观上屏+发图),保留原型气泡;`peerId` 空不触 SDK。兽医「Pakai」接通预填。
- 验证:`flutter analyze` 净 · `flutter test` **322 过** · `flutter build apk --debug` 成功。

### 后端生产(62.146.239.156)
- `~/.env.petgo`:`IM_MODE=stub→live`(已备份 `.env.petgo.bak.*`);仅 `--env-file` 重建 `petgo-server`(无 `-e`,保住假 idToken 修复)。
- 启动:Flyway 校验 28 迁移、schema 最新;`Started ... 29.9s`;`/actuator/health`=200。
- 兽医账号(无 admin 后台,直写库 + bcrypt):`vet_accounts` id=1 / **`vetdev` / `12345678`**(简化于 2026-06-23)/ `drh. Dewi Santoso` / ACTIVE。
- **链路冒烟(curl@host)**:`POST /api/v1/auth/vet/login`(drdewi)→ role=VET JWT;`GET /api/v1/im/usersig` → 真实腾讯 UserSig(`imUserId=v_1`,`sdkAppId=20043419`,sig.len=188,ttl 86400)。**证明 live 模式真签 UserSig。**

### ⏳ 待手动验收(用户自跑,双端 + 真 Google)
- APK:`petgo_app/build/app/outputs/flutter-apk/app-debug.apk`(连 `api.tailtopia.id` + 真 Google,见上构建参数)。同一 APK 装两端。
- 用户侧:emulator-5554(Google Play 镜像,已挂 shawnliugj@gmail.com)真 Google 登录。
- 兽医侧:第二个安卓模拟器 / 物理机,登录页「Vet sign-in」→ `vetdev` / `12345678`。**保持 App 前台 + Online**(退后台即停心跳→3min 后离线)。
- **CON-02**:用户发起问诊 → 兽医工作台接单 → 双向收发文字/图片验通(发送方己方气泡=乐观上屏,对端经实时流上屏)。
- **CON-03**:兽医结束会话 → 用户评分 → 落 `consult_ratings`。落库核验 SQL:
  `docker exec petgo-postgres psql -U petgo -d petgo -c "SELECT id,session_id,stars,left(comment,20),created_at FROM consult_ratings ORDER BY id DESC LIMIT 5;"`
