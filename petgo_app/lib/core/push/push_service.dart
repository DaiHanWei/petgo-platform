import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:permission_handler/permission_handler.dart' as ph;
import 'package:tencent_cloud_chat_push/tencent_cloud_chat_push.dart';
import 'package:tencent_cloud_chat_sdk/tencent_im_sdk_plugin.dart';

import '../../features/auth/domain/auth_state.dart';
import '../../features/notify/domain/notification_deep_link.dart';
import '../im/im_service.dart';
import '../router/app_router.dart';
import '../router/deep_link_routes.dart';
import '../storage/prefs.dart';

/// 推送点击透传载荷（`OfflinePushInfo.Ext` JSON）。契约：只含 `type` + `token`（+可选 `targetRef`），
/// 绝不带顺序 id / PII / 健康数据明文（后端 TencentImClient 同一契约）。
@immutable
class PushPayload {
  const PushPayload({this.type, this.token, this.targetRef});

  final String? type;
  final String? token;
  final String? targetRef;
}

/// 解析推送 ext（纯函数，L0 可测）。非 JSON / 字段缺失 → 空载荷（点击兜底落通知中心，不崩）。
PushPayload parsePushExt(String ext) {
  try {
    final decoded = jsonDecode(ext);
    if (decoded is! Map<String, dynamic>) return const PushPayload();
    return PushPayload(
      type: decoded['type'] as String?,
      token: decoded['token'] as String?,
      targetRef: decoded['targetRef']?.toString(),
    );
  } catch (_) {
    return const PushPayload();
  }
}

/// 推送点击落点分流（纯函数，L0 可测）。见 [PushService._onNotificationClicked] 的决策说明。
///
/// - [hasToken] 为真 ⇒ 通知类（通知中心有对应行）⇒ 落通知中心。
/// - 否则 ⇒ IM 会话消息（中心无条目）⇒ 落其深链目标（会话）；深链本身兜底为通知中心时按原样返回。
String pushClickLocation({required bool hasToken, required String deepLink}) =>
    hasToken ? DeepLinkRoutes.notificationsCenter : deepLink;

/// 是否可以注册离线推送（纯判定，L0 可测）。
///
/// - 兽医：恒可（新单推送是工作刚需，登录即注册，不受 F7 约束）。
/// - C 端：过了 F7 门（`f7Asked`）**或**系统通知权限已授予（`permissionGranted`）。
///   后者是存量用户的唯一入口——F7 两个触发点都是一次性的，老用户换机/重装后
///   本地标记清空且不会重走建档，缺此旁路即永久收不到推送（L2 2026-08-07 实测缺口）。
///   已授权时注册不弹窗，故不违反 FR-22D。
bool shouldRegister({
  required bool isVet,
  required bool f7Asked,
  required bool permissionGranted,
}) =>
    isVet || f7Asked || permissionGranted;

/// 系统推送单一收口（TIMPush，仅 FCM+APNs 通道）。
///
/// 注册时机（谁调 [syncRegistration]）：
/// - C 端：受 F7 门控——仅当 `pushPermissionAsked=true`（建档/首次问诊双时机已弹过权限）才注册；
///   F7 弹窗完成时经 gate `onAsked` 回调立即补注册。**registerPush 调用本身就是 F7 的门控对象**
///   （原生注册内部会触发系统权限申请，提前调用=提前弹窗，违反 FR-22D）。
/// - 兽医：不走 F7（专业用户，新单推送是工作刚需）——登录即请求通知权限并注册。
/// - 两者都要求 IM 已登录（TIMPush 硬前置）；usersig 签发已为推送放宽为「登录用户都签」
///   （2026-08-07 决策，见 ImUserSigController）。
///
/// 反注册：[unregister] 由 `AuthController.toGuest()` 在 IM logout **之前**调用——不解绑则
/// 同设备下一用户仍收到上一账号的推送（与 IM 漏登出同型的跨用户隐私泄漏）。
class PushService {
  PushService(this._ref);

  final Ref _ref;

  /// APNs 证书 ID 按构建环境编译期切换：debug 装机走 development 证书、
  /// release（TestFlight/App Store）走 production 证书。证书在 IM 控制台"编辑"重传可保 ID 不变。
  static const int apnsCertificateIdDev = 17703;
  static const int apnsCertificateIdProd = 17704;
  static int get apnsCertificateId => kReleaseMode ? apnsCertificateIdProd : apnsCertificateIdDev;

  bool _registered = false;
  Future<void>? _inFlight;

  /// 注册代际号：[unregister] 自增使**在途**注册作废（code-review 2026-08-07：登录后数秒内登出，
  /// 在途 `_doSync` 会在 logout 之后完成并把 token 绑到已登出账号，且 `_registered=true` 残留
  /// 把下一账号的注册短路——跨账号推送串号）。`_doSync` 各 await 点后比对代际，不一致即放弃。
  int _epoch = 0;

  /// 按当前门控状态注册推送（幂等；失败静默，推送是增强能力不阻塞主流程）。
  ///
  /// 并发语义：已有在途注册则等它收尾后重查状态——**不复用**其 `isVet`（code-review 2026-08-07：
  /// 单飞复用会把兽医注册并入 C 端在途 future，跳过兽医权限申请路径）。
  Future<void> syncRegistration({required bool isVet}) async {
    if (_registered) return;
    final existing = _inFlight;
    if (existing != null) {
      try {
        await existing;
      } catch (_) {
        // 在途失败与否都只影响是否需要重试。
      }
      if (_registered || _inFlight != null) return;
    }
    final run = _doSync(isVet, _epoch);
    _inFlight = run.whenComplete(() => _inFlight = null);
    return run;
  }

  Future<void> _doSync(bool isVet, int myEpoch) async {
    final prefs = await AppPrefs.create();
    // C 端未过 F7 门：不注册（注册会触发原生权限弹窗，时机归 F7 独占）。
    //
    // ⚠️ 例外——**系统通知权限已授予则直接注册**（L2 2026-08-07 实测缺口）：
    // F7 只有「建档完成」「首次问诊完成」两个触发点，都是一次性的。老用户换手机 / 重装 App
    // 后本地 `pushPermissionAsked` 清空，却再也不会走建档、也可能不再问诊 ⇒ 永远不注册、
    // 永远收不到任何推送（生产存量用户几乎全员命中）。
    // 已授权时 `registerPush` 内部的权限申请是**无弹窗直返**（iOS requestAuthorization 对
    // 已决状态、Android 13+ 同理），故不违反 FR-22D「绝不在冷启动打扰用户」。
    // 顺带覆盖：用户自行到系统设置里打开通知后，下次冷启动即自动恢复推送能力。
    if (!isVet && !prefs.pushPermissionAsked) {
      var granted = false;
      try {
        granted = (await ph.Permission.notification.status).isGranted;
      } catch (_) {
        // 查询失败按未授权处理（保守：宁可不注册也不越过 F7 弹窗）。
      }
      if (!shouldRegister(isVet: isVet, f7Asked: false, permissionGranted: granted)) return;
    }
    if (isVet) {
      // 兽医旁路：直接请求系统通知权限（已授予/永久拒绝时 request() 立即返回，不重复打扰）。
      try {
        await ph.Permission.notification.request();
      } catch (_) {
        // 权限失败不阻断注册：token 仍可上报，用户去系统设置开启后自动恢复展示。
      }
    }
    // IM 登录，失败短延迟重试一次（code-review 2026-08-07：杀进程态点通知冷启动时，
    // TIMPush 缓存的点击事件要等 registerPush 才派发——登录瞬时失败若直接放弃，
    // 用户这次点击的深链就整体丢失；一次重试覆盖绝大多数瞬时网络抖动）。
    var loggedIn = false;
    for (var attempt = 0; attempt < 2 && !loggedIn; attempt++) {
      if (_epoch != myEpoch) return; // 已登出：作废
      try {
        await _ref.read(imServiceProvider).loginIfNeeded();
        loggedIn = true;
      } catch (_) {
        if (attempt == 0) await Future<void>.delayed(const Duration(seconds: 3));
      }
    }
    if (!loggedIn || _epoch != myEpoch) return;
    try {
      if (Platform.isAndroid) {
        // 出海场景显式锁 FCM 通道（国内厂商通道一律不接）。须在 registerPush 之前调。
        await TencentCloudChatPush().forceUseFCMPushChannel(enable: true);
      }
      final res = await TencentCloudChatPush().registerPush(
        onNotificationClicked: _onNotificationClicked,
        apnsCertificateID: Platform.isIOS ? apnsCertificateId : null,
      );
      if (_epoch != myEpoch) {
        // 注册期间用户已登出：立即解绑，绝不让 token 停留在已登出账号上。
        try {
          await TencentCloudChatPush().unRegisterPush();
        } catch (_) {
          // 随后的 IM logout 停投兜底。
        }
        return;
      }
      _registered = res.code == 0;
    } catch (_) {
      // 注册失败静默（推送不可用不影响 App 主功能）。
    }
  }

  /// 反注册（登出/注销/401 收口，须在 IM logout 之前）。失败吞掉不阻断登出。
  ///
  /// 先自增代际作废在途注册，并**限时**等它收尾（避免竞态窗口）；本会话未注册过则不碰插件：
  /// 跨用户隐私保障本就由随后的 IM logout 承担（登出后腾讯侧停止离线推送投递），
  /// unRegisterPush 是注册态的补充卫生；且插件内部日志链路在宿主测试环境会触发
  /// FFI 加载失败的逃逸异步错误，未注册时必须零接触。
  Future<void> unregister() async {
    _epoch++; // 作废任何在途注册（_doSync 在 await 点后比对代际放弃/自解绑）
    final inflight = _inFlight;
    if (inflight != null) {
      try {
        await inflight.timeout(const Duration(seconds: 3));
      } catch (_) {
        // 超时/失败不等了：代际已翻，在途注册完成时会自行解绑。
      }
    }
    if (!_registered) return;
    _registered = false;
    try {
      await TencentCloudChatPush().unRegisterPush();
    } catch (_) {
      // IM logout 本身也会停掉离线推送投递，此处失败无隐私敞口。
    }
  }

  /// 通知点击（前台/后台/杀进程冷启动三态同一入口）。
  /// 与通知中心列表点击共用 NotificationDeepLink（标记已读 + 角标重算 + 算 location）。
  /// 通知点击（前台/后台/杀进程冷启动三态同一入口）。
  ///
  /// 🔄 产品决策 2026-08-07，**按推送来源分流**（判据是 ext 里有无 `token`）：
  ///
  /// - **通知类推送**（有 token——后端 `NotificationService` 下发，通知中心有对应行）
  ///   → 一律落**通知中心**，不再按 type 直达内容页（原 FR-38 直达语义已改）。落点可预期，
  ///   且不会因深链目标失效（内容被删/会话已关）而落到空页；具体跳转交由用户在中心内点条目
  ///   完成——列表点击链路（`NotificationDeepLink.open` → `pushPayloadToLocation`）保持不变。
  /// - **IM 会话消息推送**（无 token——发送端 SDK 附带 `OfflinePushInfo`，**不进通知中心**）
  ///   → 落**对应会话**。这类消息在通知中心根本没有条目，若也送去中心，用户将面对一个找不到
  ///   该消息的列表（死路）。
  ///
  /// 通知类仍按 token 标记已读（点推送即视为已读，与列表点击同口径）；IM 类无 token 自然跳过。
  /// 兽医角色不受影响：V1 兽医侧无通知中心，`/notifications` 会被角色守卫收口到工作台
  ///（Story 5.1 F2），正是兽医新单推送该去的地方。
  void _onNotificationClicked({required String ext, String? userID, String? groupID}) {
    final p = parsePushExt(ext);
    Future<void>(() async {
      String location;
      try {
        // 复用同一入口做「标记已读 + 角标重算」并拿到深链 location。
        final deepLink = await NotificationDeepLink.openFromPush(
          _ref,
          type: p.type,
          token: p.token,
          targetRef: p.targetRef,
        );
        location = pushClickLocation(
            hasToken: p.token != null && p.token!.isNotEmpty, deepLink: deepLink);
      } catch (_) {
        // 标记已读/映射失败 → 兜底通知中心。
        location = DeepLinkRoutes.notificationsCenter;
      }
      try {
        _navigate(location);
      } catch (_) {
        // 导航失败吞掉（router 未就绪等极端态）。
      }
    });
  }

  /// 三态导航：splash 未走完 → 存 pending 交 splash `onComplete` 消费（AD-8 分流：深链优先，
  /// 且有位置守卫防落地矩阵覆盖）；否则直接导航。Shell Tab 根与 /vet/* 必须 `go`
  /// （push 会二次构建 shell → GlobalKey 撞车白屏；/vet/* 靠角色守卫收口到工作台）。
  void _navigate(String location) {
    final router = _ref.read(routerProvider);
    final atSplash = router.routerDelegate.currentConfiguration.uri.path == '/splash';
    final ctx = rootNavigatorKey.currentContext;
    if (atSplash || ctx == null || !ctx.mounted) {
      _ref.read(pendingDeepLinkProvider.notifier).set(location);
      return;
    }
    if (DeepLinkRoutes.isShellTabRoot(location) || location.startsWith('/vet')) {
      ctx.go(location);
    } else if (redirectWouldRewrite(_ref.read(authControllerProvider), location)) {
      // PR#34 finding #8：push 也过 redirect 管线，游客/角色不符时受控深链会被改写到
      // shell 根（/home）——以 push 入栈即 GlobalKey 撞车 release 白屏。改写场景一律 go。
      ctx.go(location);
    } else {
      ctx.push(location);
    }
  }

  /// 前后台状态同步（Flutter 裸 SDK 不自动做——不调则后台收不到厂商推送/前台重复弹）。
  /// 仅在已注册推送后有意义（未注册无厂商通道可切换），也避免测试环境触碰原生 SDK。
  Future<void> onAppLifecycleChanged(AppLifecycleState state) async {
    if (!_registered) return;
    try {
      final mgr = TencentImSDKPlugin.v2TIMManager.getOfflinePushManager();
      if (state == AppLifecycleState.resumed) {
        await mgr.doForeground();
      } else if (state == AppLifecycleState.paused) {
        await mgr.doBackground(unreadCount: 0);
      }
    } catch (_) {
      // 生命周期同步失败不影响 App。
    }
  }
}

/// 推送服务 provider（app 级单例，跨页面/跨登录会话存活；注册态自管理）。
final pushServiceProvider = Provider<PushService>((ref) => PushService(ref));
