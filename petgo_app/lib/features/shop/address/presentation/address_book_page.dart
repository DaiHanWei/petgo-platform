import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/shop_tokens.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../presentation/widgets/shop_dialog.dart';
import '../../presentation/widgets/shop_pressable.dart';
import '../../presentation/widgets/shop_surface.dart';
import '../data/address_repository.dart';
import '../domain/shipping_address.dart';
import 'address_label.dart';

/// 地址簿（Story 2.4，FR-98）。
///
/// 🔴 **删除默认地址后要明确告诉用户「已将 X 设为默认」** ——
/// 服务端会自动把剩余中最近使用的一条升为默认（Story 2.1），
/// 但用户看不见这件事就会以为默认地址丢了，下单时才发现寄到了别处。
///
/// v1.4.2 shop Story 9.4（H1 / H2 / H5，FR-133.11、FR-172.1）：电商统一样式（白顶栏、白卡圆角 16）、
/// `serviceable == false` 的卡标橙标签「Belum bisa dikirim kurir」（null / true 不标）、标签文案走 arb、
/// 新增入口改为吸底按钮、空态 / 失败态补全、删除加确认与错误处理。
/// 🔴 `serviceable` **只是展示**：不拦截「设为默认」、编辑，选择器模式下也仍可选（结算页试算统一处理，7.1 D3）。
class AddressBookPage extends ConsumerWidget {
  const AddressBookPage({super.key, this.selecting = false});

  /// 选择器模式（D-18，2026-09-02 stag）。
  ///
  /// 🔴 从**结算页**进来时，这一页要回答的是「这单寄哪」，不是「管理我的地址」。
  /// 此前两者是同一个页面同一套交互：点卡片**毫无反应**，页面给的是
  /// 「设为默认 / 编辑 / 删除」——**管理操作，不是选择操作**。
  /// 于是多地址用户想把这单寄公司，唯一办法是把公司地址**设为默认**；
  /// 下单后想寄回家，还得再切一次。默认地址被当成"当前选择"用，语义错位。
  ///
  /// 选择器模式下：点卡片即选中并 pop 回 token，**只作用于当前订单，不改默认地址**。
  /// ⚠️ 「设为默认」仍然保留 —— 用户可能正想顺手改，只是它不再是换地址的**唯一**途径。
  final bool selecting;

  /// 地址簿上限（原有规则）。
  static const int maxAddresses = 20;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(addressListProvider);

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(
        title: l10n.addressBookTitle,
        tone: ShopAppBarTone.light,
        leading: IconButton(
          icon: const Icon(Icons.chevron_left_rounded, size: 28),
          onPressed: () => Navigator.of(context).maybePop(),
        ),
      ),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => _LoadFailed(onRetry: () => ref.invalidate(addressListProvider)),
        data: (items) => items.isEmpty
            ? const _Empty()
            : ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: items.length,
                separatorBuilder: (_, _) => const SizedBox(height: 12),
                itemBuilder: (context, i) => _AddressCard(
                  key: ValueKey('addressCard_${items[i].token}'),
                  address: items[i],
                  selecting: selecting,
                  onChanged: () => ref.invalidate(addressListProvider),
                ),
              ),
      ),
      bottomNavigationBar: async.maybeWhen(
        data: (items) => _AddBar(full: items.length >= maxAddresses),
        orElse: () => null,
      ),
    );
  }
}

/// 吸底「Tambah alamat」（`.bar`：白底 + 上阴影，内边距 12·16·安全区；`.btn` 高 48）。
class _AddBar extends StatelessWidget {
  const _AddBar({required this.full});

  /// 🔴 达上限时不给入口——比让用户填完再被服务端拒绝好得多。
  final bool full;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      padding: EdgeInsets.fromLTRB(16, 12, 16, 12 + MediaQuery.paddingOf(context).bottom),
      decoration: const BoxDecoration(
        color: ShopColors.surface,
        boxShadow: [
          BoxShadow(color: Color(0x0F2E2A45), offset: Offset(0, -4), blurRadius: 16),
        ],
      ),
      child: SizedBox(
        height: 48,
        child: FilledButton.icon(
          key: const ValueKey('addressAddButton'),
          onPressed: full ? null : () => context.push('/me/addresses/new'),
          icon: const Icon(Icons.add),
          label: Text(full ? l10n.addressLimitReached : l10n.addressAdd),
          style: FilledButton.styleFrom(
            backgroundColor: ShopColors.purple,
            disabledBackgroundColor: ShopColors.border,
            foregroundColor: ShopColors.surface,
            disabledForegroundColor: ShopColors.text4,
            textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          ),
        ),
      ),
    );
  }
}

/// H2 空态：图标块 72×72 圆角 24 + 标题 + 副句。
class _Empty extends StatelessWidget {
  const _Empty();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      key: const ValueKey('addressEmpty'),
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 72,
              height: 72,
              decoration: BoxDecoration(
                color: ShopColors.accentBg,
                borderRadius: BorderRadius.circular(24),
              ),
              child: const Icon(Icons.location_on, size: 32, color: ShopColors.purple),
            ),
            const SizedBox(height: 16),
            Text(l10n.addressEmpty,
                textAlign: TextAlign.center,
                style: AppTypography.body
                    .copyWith(fontWeight: FontWeight.w600, color: ShopColors.text)),
            const SizedBox(height: 6),
            Text(l10n.addressEmptyHint,
                textAlign: TextAlign.center,
                style: AppTypography.caption.copyWith(color: ShopColors.text3)),
          ],
        ),
      ),
    );
  }
}

class _LoadFailed extends StatelessWidget {
  const _LoadFailed({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      key: const ValueKey('addressLoadFailed'),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.cloud_off, size: 40, color: ShopColors.text4),
          const SizedBox(height: 12),
          Text(l10n.addressLoadFailed,
              textAlign: TextAlign.center,
              style: AppTypography.body.copyWith(color: ShopColors.text2)),
          const SizedBox(height: 8),
          TextButton(
            key: const ValueKey('addressRetry'),
            onPressed: onRetry,
            child: Text(l10n.commonRetry),
          ),
        ],
      ),
    );
  }
}

class _AddressCard extends ConsumerStatefulWidget {
  const _AddressCard(
      {super.key, required this.address, required this.onChanged, this.selecting = false});

  final ShippingAddress address;
  final VoidCallback onChanged;

  /// 见 [AddressBookPage.selecting]。
  final bool selecting;

  @override
  ConsumerState<_AddressCard> createState() => _AddressCardState();
}

class _AddressCardState extends ConsumerState<_AddressCard> {
  /// 删除进行中：卡内操作全部禁用，防重复点。
  bool _busy = false;

  ShippingAddress get address => widget.address;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final card = _card(l10n);
    if (!widget.selecting) return card;
    // 🔴 整卡可点：用户在选择器里想点的是**这张卡**，不是卡里的某个按钮。
    //    pop 回 token，由结算页决定怎么用（只作用于本单）。
    // ⚠️ serviceable == false 的卡**仍可选**（只标注）：结算页选中后由试算返回 NOT_SERVICEABLE 处理（7.1 D3）。
    return Semantics(
      button: true,
      label: l10n.addressUseThis,
      child: InkWell(
        key: ValueKey('addressSelect_${address.token}'),
        borderRadius: BorderRadius.circular(16),
        onTap: _busy ? null : () => Navigator.of(context).pop(address.token),
        child: card,
      ),
    );
  }

  Widget _card(AppLocalizations l10n) {
    final label = address.label;
    final notDeliverable = address.serviceable == false;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: ShopColors.surface,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text('${address.receiverName} · ${address.receiverPhone}',
                    style: const TextStyle(
                        fontSize: 15, fontWeight: FontWeight.w600, color: ShopColors.text)),
              ),
              if (address.isDefault)
                _Tag(
                  key: ValueKey('addressDefaultTag_${address.token}'),
                  text: l10n.addressDefaultBadge,
                  fg: ShopColors.purple,
                  bg: ShopColors.purpleTagBg,
                ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            '${address.addressLine}, ${address.kecamatan}, '
            '${address.kotaKabupaten}, ${address.provinsi} ${address.kodePos}',
            style: const TextStyle(fontSize: 13, color: AppColors.ink2),
          ),
          if (label != null || notDeliverable) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                if (label != null)
                  _Tag(
                    key: ValueKey('addressLabelTag_${address.token}'),
                    text: addressDisplayLabel(l10n, label),
                    fg: ShopColors.text3,
                    bg: ShopColors.divider2,
                  ),
                if (notDeliverable)
                  _Tag(
                    key: ValueKey('addressNotDeliverable_${address.token}'),
                    text: l10n.addressNotDeliverable,
                    fg: ShopColors.warnTitle,
                    bg: ShopColors.warnBg,
                  ),
              ],
            ),
          ],
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              if (!address.isDefault)
                _Link(
                  key: ValueKey('addressSetDefault_${address.token}'),
                  text: l10n.addressSetDefault,
                  onTap: _busy
                      ? null
                      : () async {
                          await ref.read(addressRepositoryProvider).setDefault(address.token);
                          widget.onChanged();
                        },
                ),
              _Link(
                key: ValueKey('addressEdit_${address.token}'),
                text: l10n.addressEdit,
                onTap: _busy ? null : () => context.push('/me/addresses/${address.token}'),
              ),
              _Link(
                key: ValueKey('addressDelete_${address.token}'),
                text: l10n.addressDelete,
                onTap: _busy ? null : _delete,
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// H5：确认后删除；失败 toast 且列表不变；删的是默认地址 → 告知新的默认地址。
  Future<void> _delete() async {
    final l10n = AppLocalizations.of(context);
    final ok = await showShopConfirm(
      context,
      title: l10n.addressDeleteConfirmTitle,
      // UI H5 示意：「{收件人} · {addressLine}」单行省略（直接拼数据，非文案 key）。
      content: Text('${address.receiverName} · ${address.addressLine}',
          maxLines: 1, overflow: TextOverflow.ellipsis, style: ShopText.body),
      confirmLabel: l10n.addressDelete,
      cancelLabel: l10n.commonCancel,
      dialogKey: const ValueKey('addressDeleteDialog'),
      confirmKey: const ValueKey('addressDeleteConfirm'),
    );
    if (!ok || !mounted) return;
    // 卡片随后会因列表刷新被销毁 —— toast 挂在 root overlay 上，提前拿住。
    final overlay = Overlay.of(context, rootOverlay: true);
    final wasDefault = address.isDefault;
    final repo = ref.read(addressRepositoryProvider);
    setState(() => _busy = true);
    try {
      await repo.delete(address.token);
    } catch (_) {
      // 🔒 不回显地址内容（PII）。列表不变。
      showAppToastOnOverlay(overlay, l10n.addressDeleteFailed);
      if (mounted) setState(() => _busy = false);
      return;
    }
    // 🔴 删的是默认地址 → 服务端已自动升级另一条，必须把结果告诉用户，
    //    否则他会以为默认地址没了，下单时才发现寄到别处。
    if (wasDefault) {
      try {
        final rest = await repo.list();
        final promoted = rest.where((a) => a.isDefault).firstOrNull;
        if (promoted != null) {
          showAppToastOnOverlay(overlay, l10n.addressDefaultPromoted(promoted.receiverName));
        }
      } catch (_) {
        // 删除已成功，只是没拿到新默认 —— 列表刷新后用户能看到「Utama」在哪。
      }
    }
    if (mounted) setState(() => _busy = false);
    widget.onChanged();
  }
}

class _Tag extends StatelessWidget {
  const _Tag({super.key, required this.text, required this.fg, required this.bg});

  final String text;
  final Color fg;
  final Color bg;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(6)),
        child: Text(text, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: fg)),
      );
}

/// 操作行链接（13 / w600 `mint`）。
class _Link extends StatelessWidget {
  const _Link({super.key, required this.text, required this.onTap});

  final String text;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => ShopPressable(
        onTap: onTap,
        minSize: kShopMinTapTarget,
        child: Padding(
          padding: const EdgeInsets.only(left: 16),
          child: Center(
            widthFactor: 1,
            child: Text(text,
                style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: onTap == null ? ShopColors.disabledText : AppColors.mint)),
          ),
        ),
      );
}
