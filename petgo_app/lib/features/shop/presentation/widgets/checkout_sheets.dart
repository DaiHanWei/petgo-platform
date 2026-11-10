/// 结算页抽屉（v1.4.2 shop Story 7.1）：D4 地址选择、D5 页内新增地址、D7 快递选择。
///
/// 🔴 全部**只对本单生效**：选地址不写回默认、选快递不持久化（页面销毁即丢）。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/shop_tokens.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/keyboard_safe_area.dart';
import '../../address/data/address_repository.dart';
import '../../address/domain/shipping_address.dart';
import '../../address/presentation/address_form_body.dart';
import '../../domain/checkout_preview.dart';
import '../../domain/shop_product.dart' show formatIdr;
import 'shop_pressable.dart';

/// 抽屉公共头：标题 + 关闭（券抽屉共用）。
class CheckoutSheetHeader extends StatelessWidget {
  const CheckoutSheetHeader({super.key, required this.title});

  final String title;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 4, 4),
        child: Row(
          children: [
            Expanded(child: Text(title, style: AppTypography.title)),
            IconButton(
              key: const ValueKey('checkoutSheetClose'),
              tooltip: AppLocalizations.of(context).commonClose,
              icon: const Icon(Icons.close_rounded),
              onPressed: () => Navigator.of(context).pop(),
            ),
          ],
        ),
      );
}

const _sheetShape =
    RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(16)));

/// D4 结果：选中某地址，或要求新增。
sealed class AddressPick {
  const AddressPick();
}

class AddressPicked extends AddressPick {
  const AddressPicked(this.token);
  final String token;
}

class AddressAddNew extends AddressPick {
  const AddressAddNew();
}

/// D4 地址选择抽屉。关闭返回 null = 不改。
Future<AddressPick?> showAddressPickerSheet(BuildContext context, {String? currentToken}) =>
    showModalBottomSheet<AddressPick>(
      context: context,
      isScrollControlled: true,
      backgroundColor: ShopColors.surface,
      shape: _sheetShape,
      builder: (_) => _AddressPickerSheet(currentToken: currentToken),
    );

class _AddressPickerSheet extends ConsumerWidget {
  const _AddressPickerSheet({this.currentToken});

  final String? currentToken;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final list = ref.watch(addressListProvider);
    return FractionallySizedBox(
      heightFactor: .75,
      child: SafeArea(
        top: false,
        child: Column(
          children: [
            CheckoutSheetHeader(title: l10n.checkoutPickAddressTitle),
            Expanded(
              child: list.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (_, _) => Center(
                  child: TextButton(
                    onPressed: () => ref.invalidate(addressListProvider),
                    child: Text(l10n.commonRetry),
                  ),
                ),
                data: (items) => ListView(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                  children: [
                    for (final a in items)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: _addressTile(context, l10n, a),
                      ),
                  ],
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
              child: ShopPressable(
                key: const ValueKey('checkoutAddNewAddress'),
                onTap: () => Navigator.of(context).pop(const AddressAddNew()),
                child: Container(
                  height: 48,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: ShopColors.border),
                  ),
                  child: Text('+ ${l10n.checkoutAddNewAddress}',
                      style: AppTypography.body
                          .copyWith(fontWeight: FontWeight.w600, color: ShopColors.purple)),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _addressTile(BuildContext context, AppLocalizations l10n, ShippingAddress a) {
    final on = a.token == currentToken;
    final text = [a.addressLine, a.kecamatan, a.kotaKabupaten, a.provinsi, a.kodePos]
        .where((s) => s.isNotEmpty)
        .join(', ');
    return ShopPressable(
      key: ValueKey('checkoutAddressOption_${a.token}'),
      onTap: () => Navigator.of(context).pop(AddressPicked(a.token)),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
              color: on ? ShopColors.purple : ShopColors.border2, width: on ? 1.5 : 1),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Wrap(
              spacing: 6,
              runSpacing: 4,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Text('${a.receiverName} · ${a.receiverPhone}',
                    style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
                if (a.isDefault) _tag(l10n.addressDefaultBadge, ShopColors.purple),
                // 🔴 不可配送仍可选（选了进 D3）；原文小写，首字母大写渲染
                if (a.serviceable == false)
                  _tag(_capitalize(l10n.addressNotServiceable), ShopColors.errorText,
                      key: ValueKey('checkoutAddressNotServiceable_${a.token}')),
              ],
            ),
            const SizedBox(height: 2),
            Text(text, style: AppTypography.caption),
          ],
        ),
      ),
    );
  }

  Widget _tag(String label, Color color, {Key? key}) => Container(
        key: key,
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(4),
          border: Border.all(color: color),
        ),
        child: Text(label, style: AppTypography.micro.copyWith(color: color)),
      );

  static String _capitalize(String s) =>
      s.isEmpty ? s : '${s[0].toUpperCase()}${s.substring(1)}';
}

/// D5 页内新增地址（近全屏抽屉，与地址簿同一个 [AddressFormBody]）。返回服务端保存后的地址；关闭返回 null。
Future<ShippingAddress?> showAddAddressSheet(BuildContext context) =>
    showModalBottomSheet<ShippingAddress>(
      context: context,
      isScrollControlled: true,
      backgroundColor: ShopColors.surface,
      shape: _sheetShape,
      builder: (sheetCtx) {
        final l10n = AppLocalizations.of(sheetCtx);
        return FractionallySizedBox(
          heightFactor: .92,
          // 🔴 固定高度 sheet 必须套 KeyboardInset（CLAUDE.md 键盘避让标准）
          child: KeyboardInset(
            child: Column(
              children: [
                ColoredBox(
                  color: ShopColors.surface,
                  child: CheckoutSheetHeader(title: l10n.addressAdd),
                ),
                Expanded(
                  child: AddressFormBody(
                    compact: true,
                    submitLabel: l10n.checkoutSaveAndUse,
                    onSaved: (a) => Navigator.of(sheetCtx).pop(a),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );

/// D7 快递选择抽屉。返回所选 `courierKey`；关闭返回 null。
Future<String?> showCourierPickerSheet(
  BuildContext context, {
  required List<CourierOption> couriers,
  String? selectedKey,
}) =>
    showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      backgroundColor: ShopColors.surface,
      shape: _sheetShape,
      builder: (sheetCtx) {
        final l10n = AppLocalizations.of(sheetCtx);
        return FractionallySizedBox(
          heightFactor: .75,
          child: SafeArea(
            top: false,
            child: Column(
              children: [
                CheckoutSheetHeader(title: l10n.checkoutPickCourierTitle),
                Expanded(
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                    children: [
                      // 服务端已按价格升序，按响应顺序渲染，首项标「Termurah」
                      for (var i = 0; i < couriers.length; i++)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: _courierTile(sheetCtx, l10n, couriers[i],
                              first: i == 0, on: couriers[i].courierKey == selectedKey),
                        ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                  child: Text(l10n.checkoutCourierCapNote,
                      key: const ValueKey('checkoutCourierCapNote'),
                      style: AppTypography.caption),
                ),
              ],
            ),
          ),
        );
      },
    );

Widget _courierTile(BuildContext context, AppLocalizations l10n, CourierOption c,
    {required bool first, required bool on}) {
  final eta = courierEtaText(l10n, c.etaText);
  return ShopPressable(
    key: ValueKey('checkoutCourierOption_${c.courierKey}'),
    onTap: () => Navigator.of(context).pop(c.courierKey),
    child: Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: on ? ShopColors.purple : ShopColors.border2, width: on ? 1.5 : 1),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${c.courierName} · ${c.serviceName}',
                    style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
                if (eta != null) Text(eta, style: AppTypography.caption),
                Wrap(
                  spacing: 6,
                  children: [
                    if (first)
                      Text(l10n.checkoutCourierCheapest,
                          key: const ValueKey('checkoutCourierCheapestTag'),
                          style: AppTypography.caption.copyWith(color: ShopColors.saving)),
                    if (!c.insuranceSupported)
                      Text(l10n.checkoutCourierNoInsurance,
                          key: ValueKey('checkoutCourierNoInsurance_${c.courierKey}'),
                          style: AppTypography.caption),
                  ],
                ),
              ],
            ),
          ),
          Text(formatIdr(c.price),
              style: AppTypography.body.copyWith(
                  fontWeight: FontWeight.w600,
                  fontFeatures: const [FontFeature.tabularFigures()])),
        ],
      ),
    ),
  );
}

/// 时效文案：服务端 `etaText` 为纯区间（如 `2 - 4`）→「Estimasi 2 - 4 hari」；含其他字符则原样显示
/// （单位不一定是天，不硬套「hari」）；缺失 → null。
String? courierEtaText(AppLocalizations l10n, String? etaText) {
  if (etaText == null || etaText.trim().isEmpty) return null;
  final t = etaText.trim();
  return RegExp(r'^[0-9\s\-–]+$').hasMatch(t) ? l10n.checkoutCourierEta(t) : t;
}
