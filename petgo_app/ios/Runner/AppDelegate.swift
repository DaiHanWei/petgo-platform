import Flutter
import UIKit
import TIMPush
import tencent_cloud_chat_push

/// 应用 AppDelegate。
///
/// **必须实现 `TIMPushDelegate`**（系统推送）：TIMPush SDK 的证书 ID 是向
/// `UIApplication.shared.delegate` 索取的（协议继承自 `UIApplicationDelegate`，
/// 头文件原文：「您需要在 AppDelegate.m 中实现该方法」）。缺这一步的表象极具迷惑性——
/// 注册、IM 登录、服务端 `/v4/timpush/batch` 全部成功返回，唯独通知永远收不到
/// （证书 ID 取不到 ⇒ APNs token 没绑到任何证书 ⇒ 腾讯侧无处投递）。
/// L2 实测于 2026-08-07 定位。
@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate, TIMPushDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    provideGoogleMapsApiKeyIfConfigured()
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  /// Google 地图 iOS 密钥（V1.3.0 batch-b1 Story 1.4 · AD-3 Rule 4）。
  ///
  /// 🔴 **密钥不在代码里**：从 Info.plist 的 `GMSApiKey` 读，而那个值是构建变量
  /// `$(GOOGLE_MAPS_API_KEY)`，来自 **gitignored** 的 `ios/Flutter/Maps.xcconfig`
  /// （模板 `Maps.xcconfig.example` 只放占位符）。
  ///
  /// 🔴 **没配密钥时也必须调用**（code-review 2026-09-15 订正）。
  ///
  /// iOS 与 Android 在这一点上**不一样**：`google_maps_flutter_ios` 创建地图时会走
  /// `[GMSServices sharedServices]`，而 SDK 在**从未 provideAPIKey** 的情况下会直接抛异常 ——
  /// 也就是说「不调用」的后果不是 Android 那种灰地图，而是**打开选点弹层就崩**
  /// （而 AC4 明写「不空白、不崩」）。
  ///
  /// 所以没配时传一个**非空但显然无效**的哨兵串：SDK 正常初始化，地图落到标准的
  /// 「授权失败」空白态并在控制台留一条清楚的原因，而不是把整个 App 带走。
  ///
  /// ⚠️ 用反射式的 `NSClassFromString` 调用，**避免在未装 Pod 的环境里编译失败** ——
  /// `GoogleMaps` 是 google_maps_flutter_ios 通过 CocoaPods 带进来的，
  /// 而本文件在 `pod install` 之前也要能编过。
  private func provideGoogleMapsApiKeyIfConfigured() {
    let configured = Bundle.main.object(forInfoDictionaryKey: "GMSApiKey") as? String
    // 占位符没被替换掉时（例如有人直接把 example 复制成正式文件）也当没配。
    let usable = (configured?.isEmpty == false && configured?.hasPrefix("YOUR_") == false)
      ? configured!
      : "MISSING_GOOGLE_MAPS_API_KEY"
    guard let services = NSClassFromString("GMSServices") as AnyObject? else { return }
    let selector = NSSelectorFromString("provideAPIKey:")
    if services.responds(to: selector) {
      _ = services.perform(selector, with: usable)
    }
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
  }

  /// 离线推送证书 ID。**单一事实源在 Dart**（`PushService.apnsCertificateId`：
  /// release 用生产证书 17704、debug/profile 用开发证书 17703），经插件的
  /// `registerPush(apnsCertificateID:)` → `setBusID` 存入下面这个单例后由此返回。
  /// 不在此处硬编码 `#if DEBUG`：profile 包不定义 DEBUG，会错选成生产证书，
  /// 而 profile 包签的是 development 描述文件（APNs sandbox）⇒ 环境错配收不到推送。
  func businessID() -> Int32 {
    return TencentCloudChatPushFlutterModal.shared.busId
  }

  /// App Group ID（仅统计推送抵达率时需要；V1 不做，返回空串）。
  func applicationGroupID() -> String! {
    return TencentCloudChatPushFlutterModal.shared.kAPNSApplicationGroupID
  }

  /// 收到远程推送 / 点击通知栏通知（离线态点击即经此回调）。
  ///
  /// **必须实现并转发给插件**：插件的 `tryNotifyDartOnNotificationClickEvent` 是把 ext 送回
  /// Dart（`onNotificationClicked`）的唯一入口，而它在插件内部**没有任何调用方**——
  /// 按官方集成要求由宿主 AppDelegate 调用。缺这一步的表象是：推送能收到、点击却什么也不发生，
  /// App 只是普通冷启动落到首页（L2 实测 2026-08-07 定位）。
  ///
  /// 返回 `true`：接管解析，阻止 TIMPush 走内置 TUIKit 的跳转逻辑（本项目自己用 go_router
  /// 落地，见 `PushService._onNotificationClicked`）。
  func onRemoteNotificationReceived(_ notice: String?) -> Bool {
    TencentCloudChatPushPlugin.shared.tryNotifyDartOnNotificationClickEvent(notice)
    return true
  }
}
