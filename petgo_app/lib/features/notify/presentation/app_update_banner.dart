import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/app_update_controller.dart';
import 'app_update_icons.dart';
import 'app_update_presenter.dart';

/// 首页顶部推荐更新横幅（V1.3.4 Story 3.3 · UX-DR5 / UI 稿 C1）。
///
/// - 显示 ⇔ [AppUpdateState.bannerVisible]（推荐 + 本平台商店链接非空 + 不在 72h 冷却）；
///   否则 `SizedBox.shrink()`，首页与现状一致、不留占位。
/// - 只挂在首页 `_BerandaTop`（场所入口行之上），随页面滚动、不悬浮。
/// - 点「Perbarui sekarang」/ ✕ 都进 72h 冷却（先落盘再跳商店，见 Story 3.3 关键设计点）。
/// - 曝光每次运行只报一次：去重标记在控制器（组件会随离开首页销毁重建）。
class AppUpdateBanner extends ConsumerStatefulWidget {
  const AppUpdateBanner({super.key});

  @override
  ConsumerState<AppUpdateBanner> createState() => _AppUpdateBannerState();
}

class _AppUpdateBannerState extends ConsumerState<AppUpdateBanner> {
  bool _exposureScheduled = false;

  /// 最近一次 build 时是否台前（`StatefulShellRoute.indexedStack` 里离台分支也会 build，
  /// 但被 `TickerMode(enabled: false)` 包着）。
  bool _onStage = false;

  void _scheduleExposure() {
    if (_exposureScheduled) return;
    _exposureScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      // 离台或已不可见 → 复位，下次台前可见的 build 再排。
      if (!_onStage || !ref.read(appUpdateControllerProvider).bannerVisible) {
        _exposureScheduled = false;
        return;
      }
      if (ref.read(appUpdateControllerProvider.notifier).markBannerShownReported()) {
        unawaited(Analytics.capture('app_update_prompt_shown', {'decision': 'recommend', 'surface': 'banner'}));
      }
    });
  }

  Future<void> _onGoStore() async {
    final controller = ref.read(appUpdateControllerProvider.notifier);
    final launch = ref.read(storeLauncherProvider);
    final url = controller.storeUrlForPlatform;
    unawaited(Analytics.capture('app_update_go_store_tapped', {'decision': 'recommend', 'surface': 'banner'}));
    // 先落盘冷却再跳商店：跳商店会让 App 进后台，进程可能被回收。
    // 冷却写失败不能挡住跳商店。
    try {
      await controller.recordSkip();
    } catch (_) {}
    if (url != null) await launch(url);
  }

  Future<void> _onClose() async {
    final controller = ref.read(appUpdateControllerProvider.notifier);
    unawaited(Analytics.capture('app_update_later_tapped', {'decision': 'recommend', 'surface': 'banner'}));
    try {
      await controller.recordSkip();
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final visible = ref.watch(appUpdateControllerProvider.select((s) => s.bannerVisible));
    // 依赖 TickerMode：分支回到台前时会重建，届时再排曝光。
    _onStage = TickerMode.valuesOf(context).enabled;
    if (!visible) return const SizedBox.shrink();
    if (_onStage) _scheduleExposure();

    final l10n = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
      child: Material(
        key: const ValueKey('appUpdateBanner'),
        color: AppColors.mintTint,
        borderRadius: BorderRadius.circular(14),
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 52),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 0, 8),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Container(
                  key: const ValueKey('appUpdateBannerIcon'),
                  width: 36,
                  height: 36,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: AppColors.card,
                    borderRadius: BorderRadius.circular(10),
                    boxShadow: const [
                      BoxShadow(color: Color(0x0F2E2A45), offset: Offset(0, 1), blurRadius: 2),
                    ],
                  ),
                  child: SvgPicture.string(
                    kUpdatePhoneSvgBanner,
                    width: 17,
                    height: 17,
                    colorFilter: const ColorFilter.mode(AppColors.mint, BlendMode.srcIn),
                  ),
                ),
                const SizedBox(width: 6),
                // 不设 maxLines / ellipsis：窄屏宁可折行增高，也不截断标题。
                Expanded(
                  child: Text(
                    l10n.updateRecommendTitle,
                    style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink),
                  ),
                ),
                const SizedBox(width: 10),
                Material(
                  color: AppColors.mint,
                  borderRadius: BorderRadius.circular(999),
                  child: InkWell(
                    key: const ValueKey('appUpdateBannerGoStore'),
                    borderRadius: BorderRadius.circular(999),
                    onTap: _onGoStore,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
                      child: Text(
                        l10n.updateGoStore,
                        maxLines: 1,
                        softWrap: false,
                        style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: Colors.white),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 6),
                Semantics(
                  button: true,
                  label: MaterialLocalizations.of(context).closeButtonTooltip,
                  child: SizedBox(
                    key: const ValueKey('appUpdateBannerClose'),
                    width: 44,
                    height: 44,
                    child: InkWell(
                      customBorder: const CircleBorder(),
                      onTap: _onClose,
                      child: const Icon(Icons.close_rounded, size: 15, color: AppColors.muted),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
