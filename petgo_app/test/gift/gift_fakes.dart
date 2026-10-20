import 'dart:async';

import 'package:dio/dio.dart';
import 'package:tailtopia/features/gift/data/gift_repository.dart';
import 'package:tailtopia/features/gift/domain/gift_catalog.dart';
import 'package:tailtopia/features/gift/domain/gift_send_result.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';

/// V1.4.0 gift Story 1.3 测试共用：假仓储、假余额、目录样例、ProblemDetail 异常。

const tulang = GiftItem(giftToken: 'g-tulang', nameEn: 'Bone', nameId: 'Tulang', price: 1000);
const ikan = GiftItem(giftToken: 'g-ikan', nameEn: 'Dried fish', nameId: 'Ikan teri', price: 1000);
const ayam = GiftItem(giftToken: 'g-ayam', nameEn: 'Drumstick', nameId: 'Paha ayam', price: 5000);
const mahkota = GiftItem(
    giftToken: 'g-mahkota',
    nameEn: 'Crown',
    nameId: 'Mahkota',
    price: 25000,
    kind: 'EXCLUSIVE',
    minLevel: 5);

GiftCatalog sampleCatalog({
  int qrisMinAmount = 10000,
  List<GiftExclusiveGroup> exclusiveGroups = const [],
  GiftLockBlock? lockBlock,
  String petName = 'Mochi',
}) =>
    GiftCatalog(
      recipient: GiftRecipient(petId: 42, name: petName, petType: 'CAT', breed: 'Persia'),
      qrisMinAmount: qrisMinAmount,
      // 故意乱序：App 按价位升序再排一次。
      tiers: const [
        GiftTier(price: 5000, gifts: [ayam]),
        GiftTier(price: 1000, gifts: [tulang, ikan]),
      ],
      exclusiveGroups: exclusiveGroups,
      lockBlock: lockBlock,
    );

DioException problem(String slug, {int status = 409}) {
  final ro = RequestOptions(path: '/');
  return DioException(
    requestOptions: ro,
    response: Response<dynamic>(
      requestOptions: ro,
      statusCode: status,
      data: {'type': 'https://tailtopia.app/problems/$slug', 'status': status},
    ),
  );
}

class SendCall {
  SendCall(this.petId, this.giftToken, this.quantity, this.channel, this.requestId, this.source,
      this.postId);
  final int petId;
  final String giftToken;
  final int quantity;
  final String channel;
  final String requestId;
  final String source;
  final int? postId;
}

class FakeGiftRepository extends GiftRepository {
  FakeGiftRepository({GiftCatalog? catalog}) : catalog = catalog ?? sampleCatalog(), super(dio: Dio());

  GiftCatalog catalog;

  /// 非空 → 目录接口抛它。
  Object? catalogError;

  /// 非空 → 目录接口等它（B6 加载中）。
  Completer<void>? catalogGate;
  int catalogCalls = 0;
  final List<({int? petId, int? postId})> catalogArgs = [];

  /// 每次发起的结局：按顺序消费；用完后重复最后一个。元素为 [GiftSendResult] 或要抛出的异常。
  List<Object> sendResults = [const GiftSendResult(delivered: true, orderToken: 'ord-1')];
  final List<SendCall> sends = [];

  /// 订单状态序列（QRIS 轮询）：按顺序消费；用完后重复最后一个。
  List<GiftOrderStatus> statuses = [GiftOrderStatus.pending];
  int statusCalls = 0;

  @override
  Future<GiftCatalog> fetchCatalog({int? petId, int? postId}) async {
    catalogCalls++;
    catalogArgs.add((petId: petId, postId: postId));
    final gate = catalogGate;
    if (gate != null) await gate.future;
    final err = catalogError;
    if (err != null) throw err;
    return catalog;
  }

  @override
  Future<GiftSendResult> send({
    required int petId,
    required String giftToken,
    required int quantity,
    required String channel,
    required String requestId,
    required String source,
    int? postId,
  }) async {
    sends.add(SendCall(petId, giftToken, quantity, channel, requestId, source, postId));
    final i = sends.length - 1;
    final r = sendResults[i < sendResults.length ? i : sendResults.length - 1];
    if (r is GiftSendResult) return r;
    throw r;
  }

  @override
  Future<GiftOrderStatus> orderStatus(String orderToken) async {
    final i = statusCalls++;
    return statuses[i < statuses.length ? i : statuses.length - 1];
  }
}

/// 假余额：记录 build 次数（= 被 invalidate 后重读的次数）。
class FakePawCoin extends PawCoinController {
  FakePawCoin(this.balance, this.builds);

  final int Function() balance;
  final List<int> builds;

  @override
  Future<PawCoinState> build() async {
    builds.add(balance());
    return PawCoinState(balance: balance(), items: const []);
  }
}
