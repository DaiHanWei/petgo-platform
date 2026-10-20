import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../pet_slot/presentation/pet_readonly_block.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../data/id_card_repository.dart';
import '../data/pet_list_repository.dart';
import '../domain/id_card.dart';
import '../domain/id_card_style.dart';
import 'id_card/id_card_hd_flow.dart';
import 'id_card/id_card_style_grid.dart';
import 'id_card/id_card_ui_style.dart';
import 'id_card_create_page.dart';

/// 身份证列表页（Story 6-7；V1.3.4 ktp Story 1.5 改双 Tab）。
///
/// 「Pilih Kartu / Create new」：8 款样式网格（[IdCardStyleGrid]），点格带卡款进建卡页；
/// 「Kartuku / My Cards」：历史快照卡列表（原列表页内容原样平移）+ 建卡入口。
/// 从任何入口进入都停在 Pilih Kartu、不记忆上次 Tab（FR-142B.1 / AD-10）；从 Kartuku 点卡进详情再返回
/// 仍停在 Kartuku（D-14）。Pilih Kartu 不依赖卡列表接口，加载 / 失败态只在 Kartuku 内。
class IdCardPage extends ConsumerStatefulWidget {
  const IdCardPage({super.key, this.clock});

  /// 测试注入当前时间（网格 `new` 角标判定）；生产为 null。
  @visibleForTesting
  final DateTime Function()? clock;

  @override
  ConsumerState<IdCardPage> createState() => _IdCardPageState();
}

class _IdCardPageState extends ConsumerState<IdCardPage> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 2, vsync: this);

  /// 最近一次已上报 `id_card_tab_viewed` 的 Tab；null = 进入页面那一次尚未上报（等列表首个结果）。
  int? _reportedTab;

  static const List<String> _tabWire = ['style', 'created'];

  /// 列表「Buka HD」付费进行中（防重入）。
  bool _hdBusy = false;

  @override
  void initState() {
    super.initState();
    _tabs.addListener(_onTabChanged);
    // 进入页面那一次：等列表首个结果（成功或失败）出来再报，带上 has_cards（失败则省略，T-9）。
    // ⚠️ Riverpod 3 失败会自动重试，重试期间状态是「带 error 的 AsyncLoading」，故按 hasError 也算有结果。
    ref.listenManual<AsyncValue<List<IdCard>>>(idCardListProvider, (_, next) {
      if (_reportedTab != null || (next.isLoading && !next.hasError)) return;
      // 进入页面恒在 Pilih Kartu：先报这一次；若结果到达前用户已切了 Tab，再补报当前 Tab。
      _reportTab(0);
      if (_tabs.index != 0 && !_tabs.indexIsChanging) _reportTab(_tabs.index);
    }, fireImmediately: true);
  }

  @override
  void dispose() {
    _tabs.removeListener(_onTabChanged);
    _tabs.dispose();
    super.dispose();
  }

  void _onTabChanged() {
    if (_tabs.indexIsChanging) return; // 动画中途不报，落定后报一次
    if (_reportedTab == null || _tabs.index == _reportedTab) return; // 首报未发 / 同一 Tab 重复点不报
    _reportTab(_tabs.index);
  }

  void _reportTab(int index) {
    _reportedTab = index;
    final cards = ref.read(idCardListProvider);
    Analytics.capture('id_card_tab_viewed', {
      'tab': _tabWire[index],
      if (cards.hasValue && !cards.hasError) 'has_cards': cards.value!.isNotEmpty,
    });
  }

  void _onStyleTap(IdCardStyle style) {
    // V1.4.0 multi-pet Story 2.4 · AC5.5 + Story 4.2（AD-8「KTP 创建只列正常宠物，卡不挂宠物」/ decision-log I2）：
    // 当前宠物只读但账号还有正常宠物 → 照常进建卡页（预填最早创建的正常宠物）；只有**一只正常宠物都没有**才弹 E1。
    if (!_guardKtpEntry()) return;
    // AD-12 / T-12：wire 值大写；非学生卡不带 card_variant 键。
    Analytics.capture('id_card_style_tapped', {
      'card_type': style.cardType,
      if (style.cardVariant != null) 'card_variant': style.cardVariant!,
    });
    context.push(IdCardCreatePage.locationFor(style));
  }

  /// 建卡入口的只读拦截（Story 4.2）：当前宠物只读 **且** 已加载的宠物列表里没有任何 ACTIVE → 弹 E1、返回 false；
  /// 其余（当前宠物可写 / 还有正常宠物可预填 / 列表未加载 = 不知道）→ 放行。
  bool _guardKtpEntry() {
    final pet = currentPetIfLoaded(ref);
    if (pet == null || !pet.isReadOnly) return true;
    final pets = ref.read(petListProvider).value ?? const <PetListItem>[];
    if (pets.any((p) => !p.isReadOnly)) return true;
    showPetReadonlyBlockSheet(context, ref, pet);
    return false;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.cream2,
      appBar: AppBar(
        title: Text(l10n.idCardTitle),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(46),
          child: Container(
            height: 46,
            decoration: const BoxDecoration(
              color: AppColors.cream,
              border: Border(bottom: BorderSide(color: AppColors.line)),
            ),
            child: TabBar(
              controller: _tabs,
              labelColor: AppColors.ink,
              unselectedLabelColor: AppColors.muted,
              labelStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
              unselectedLabelStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
              indicatorColor: AppColors.mint,
              indicatorWeight: 2.5,
              indicatorSize: TabBarIndicatorSize.tab,
              dividerColor: Colors.transparent,
              tabs: [
                Tab(key: const ValueKey('idCardTab_style'), height: 46, text: l10n.idCardTabStyles),
                Tab(key: const ValueKey('idCardTab_created'), height: 46, text: l10n.idCardTabMyCards),
              ],
            ),
          ),
        ),
      ),
      body: TabBarView(
        controller: _tabs,
        children: [
          IdCardStyleGrid(onTap: _onStyleTap, clock: widget.clock),
          _myCards(context, l10n),
        ],
      ),
    );
  }

  /// Kartuku（V1.3.4 ktp Story 1.6，UI 稿 B2 / B3 / B4 / B5）。只在本子树 watch 列表，列表状态变化不重建网格。
  Widget _myCards(BuildContext context, AppLocalizations l10n) {
    return Consumer(builder: (context, ref, _) {
      final async = ref.watch(idCardListProvider);
      // 有数据就显示数据：付费成功后的静默刷新（含刷新失败、自动重试）保留旧列表，不闪整页转圈（AC4.4）。
      final cards = async.hasValue ? async.value : null;
      if (cards != null) return cards.isEmpty ? _empty(l10n) : _list(context, l10n, cards);
      // 无数据：加载中与「Coba lagi」重拉（含 Riverpod 3 自动重试）显示转圈（B4），否则失败页（B5）。
      if (async.isLoading) return _loading();
      return _error(l10n);
    });
  }

  Widget _loading() => const Center(child: CircularProgressIndicator());

  /// 有卡态（B3）：说明句 + 卡片行，吸底「Buat Kartu Baru」。
  Widget _list(BuildContext context, AppLocalizations l10n, List<IdCard> cards) {
    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
            children: [
              Text(l10n.idCardListSubtitle,
                  style: const TextStyle(fontSize: 12.5, color: AppColors.ink2, height: 1.5)),
              const SizedBox(height: 14),
              // 顺序 = 接口返回顺序（后端已按建卡时刻倒序），App 不再排序。
              for (final card in cards) ...[
                _cardTile(context, l10n, card),
                const SizedBox(height: 10),
              ],
            ],
          ),
        ),
        _createNewCta(l10n),
      ],
    );
  }

  /// 空态（B2）：紫浅底圆形证件图标 + 标题 / 说明，吸底「Buat Kartu Baru」。
  Widget _empty(AppLocalizations l10n) {
    return Column(
      children: [
        Expanded(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(28),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 72,
                    height: 72,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: AppColors.mintTint,
                      shape: BoxShape.circle,
                      border: Border.all(color: AppColors.lineViolet),
                    ),
                    child: const Icon(Icons.badge_outlined, size: 34, color: AppColors.mint),
                  ),
                  const SizedBox(height: 14),
                  Text(l10n.idCardListEmptyTitle,
                      textAlign: TextAlign.center,
                      style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
                  const SizedBox(height: 8),
                  // 稿 B2 `.e-b{ max-width:280px }`。
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 280),
                    child: Text(l10n.idCardListEmptyBody,
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 13, color: AppColors.ink2, height: 1.5)),
                  ),
                ],
              ),
            ),
          ),
        ),
        _createNewCta(l10n),
      ],
    );
  }

  /// 失败态（B5）：断网图标 + 文案 +「Coba lagi」描边按钮，点击重新拉取。
  Widget _error(AppLocalizations l10n) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 72,
              height: 72,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: AppColors.card,
                shape: BoxShape.circle,
                border: Border.all(color: AppColors.line),
              ),
              child: const Icon(Icons.cloud_off_outlined, size: 34, color: AppColors.muted),
            ),
            const SizedBox(height: 14),
            Text(l10n.idCardLoadError,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13.5, color: AppColors.ink2, height: 1.5)),
            const SizedBox(height: 18),
            SizedBox(
              height: 44,
              child: OutlinedButton(
                key: const ValueKey('idCardListRetry'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.mint,
                  side: const BorderSide(color: AppColors.lineViolet, width: 1.5),
                  padding: const EdgeInsets.symmetric(horizontal: 28),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
                ),
                onPressed: () => ref.invalidate(idCardListProvider),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.refresh_rounded, size: 18),
                    const SizedBox(width: 6),
                    Text(l10n.commonRetry, style: const TextStyle(fontWeight: FontWeight.w600)),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 吸底主按钮「Buat Kartu Baru」：切到 Pilih Kartu 选款（不再直接 push 建卡页）。
  Widget _createNewCta(AppLocalizations l10n) {
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
        child: SizedBox(
          width: double.infinity,
          child: FilledButton.icon(
            key: const ValueKey('idCardCreateNewCta'),
            style: idCardPrimaryButtonStyle(),
            onPressed: () => _tabs.animateTo(0),
            icon: const Icon(Icons.add),
            label: Text(l10n.idCardCreateNewCta),
          ),
        ),
      ),
    );
  }

  /// 「Buka HD」标签：直接弹付费抽屉，不进详情（FR-142B.9）；落点 = 留在列表（AD-10 `list_badge`）。
  Future<void> _onBukaHd(IdCard card) async {
    if (_hdBusy) return; // 付费进行中不可再次触发，防连点重复下单
    setState(() => _hdBusy = true);
    try {
      await openIdCardHdPaywall(context, ref, card, entry: IdCardPaywallEntry.listBadge);
    } finally {
      if (mounted) setState(() => _hdBusy = false);
    }
  }

  Widget _cardTile(BuildContext context, AppLocalizations l10n, IdCard card) {
    // 编号 = card_no 原样（SIM / 居住证也不格式化，FR-142E.7）；旧卡（cardNo=null）维持 serial 补零 4 位。
    final serial = (card.cardNo?.isNotEmpty == true)
        ? card.cardNo!
        : (card.serialId == null ? '----' : card.serialId!.toString().padLeft(4, '0'));
    // 卡款名取注册表（AD-6，8 款各显示自己的名称）。
    final typeLabel = IdCardStyle.fromCard(card).label(l10n);
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(14),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        key: ValueKey('idCardTile_${card.id}'),
        onTap: () => context.push('/profile/id-cards/${card.id}'),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Container(
                width: 46,
                height: 46,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: AppColors.mintTint, borderRadius: BorderRadius.circular(12)),
                child: const Icon(Icons.badge_outlined, size: 24, color: AppColors.mint),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(card.name ?? 'Pet',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
                    const SizedBox(height: 3),
                    Text('$typeLabel · ${l10n.idCardSerialLabel(serial)}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 12,
                            color: AppColors.ink2,
                            fontFeatures: [FontFeature.tabularFigures()])),
                    if (card.createdAt != null) ...[
                      const SizedBox(height: 1),
                      Text(l10n.idCardCreatedLabel(formatDayMonthYear(context, card.createdAt!.toLocal())),
                          style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                    ],
                  ],
                ),
              ),
              if (!card.hdUnlocked) ...[
                const SizedBox(width: 4),
                _bukaHdTag(l10n, card),
              ],
              const SizedBox(width: 4),
              const Icon(Icons.chevron_right, color: AppColors.line),
            ],
          ),
        ),
      ),
    );
  }

  /// 紫色胶囊「Buka HD」：独立 ≥44×44 热区，点击不冒泡到整行（整行点进详情）。
  Widget _bukaHdTag(AppLocalizations l10n, IdCard card) {
    return GestureDetector(
      key: ValueKey('idCardBukaHd_${card.id}'),
      behavior: HitTestBehavior.opaque,
      // 付费进行中也占住点击（_onBukaHd 内防重入），否则无识别器的 GestureDetector 会让点击落到整行进详情。
      onTap: () => _onBukaHd(card),
      child: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
        child: Center(
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
            decoration: BoxDecoration(color: AppColors.mint, borderRadius: BorderRadius.circular(999)),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.lock_outline, size: 12, color: Colors.white),
                const SizedBox(width: 4),
                Text(l10n.idCardUnlockHd,
                    style: const TextStyle(fontSize: 10.5, fontWeight: FontWeight.w700, color: Colors.white)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
