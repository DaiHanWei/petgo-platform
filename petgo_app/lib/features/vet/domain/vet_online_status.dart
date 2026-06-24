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

  /// 兜底纠偏：以服务端权威在线态校正本地显示态（回前台时调用）。
  /// 防「客户端乐观态显示 online 但服务端已过期 offline」的撒谎窗口；保活失败时如实翻 offline。
  Future<void> syncFromServer() async {
    if (_inFlight) return; // 切换在途时不覆盖乐观态
    try {
      state = await ref.read(vetRepositoryProvider).readOnlineStatus();
    } catch (_) {
      // 读失败保持现状,不误翻。
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

/// 兽医可用状态三态（原型 V-st 状态切换抽屉）。
/// ⚠️ 后端在线态当前仅二元 online/offline（`setOnline(bool)`）；[busy]「Sibuk」为**前端占位态**——
/// 持久化时与 offline 同样映射为「不接单」(false)，待后端补三态枚举后才能独立持久（见 CROSS-STORY-DECISIONS）。
enum VetAvailability { online, busy, offline }

/// V-st 抽屉用的可用状态显示源：跟随二元在线态（true→online / false→offline），
/// 但保留用户**显式选择的 Sibuk**（后端仍为不接单 false）直到在线态再次变化。
class VetAvailabilityNotifier extends Notifier<VetAvailability> {
  /// 用户上次显式选择（用于在后端二元态为「不接单」时区分 Sibuk 与 Offline）。
  VetAvailability? _explicit;

  @override
  VetAvailability build() {
    final online = ref.watch(vetOnlineStatusProvider);
    if (online) return VetAvailability.online;
    // 不接单：若用户显式选了 Sibuk 则保持，否则按 Offline。
    return _explicit == VetAvailability.busy ? VetAvailability.busy : VetAvailability.offline;
  }

  /// 选定可用状态：先乐观置显示态，再持久化二元在线态（Online=接单 true；Sibuk/Offline=不接单 false）。
  Future<void> select(VetAvailability next) async {
    _explicit = next;
    state = next;
    try {
      await ref.read(vetOnlineStatusProvider.notifier).toggle(next == VetAvailability.online);
    } catch (_) {
      // 失败时在线态 notifier 已回滚；显示态跟随其权威值重算。
      state = build();
    }
  }
}

final NotifierProvider<VetAvailabilityNotifier, VetAvailability> vetAvailabilityProvider =
    NotifierProvider<VetAvailabilityNotifier, VetAvailability>(VetAvailabilityNotifier.new);
