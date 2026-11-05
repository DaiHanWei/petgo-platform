/// 送达地区抽屉（v1.4.2 shop Story 8.2 · UI B6）。
///
/// 省 / 市 / 区三级，数据取开通区域树（`/shop/regions`，[regionTreeProvider]）；顶部面包屑显示已选层级，
/// 点某一级回到该级重选；**未开通的区仍列出**、标「Belum dilayani」仍可选；当前所选打 `check`。
/// 选中区即关抽屉并返回 [ShopDeliveryArea] —— 🔴 **只记本地、不生成收货地址**（写 prefs 由调用方做）。
///
/// ⚠️ 与地址簿表单里的三级选择（H4，9.4）不共用逻辑：那里选完是表单字段，这里选完是本机偏好。
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

class DeliveryAreaSheet extends ConsumerStatefulWidget {
  const DeliveryAreaSheet({super.key, this.selectedKecamatanId});

  final int? selectedKecamatanId;

  @override
  ConsumerState<DeliveryAreaSheet> createState() => _DeliveryAreaSheetState();
}

class _DeliveryAreaSheetState extends ConsumerState<DeliveryAreaSheet> {
  /// 已选层级（[0] = 省，[1] = 市）。回到某级只需截断。
  List<String> _path = const [];

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final tree = ref.watch(regionTreeProvider);
    return SizedBox(
      key: const ValueKey('deliveryAreaSheet'),
      height: MediaQuery.sizeOf(context).height * .72,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(AppSpacing.screenEdge, AppSpacing.md, 4, 0),
            child: Row(
              children: [
                Expanded(
                  child: Text(l10n.tokoChooseArea,
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
    if (prov == null) {
      rows = [
        for (final p in t.provinsi)
          _row(p.name, key: 'deliveryAreaProv_${p.name}', onTap: () => setState(() => _path = [p.name])),
      ];
    } else if (kota == null) {
      rows = [
        for (final k in prov.kota)
          _row(k.name,
              key: 'deliveryAreaKota_${k.name}',
              onTap: () => setState(() => _path = [prov!.name, k.name])),
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
