import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/im/im_service.dart';
import '../data/vet_repository.dart';

/// 兽医在线态单一事实源（P1 工作台首页）。
///
/// 工作台首页深色顶栏开关与「我的」Tab 开关共用本 provider，避免双源漂移。
/// 切换走 `PUT /vet/online-status`（乐观更新 + 失败回滚），并联动 IM 上/下线
/// （IM 跟随在线态，与触发屏无关）。心跳与前后台生命周期由 `VetMePage` 持有
/// （`IndexedStack` 保证其在工作台期间始终存活），通过 `ref.listen` 跟随本态。
class VetOnlineStatusNotifier extends Notifier<bool> {
  /// 切换在途锁：顶栏与「我的」两个开关共用本 provider，集中防并发双切
  /// （快速双击 / 两处近同时切换会让乐观态与 IM 上下线乱序）。
  bool _inFlight = false;

  @override
  bool build() {
    // 首次被 watch 时拉取服务端权威态（仅置态，不触发 IM——保持原 5.2 行为）。
    _load();
    return false;
  }

  Future<void> _load() async {
    try {
      state = await ref.read(vetRepositoryProvider).readOnlineStatus();
    } catch (_) {
      // 读失败维持离线初值；用户可手动切换重试。
    }
  }

  /// 切在线/离线。乐观更新 → 服务端权威态；失败回滚并 rethrow 供调用方提示。
  /// 成功后 IM 跟随：上线 `loginIfNeeded`、下线 `logout`（不阻塞在线态）。
  Future<void> toggle(bool next) async {
    if (_inFlight) return; // 在途防并发：丢弃重复切换
    _inFlight = true;
    state = next; // 乐观
    try {
      final authoritative = await ref.read(vetRepositoryProvider).setOnline(next);
      state = authoritative;
      if (authoritative) {
        ref.read(imServiceProvider).loginIfNeeded().catchError((_) {});
      } else {
        ref.read(imServiceProvider).logout();
      }
    } catch (e) {
      state = !next; // 回滚
      rethrow;
    } finally {
      _inFlight = false;
    }
  }
}

final NotifierProvider<VetOnlineStatusNotifier, bool> vetOnlineStatusProvider =
    NotifierProvider<VetOnlineStatusNotifier, bool>(VetOnlineStatusNotifier.new);
