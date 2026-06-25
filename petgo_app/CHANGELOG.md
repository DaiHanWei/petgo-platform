# 更新日志 / Changelog

## v1.0.1 (build 2) — 内部测试 / Internal Test — 2026-06-25

### 中文

**视觉**
- 全新 app 图标与启动页，移除原 Flutter 默认 logo。

**修复与改进**
- 修复 Android 12 及以下机型「选择相册照片」无法授权、反复提示去设置却无可开项的死循环（按系统版本申请正确的存储/媒体权限）。
- 问诊「开始咨询」流程优化：进入页面不再常驻「有兽医在线」提示，改为点击「开始咨询」时即时查询后台是否有兽医在线——有则进入填写病例、无则给出 AI 自测引导。
- 修复兽医端聊天页底部输入框被系统导航栏（手势条/三键）遮挡的问题。
- 用户与兽医聊完点返回，现在直接回到首页，不再退回到「开始咨询」入口页。
- 账号删除与儿童安全合规页面（满足 Google Play 上架要求）。

### English

**Visuals**
- New app icon and splash screen; removed the default Flutter logo.

**Fixes & Improvements**
- Fixed an issue where picking a photo from the gallery could not be granted on Android 12 and below (it kept asking to enable in Settings with no option to enable). The app now requests the correct storage/media permission per Android version.
- Improved the consultation flow: the entry screen no longer shows a persistent "vets available" banner. Tapping "Start consultation" now checks vet availability in real time — proceed to the case form if a vet is online, otherwise you're guided to the AI symptom check.
- Fixed the vet chat screen where the message input box was hidden behind the system navigation bar (gesture/3-button).
- After chatting with a vet, pressing Back now returns straight to Home instead of the "Start consultation" entry page.
- Added account-deletion and child-safety pages (required for Google Play listing).
