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
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
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
}
