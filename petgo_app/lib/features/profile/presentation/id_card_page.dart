import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../data/id_card_repository.dart';
import '../domain/id_card.dart';
import '../domain/id_card_style.dart';
import 'id_card/id_card_style_grid.dart';
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
    // AD-12 / T-12：wire 值大写；非学生卡不带 card_variant 键。
    Analytics.capture('id_card_style_tapped', {
      'card_type': style.cardType,
      if (style.cardVariant != null) 'card_variant': style.cardVariant!,
    });
    context.push(IdCardCreatePage.locationFor(style));
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

  /// Kartuku：原列表页内容原样平移（Story 1.6 再改造）。只在本子树 watch 列表，列表状态变化不重建网格。
  Widget _myCards(BuildContext context, AppLocalizations l10n) {
    return Consumer(
      builder: (context, ref, _) => ref.watch(idCardListProvider).when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (_, _) => _centered(l10n.idCardLoadError),
            data: (cards) => cards.isEmpty ? _empty(context, l10n) : _list(context, l10n, cards),
          ),
    );
  }

  Widget _list(BuildContext context, AppLocalizations l10n, List<IdCard> cards) {
    return SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        children: [
          Text(l10n.idCardListSubtitle,
              style: const TextStyle(fontSize: 12.5, color: AppColors.ink2, height: 1.5)),
          const SizedBox(height: 14),
          _createEntry(context, l10n),
          const SizedBox(height: 14),
          for (final card in cards) ...[
            _cardTile(context, l10n, card),
            const SizedBox(height: 10),
          ],
        ],
      ),
    );
  }

  Widget _empty(BuildContext context, AppLocalizations l10n) {
    return SafeArea(
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('🪪', style: TextStyle(fontSize: 44)),
              const SizedBox(height: 14),
              Text(l10n.idCardListEmptyTitle,
                  style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
              const SizedBox(height: 8),
              Text(l10n.idCardListEmptyBody,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13, color: AppColors.ink2, height: 1.5)),
              const SizedBox(height: 22),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  key: const ValueKey('idCardCreateEntry'),
                  style: FilledButton.styleFrom(
                      backgroundColor: AppColors.mint, padding: const EdgeInsets.symmetric(vertical: 14)),
                  onPressed: () => context.push('/profile/id-cards/create'),
                  icon: const Icon(Icons.add),
                  label: Text(l10n.idCardCreateEntry),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _createEntry(BuildContext context, AppLocalizations l10n) {
    return Material(
      color: AppColors.mintTint,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        key: const ValueKey('idCardCreateEntry'),
        borderRadius: BorderRadius.circular(14),
        onTap: () => context.push('/profile/id-cards/create'),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: Row(
            children: [
              Container(
                width: 40,
                height: 40,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: AppColors.mint, borderRadius: BorderRadius.circular(11)),
                child: const Icon(Icons.add, color: Colors.white),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(l10n.idCardCreateEntry,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.mint)),
              ),
              const Icon(Icons.chevron_right, color: AppColors.mint),
            ],
          ),
        ),
      ),
    );
  }

  Widget _cardTile(BuildContext context, AppLocalizations l10n, IdCard card) {
    // 展示优先新编码 cardNo（TT 开头 14 位）；旧卡（cardNo=null）维持旧 serial 展示。
    final serial = (card.cardNo?.isNotEmpty == true)
        ? card.cardNo!
        : (card.serialId == null ? '----' : card.serialId!.toString().padLeft(4, '0'));
    // bug 20260730-430：一卡一面后列表须能分辨卡种。V1.3.4 ktp（AD-6）：卡款名取注册表（8 款各显示自己的名称）。
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
                child: const Text('🪪', style: TextStyle(fontSize: 22)),
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
                        style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
                    if (card.createdAt != null) ...[
                      const SizedBox(height: 1),
                      Text(l10n.idCardCreatedLabel(formatDayMonthYear(context, card.createdAt!.toLocal())),
                          style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 8),
              if (card.hdUnlocked)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                      color: AppColors.mintTint2, borderRadius: BorderRadius.circular(8)),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.check_circle_rounded, size: 13, color: AppColors.mint),
                      const SizedBox(width: 4),
                      Text(l10n.idCardHdUnlockedBadge,
                          style: const TextStyle(
                              fontSize: 10.5, fontWeight: FontWeight.w700, color: AppColors.mint)),
                    ],
                  ),
                ),
              const SizedBox(width: 4),
              const Icon(Icons.chevron_right, color: AppColors.line),
            ],
          ),
        ),
      ),
    );
  }

  Widget _centered(String text) => Center(
      child: Padding(padding: const EdgeInsets.all(24), child: Text(text, textAlign: TextAlign.center)));
}
