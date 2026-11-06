/// 送达地区抽屉（v1.4.2 shop Story 8.2 · UI B6）。
///
/// 省 / 市 / 区三级，数据取开通区域树（`/shop/regions`，[regionTreeProvider]）；顶部面包屑显示已选层级，
/// 点某一级回到该级重选；**未开通的区仍列出**、标「Belum dilayani」仍可选；当前所选打 `check`。
/// 选中区即关抽屉并返回 [ShopDeliveryArea] —— 🔴 **只记本地、不生成收货地址**（写 prefs 由调用方做）。
///
/// v1.4.2 shop Story 9.4（H4）：泛化为共享组件 —— 地址表单以**表单模式**（[showRegionPickerSheet]）调用同一抽屉：
/// 选完回调表单字段、不写本地记忆、不要求区有 id、标题「Pilih wilayah」、各级当前所选打勾。
/// 默认模式（[showDeliveryAreaSheet]，8.2 B6）行为不变。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/storage/prefs.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/shop_tokens.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../address/data/address_repository.dart';
import '../../address/domain/shipping_address.dart';
import 'shop_pressable.dart';

/// 打开 B6。返回所选区（含区域树里该区的 `serviceable`）；关掉 → null。
Future<ShopDeliveryArea?> showDeliveryAreaSheet(BuildContext context, {int? selectedKecamatanId}) =>
    showModalBottomSheet<ShopDeliveryArea>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: AppColors.card,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (_) => DeliveryAreaSheet(selectedKecamatanId: selectedKecamatanId),
    );

/// 表单模式（H4）的选择结果 / 当前值：省 · 市 · 区三级名称 + 区是否开通。
class RegionSelection {
  const RegionSelection({
    required this.provinsi,
    required this.kota,
    required this.kecamatan,
    this.serviceable = true,
  });

  final String provinsi;
  final String kota;
  final String kecamatan;
  final bool serviceable;
}

/// 打开 H4（地址表单的地区选择）。返回所选区；关掉 → null。
///
/// 🔴 **未开通的区同样可选**（只告知不阻断，FR-98 / FR-99），行尾灰字「Belum dilayani」。
Future<RegionSelection?> showRegionPickerSheet(BuildContext context, {RegionSelection? selected}) =>
    showModalBottomSheet<RegionSelection>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: AppColors.card,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (_) => DeliveryAreaSheet(formMode: true, formSelected: selected),
    );

class DeliveryAreaSheet extends ConsumerStatefulWidget {
  const DeliveryAreaSheet(
      {super.key, this.selectedKecamatanId, this.formMode = false, this.formSelected});

  final int? selectedKecamatanId;

  /// 表单模式（9.4 H4）。默认 false = 8.2 B6 现状。
  final bool formMode;

  /// 表单模式下的当前值（打勾、并直接展开到其所在市的区列表）。
  final RegionSelection? formSelected;

  @override
  ConsumerState<DeliveryAreaSheet> createState() => _DeliveryAreaSheetState();
}

class _DeliveryAreaSheetState extends ConsumerState<DeliveryAreaSheet> {
  /// 已选层级（[0] = 省，[1] = 市）。回到某级只需截断。
  List<String> _path = const [];

  @override
  void initState() {
    super.initState();
    final sel = widget.formSelected;
    // 表单模式且已有值 → 直接停在该市的区列表（面包屑可回上级重选）；名字在树里找不到时 _list 会回顶层。
    if (widget.formMode && sel != null) _path = [sel.provinsi, sel.kota];
  }

  /// 「belum dilayani」→「Belum dilayani」（9.4 待确认 3：复用原 key，句首大写渲染）。
  String _notServiceableTag(AppLocalizations l10n) {
    final t = l10n.addressNotServiceable;
    if (!widget.formMode || t.isEmpty) return t;
    return t[0].toUpperCase() + t.substring(1);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final tree = ref.watch(regionTreeProvider);
    return SizedBox(
      key: ValueKey(widget.formMode ? 'regionPickerSheet' : 'deliveryAreaSheet'),
      height: MediaQuery.sizeOf(context).height * .72,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(AppSpacing.screenEdge, AppSpacing.md, 4, 0),
            child: Row(
              children: [
                Expanded(
                  child: Text(widget.formMode ? l10n.addressRegionPick : l10n.tokoChooseArea,
                      style: AppTypography.title.copyWith(fontSize: 16, color: AppColors.ink)),
                ),
                IconButton(
                  key: const ValueKey('deliveryAreaClose'),
                  icon: const Icon(Icons.close),
                  tooltip: MaterialLocalizations.of(context).closeButtonTooltip,
                  onPressed: () => Navigator.of(context).pop(),
                ),
              ],
            ),
          ),
          if (_path.isNotEmpty) _breadcrumb(),
          const Divider(height: 1, color: ShopColors.divider),
          Expanded(
            child: tree.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (_, _) => Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(l10n.tokoLoadFailed, style: AppTypography.body),
                    TextButton(
                      key: const ValueKey('deliveryAreaRetry'),
                      onPressed: () => ref.invalidate(regionTreeProvider),
                      child: Text(l10n.commonRetry),
                    ),
                  ],
                ),
              ),
              data: (t) => _list(l10n, t),
            ),
          ),
        ],
      ),
    );
  }

  Widget _breadcrumb() => Padding(
        padding: const EdgeInsets.fromLTRB(AppSpacing.screenEdge, 0, AppSpacing.screenEdge, 8),
        child: Wrap(
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            for (var i = 0; i < _path.length; i++) ...[
              if (i > 0) const Icon(Icons.chevron_right, size: 16, color: ShopColors.text4),
              ShopPressable(
                key: ValueKey('deliveryAreaCrumb_$i'),
                minSize: kShopMinTapTarget,
                onTap: () => setState(() => _path = _path.sublist(0, i)),
                child: Text(_path[i],
                    style: AppTypography.caption
                        .copyWith(color: AppColors.mint, fontWeight: FontWeight.w600)),
              ),
            ],
          ],
        ),
      );

  Widget _list(AppLocalizations l10n, RegionTree t) {
    // 面包屑里的名字在树里找不到（树刷新过）→ 回到顶层，别画空列表。
    RegionProvinsi? prov;
    RegionKota? kota;
    if (_path.isNotEmpty) {
      prov = t.provinsi.where((p) => p.name == _path[0]).firstOrNull;
      if (prov != null && _path.length > 1) {
        kota = prov.kota.where((k) => k.name == _path[1]).firstOrNull;
      }
    }
    final List<Widget> rows;
    final form = widget.formMode;
    final sel = widget.formSelected;
    if (prov == null) {
      rows = [
        for (final p in t.provinsi)
          _row(p.name,
              key: 'deliveryAreaProv_${p.name}',
              checked: form && sel?.provinsi == p.name,
              onTap: () => setState(() => _path = [p.name])),
      ];
    } else if (kota == null) {
      rows = [
        for (final k in prov.kota)
          _row(k.name,
              key: 'deliveryAreaKota_${k.name}',
              checked: form && sel?.provinsi == prov.name && sel?.kota == k.name,
              onTap: () => setState(() => _path = [prov!.name, k.name])),
      ];
    } else if (form) {
      // 表单模式：按名称回填表单，不要求 id（地址按名称存，见 ShippingAddress）。
      // 🔴 换了上级 = 走了另一条路径，下级从新的上级重新列出 —— 不会拼出旧下级 + 新上级。
      rows = [
        for (final kec in kota.kecamatan)
          _row(
            kec.name,
            key: 'deliveryAreaKec_${kec.name}',
            tag: kec.serviceable ? null : _notServiceableTag(l10n),
            checked: sel?.provinsi == prov.name &&
                sel?.kota == kota.name &&
                sel?.kecamatan == kec.name,
            onTap: () => Navigator.of(context).pop(RegionSelection(
              provinsi: prov!.name,
              kota: kota!.name,
              kecamatan: kec.name,
              serviceable: kec.serviceable,
            )),
          ),
      ];
    } else {
      rows = [
        for (final kec in kota.kecamatan)
          _row(
            kec.name,
            key: 'deliveryAreaKec_${kec.name}',
            tag: kec.serviceable ? null : l10n.addressNotServiceable,
            checked: kec.id != null && kec.id == widget.selectedKecamatanId,
            // 🔴 没有 id 的区（旧后端）无法估运费 → 不可选，免得选了也白选。
            onTap: kec.id == null
                ? null
                : () => Navigator.of(context).pop(ShopDeliveryArea(
                      kecamatanId: kec.id!,
                      kecamatan: kec.name,
                      kota: kota!.name,
                      provinsi: prov!.name,
                      serviceable: kec.serviceable,
                    )),
          ),
      ];
    }
    return ListView(padding: const EdgeInsets.only(bottom: AppSpacing.lg), children: rows);
  }

  Widget _row(String label,
      {required String key, VoidCallback? onTap, String? tag, bool checked = false}) {
    return ShopPressable(
      key: ValueKey(key),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge, vertical: 14),
        child: Row(
          children: [
            Expanded(
              child: Text(label,
                  style: AppTypography.body.copyWith(
                      color: checked ? AppColors.mint : AppColors.ink,
                      fontWeight: checked ? FontWeight.w600 : FontWeight.w400)),
            ),
            if (tag != null)
              Padding(
                padding: const EdgeInsets.only(left: 8),
                child: Text(tag,
                    key: ValueKey('${key}_notServiceable'),
                    style: AppTypography.caption.copyWith(color: ShopColors.text4)),
              ),
            if (checked)
              const Padding(
                padding: EdgeInsets.only(left: 8),
                child: Icon(Icons.check, size: 18, color: AppColors.mint),
              ),
          ],
        ),
      ),
    );
  }
}
