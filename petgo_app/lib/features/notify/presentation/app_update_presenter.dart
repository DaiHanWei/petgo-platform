import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/router/app_router.dart';
import '../domain/app_update_controller.dart';
import '../domain/app_version_check.dart';
import 'app_update_dialog.dart';

/// 跳商店（V1.3.4 Story 3.2 · AC8）。测试 override 记录调用。
final storeLauncherProvider = Provider<Future<void> Function(String url)>((ref) => _launchStore);

/// 默认实现：外部应用打开商店页。吞掉一切异常（打不开商店也不能把强制弹窗搞崩）。
Future<void> _launchStore(String url) async {
  try {
    final uri = Uri.tryParse(url);
    if (uri != null) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  } catch (e) {
    debugPrint('[AppUpdate] launch store failed: $e');
  }
}

/// 进程内标记：强制弹窗已在显示 → 回前台再判定强制不会叠第二个。
bool _forcedShowing = false;

@visibleForTesting
void debugResetForcedUpdateDialog() => _forcedShowing = false;

/// 等路由离开 `/splash`（最多 10 秒，超时照常继续）。
///
/// 🔴 splash 交棒用 `go(...)` 替换页面栈，挂在 splash 页上的无页路由（dialog）会被一起移除，
/// 强制弹窗就「闪一下没了」。
Future<void> _waitLeaveSplash(WidgetRef ref) async {
  final router = ref.read(routerProvider);
  final delegate = router.routerDelegate;
  bool onSplash() => delegate.currentConfiguration.uri.path == '/splash';
  if (!onSplash()) return;
  final left = Completer<void>();
  void listener() {
    if (!onSplash() && !left.isCompleted) left.complete();
  }

  delegate.addListener(listener);
  try {
    await left.future.timeout(const Duration(seconds: 10), onTimeout: () {});
  } finally {
    delegate.removeListener(listener);
  }
  // 🔴 再等一帧：delegate 在 `setNewRoutePath` 里**同步**通知，此刻 Navigator 还没按新页面栈重建，
  // 立刻弹出的弹窗仍挂在即将被移除的 splash 页上（L0 测试实测会被一起带走）。
  await WidgetsBinding.instance.endOfFrame;
}

bool _stillForced(WidgetRef ref) =>
    ref.read(appUpdateControllerProvider).decision == UpdateDecision.forced;

/// 弹出强制更新弹窗（不可跳过）。由冷启动 / 回前台判定为 forced 时调用。
///
/// 🔴 **守住循环**（3-2 复审）：弹窗是挂在根 Navigator 上的无页路由，离开 splash 后推送点击 /
/// 热启动深链的 `go(...)` 会替换页面栈把它一起带走。所以 `show` 返回后只要控制器判定仍是
/// forced，就等一帧重弹 —— 否则用户点一条推送就绕过了强制更新（PRD AC4）。
/// 曝光埋点只在本次调用首次出现时报一次（被路由替换后重弹是同一次曝光，不重复计数）。
Future<void> showForcedUpdateDialog(WidgetRef ref) async {
  if (_forcedShowing) return;
  _forcedShowing = true;
  var reported = false;
  try {
    while (true) {
      await _waitLeaveSplash(ref);
      final ctx = rootNavigatorKey.currentContext;
      if (ctx == null || !ctx.mounted) return;

      final controller = ref.read(appUpdateControllerProvider.notifier);
      final version = controller.latestForPlatform ?? '';
      final storeUrl = controller.storeUrlForPlatform;
      final launch = ref.read(storeLauncherProvider);

      if (!reported) {
        reported = true;
        unawaited(Analytics.capture('app_update_prompt_shown', {'decision': 'force', 'surface': 'dialog'}));
      }
      // LEDGER-HOOK(5.3): 强制更新弹窗真正出现时登记 startupPopupLedger
      await AppUpdateDialog.show(
        ctx,
        decision: UpdateDecision.forced,
        version: version,
        onGoStore: storeUrl == null
            ? null
            : () {
                unawaited(Analytics.capture(
                    'app_update_go_store_tapped', {'decision': 'force', 'surface': 'dialog'}));
                unawaited(launch(storeUrl));
              },
      );
      // 强制弹窗不会被用户关掉；走到这里说明被路由栈替换带走了。
      if (!_stillForced(ref)) return;
      await WidgetsBinding.instance.endOfFrame;
    }
  } finally {
    _forcedShowing = false;
  }
}

/// 弹出推荐更新弹窗（V1.3.4 Story 3.4 · 设置页手动检查判定为 recommended 时调用）。
///
/// - 弹出前报 `app_update_prompt_shown`（surface=dialog）。
/// - 「Nanti」：弹窗先关（[AppUpdateDialog] 内部），再报 `app_update_later_tapped` → 记 72h 冷却。
/// - 「Perbarui sekarang」：弹窗先关（[AppUpdateDialog] 内部），再报 `app_update_go_store_tapped`
///   → 记冷却（先落盘：跳商店会让 App 进后台，进程可能被回收；写失败不挡跳商店）→ 跳本平台商店。
/// - 点遮罩 / 返回键关闭：不记冷却、不报埋点。
/// - 本平台商店链接为空 → 不渲染主按钮（同 3.2 AC6）。
Future<void> showRecommendedUpdateDialog(BuildContext context, WidgetRef ref) async {
  final controller = ref.read(appUpdateControllerProvider.notifier);
  final version = controller.latestForPlatform ?? '';
  final storeUrl = controller.storeUrlForPlatform;
  final launch = ref.read(storeLauncherProvider);

  Future<void> skip() async {
    try {
      await controller.recordSkip();
    } catch (_) {}
  }

  unawaited(Analytics.capture('app_update_prompt_shown', {'decision': 'recommend', 'surface': 'dialog'}));
  await AppUpdateDialog.show(
    context,
    decision: UpdateDecision.recommended,
    version: version,
    onGoStore: storeUrl == null
        ? null
        : () async {
            unawaited(Analytics.capture(
                'app_update_go_store_tapped', {'decision': 'recommend', 'surface': 'dialog'}));
            await skip();
            await launch(storeUrl);
          },
    onLater: () {
      unawaited(Analytics.capture('app_update_later_tapped', {'decision': 'recommend', 'surface': 'dialog'}));
      unawaited(skip());
    },
  );
}

/// 回前台版本检查（`app.dart` resumed 调用）。
///
/// - 已判定 forced（弹窗可能已被意外移除）→ **直接重弹，不受 6h 节流**；已在显示则空转。
/// - 否则走控制器 `onResumed()`（6h 节流 / 冷启动未发出时空转），判定 forced 再弹。
Future<void> onResumeUpdateCheck(WidgetRef ref) async {
  if (_stillForced(ref)) {
    await showForcedUpdateDialog(ref);
    return;
  }
  final d = await ref.read(appUpdateControllerProvider.notifier).onResumed();
  if (d == UpdateDecision.forced) await showForcedUpdateDialog(ref);
}
