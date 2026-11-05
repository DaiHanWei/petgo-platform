/// 券数据层（v1.4.2 shop Story 8.1，消费 2.2：领券 / 给你的券 / 游客新人活动 / 进入 Toko 发新人券）。
///
/// 错误以 [DioException] 抛给调用方（领券要按 ProblemDetail 分类给 toast）；401 由 AuthInterceptor 处理。
/// 「给你的券」provider 与页面对新人活动的读取**失败一律当作没有**：它们是首页锦上添花的块，
/// 挂了不该让 Toko 进错误态（AC3.1 / AC4.2）。
library;

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/network/api_paths.dart';
import '../../../../core/network/dio_client.dart';
import '../../../auth/domain/auth_state.dart';
import '../domain/shop_coupon.dart';

class CouponRepository {
  CouponRepository({required this.dio});

  final Dio dio;

  /// 领券。成功返回该券（已领态）；失败抛 [DioException]（调用方用 [CouponClaimFailure.from] 分类）。
  Future<ShopCoupon> claim(String couponToken, CouponClaimSource source) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.meCouponsClaim,
      data: {'couponToken': couponToken, 'source': source.api},
    );
    return ShopCoupon.fromJson(resp.data ?? const {});
  }

  /// 可用券张数（`GET /me/coupons/summary` → `{availableCount}`）。
  Future<int> availableCount() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meCouponsSummary);
    final v = resp.data?['availableCount'];
    return v is num ? v.toInt() : 0;
  }

  /// 首页「给你的券」（新人券已由服务端置顶）。
  Future<List<ShopCoupon>> forYou() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.shopCouponsForYou);
    final items = resp.data?['items'];
    if (items is! List) return const [];
    return items
        .whereType<Map<String, dynamic>>()
        .map(ShopCoupon.fromJson)
        .where((c) => c.couponToken.isNotEmpty)
        .toList(growable: false);
  }

  /// 商品可领的券（v1.4.2 shop Story 8.2 · B1 / B7）。游客可读（`claimed` 恒 false）。
  Future<List<ShopCoupon>> forProduct(String productToken) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.shopProductCoupons(productToken));
    final items = resp.data?['items'];
    if (items is! List) return const [];
    return items
        .whereType<Map<String, dynamic>>()
        .map(ShopCoupon.fromJson)
        .where((c) => c.couponToken.isNotEmpty)
        .toList(growable: false);
  }

  /// 游客新人券活动。无活动（204）→ null。
  Future<NewbieCampaign?> newbieCampaign() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.shopNewbieCampaign);
    final data = resp.data;
    if (resp.statusCode == 204 || data == null) return null;
    final c = NewbieCampaign.fromJson(data);
    return c.campaignToken.isEmpty ? null : c;
  }

  /// 进入 Toko（幂等）。返回本次是否发放了新人券。
  Future<bool> enterShop() async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.meShopEntry);
    return resp.data?['newbieCouponIssued'] == true;
  }
}

/// 我的券入口计数（v1.4.2 shop Story 9.1 AC2.4，后端 2.2 AC8.4 `GET /me/coupons/summary`）。
/// 🔴 失败 → null：入口只显示「Kupon Saya」，不显示「0 bisa dipakai」。
final couponSummaryProvider = FutureProvider.autoDispose<int?>((ref) async {
  try {
    return await ref.read(couponRepositoryProvider).availableCount();
  } catch (_) {
    return null;
  }
});

final couponRepositoryProvider =
    Provider<CouponRepository>((ref) => CouponRepository(dio: ref.read(dioProvider)));

/// 首页「Kupon buat kamu」。🔴 游客在数据层短路（`/shop/coupons/for-you` 仅 USER，游客请求会 401 → 强登录引导）。
/// 失败 → 空列表（整块不出现，不出错误态）。
final couponsForYouProvider = FutureProvider.autoDispose<List<ShopCoupon>>((ref) async {
  final auth = ref.watch(authControllerProvider);
  if (!auth.isLoggedIn) return const [];
  try {
    return await ref.read(couponRepositoryProvider).forYou();
  } catch (_) {
    return const [];
  }
});

/// 商品详情「可领的券」（8.2 AC4）。空 / 失败 → 空列表（整行不画）。
///
/// 🔴 watch 登录态：登录 / 换号后重拉，`claimed` 只取服务端值（不在本地记「已领」，同 8.1 复审 #1）。
final productCouponsProvider =
    FutureProvider.autoDispose.family<List<ShopCoupon>, String>((ref, productToken) async {
  ref.watch(authControllerProvider.select((a) => a.isLoggedIn));
  try {
    return await ref.read(couponRepositoryProvider).forProduct(productToken);
  } catch (_) {
    return const [];
  }
});

/// `POST /me/shop/entry` 的进程内去重（AC4.1：同一进程 60 秒内不重复）。
///
/// 模块级而非 provider：去重跨页面实例（切 Tab 回来是同一 State，但冷热重建不该各自重计）。
/// 切换账号时由 `resetUserScopedCaches` 调 [resetShopEntryThrottle] 清零 —— 新账号首次进 Toko 必须立刻判新人。
DateTime? _lastShopEntryCall;

/// 去重窗口。
const Duration kShopEntryThrottle = Duration(seconds: 60);

/// 测试注入时钟。
@visibleForTesting
DateTime Function() shopEntryClock = DateTime.now;

/// 清零去重（换账号 / 测试）。
void resetShopEntryThrottle() => _lastShopEntryCall = null;

/// 60 秒窗口内是否已调过；没调过则占位并返回 true（调用方随即发请求）。
bool claimShopEntrySlot() {
  final now = shopEntryClock();
  final last = _lastShopEntryCall;
  if (last != null && now.difference(last) < kShopEntryThrottle) return false;
  _lastShopEntryCall = now;
  return true;
}
