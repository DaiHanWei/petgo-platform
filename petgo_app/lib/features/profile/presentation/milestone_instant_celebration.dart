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
///
/// [forPetId]（V1.4.0 multi-pet Story 3.3 · AC6.6）：查这只宠物的里程碑（多宠打卡逐只检查）；
/// 不给 = 当前宠物（改造前口径）。
Future<void> celebrateAutoMilestoneIfAny(BuildContext context, WidgetRef ref, Set<String> codes,
    {int? forPetId}) async {
  final delays = ref.read(milestoneInstantPollDelaysProvider);
  if (delays.isEmpty) return;

  // V1.4.0 multi-pet（Story 1.5）：里程碑按当前宠物（family 键）；取不到当前宠物 → 交补庆祝兜底。
  final int? petId;
  if (forPetId != null) {
    petId = forPetId;
  } else {
    try {
      petId = await ref.read(effectivePetIdProvider.future).timeout(_readTimeout);
    } catch (_) {
      return;
    }
  }
  if (petId == null || !context.mounted) return;
  final listProvider = milestoneListProvider(petId);
  // 本机已庆祝：逐只（forPetId）按 (petId, code) 判，A 的庆祝不挡 B；不给 forPetId → 原口径（code 集合）。
  final localNotifier = ref.read(locallyCelebratedMilestonesProvider.notifier);
  Set<String> local() =>
      forPetId == null ? ref.read(locallyCelebratedMilestonesProvider) : localNotifier.codesFor(forPetId);

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
  final hit = pickInstantCelebration(first, codes, local());
  if (hit != null) {
    if (!_hostIsCurrent(context)) return;
    return _celebrate(context, ref, first, hit, petId: forPetId);
  }
  if (instantCelebrationSettled(first, codes, local())) return;

  for (final d in delays) {
    await Future<void>.delayed(d);
    if (!context.mounted) return;
    ref.invalidate(listProvider);
    final now = await read();
    if (now == null || !context.mounted) return;
    final item = pickInstantCelebration(now, codes, local());
    if (item != null) {
      // 宿主页已被别的页盖住（如成功页 push 了落章页 / 发帖页）→ 不弹到别人头上，交补庆祝。
      if (!_hostIsCurrent(context)) return;
      return _celebrate(context, ref, now, item, petId: forPetId);
    }
  }
  // 三次都没等到：不弹、不报错、不提示，进里程碑列表页时 resolveCatchup 补弹。
}

/// 多宠打卡逐只庆祝（V1.4.0 multi-pet Story 3.3 · AC6.6）：按 [petIds] 顺序依次调 [celebrateAutoMilestoneIfAny]。
///
/// 上一只的庆祝弹层关闭后，它的退场转场期间宿主页还不是「当前路由」（`isCurrent == false`），
/// 直接查下一只会被 [_hostIsCurrent] 当成「被别的页盖住」而丢掉 —— 所以先等宿主重新成为当前路由
/// （最多 ~1.5s；期间用户若进了别的页，就此停下，交补庆祝兜底）。
Future<void> celebrateAutoMilestonesForPets(
    BuildContext context, WidgetRef ref, Set<String> codes, List<int> petIds) async {
  for (var i = 0; i < petIds.length; i++) {
    if (!context.mounted) return;
    if (i > 0) {
      var waited = 0;
      while (true) {
        if (!context.mounted) return;
        if (_hostIsCurrent(context)) break;
        if (waited >= 30) return;
        await Future<void>.delayed(const Duration(milliseconds: 50));
        waited++;
      }
    }
    if (!context.mounted) return;
    await celebrateAutoMilestoneIfAny(context, ref, codes, forPetId: petIds[i]);
  }
}

/// AD-19「盖在当前页上，关闭回原页」：宿主仍挂载**且**是当前路由才弹。
/// 成功页 push 落章页 / 发帖页后仍挂载（`context.mounted == true`），只查 mounted 会把庆祝盖到新页上。
bool _hostIsCurrent(BuildContext context) =>
    context.mounted && ModalRoute.of(context)?.isCurrent == true;

/// 照 `health_list_page.dart` `_celebrate`：先记本地 → 弹 → 关闭后回报（best-effort、不 await）。
///
/// [petId]：逐只庆祝时这只宠物的 id —— 本地标记与回报都按它（null = 当前宠物，原口径）。
Future<void> _celebrate(BuildContext context, WidgetRef ref, MilestoneList list, MilestoneItem item,
    {int? petId}) async {
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
  markMilestonesCelebrating(ref, [item.code], petId: petId);
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
      petId: petId, // OCR #5：逐只庆祝时分享这只宠物的里程碑，不是当前宠物
    ),
    // push 而非 go：返回键回到成功页 / 结果页；带 justCelebrated 防列表页在回报落库前连弹。
    onSeeAll: router == null
        ? null
        : () => router.push(DeepLinkRoutes.milestoneList, extra: <String>{item.code}),
  );
  // 即时庆祝同样要回报（AD-A3.1），否则下次进列表页会被当成「未庆祝」再弹一遍。
  notifier.report([item.code], petId: petId);
}
