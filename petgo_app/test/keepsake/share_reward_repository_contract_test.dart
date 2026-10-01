import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/api_paths.dart';
import 'package:tailtopia/core/network/dio_client.dart';
import 'package:tailtopia/features/pet_passport/data/passport_share_reward_repository.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_share_reward_repository.dart';

/// V1.3.2 Story 4.5 · AC3.2 契约：请求体**只含 `cardType`**、路径固定、`coins` 非 num → 0。
void main() {
  late List<RequestOptions> sent;
  late Object? reply;

  ProviderContainer container() {
    final dio = Dio(BaseOptions(baseUrl: 'http://x'))
      ..interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
        sent.add(o);
        h.resolve(Response(requestOptions: o, statusCode: 200, data: reply));
      }));
    final c = ProviderContainer(overrides: [dioProvider.overrideWithValue(dio)]);
    addTearDown(c.dispose);
    return c;
  }

  setUp(() {
    sent = [];
    reply = {'coins': 12};
  });

  test('Tailsonality：POST 固定路径、请求体只有 cardType', () async {
    final c = container();
    final coins = await c.read(tailsonalityShareRewardRepositoryProvider).reportShareForReward('MATCH');
    expect(coins, 12);
    expect(sent.single.method, 'POST');
    expect(sent.single.path, ApiPaths.meTailsonalityShareRewards);
    expect(sent.single.data, {'cardType': 'MATCH'});
  });

  test('护照：POST 固定路径、请求体只有 cardType', () async {
    final c = container();
    final coins = await c.read(passportShareRewardRepositoryProvider).reportShareForReward('BOARDING');
    expect(coins, 12);
    expect(sent.single.path, ApiPaths.mePassportShareRewards);
    expect(sent.single.data, {'cardType': 'BOARDING'});
  });

  test('coins 非 num / 缺键 → 0', () async {
    final c = container();
    reply = {'coins': '5'};
    expect(await c.read(tailsonalityShareRewardRepositoryProvider).reportShareForReward('RESULT'), 0);
    reply = <String, dynamic>{};
    expect(await c.read(passportShareRewardRepositoryProvider).reportShareForReward('PAGE'), 0);
  });

  test('路径常量', () {
    expect(ApiPaths.meTailsonalityShareRewards, endsWith('/pet-profiles/me/tailsonality/share-rewards'));
    expect(ApiPaths.mePassportShareRewards, endsWith('/pet-profiles/me/passport/share-rewards'));
  });
}
