import 'package:flutter/material.dart';

import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/customer_service_sheet.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_surface.dart';

/// 退货暂不可用说明页（V1.3.0 · SD-5 / SHOP-FR-05）。
///
/// 两条退货路由（`/shop/orders/:token/return` 与 `/shop/returns/:token/refund-method`）
/// 的 builder 都换成了本页，**path 字符串逐字未动**。
///
/// 🔴 <b>为什么是「保留路由 + 换 builder」而不是删路由</b>：删掉之后老版本 App 的深链、
/// 通知跳转、站外分享链接会落到 go_router 的 `errorBuilder`，用户看到一个通用错误页 ——
/// 那等于告诉他「链接坏了」。这里要告诉他的是「这条路现在走不通，找谁能解决」。
///
/// 🔴 <b>本页不发起任何网络请求</b>：不 watch `returnEligibilityProvider` / `returnProgressProvider`，
/// 因此没有 loading 转圈、没有错误重试态。一个必然给不出结果的请求只会让人多等几秒。
///
/// ⚠️ 客服入口直接复用共享件 {@link showCustomerServiceSheet}，**不在本页引入任何号码字面量** ——
/// Story 3-1 会把客服号改成后端下发的配置项，本页届时零改动。
class ShopReturnUnavailablePage extends StatelessWidget {
  const ShopReturnUnavailablePage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      key: const ValueKey('shopReturnUnavailableV2'),
      backgroundColor: ShopColors.bg,
      // ShopAppBar 自带返回键 —— 既不白屏，也不 redirect 回首页
      // （redirect 会让人以为链接坏了）。
      appBar: ShopAppBar(title: l10n.shopReturnUnavailableTitle),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(24, 24, 24, 24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  l10n.shopReturnUnavailableBody,
                  key: const ValueKey('shopReturnUnavailableBodyV2'),
                  textAlign: TextAlign.center,
                  style: ShopText.body,
                ),
                const SizedBox(height: 24),
                ShopButton(
                  key: const ValueKey('shopReturnUnavailableCsV2'),
                  label: l10n.shopReturnUnavailableContactCs,
                  variant: ShopButtonVariant.outlinePurple,
                  onTap: () => showCustomerServiceSheet(context),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
