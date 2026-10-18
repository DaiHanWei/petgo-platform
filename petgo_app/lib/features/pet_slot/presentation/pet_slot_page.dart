import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../profile/data/pet_list_repository.dart';
import '../data/pet_slot_repository.dart';
import '../domain/pet_slot_purchase.dart';
import 'active_selection_sheet.dart';
import 'pet_readonly_block.dart';
import 'pet_slot_pet_row.dart';
import 'pet_slot_purchase_flow.dart';

/// 名额管理页（`/me/pet-slots`，V1.4.0 multi-pet Story 2.4 · AC1 · UI 稿 D1 ~ D4 / D15）。
///
/// 从上到下：名额卡（已用 / 总数 + 5 个名额格）→「Slot sewa」（有月租才有）→ Hewanku（每只状态；有只读时
/// 「Ubah pilihan ›」+ 说明）→ 不自动续费说明 → 吸底「Sewa slot · {price} / {days} hari」（+「Perpanjang」）。
/// 价格 / 天数一律取后端（无本地兜底价）。数据：[petSlotOverviewProvider] + [petListProvider]。
///
/// [action]（`?action=rent|renew`）：进页拿到最新摘要后直接弹抽屉 ——
/// `rent`（A4「Sewa slot」）→ D5；`renew`（B4 / E1「Perpanjang」，P-5）→ 有生效中月租 D11，没有 → D5、数量默认 `shortBy`。
class PetSlotPage extends ConsumerStatefulWidget {
  const PetSlotPage({super.key, this.action});

  final String? action;

  @override
  ConsumerState<PetSlotPage> createState() => _PetSlotPageState();
}

class _PetSlotPageState extends ConsumerState<PetSlotPage> {
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    // AC1.5：进入本页先重取（名额 / 宠物状态可能已在别处变化），再按 action 自动弹抽屉。
    WidgetsBinding.instance.addPostFrameCallback((_) => _enter());
  }

  Future<void> _enter() async {
    if (!mounted) return;
    ref.invalidate(petSlotOverviewProvider);
    ref.invalidate(petListProvider);
    final action = widget.action;
    if (action != kPetSlotActionRent && action != kPetSlotActionRenew) return;
    PetSlotOverview o;
    try {
      o = await ref.read(petSlotOverviewProvider.future);
    } catch (_) {
      return; // 失败态 D15 由页面渲染
    }
    if (!mounted) return;
    if (action == kPetSlotActionRenew && o.hasRentals) {
      await _renew(o);
    } else if (o.rentable > 0) {
      await _rent(o, initial: action == kPetSlotActionRenew && o.shortBy > 0 ? o.shortBy : 1);
    }
  }

  Future<void> _rent(PetSlotOverview o, {int initial = 1}) async {
    if (_busy || o.rentable <= 0) return;
    setState(() => _busy = true);
    try {
      await runPetSlotRent(context, ref, o, initialQuantity: initial);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _renew(PetSlotOverview o) async {
    if (_busy || !o.hasRentals) return;
    setState(() => _busy = true);
    try {
      await runPetSlotRenew(context, ref, o);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _changeSelection(PetSlotOverview o, List<PetListItem> pets) async {
    final l10n = AppLocalizations.of(context);
    final saved = await showActiveSelectionSheet(context, pets: pets, slotTotal: o.total);
    if (!saved || !mounted) return;
    ref.invalidate(petSlotOverviewProvider);
    ref.invalidate(petListProvider);
    showAppToast(context, l10n.petSlotSelectSaved);
  }

  void _retry() {
    ref.invalidate(petSlotOverviewProvider);
    ref.invalidate(petListProvider);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final overviewAsync = ref.watch(petSlotOverviewProvider);
    final petsAsync = ref.watch(petListProvider);
    final Widget body;
    if (overviewAsync.hasError && !overviewAsync.isLoading ||
        petsAsync.hasError && !petsAsync.isLoading) {
      // D15：整页 cloud_off +「Gagal memuat slot hewan」+「Coba lagi」（写法照 KTP 列表 B5 / 登机牌列表）。
      body = EmptyState(
        key: const ValueKey('petSlotLoadError'),
        title: l10n.petSlotLoadError,
        icon: Icons.cloud_off_rounded,
        actionLabel: l10n.commonRetry,
        onAction: _retry,
      );
    } else if (overviewAsync.value == null || petsAsync.value == null) {
      body = const Center(child: CircularProgressIndicator());
    } else {
      body = _content(l10n, overviewAsync.value!, petsAsync.value!);
    }
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.base,
        centerTitle: true,
        scrolledUnderElevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.chevron_left_rounded, size: 28, color: AppColors.ink),
          onPressed: () => context.canPop() ? context.pop() : context.go('/me'),
        ),
        title: Text(l10n.petSlotPageTitle),
      ),
      body: body,
    );
  }

  Widget _content(AppLocalizations l10n, PetSlotOverview o, List<PetListItem> pets) {
    return Column(
      children: [
        Expanded(
          child: ListView(
            key: const ValueKey('petSlotPageList'),
            padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 8, AppSpacing.lg, AppSpacing.lg),
            children: [
              _SlotCard(overview: o, pets: pets),
              if (o.hasRentals) ...[
                const SizedBox(height: 20),
                _sectionTitle(l10n.petSlotRentalsSection),
                const SizedBox(height: 8),
                for (final r in o.rentals) _RentalRow(rental: r),
              ],
              const SizedBox(height: 20),
              Row(
                children: [
                  Expanded(child: _sectionTitle(l10n.mePetsSection)),
                  if (o.needsSelection)
                    GestureDetector(
                      key: const ValueKey('petSlotChangeSelection'),
                      behavior: HitTestBehavior.opaque,
                      onTap: () => _changeSelection(o, pets),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(l10n.petSlotChangeSelection,
                              style: const TextStyle(
                                  fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.mint)),
                          const Icon(Icons.chevron_right, size: 16, color: AppColors.mint),
                        ],
                      ),
                    ),
                ],
              ),
              if (o.needsSelection && o.shortBy > 0) ...[
                const SizedBox(height: 6),
                Text(l10n.petReadonlyShortNote(o.shortBy),
                    key: const ValueKey('petSlotShortNote'),
                    style: const TextStyle(fontSize: 12, height: 1.45, color: AppColors.ink2)),
              ],
              const SizedBox(height: 8),
              for (final p in pets)
                Padding(
                  key: ValueKey('petSlotPet-${p.id}'),
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: PetSlotPetRow(pet: p, trailing: _StatusPill(readOnly: p.isReadOnly)),
                ),
              const SizedBox(height: 16),
              Text(l10n.petSlotNoAutoRenewNote,
                  style: const TextStyle(fontSize: 12, height: 1.45, color: AppColors.muted)),
            ],
          ),
        ),
        _bottomBar(l10n, o),
      ],
    );
  }

  Widget _sectionTitle(String text) => Text(text,
      style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink));

  Widget _bottomBar(AppLocalizations l10n, PetSlotOverview o) {
    final canRent = o.rentable > 0;
    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 10, AppSpacing.lg, 14),
        decoration: const BoxDecoration(
          color: AppColors.card,
          border: Border(top: BorderSide(color: AppColors.line2)),
        ),
        child: Row(
          children: [
            Expanded(
              child: FilledButton(
                key: const ValueKey('petSlotRentButton'),
                // D4：已到 5 个名额 → 置灰「Sudah maksimal 5 slot」；续租照常。
                onPressed: canRent && !_busy ? () => _rent(o) : null,
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.mint,
                  foregroundColor: AppColors.onAccent,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                ),
                child: Text(
                  canRent
                      ? l10n.petSlotRentCta(petSlotPrice(o.unitPrice), o.days)
                      : l10n.petSlotMaxReached,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
                ),
              ),
            ),
            if (o.hasRentals) ...[
              const SizedBox(width: 10),
              OutlinedButton(
                key: const ValueKey('petSlotRenewButton'),
                onPressed: _busy ? null : () => _renew(o),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.mint,
                  side: const BorderSide(color: AppColors.mint),
                  padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
                ),
                child: Text(l10n.petSlotRenew,
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// 一个名额格的形态（AC1.2）。
enum PetSlotCellKind { pet, empty, available }

/// 名额格（纯函数，L0 可测）：宠物按「正常在前、只读在后」依次占格；已拥有的名额来源依次为
/// 默认 × defaultSlots → 等级 × levelSlots → 月租 × rentalSlots。之后已拥有未占用 →「Kosong」，其余 →「Bisa disewa」。
class PetSlotCell {
  const PetSlotCell(this.kind, {this.pet, this.source});

  final PetSlotCellKind kind;
  final PetListItem? pet;

  /// `default` / `level` / `rent`；超出名额的只读宠物格为 null。
  final String? source;

  static List<PetSlotCell> build(PetSlotOverview o, List<PetListItem> pets) {
    final sources = <String>[
      for (var i = 0; i < o.defaultSlots; i++) 'default',
      for (var i = 0; i < o.levelSlots; i++) 'level',
      for (var i = 0; i < o.rentalSlots; i++) 'rent',
    ];
    final ordered = [...pets.where((p) => !p.isReadOnly), ...pets.where((p) => p.isReadOnly)];
    final count = [o.cap, ordered.length, o.total].reduce((a, b) => a > b ? a : b);
    return [
      for (var i = 0; i < count; i++)
        if (i < ordered.length)
          PetSlotCell(PetSlotCellKind.pet,
              pet: ordered[i], source: i < sources.length ? sources[i] : null)
        else if (i < o.total)
          PetSlotCell(PetSlotCellKind.empty, source: i < sources.length ? sources[i] : null)
        else
          const PetSlotCell(PetSlotCellKind.available),
    ];
  }
}

class _SlotCard extends StatelessWidget {
  const _SlotCard({required this.overview, required this.pets});

  final PetSlotOverview overview;
  final List<PetListItem> pets;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final cells = PetSlotCell.build(overview, pets);
    return Container(
      key: const ValueKey('petSlotCard'),
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
        boxShadow: const [BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8)],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Text('${overview.petCount}',
                  key: const ValueKey('petSlotUsed'),
                  style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w800, color: AppColors.mint)),
              const SizedBox(width: 6),
              Flexible(
                child: Text(l10n.petSlotUsedSuffix(overview.total),
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink2)),
              ),
            ],
          ),
          const SizedBox(height: 2),
          Text(l10n.petSlotCapNote, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
          const SizedBox(height: 14),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (var i = 0; i < cells.length; i++)
                Expanded(child: _Cell(key: ValueKey('petSlotCell-$i'), cell: cells[i])),
            ],
          ),
        ],
      ),
    );
  }
}

class _Cell extends StatelessWidget {
  const _Cell({super.key, required this.cell});

  final PetSlotCell cell;

  static const ColorFilter _greyscale = ColorFilter.matrix(<double>[
    0.2126, 0.7152, 0.0722, 0, 0, //
    0.2126, 0.7152, 0.0722, 0, 0, //
    0.2126, 0.7152, 0.0722, 0, 0, //
    0, 0, 0, 0.75, 0,
  ]);

  String? _sourceLabel(AppLocalizations l10n) => switch (cell.source) {
        'default' => l10n.petSlotSourceDefault,
        'level' => l10n.petSlotSourceLevel,
        'rent' => l10n.petSlotSourceRent,
        _ => null,
      };

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    const double size = 44;
    final Widget circle;
    final String label;
    var labelColor = AppColors.ink2;
    switch (cell.kind) {
      case PetSlotCellKind.pet:
        final pet = cell.pet!;
        final hasAvatar = pet.avatarUrl != null && pet.avatarUrl!.isNotEmpty;
        Widget avatar = CircleAvatar(
          radius: size / 2,
          backgroundColor: AppColors.border,
          backgroundImage: AppImage.provider(pet.avatarUrl, thumbWidth: 120),
          child: hasAvatar ? null : const Icon(Icons.pets, size: 20, color: AppColors.textTertiary),
        );
        if (pet.isReadOnly) avatar = ColorFiltered(colorFilter: _greyscale, child: avatar);
        circle = Stack(
          clipBehavior: Clip.none,
          children: [
            avatar,
            if (pet.isReadOnly)
              Positioned(
                left: -3,
                bottom: -2,
                child: Container(
                  width: 18,
                  height: 18,
                  decoration: BoxDecoration(
                    color: AppColors.ink2,
                    shape: BoxShape.circle,
                    border: Border.all(color: Colors.white, width: 2),
                  ),
                  child: const Icon(Icons.lock, size: 9, color: Colors.white),
                ),
              ),
          ],
        );
        if (pet.isReadOnly) {
          label = l10n.petReadonlyBadge;
          labelColor = AppColors.muted;
        } else {
          label = _sourceLabel(l10n) ?? '';
          labelColor = AppColors.mint;
        }
      case PetSlotCellKind.empty:
        circle = Container(
          width: size,
          height: size,
          decoration: BoxDecoration(
            color: AppColors.mintTint,
            shape: BoxShape.circle,
            border: Border.all(color: AppColors.dashedViolet, width: 1.5),
          ),
          child: const Icon(Icons.add, size: 20, color: AppColors.mint),
        );
        final src = _sourceLabel(l10n);
        label = src == null ? l10n.petSlotCellEmpty : '${l10n.petSlotCellEmpty} · $src';
      case PetSlotCellKind.available:
        circle = Container(
          width: size,
          height: size,
          decoration: const BoxDecoration(color: AppColors.cream2, shape: BoxShape.circle),
          child: const Icon(Icons.lock_outline, size: 18, color: AppColors.muted),
        );
        label = l10n.petSlotCellAvailable;
        labelColor = AppColors.muted;
    }
    return Column(
      children: [
        circle,
        const SizedBox(height: 6),
        if (cell.kind == PetSlotCellKind.pet)
          Text(cell.pet!.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.ink)),
        Text(label,
            maxLines: 2,
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 10.5, color: labelColor, height: 1.2)),
      ],
    );
  }
}

class _RentalRow extends StatelessWidget {
  const _RentalRow({required this.rental});

  final PetSlotRental rental;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final date = formatDayMonthYear(context, rental.expiresAt.toLocal());
    final soon = rental.endsSoon;
    return Padding(
      key: ValueKey('petSlotRental-${rental.index}'),
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Container(
            width: 36,
            height: 36,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: soon ? AppColors.goldTint : AppColors.mintTint,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(Icons.event_outlined, size: 18, color: soon ? AppColors.gold : AppColors.mint),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.petSlotRentalName(rental.index),
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink)),
                const SizedBox(height: 2),
                Text(
                  soon ? l10n.petSlotRentalEndsSoon(date, rental.daysLeft) : l10n.petSlotRentalEnds(date),
                  key: ValueKey('petSlotRentalEnds-${rental.index}'),
                  style: TextStyle(
                      fontSize: 12,
                      fontWeight: soon ? FontWeight.w600 : FontWeight.w400,
                      color: soon ? AppColors.gold : AppColors.ink2),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.readOnly});

  final bool readOnly;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: readOnly ? AppColors.cream2 : AppColors.mintTint,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (readOnly) ...[
            const Icon(Icons.lock, size: 11, color: AppColors.muted),
            const SizedBox(width: 3),
          ],
          Text(readOnly ? l10n.petReadonlyBadge : l10n.petSlotStatusActive,
              style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  color: readOnly ? AppColors.muted : AppColors.mint)),
        ],
      ),
    );
  }
}
