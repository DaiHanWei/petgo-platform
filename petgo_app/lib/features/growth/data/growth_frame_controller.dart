import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/domain/auth_state.dart';
import '../../me/data/me_counts_provider.dart';
import '../domain/growth_models.dart';
import 'growth_repository.dart';

/// 切换头像框（V1.4.0 ① Story 3.3）：等级页一排与庆祝页「Pakai Bingkai Baru」共用。
///
/// 成功后同步「我的」页头像框：本地给 `authController` 的 profile 打补丁（`growth.frame` = 新生效框），并失效
/// `meCountsProvider` 重拉 `/me`（「我的」页优先读它）。他人看到的 `authorFrame` 由服务端读时推导，无需处理。
class GrowthFrameController {
  GrowthFrameController(this._ref);

  final Ref _ref;

  /// 失败直接抛（含 409 `frame-locked`），调用方统一按「切换失败」处理。
  Future<GrowthFrames> choose(String frame) async {
    final frames = await _ref.read(growthRepositoryProvider).setFrame(frame);
    // 服务端已换好：本地同步失败也不能让调用方报「切换失败」（下次拉 /me 即正确）。
    try {
      _syncMe(frames.effective);
    } catch (_) {}
    return frames;
  }

  void _syncMe(String? effective) {
    final profile = _ref.read(authControllerProvider).profile;
    final g = profile?.growth;
    if (profile != null && g != null) {
      _ref.read(authControllerProvider.notifier).applyProfile(profile.copyWith(
            growth: GrowthBrief(
              level: g.level,
              tier: g.tier,
              frame: effective,
              lifetimeTotal: g.lifetimeTotal,
              nextThreshold: g.nextThreshold,
            ),
          ));
    }
    _ref.invalidate(meCountsProvider);
  }
}

final Provider<GrowthFrameController> growthFrameControllerProvider =
    Provider<GrowthFrameController>(GrowthFrameController.new);
