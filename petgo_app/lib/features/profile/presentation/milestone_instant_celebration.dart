import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/deep_link_routes.dart';
import '../../../l10n/app_localizations.dart';
import '../data/current_pet_provider.dart';
import '../data/milestone_celebration_reporter.dart';
import '../data/milestone_repository.dart';
import '../domain/milestone.dart';
import '../domain/milestone_instant_codes.dart';
import '../domain/milestone_share.dart';
import '../domain/milestone_titles.dart';
import 'widgets/milestone_celebration.dart';

/// 当场庆祝短轮询间隔（健康页范式）。测试覆盖为 const [] 即完全不检测。
final milestoneInstantPollDelaysProvider = Provider<List<Duration>>((ref) => const [
      Duration(milliseconds: 500),
      Duration(milliseconds: 800),
      Duration(milliseconds: 1200),
    ]);

const Duration _readTimeout = Duration(seconds: 2);

/// 打卡 / 完整解读解锁成功后：等后端异步达成 [codes] 中的那一条，已完成未庆祝 → 当场庆祝。
/// 没等到 / 读失败 / 页面已销毁 → 静默结束，交补庆祝兜底（AD-19）。
///
/// V1.3.4 Story 4.3 · FR-126。后端达成是 AFTER_COMMIT + @Async（Story 4.2），接口返回瞬间大概率尚未落库，
/// 故照 `health_list_page.dart` `_pollNewlyUnlocked` 的 500/800/1200ms 短轮询；
/// 但**先预读一次**：目标项已庆祝 / 不在列表 → 直接结束不轮询，已是「已完成未庆祝」→ 直接弹。
Future<void> celebrateAutoMilestoneIfAny(BuildContext context, WidgetRef ref, Set<String> codes) async {
  final delays = ref.read(milestoneInstantPollDelaysProvider);
  if (delays.isEmpty) return;

  // V1.4.0 multi-pet（Story 1.5）：里程碑按当前宠物（family 键）；取不到当前宠物 → 交补庆祝兜底。
  final int? petId;
  try {
    petId = await ref.read(effectivePetIdProvider.future).timeout(_readTimeout);
  } catch (_) {
    return;
  }
  if (petId == null || !context.mounted) return;
  final listProvider = milestoneListProvider(petId);

  Future<MilestoneList?> read() async {
    try {
      return await ref.read(listProvider.future).timeout(_readTimeout);
    } catch (_) {
      return null; // 读失败 / 超时：静默放弃，交补庆祝兜底。
    }
  }

  // 会话早先断网留下的旧错误会被 `.future` 直接抛出 → 先失效再读，别被旧错误掐掉检测。
  if (ref.read(listProvider).hasError) ref.invalidate(listProvider);

  // 预读：回填过 / 重测再解锁 / 再次打卡的常见情形在这里就结束，省掉 ~2.5s 请求。
  final first = await read();
  if (first == null || !context.mounted) return;
  final hit = pickInstantCelebration(first, codes, ref.read(locallyCelebratedMilestonesProvider));
  if (hit != null) {
    if (!_hostIsCurrent(context)) return;
    return _celebrate(context, ref, first, hit);
  }
  if (instantCelebrationSettled(first, codes, ref.read(locallyCelebratedMilestonesProvider))) return;

  for (final d in delays) {
    await Future<void>.delayed(d);
    if (!context.mounted) return;
    ref.invalidate(listProvider);
    final now = await read();
    if (now == null || !context.mounted) return;
    final item = pickInstantCelebration(now, codes, ref.read(locallyCelebratedMilestonesProvider));
    if (item != null) {
      // 宿主页已被别的页盖住（如成功页 push 了落章页 / 发帖页）→ 不弹到别人头上，交补庆祝。
      if (!_hostIsCurrent(context)) return;
      return _celebrate(context, ref, now, item);
    }
  }
  // 三次都没等到：不弹、不报错、不提示，进里程碑列表页时 resolveCatchup 补弹。
}

/// AD-19「盖在当前页上，关闭回原页」：宿主仍挂载**且**是当前路由才弹。
/// 成功页 push 落章页 / 发帖页后仍挂载（`context.mounted == true`），只查 mounted 会把庆祝盖到新页上。
bool _hostIsCurrent(BuildContext context) =>
    context.mounted && ModalRoute.of(context)?.isCurrent == true;

/// 照 `health_list_page.dart` `_celebrate`：先记本地 → 弹 → 关闭后回报（best-effort、不 await）。
Future<void> _celebrate(BuildContext context, WidgetRef ref, MilestoneList list, MilestoneItem item) async {
  if (!_hostIsCurrent(context)) return;
  final l10n = AppLocalizations.of(context);
  final locale = Localizations.localeOf(context);
  final petName = list.petName;
  final collection = [for (final g in list.groups) ...g.items];
  final shareText = l10n.milestoneShareText(localizedMilestoneTitle(item.code, locale));
  final router = GoRouter.maybeOf(context);
  // 在 show 之前取好 notifier：关闭时宿主页可能已销毁，不能再在已销毁的 WidgetRef 上 read。
  final notifier = ref.read(locallyCelebratedMilestonesProvider.notifier);
  // 弹出前先记本地：回报要等弹窗关掉才发，这段时间里列表重拉仍读到「未庆祝」。
  markMilestonesCelebrating(ref, [item.code]);
  // 冷启动功能弹窗登记（AD-12）由 Story 5.3 在 showMilestoneCelebration 入口统一完成，此处不另登记。
  await showMilestoneCelebration(
    context,
    item,
    petName: petName,
    path: MilestoneCelebrationPath.instant,
    collection: collection,
    onShare: () => shareMilestoneWithLink(
      ref,
      item: item,
      locale: locale,
      petName: petName,
      shareText: shareText,
      collection: collection,
    ),
    // push 而非 go：返回键回到成功页 / 结果页；带 justCelebrated 防列表页在回报落库前连弹。
    onSeeAll: router == null
        ? null
        : () => router.push(DeepLinkRoutes.milestoneList, extra: <String>{item.code}),
  );
  // 即时庆祝同样要回报（AD-A3.1），否则下次进列表页会被当成「未庆祝」再弹一遍。
  notifier.report([item.code]);
}
