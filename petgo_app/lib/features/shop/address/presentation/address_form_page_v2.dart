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
import 'package:go_router/go_router.dart';

import '../../../../core/theme/shop_tokens.dart';
import '../../../../l10n/app_localizations.dart';
import '../../presentation/widgets/shop_surface.dart';
import 'address_form_body.dart';

/// v1.4.2 shop Story 7.1 AC4.6：字段 / 校验 / 保存已抽到 [AddressFormBody]（结算页 D5 共用），
/// 本页只包一层 Scaffold，保存后照旧 `context.pop()`。
class AddressFormPageV2 extends StatelessWidget {
  const AddressFormPageV2({super.key, this.token});

  /// 非空 = 编辑既有地址。
  final String? token;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: l10n.addressAdd),
      body: AddressFormBody(
        editToken: token,
        submitLabel: l10n.addressSave,
        onSaved: (_) => context.pop(),
      ),
    );
  }
}

