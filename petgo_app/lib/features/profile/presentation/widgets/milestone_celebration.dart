import 'dart:async';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/router/app_router.dart';
import '../../../../core/startup/startup_popup_ledger.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_frame.dart';
import '../../../../shared/utils/haptics.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../../content/domain/content_type.dart';
import '../../../content/presentation/brag_post_entry.dart';
import '../../data/current_pet_provider.dart';
import '../../data/pet_list_repository.dart';
import '../../domain/celebration_pet.dart';
import '../../domain/milestone.dart';
import '../../domain/milestone_account_codes.dart';
import '../../domain/milestone_celebration_copy.dart';
import '../../domain/milestone_compose_preset.dart';
import '../../domain/milestone_share.dart';
import '../../domain/milestone_titles.dart';
import 'milestone_badge.dart';
import 'milestone_share_card.dart';
import 'pet_species_avatar.dart';

/// 里程碑解锁庆祝（Story 8.5 · FR-42）：**统一全屏页**（不分级仪式，决策 D-11），mint 风格、无第三方动画包。
/// 徽章走 [MilestoneBadge]（V1.3.2 Story 5.1：素材到了显示该枚专属徽章，没到回落紫渐变奖杯）。
///
/// 云端 headless 验不了视觉/计时观感（L2 待本地）；本组件保证构建/计时/自动消失逻辑 L0 可测。

/// 一次庆祝展示是由什么路径触发的（V1.3.0 Story 1.5 · AC6 · AD-A26.5）。
///
/// 埋点 `milestone_celebration_shown` 的 `path` 属性**值域就是这三个**，不多不少 ——
/// 看板据此区分「当场庆祝的」「事后补的」「用户自己回头看的」。
enum MilestoneCelebrationPath {
  /// 打卡 / 存健康记录后当场弹（含 500/800/1200ms 三次短轮询拉到的）。
  instant,

  /// 进里程碑列表页时补弹（本 story 新增的兜底路径）。
  catchup,

  /// 用户点已完成徽章回头重温。**不改写 `celebrated_at`、不产生回报**（AD-A3.3），
  /// 但仍上报埋点 —— 埋点与回报是两件事。
  revisit;

  String get wire => name;
}

/// 庆祝页的退出方式（V1.4.2 social-topic Story 3.2 · T2.3）：只有「Bagikan ke Komunitas」成功出图时带回 JPEG，
/// 由 [showMilestoneCelebration] 在 dialog 关闭**之后**打开发布页；其余关闭方式（✕ / 滑动 / 遮罩 / 站外分享 /
/// 查看全部）都是 null。
class _CelebrationExit {
  const _CelebrationExit.compose(this.jpeg);
  final Uint8List jpeg;
}

/// `milestone_share_failed {code, stage}`（PRD §4 FR-156）：`stage ∈ render_card / open_compose / create_link / open_sheet`。
/// ⚠️ 事件名写字面量 —— 埋点清单测试按 `Analytics.capture('…'` 字面量对账。只带 code / stage，无 PII。
void _reportShareFailed(String code, String stage) =>
    Analytics.capture('milestone_share_failed', {'code': code, 'stage': stage});

/// 统一庆祝页入口（6 处生产弹出点 + debug 钩子）。
///
/// V1.4.2 social-topic Story 3.2（D-21 · 架构 §1.2 第 7 条）：依赖一律**弹出前**取好 ——
/// [ProviderContainer] / l10n / locale / 分享文案；按钮里不读宿主 `WidgetRef`（宿主页可能已销毁 / 被盖住）。
///
/// - [petId]：这条里程碑所属宠物（站外建链、发布页预选、卡面宠物）；null = 当前宠物（原口径）。
/// - [beforeCompose]：「Bagikan ke Komunitas」在庆祝页关闭后、打开发布页**之前**调用 ——
///   给「去发布」宿主先关旧发布 sheet（`publishControllerProvider` 是全局单例，两页并存会共用草稿）。
/// - 「Bagikan ke Komunitas」的发布页由本函数打开并 **await 到它关闭**才返回：调用方 `await` 之后的收尾
///   （回报、pop、跳转）一定发生在发布页关闭之后，不会把刚打开的发布页 pop 掉。
Future<void> showMilestoneCelebration(
  BuildContext context,
  MilestoneItem item, {
  required String petName,
  required MilestoneCelebrationPath path,
  int? petId,
  List<MilestoneItem> collection = const [],
  List<CelebrationPet> accountPets = const [],
  VoidCallback? onSeeAll,
  Future<void> Function()? beforeCompose,
}) async {
  // 入口统一登记本次冷启动功能弹窗（V1.3.4 Story 5.3 · AD-12），覆盖全部调用方。
  StartupPopupLedger.instance.record(StartupPopupKind.milestoneCelebration);
  // AC6：展示即上报，携带 code / level / path。
  // ⚠️ 必须写成 `Analytics.capture(` 这个自限定形式 —— 埋点清单测试按该字面量对账。
  // 两台设备同时补弹会各报一次（**接受双计**，AD-A26.5）；回报本身仍是幂等的。
  // 属性里只有 code / level / path，**没有任何 PII**（NFR-5）。
  Analytics.capture('milestone_celebration_shown', {
    'code': item.code,
    'level': item.level.name.toUpperCase(),
    'path': path.wire,
  });
  // Story 3.2 · T2.2：弹出前取好（照 milestone_instant_celebration「在 show 之前取好」）。
  final container = ProviderScope.containerOf(context, listen: false);
  final l10n = AppLocalizations.of(context);
  final locale = Localizations.localeOf(context);
  final shareText = l10n.milestoneShareText(localizedMilestoneTitle(item.code, locale));
  final exit = await showGeneralDialog<_CelebrationExit>(
    context: context,
    barrierDismissible: true,
    barrierLabel: 'milestone-celebration',
    barrierColor: Colors.black.withValues(alpha: 0.6),
    transitionDuration: const Duration(milliseconds: 240),
    pageBuilder: (_, _, _) => _MilestoneCelebrationView(
        item: item,
        petName: petName,
        petId: petId,
        container: container,
        locale: locale,
        shareText: shareText,
        onSeeAll: onSeeAll,
        collection: collection,
        accountPets: accountPets),
  );
  if (exit == null) return;
  // ---- 「Bagikan ke Komunitas」：庆祝页已关 → 打开预填发布页（AC4.2）。----
  try {
    int? pid = petId;
    if (pid == null) {
      try {
        pid = await readRequestPetIdFrom(container).timeout(const Duration(seconds: 3));
      } catch (_) {
        pid = null; // 取不到 → 不预选（发布页按默认宠物）
      }
    }
    // milestoneComposePreset 要一个 int：取不到当前宠物时用占位 0，随后从预选里滤掉（宠物 id 恒为正）。
    final preset = milestoneComposePreset(
        item: item, petId: pid ?? 0, petName: petName, accountPets: accountPets, l10n: l10n, locale: locale);
    final petIds = [for (final id in preset.petIds) if (id > 0) id];
    await beforeCompose?.call();
    // 根导航上打开（宿主页此时可能已关 / 已被 beforeCompose 关掉，不用宿主 context）；根导航不可用 → open_compose。
    final ctx = rootNavigatorKey.currentContext;
    if (ctx == null || !ctx.mounted) throw StateError('root navigator unavailable');
    await BragPostEntry.openPrepared(ctx,
        jpeg: exit.jpeg,
        text: preset.text,
        preset: ContentType.growthMoment,
        petIds: petIds.isEmpty ? null : petIds,
        source: BragPostSource.milestoneCelebration);
  } catch (_) {
    // AC4.4：root context 不可用等 → 上报后静默结束（庆祝页此时已关，不再弹回）。
    _reportShareFailed(item.code, 'open_compose');
  }
}

/// 两个分享按钮同一时刻只允许一个动作（AC3.3）。
enum _Busy { community, outside }

class _MilestoneCelebrationView extends StatefulWidget {
  const _MilestoneCelebrationView(
      {required this.item,
      required this.petName,
      required this.container,
      required this.locale,
      required this.shareText,
      this.petId,
      this.onSeeAll,
      this.collection = const [],
      this.accountPets = const []});

  final MilestoneItem item;
  final String petName;
  final int? petId;

  /// 弹出前取好的 container（D-21）：按钮里一切 provider 读取都走它，不持有宿主 `WidgetRef`。
  final ProviderContainer container;
  final Locale locale;

  /// 站外分享文案（`milestoneShareText`，弹出前取好）。
  final String shareText;
  final VoidCallback? onSeeAll;

  /// 全部里程碑（用于「已解锁合集」预览）；为空则不显示该区。
  final List<MilestoneItem> collection;

  /// 账号下 ACTIVE 宠物（V1.4.2 Story 2.3 · B2，调用方弹出前取好，最多 5 只）。
  /// 账号级编码且 ≥ 2 只 → 多宠布局：宠物行 + 日期胶囊只写日期 + 不显示 KOLEKSI。
  final List<CelebrationPet> accountPets;

  @override
  State<_MilestoneCelebrationView> createState() => _MilestoneCelebrationViewState();
}

class _MilestoneCelebrationViewState extends State<_MilestoneCelebrationView>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  /// 屏外预挂的里程碑卡截图区（3.1 交接：弹出即挂载预热，点按钮时图片已加载）。
  final GlobalKey _cardKey = GlobalKey();

  /// 「Bagikan ke luar」按钮（取全局矩形作 iOS `sharePositionOrigin`）。
  final GlobalKey _outsideKey = GlobalKey();

  late final List<CelebrationPet> _cardPets;
  late final DateTime _cardDate;

  /// 正在处理的动作（null = 空闲）。
  _Busy? _busy;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 700),
    )..forward();
    _cardPets = _resolveCardPets();
    // 重温旧数据可能没有完成时间 → 用今天。
    _cardDate = widget.item.completedAt ?? DateTime.now();
    // 解锁瞬间一次振动反馈（弹框一打开即触发，方便测）。
    celebrationVibrate();
  }

  /// 卡面宠物（T3.2）：账号级且取到了账号宠物 → 全部；否则这一只 —— 名字 / 头像 / 物种从**已加载的**宠物列表按
  /// petId 取（不为此新发列表请求），取不到 → [petName] + 按编码前缀的物种默认头像。
  List<CelebrationPet> _resolveCardPets() {
    if (isAccountMilestone(widget.item.code) && widget.accountPets.isNotEmpty) return widget.accountPets;
    final c = widget.container;
    PetListItem? hit;
    if (c.exists(petListProvider)) {
      final pets = c.read(petListProvider).asData?.value ?? const <PetListItem>[];
      final id = widget.petId ?? (c.exists(effectivePetIdProvider) ? c.read(effectivePetIdProvider).asData?.value : null);
      hit = pets.where((p) => p.id == id).firstOrNull;
    }
    if (hit != null) {
      return [CelebrationPet(name: hit.name, avatarUrl: hit.avatarUrl, petType: hit.petType, id: hit.id, species: hit.species)];
    }
    final code = widget.item.code;
    final type = code.startsWith('C') ? 'CAT' : code.startsWith('D') ? 'DOG' : null;
    return [CelebrationPet(name: widget.petName, petType: type, id: widget.petId)];
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  /// 已发起关闭（✕ / 滑动 / 查看全部 / 站外分享后 / 发到社区带图退出）。
  bool _closed = false;

  /// 庆祝层已不该再操作导航（复审 3-2 CONFIRMED）：已发起关闭、已卸载，或本路由已不是栈顶 ——
  /// 例如出图期间点了 ✕，截图恰好在 240ms 退场动画内完成：此时 `mounted` 仍为 true，再 `pop` 会弹掉**下面的宿主路由**
  /// （「去发布」连弹两层、健康记录连带关掉列表页）。遮罩点击 / 系统返回不经 [_close]，靠 `isCurrent` 兜住。
  bool get _gone => !mounted || _closed || ModalRoute.of(context)?.isCurrent != true;

  void _close() {
    if (_gone) return;
    _closed = true;
    Navigator.of(context).pop();
  }

  /// 「Bagikan ke Komunitas」（AC4）：截卡 → 转 JPEG → 带图关闭庆祝页（发布页由 [showMilestoneCelebration] 打开）。
  Future<void> _onCommunityPressed() async {
    if (_busy != null || _gone) return;
    final code = widget.item.code;
    // AC4.1：点击当下上报（截图 / 转码失败也算一次点击）。
    BragPostEntry.reportTap(BragPostSource.milestoneCelebration,
        extra: {'code': code, 'level': widget.item.level.name.toUpperCase()});
    setState(() => _busy = _Busy.community);
    Uint8List? jpeg;
    // prepare 转码失败时自己弹过 toast（不重复弹）。
    var toasted = false;
    try {
      final png = await captureMilestoneShareCard(_cardKey);
      // 用户在出图期间关掉了庆祝页（含退场动画中）→ 放弃，不再碰导航、不算失败。
      if (!mounted || _gone) return;
      if (png != null) {
        jpeg = await BragPostEntry.prepare(context, png, toastExtraBottom: 96);
        if (!mounted || _gone) return;
        toasted = jpeg == null;
      }
    } catch (_) {
      // 复审 3-2 PLAUSIBLE(a)：截图 / 转码抛异常按「出图失败」处理，按钮不能卡在转圈。
      jpeg = null;
    }
    if (!mounted || _gone) return;
    if (jpeg == null) {
      // D3：截图超时 / 失败 → 轻提示（抬到吸底栏上沿之上，不用 top：会压住 ✕）。
      if (!toasted) showAppToast(context, AppLocalizations.of(context).shareCardExportError, extraBottom: 96);
      _reportShareFailed(code, 'render_card');
      setState(() => _busy = null); // 不关庆祝页、按钮恢复可重试
      return;
    }
    _closed = true;
    Navigator.of(context).pop(_CelebrationExit.compose(jpeg));
  }

  /// 「Bagikan ke luar」（AC5）：建链 → 系统分享面板 → 面板返回后关闭庆祝页；面板没能拉起 → 按钮恢复。
  Future<void> _onOutsidePressed() async {
    if (_busy != null || _gone) return;
    // AC5.4：iOS 分享面板定位用按钮全局矩形（iPad 不传会崩）。
    final box = _outsideKey.currentContext?.findRenderObject() as RenderBox?;
    final origin = box == null || !box.hasSize ? null : box.localToGlobal(Offset.zero) & box.size;
    setState(() => _busy = _Busy.outside);
    var opened = false;
    try {
      opened = await shareMilestoneWithLink(widget.container,
          item: widget.item,
          locale: widget.locale,
          petName: widget.petName,
          shareText: widget.shareText,
          collection: widget.collection,
          petId: widget.petId,
          sharePositionOrigin: origin,
          onFailed: (stage) => _reportShareFailed(widget.item.code, stage));
    } catch (_) {
      _reportShareFailed(widget.item.code, 'open_sheet'); // 兜底：任何异常都不让按钮卡死
    }
    // 面板期间庆祝页已被关掉（✕ / 遮罩 / 返回）→ 不再碰导航（[_close] 内同样有此判断）。
    if (!mounted || _gone) return;
    if (opened) {
      _close();
    } else {
      setState(() => _busy = null);
    }
  }

  // 查看全部里程碑：先关庆祝层，再跳里程碑列表（由调用方注入跳转）。处理中不可点（AC3 / T3.4）。
  void _seeAll() {
    if (_busy != null || _gone) return;
    _close();
    widget.onSeeAll?.call();
  }

  @override
  Widget build(BuildContext context) => _celebration(context);

  // ---- P-35 统一解锁庆祝（不分级别，原型 milestone-unlock）：纯深色全屏页 + 渐变大徽章
  //      + 解锁标头 + 标题/正文 + 宠物·日期；V1.4.2 Story 3.2 · D1：按钮组吸底（发到社区 / 站外分享 / 查看全部）----
  Widget _celebration(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    final copy = localizedMilestoneCelebration(widget.item.code, locale, widget.petName);
    // V1.4.2 Story 2.3 · B2：账号级里程碑 + 账号 ≥ 2 只 ACTIVE 宠物 → 全部宠物一起完成的多宠布局。
    final multiPet = isAccountMilestone(widget.item.code) && widget.accountPets.length >= 2;
    final dateText = multiPet
        ? (widget.item.completedAt == null ? '' : _formatLongDate(widget.item.completedAt!, locale))
        : widget.item.completedAt == null
            ? widget.petName
            : '${widget.petName} · ${_formatLongDate(widget.item.completedAt!, locale)}';
    // 整页纯深色（#141019），非半透明遮罩——与原型一致。
    return Material(
      color: AppColors.splashInk,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        // 左右滑动即关闭（bug 20260707：庆祝全屏页此前无关闭/返回入口，dialog 不吃 iOS 边缘返回手势）。
        onHorizontalDragEnd: (d) {
          if ((d.primaryVelocity ?? 0).abs() > 300) _close();
        },
        child: Stack(
        children: [
          // 掉落彩纸（纯自绘，不挡交互）。
          const Positioned.fill(child: IgnorePointer(child: _Confetti())),
          // Story 3.2 · T3.2：里程碑卡屏外预挂（移出可视区；不用 Offstage / Visibility / Opacity —— 它们不绘制，截出空图）。
          Positioned(
            left: -2000,
            top: 0,
            width: 360,
            child: IgnorePointer(
              child: CardFrame(
                boundaryKey: _cardKey,
                canvas: kMilestoneShareCanvas,
                child: MilestoneShareCard(item: widget.item, pets: _cardPets, completedAt: _cardDate),
              ),
            ),
          ),
          SafeArea(
            child: Column(
              children: [
                Expanded(
                  child: Stack(
                    children: [
                      Positioned.fill(
                        child: Center(
                          key: const ValueKey('milestoneCelebration'),
                          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                ScaleTransition(
                  scale: CurvedAnimation(parent: _controller, curve: Curves.elasticOut),
                  child: SizedBox(
                    width: 140,
                    height: 120,
                    child: Stack(
                      alignment: Alignment.center,
                      clipBehavior: Clip.none,
                      children: [
                        MilestoneBadge(
                          code: widget.item.code,
                          size: 120,
                          level: widget.item.level,
                          fallback: (_) => _badge(120),
                        ),
                        // 级别小标签，叠在徽章下沿（原型 milestone-unlock）。
                        Positioned(bottom: -10, child: _levelChip(l10n)),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 26),
                // 解锁标头（大写、字距）。
                Text(l10n.milestoneCelebrateUnlocked.toUpperCase(),
                    textAlign: TextAlign.center,
                    style: TextStyle(
                        color: Colors.white.withValues(alpha: 0.5),
                        fontWeight: FontWeight.w700,
                        fontSize: 13,
                        letterSpacing: 0.6)),
                const SizedBox(height: 8),
                // 庆祝标题（粗体，emoji 结尾）。
                Text(copy.title,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        color: Colors.white, fontWeight: FontWeight.w700, fontSize: 26, height: 1.2)),
                if (copy.body.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  Text(copy.body,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.6), fontSize: 13, height: 1.6)),
                ],
                const SizedBox(height: 20),
                // B2 宠物行（正文 → 宠物行 20、宠物行 → 日期胶囊 12）。
                if (multiPet) ...[
                  _petRow(),
                  const SizedBox(height: 12),
                ],
                // 宠物 + 日期胶囊（B2：只写日期，无物种 emoji、无名字）。
                if (!multiPet || dateText.isNotEmpty)
                Container(
                  key: const ValueKey('milestoneCelebrationDateChip'),
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.07),
                    borderRadius: BorderRadius.circular(9999),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (!multiPet) ...[
                        Text(_speciesEmoji(widget.item.code), style: const TextStyle(fontSize: 16)),
                        const SizedBox(width: 8),
                      ],
                      Text(dateText,
                          style: TextStyle(
                              fontSize: 12, color: Colors.white.withValues(alpha: 0.7))),
                    ],
                  ),
                ),
                if (widget.collection.isNotEmpty && !multiPet) ...[
                  const SizedBox(height: 24),
                  _collection(l10n),
                ],
              ],
            ),
          ),
                        ),
                      ),
                      // D1 上沿渐隐：吸底栏上方 32 高、splashInk 0 → 100%，叠在滚动区底部、不占布局、不挡交互。
                      Positioned(
                        left: 0,
                        right: 0,
                        bottom: 0,
                        height: 32,
                        child: IgnorePointer(
                          child: DecoratedBox(
                            key: const ValueKey('milestoneCelebrationFade'),
                            decoration: BoxDecoration(
                              gradient: LinearGradient(
                                begin: Alignment.topCenter,
                                end: Alignment.bottomCenter,
                                colors: [AppColors.splashInk.withValues(alpha: 0), AppColors.splashInk],
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                _actionBar(l10n),
              ],
            ),
          ),
          // 右上角关闭按钮（始终有明确出口，配合滑动关闭；处理中仍可点 = 放弃）。放最上层，不被滚动区挡住。
          Positioned(
            top: 4,
            right: 4,
            child: SafeArea(
              child: IconButton(
                key: const ValueKey('milestoneCelebrationClose'),
                icon: const Icon(Icons.close_rounded, color: Colors.white70, size: 26),
                onPressed: _close,
              ),
            ),
          ),
        ],
      ),
      ),
    );
  }

  /// D1 吸底栏（不随内容滚动，底色 splashInk）：16 + 主按钮 52 + 12 + 描边次按钮 52 + 14 + 查看全部（+ 底部安全区，外层 SafeArea）。
  /// D2：处理中的按钮自己转圈（宽高不变），其余按钮 40%、链接 50% 不透明度且不可点。
  Widget _actionBar(AppLocalizations l10n) {
    final busy = _busy;
    const spinner = SizedBox(
      key: ValueKey('milestoneShareBusy'),
      width: 20,
      height: 20,
      child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
    );
    const btnText = TextStyle(fontSize: 14, fontWeight: FontWeight.w700);
    final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(14));
    Widget dim(bool on, double opacity, Widget child) =>
        on ? IgnorePointer(child: Opacity(opacity: opacity, child: child)) : child;
    return ColoredBox(
      color: AppColors.splashInk,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(28, 16, 28, 0),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            dim(
              busy == _Busy.outside,
              0.4,
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('milestoneShare'),
                  // 处理中保持紫底（onPressed 不置 null，否则变禁用灰），点击由 _busy 拦下。
                  onPressed: _onCommunityPressed,
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(52),
                    textStyle: btnText,
                    shape: shape,
                  ),
                  child: busy == _Busy.community ? spinner : Text(l10n.milestoneCelebrateShare),
                ),
              ),
            ),
            const SizedBox(height: 12),
            dim(
              busy == _Busy.community,
              0.4,
              SizedBox(
                key: _outsideKey,
                width: double.infinity,
                child: OutlinedButton(
                  key: const ValueKey('milestoneShareOutside'),
                  onPressed: _onOutsidePressed,
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(52),
                    foregroundColor: Colors.white,
                    textStyle: btnText,
                    shape: shape,
                    side: BorderSide(color: Colors.white.withValues(alpha: 0.35), width: 1.5),
                  ),
                  child: busy == _Busy.outside
                      ? spinner
                      : Text(l10n.milestoneCelebrateShareOutside),
                ),
              ),
            ),
            const SizedBox(height: 14),
            // 查看全部里程碑 → 跳转列表页。
            dim(
              busy != null,
              0.5,
              GestureDetector(
                key: const ValueKey('milestoneCelebrateSeeAll'),
                onTap: _seeAll,
                behavior: HitTestBehavior.opaque,
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Text(l10n.milestoneCelebrateSeeAll,
                      style: TextStyle(color: Colors.white.withValues(alpha: 0.45), fontSize: 13)),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // B2 宠物行：全部 ACTIVE 宠物（建档顺序，最多 5 只，调用方已截断）头像 40 + 名字 11（单行省略），间距 12；
  // 头像 2px splashInk 描边 + 白 25% 外圈。
  Widget _petRow() => Wrap(
        key: const ValueKey('milestoneCelebrationPetRow'),
        alignment: WrapAlignment.center,
        spacing: AppSpacing.md,
        runSpacing: AppSpacing.md,
        children: [
          for (final p in widget.accountPets.take(5))
            SizedBox(
              width: 48,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    padding: const EdgeInsets.all(2),
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: AppColors.splashInk,
                      boxShadow: [
                        BoxShadow(color: Colors.white.withValues(alpha: 0.25), spreadRadius: 2),
                      ],
                    ),
                    child: PetSpeciesAvatar(avatarUrl: p.avatarUrl, petType: p.petType, size: 40, species: p.species),
                  ),
                  const SizedBox(height: 4),
                  Text(p.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      textAlign: TextAlign.center,
                      style: TextStyle(fontSize: 11, color: Colors.white.withValues(alpha: 0.7))),
                ],
              ),
            ),
        ],
      );

  // 物种 emoji（按 code 前缀：C=猫 / D=狗 / 其余=通用）。
  String _speciesEmoji(String code) => switch (code.isEmpty ? '' : code[0]) {
        'C' => '🐱',
        'D' => '🐶',
        _ => '🐾',
      };

  // 「15 Juni 2026」/「15 June 2026」——手写月份名，避免依赖 intl locale 数据加载。
  static const _monthsId = [
    'Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni',
    'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember'
  ];
  static const _monthsEn = [
    'January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'
  ];
  String _formatLongDate(DateTime d, Locale locale) {
    final local = d.toLocal();
    final months = locale.languageCode == 'id' ? _monthsId : _monthsEn;
    return '${local.day} ${months[local.month - 1]} ${local.year}';
  }

  // 渐变大徽章（原型 P-35：紫渐变圆 + 辉光 + 白奖杯）。
  Widget _badge(double size) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: const LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [AppColors.mint, AppColors.mint500],
          ),
          boxShadow: [
            BoxShadow(
                color: AppColors.mint.withValues(alpha: 0.45),
                blurRadius: size * 0.22,
                offset: Offset(0, size * 0.06)),
          ],
        ),
        child: Icon(Icons.emoji_events_rounded, color: Colors.white, size: size * 0.5),
      );

  // 级别配色（与列表页一致：L 金 / M 紫 / S 绿）。
  Color _levelColor(MilestoneLevel level) => switch (level) {
        MilestoneLevel.l => AppColors.gold,
        MilestoneLevel.m => AppColors.mint,
        MilestoneLevel.s => AppColors.triageGreen,
      };

  // 级别小标签（M · MAJOR 等），叠在徽章下沿。
  Widget _levelChip(AppLocalizations l10n) {
    final label = switch (widget.item.level) {
      MilestoneLevel.l => l10n.milestoneLevelChipL,
      MilestoneLevel.m => l10n.milestoneLevelChipM,
      MilestoneLevel.s => l10n.milestoneLevelChipS,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 3),
      decoration: BoxDecoration(
        color: _levelColor(widget.item.level),
        borderRadius: BorderRadius.circular(9999),
        border: Border.all(color: AppColors.splashInk, width: 2),
      ),
      child: Text(label,
          style: const TextStyle(
              fontSize: 10, fontWeight: FontWeight.w700, color: Colors.white, letterSpacing: 0.6)),
    );
  }

  // 已解锁徽章合集预览（原型 KOLEKSI）：只展示已解锁项（级别色圆），最多两排，
  // 放不下时最后一格折叠为「+N」，故「圆点数 + N = 已解锁总数」，与列表页进度一致。
  Widget _collection(AppLocalizations l10n) {
    final unlocked = widget.collection.where((m) => m.completed).toList();
    if (unlocked.isEmpty) return const SizedBox.shrink();
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Text('${l10n.milestoneCelebrateCollection(widget.petName).toUpperCase()} · ${unlocked.length}',
              textAlign: TextAlign.center,
              style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.5,
                  color: Colors.white.withValues(alpha: 0.4))),
          const SizedBox(height: 10),
          LayoutBuilder(
            builder: (context, constraints) {
              const cell = 44.0, gap = 8.0;
              // 按实际宽度算每排个数 → 两排容量；超出则末格显示「+N」。
              final perRow = ((constraints.maxWidth + gap) / (cell + gap)).floor().clamp(1, 99);
              final capacity = perRow * 2;
              final List<Widget> cells;
              if (unlocked.length <= capacity) {
                cells = [for (final m in unlocked) _collectionBadge(m)];
              } else {
                final showN = capacity - 1; // 留一格给「+N」
                cells = [
                  for (final m in unlocked.take(showN)) _collectionBadge(m),
                  _collectionCircle(text: '+${unlocked.length - showN}'),
                ];
              }
              return Wrap(
                spacing: gap,
                runSpacing: gap,
                alignment: WrapAlignment.center,
                children: cells,
              );
            },
          ),
        ],
      ),
    );
  }

  /// KOLEKSI 一枚已解锁徽章：素材到了显示该枚，没到回落原级别色圆（V1.3.2 Story 5.1）。
  Widget _collectionBadge(MilestoneItem m) => MilestoneBadge(
        code: m.code,
        size: 44,
        level: m.level,
        fallback: (_) => _collectionCircle(color: _levelColor(m.level)),
      );

  Widget _collectionCircle({Color? color, String? text}) => Container(
        width: 44,
        height: 44,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: color == null
              ? null
              : LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [color, Color.lerp(color, Colors.white, 0.25)!]),
          color: color == null ? Colors.white.withValues(alpha: 0.1) : null,
          boxShadow: color == null
              ? null
              : [BoxShadow(color: color.withValues(alpha: 0.4), blurRadius: 8, offset: const Offset(0, 3))],
        ),
        child: text != null
            ? Text(text,
                style: TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w700, color: Colors.white.withValues(alpha: 0.6)))
            : const Icon(Icons.emoji_events_rounded, color: Colors.white, size: 20),
      );
}

/// 持续掉落彩纸覆盖层（纯自绘 CustomPainter，无第三方包）。
///
/// 连续不断的关键：每片彩纸有独立的初始相位 [offset] 均匀铺在 0..1（即任意时刻整屏都有彩纸），
/// 且每轮的「下落圈数 [fallTurns]」「自转圈数 [spinTurns]」「左右摆动圈数 [swayTurns]」都取**整数**，
/// 使位置/角度对 progress 以 1 为周期连续 —— controller `repeat()` 在 1→0 回绕时无跳变、无空档。
class _Confetti extends StatefulWidget {
  const _Confetti();

  @override
  State<_Confetti> createState() => _ConfettiState();
}

class _ConfettiState extends State<_Confetti> with SingleTickerProviderStateMixin {
  late final AnimationController _c;
  late final List<_ConfettiPiece> _pieces;

  static const _palette = [
    AppColors.mint,
    AppColors.gold,
    AppColors.coral,
    AppColors.triageGreen,
    AppColors.mint500,
    AppColors.grape,
  ];

  @override
  void initState() {
    super.initState();
    final rnd = Random();
    _pieces = List.generate(54, (i) {
      return _ConfettiPiece(
        x: rnd.nextDouble(),
        size: 5 + rnd.nextDouble() * 9,
        color: _palette[i % _palette.length],
        offset: rnd.nextDouble(), // 均匀铺满整屏，无空档
        fallTurns: 1 + rnd.nextInt(2), // 每轮下落 1~2 屏（整数 → 回绕连续）
        spinTurns: (rnd.nextBool() ? 1 : -1) * (1 + rnd.nextInt(2)), // 整数圈自转
        swayTurns: 1 + rnd.nextInt(2), // 整数次左右摆动
        swayAmp: 10 + rnd.nextDouble() * 34,
        swayPhase: rnd.nextDouble() * pi * 2,
        spin0: rnd.nextDouble() * pi * 2,
      );
    });
    _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 5200))..repeat();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _c,
        builder: (_, _) => CustomPaint(
          size: Size.infinite,
          painter: _ConfettiPainter(_c.value, _pieces),
        ),
      );
}

class _ConfettiPiece {
  const _ConfettiPiece({
    required this.x,
    required this.size,
    required this.color,
    required this.offset,
    required this.fallTurns,
    required this.spinTurns,
    required this.swayTurns,
    required this.swayAmp,
    required this.swayPhase,
    required this.spin0,
  });

  final double x; // 0..1 水平基准
  final double size;
  final Color color;
  final double offset; // 0..1 垂直初始相位
  final int fallTurns; // 每轮下落屏数（整数）
  final int spinTurns; // 每轮自转圈数（整数，带方向）
  final int swayTurns; // 每轮摆动次数（整数）
  final double swayAmp; // 水平摆幅 px
  final double swayPhase;
  final double spin0; // 初始角度
}

class _ConfettiPainter extends CustomPainter {
  _ConfettiPainter(this.progress, this.pieces);

  final double progress;
  final List<_ConfettiPiece> pieces;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint();
    for (final p in pieces) {
      // 垂直相位 0..1（整数 fallTurns → 1→0 回绕处连续）。
      final phase = (p.offset + progress * p.fallTurns) % 1.0;
      final y = phase * (size.height + 40) - 20;
      final x = p.x * size.width + sin(phase * 2 * pi * p.swayTurns + p.swayPhase) * p.swayAmp;
      final rot = p.spin0 + progress * p.spinTurns * 2 * pi;
      paint.color = p.color;
      canvas.save();
      canvas.translate(x, y);
      canvas.rotate(rot);
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromCenter(center: Offset.zero, width: p.size, height: p.size * 0.6),
          const Radius.circular(2),
        ),
        paint,
      );
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(covariant _ConfettiPainter oldDelegate) => oldDelegate.progress != progress;
}
