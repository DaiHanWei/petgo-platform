import 'package:tailtopia/features/pet_passport/data/passport_share_reward_repository.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_share_reward_repository.dart';

/// V1.3.2 Story 4.5 测试替身：记录上报的卡类型、按 [coins] 返回（[fail] 时抛错）。
class FakeTailsonalityShareReward implements TailsonalityShareRewardRepository {
  final List<String> calls = [];
  int coins = 0;
  bool fail = false;

  @override
  Future<int> reportShareForReward(String cardType) async {
    calls.add(cardType);
    if (fail) throw Exception('network');
    return coins;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class FakePassportShareReward implements PassportShareRewardRepository {
  final List<String> calls = [];
  int coins = 0;
  bool fail = false;

  @override
  Future<int> reportShareForReward(String cardType) async {
    calls.add(cardType);
    if (fail) throw Exception('network');
    return coins;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
