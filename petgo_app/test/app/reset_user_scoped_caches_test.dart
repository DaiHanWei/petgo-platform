import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';

/// 回归：切换账号后失效用户维度缓存，使新用户各 Tab 重拉（修：换账号后档案/我的等仍显上一用户）。
/// 以 petProfileProvider 为代表：`resetUserScopedCaches` 调用后应触发其重新拉取。
void main() {
  testWidgets('resetUserScopedCaches → 用户维度 provider 重新拉取', (tester) async {
    var fetches = 0;
    late WidgetRef captured;

    await tester.pumpWidget(ProviderScope(
      overrides: [
        petProfileProvider.overrideWith((ref) async {
          fetches++;
          return null;
        }),
      ],
      child: MaterialApp(
        home: Consumer(
          builder: (context, ref, _) {
            captured = ref;
            ref.watch(petProfileProvider); // 保持 provider 活跃（有监听者才会重执行）
            return const SizedBox.shrink();
          },
        ),
      ),
    ));
    await tester.pump();

    expect(fetches, 1, reason: '首帧拉取一次');

    // 模拟账号切换收口调用。
    resetUserScopedCaches(captured);
    await tester.pump();

    expect(fetches, 2, reason: '失效后新用户应重新拉取档案');
  });

  // 类型自证：确保覆写签名与真实 provider 一致（PetProfile? 可空）。
  test('petProfileProvider 类型为 FutureProvider<PetProfile?>', () {
    expect(petProfileProvider, isA<FutureProvider<PetProfile?>>());
  });
}
