/// 登录引导组件一次登录尝试的结果（Story 1.4 R2 / AC3 · 决策 F13）。
///
/// 三态语义（与 [LoginGuideController] 一致）：
/// - [success]：登录成功，协调器已关闭引导并回跳/路由（组件随之消失）。
/// - [cancelled]：用户主动取消授权（区别于失败）——引导**保持打开、不显失败态**、停留原页。
/// - [failed]：授权失败（网络超时 / Google 异常 / 后端校验失败）——引导内显示
///   「登录失败，请重试」+ 重试入口，**保留 pendingAction 不清空、不前进到注册引导**。
enum LoginGuideOutcome { success, cancelled, failed }
