import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/id_card.dart';

/// 宠物身份证数据层（Story 6.2）。消费后端 6-1 的 `GET/POST /api/v1/pet-profiles/me/id-card`。
/// 抽象便于测试注入 fake（照 [ProfileRepository] 范式）。
abstract class IdCardRepository {
  /// 当前用户身份证数据；无档案（后端 404）归一为 null（前端落空态）。
  /// 老用户/未生成 → `generated=false`（前端引导态）。**GET 不分配号**。
  Future<IdCardData?> getMyIdCard();

  /// 生成身份证（分配全平台自增流水号，幂等）。无档案 → 抛错（404）。
  Future<IdCardData> generate();

  /// 购买高清图下载（一次性永久，Story 6.3）。已购买/PawCoin 即时 → unlocked；QRIS → 返回待支付 token。
  /// 余额不足 → 抛 DioException(409)。无档案 → 404。
  Future<HdPurchaseResult> purchaseHd(HdPayChannel channel);

  // —— 多卡快照（Story 6.7）——

  /// 建新卡快照（分配新 serial）。[req.name] 必填。无档案 → 404。
  Future<IdCard> createCard(CreateIdCardRequest req);

  /// 历史卡列表（createdAt 倒序）。无卡 → 空列表。
  Future<List<IdCard>> listCards();

  /// 单卡快照详情。非本人 → 404。
  Future<IdCard> getCard(int cardId);

  /// 为指定卡购买高清图下载（每卡独立，Story 6.7）。语义同 [purchaseHd]。
  Future<HdPurchaseResult> purchaseHdForCard(int cardId, HdPayChannel channel);
}

class DioIdCardRepository implements IdCardRepository {
  DioIdCardRepository(this.dio);

  final Dio dio;

  @override
  Future<IdCardData?> getMyIdCard() async {
    try {
      final res = await dio.get<Map<String, dynamic>>(ApiPaths.petProfileIdCard);
      return IdCardData.fromJson(res.data ?? const {});
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) {
        return null; // 无档案：归一为空态
      }
      rethrow;
    }
  }

  @override
  Future<IdCardData> generate() async {
    final res = await dio.post<Map<String, dynamic>>(ApiPaths.petProfileIdCard);
    return IdCardData.fromJson(res.data ?? const {});
  }

  @override
  Future<HdPurchaseResult> purchaseHd(HdPayChannel channel) async {
    final res = await dio.post<Map<String, dynamic>>(
      ApiPaths.petProfileIdCardHdDownload,
      data: {'channel': channel.wire},
    );
    return HdPurchaseResult.fromJson(res.data ?? const {});
  }

  @override
  Future<IdCard> createCard(CreateIdCardRequest req) async {
    final res = await dio.post<Map<String, dynamic>>(ApiPaths.meIdCards, data: req.toJson());
    return IdCard.fromJson(res.data ?? const {});
  }

  @override
  Future<List<IdCard>> listCards() async {
    final res = await dio.get<List<dynamic>>(ApiPaths.meIdCards);
    return (res.data ?? const [])
        .map((e) => IdCard.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  @override
  Future<IdCard> getCard(int cardId) async {
    final res = await dio.get<Map<String, dynamic>>(ApiPaths.meIdCard(cardId));
    return IdCard.fromJson(res.data ?? const {});
  }

  @override
  Future<HdPurchaseResult> purchaseHdForCard(int cardId, HdPayChannel channel) async {
    final res = await dio.post<Map<String, dynamic>>(
      ApiPaths.meIdCardHd(cardId),
      data: {'channel': channel.wire},
    );
    return HdPurchaseResult.fromJson(res.data ?? const {});
  }
}

final Provider<IdCardRepository> idCardRepositoryProvider =
    Provider<IdCardRepository>((ref) => DioIdCardRepository(ref.read(dioProvider)));

/// 当前用户身份证数据（无档案 → null）。旧单卡端点，保留兼容。
final FutureProvider<IdCardData?> idCardProvider = FutureProvider<IdCardData?>(
  (ref) => ref.read(idCardRepositoryProvider).getMyIdCard(),
);

/// 历史卡列表（Story 6.7，createdAt 倒序）。KTP 页进入即渲染此列表。
final FutureProvider<List<IdCard>> idCardListProvider = FutureProvider<List<IdCard>>(
  (ref) => ref.read(idCardRepositoryProvider).listCards(),
);

/// 单卡快照详情（Story 6.7）。详情页按 cardId 拉取，购买解锁后 invalidate 刷新。
final idCardDetailProvider = FutureProvider.family<IdCard, int>(
  (ref, cardId) => ref.read(idCardRepositoryProvider).getCard(cardId),
);
