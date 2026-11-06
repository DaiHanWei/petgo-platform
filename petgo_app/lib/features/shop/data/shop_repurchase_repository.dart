import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../auth/domain/auth_state.dart';
import '../domain/shop_repurchase.dart';

/// 推荐数据层（Story 6.5；v1.4.2 shop Story 9.4：复购卡 `cards` / `dismiss` / `repurchaseCardsProvider`
/// 随 FR-136 下线删除，**「为宠物推荐」`recommendations` 保留**）。
///
/// 🔒 **端点在 `/me` 下**：FR-93 状态矩阵里游客两区都不展示 ——
/// 做成对游客开放的接口再靠前端不渲染，等于给游客态留了一条数据暴露路径。
class ShopRepurchaseRepository {
  ShopRepurchaseRepository({required this.dio});

  final Dio dio;

  Future<Recommendations> recommendations() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meShopRecommendations);
    return Recommendations.fromJson(resp.data!);
  }
}

final Provider<ShopRepurchaseRepository> shopRepurchaseRepositoryProvider =
    Provider<ShopRepurchaseRepository>(
        (ref) => ShopRepurchaseRepository(dio: ref.read(dioProvider)));

/// 区域② 档案推荐。
///
/// 🔴 **游客态在数据层短路**（Epic 3 硬结论 1）：任何 `/me/*` provider 被游客 watch 一次
/// 就会 401 → 强登录引导 = 变相登录墙。这里直接返回「未建档」而不发请求。
final recommendationsProvider = FutureProvider.autoDispose<Recommendations>((ref) async {
  final auth = ref.watch(authControllerProvider);
  if (!auth.isLoggedIn) {
    // 🔴 GUEST 与 PROFILE 是两回事：游客【整区不渲染】，已登录未建档才换成建档引导卡
    //    （FR-93 状态矩阵第 1 行 vs 第 2 行）。混成一个值会让游客也看到建档卡 ——
    //    而那张卡点下去就是登录墙，正是 FR-93A「浏览路径零门槛」要防的东西。
    return const Recommendations(degraded: true, missing: 'GUEST', items: []);
  }
  return ref.read(shopRepurchaseRepositoryProvider).recommendations();
});

