/// 新增收货地址 —— **设计稿版式**（V1.4.0 · `01_screens_browse_order.md` 屏 8）。
///
/// ⚠️ 2026-08-28：v1 版式已整体删除，本文件是该页唯一实现（`_v2` 后缀保留以免制造纯改名 diff）。
///
/// ## 🔴 三条来自设计稿的硬规则
///
/// 1. **行政区三级级联，不做自由输入** —— 服务范围判定依赖标准化行政区划。
///    让用户自己打字，服务范围就永远对不上，且这种错在下单前无从发现。
/// 2. **范围提示只告知、不阻断保存**（FR-98 / FR-99 的分界）。用户可能为将来备一个地址；
///    真正的拦截发生在**结算**。这里把保存挡掉只会让人以为「这个地址存不了」。
/// 3. **patokan（地标）与门牌同一个多行字段** —— 印尼地址实践中门牌常需地标补充。
///    拆成两栏的结果是用户只填一栏，另一栏空着。
///
/// ## 校验时机
///
/// 失焦即校验单字段（红边 + 字段下一行说明）；点保存全量校验并**滚到第一个错误**。
/// 🔴 滚动用容器偏移计算，**不用 `Scrollable.ensureVisible`** ——
/// 后者会把目标顶到视口正中，在带底部固定条的表单里经常把错误项推到条底下看不见。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/shop_tokens.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../presentation/widgets/shop_surface.dart';
import '../data/address_repository.dart';
import '../domain/shipping_address.dart';
import 'address_form_body.dart';

/// v1.4.2 shop Story 7.1 AC4.6：字段 / 校验 / 保存已抽到 [AddressFormBody]（结算页 D5 共用），
/// 本页只包一层 Scaffold，保存后照旧 `context.pop()`。
///
/// v1.4.2 shop Story 9.4（H3）：
/// - 标题：新增「Tambah alamat」/ 编辑「Ubah alamat」（修线上：编辑也显示 Tambah）；
/// - 🔴 **编辑预填**（修线上：编辑进来是空表单）：数据源 = [addressListProvider] 中同 token 项
///   （列表已含全部字段，无单条 GET）；列表未加载先等；找不到 / 加载失败 → toast `addressLoadFailed` 并 pop。
///   预填只在首次进入做一次（表单 State 只在 initState 读 initial），列表刷新不覆盖用户正在改的值。
class AddressFormPageV2 extends ConsumerStatefulWidget {
  const AddressFormPageV2({super.key, this.token});

  /// 非空 = 编辑既有地址。
  final String? token;

  @override
  ConsumerState<AddressFormPageV2> createState() => _AddressFormPageV2State();
}

class _AddressFormPageV2State extends ConsumerState<AddressFormPageV2> {
  /// 编辑态已定位到的地址（定位一次后固定，不随列表刷新变化）。
  ShippingAddress? _initial;
  bool _bailed = false;

  void _bail(AppLocalizations l10n) {
    if (_bailed) return;
    _bailed = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      showAppToast(context, l10n.addressLoadFailed);
      Navigator.of(context).maybePop();
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final token = widget.token;
    final Widget body;
    if (token == null) {
      body = _form(l10n, null);
    } else if (_initial != null) {
      body = _form(l10n, _initial);
    } else {
      final list = ref.watch(addressListProvider);
      body = list.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) {
          _bail(l10n);
          return const SizedBox.shrink();
        },
        data: (items) {
          final hit = items.where((a) => a.token == token).firstOrNull;
          if (hit == null) {
            _bail(l10n);
            return const SizedBox.shrink();
          }
          _initial = hit;
          return _form(l10n, hit);
        },
      );
    }
    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(
        title: token == null ? l10n.addressAdd : l10n.addressEditTitle,
        tone: ShopAppBarTone.light,
        leading: IconButton(
          icon: const Icon(Icons.chevron_left_rounded, size: 28),
          onPressed: () => Navigator.of(context).maybePop(),
        ),
      ),
      body: body,
    );
  }

  Widget _form(AppLocalizations l10n, ShippingAddress? initial) => AddressFormBody(
        key: ValueKey('addressFormBody_${widget.token ?? 'new'}'),
        initial: initial,
        editToken: widget.token,
        submitLabel: l10n.addressSave,
        onSaved: (_) => context.pop(),
      );
}
