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
  ref.read(milestoneRepositoryProvider).reportCelebrations(codes).then((_) {
    onDone?.call();
  }).catchError((Object _) {
    // 静默。下次进列表页会再补弹一次，用户不会永久失去这次庆祝。
  });
}
