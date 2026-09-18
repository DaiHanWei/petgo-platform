import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'milestone_repository.dart';

/// 庆祝回报的**唯一发起点**（V1.3.0 批次 A · Story 1.5 · FR-111 · AD-A3.1）。
///
/// 三条庆祝路径（打卡即时 / 存健康记录后短轮询 / 进列表页补弹）都经这里回报，
/// 免得「失败静默」「不要 await」这两条纪律在三处各写一遍、迟早有一处写成阻塞的。
///
/// 🔴 **best-effort，三个"不"**：
/// - **不 `await` 到阻塞 UI** —— 调用方拿到的是一个已经在跑的 Future，通常直接丢掉；
/// - **失败静默** —— 不弹错、不重试到用户可感知；
/// - **不得改成同步** —— 那会让庆祝页、乃至「去发布」的发布流程卡在一个可失败的写上。
///
/// 回报失败的代价只是**下次进里程碑列表页再补弹一次**，这是设计上接受的。
///
/// ⚠️ 「重温庆祝」（点已完成徽章回看）**不调本函数**：重温不改写 `celebrated_at`、
/// 不产生回报（AD-A3.3）。埋点仍照常上报 —— 埋点与回报是两件事。
void reportMilestoneCelebrated(WidgetRef ref, List<String> codes, {void Function()? onDone}) {
  if (codes.isEmpty) return;
  // ⚠️ 异步收尾交给 provider 自己的 Ref 做：调用方的 WidgetRef 常常在回报落库前就已销毁
  //（「已打卡」picker 早已 pop），在它上面 invalidate 会抛 StateError。
  ref.read(locallyCelebratedMilestonesProvider.notifier).report(codes, onDone: onDone);
}

/// 在回报前**先**把 code 记进本地集合（见 [locallyCelebratedMilestonesProvider]）。
///
/// 用于「庆祝已经/即将弹出、但回报要等弹窗关掉才发」的那段空档：这段时间里列表若被重拉，
/// 读到的仍是 `celebratedAt == null`，不先记一笔就会在即时庆祝之上再补弹一次。
void markMilestonesCelebrating(WidgetRef ref, List<String> codes) {
  if (codes.isEmpty) return;
  ref.read(locallyCelebratedMilestonesProvider.notifier).add(codes);
}

/// 本 App 会话内**已在本机弹过庆祝**的 code（回报在途或已落库）。
///
/// 🔴 补弹判定一律先扣掉这份集合 —— 服务端 `celebrated_at` 才是事实来源，但回报是异步、
/// best-effort 的，从「弹出」到「列表读到已置位」之间有三种窗口会重复庆祝：
/// ① 列表页「已打卡」→ 先重拉列表、弹窗关掉才回报；
/// ② 从列表页进「去发布」/健康记录，**底下那个**列表页被重拉时自己补弹；
/// ③ 回报成功前拉进缓存的旧数据，下次进页读到仍是未庆祝。
/// 与 `justCelebrated`（一次导航内的防抖）互补，不取代它。
///
/// 回报失败时撤回（保持「失败 = 下次再补弹」的既有语义）。纯内存、不持久化；
/// **按账号清空**（已登记 `resetUserScopedCaches`）。
final locallyCelebratedMilestonesProvider =
    NotifierProvider<LocallyCelebratedMilestones, Set<String>>(LocallyCelebratedMilestones.new);

class LocallyCelebratedMilestones extends Notifier<Set<String>> {
  /// 代际：每次 build（含换账号 invalidate）+1。
  /// ⚠️ invalidate 后 Riverpod 复用同一个 notifier 实例、`ref.mounted` 仍为 true ——
  /// 只判 mounted 挡不住旧账号迟到的回调改到新账号的集合。
  int _epoch = 0;

  @override
  Set<String> build() {
    _epoch++;
    return const <String>{};
  }

  bool _stale(int epoch) => !ref.mounted || epoch != _epoch;

  void add(Iterable<String> codes) {
    if (state.containsAll(codes)) return;
    state = {...state, ...codes};
  }

  void report(List<String> codes, {void Function()? onDone}) {
    add(codes);
    final epoch = _epoch;
    ref.read(milestoneRepositoryProvider).reportCelebrations(codes).then((_) {
      // 换账号时本 provider 已被 invalidate：旧账号的收尾一概不做。
      if (_stale(epoch)) return;
      // 落库后刷新列表缓存：milestoneListProvider 不 autoDispose，不刷新的话
      // 缓存里一直是「未庆祝」的旧值（本地集合挡住了补弹，但角标口径仍旧）。
      ref.invalidate(milestoneListProvider);
      onDone?.call();
    }).catchError((Object _) {
      // 静默。撤回本地标记 —— 下次进列表页会再补弹一次，用户不会永久失去这次庆祝。
      if (_stale(epoch)) return;
      state = {...state}..removeAll(codes);
    });
  }
}
