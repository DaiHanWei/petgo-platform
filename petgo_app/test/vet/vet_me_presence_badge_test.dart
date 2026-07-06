import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/vet/data/vet_repository.dart';
import 'package:tailtopia/features/vet/domain/vet_login_response.dart';
import 'package:tailtopia/features/vet/domain/vet_online_status.dart';
import 'package:tailtopia/features/vet/domain/vet_workbench_lists.dart';
import 'package:tailtopia/features/vet/presentation/vet_me_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 头像在线状态角标（修 20260702-234）：
/// 角标恒显示，颜色随三态 availability 变（online 绿 / busy 黄 / offline 灰）——
/// 回归此前「颜色写死 vetPrimary + 门控用二元 online 致 Busy/Offline 角标消失」的缺陷。
class _FakeVetRepository extends VetRepository {
  _FakeVetRepository() : super(dio: Dio(), tokenStore: InMemoryTokenStore());

  @override
  Future<VetMe> me() async => const VetMe(id: 1, displayName: 'drh. Dewi', status: 'ACTIVE');

  @override
  Future<List<VetHistoryEntry>> history() async => const [];
}

/// 固定 availability 的 notifier（跳过对二元在线态的派生，直接返回目标态）。
class _FixedAvail extends VetAvailabilityNotifier {
  _FixedAvail(this._fixed);
  final VetAvailability _fixed;
  @override
  VetAvailability build() => _fixed;
}

Future<void> _pump(WidgetTester tester, VetAvailability avail) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      vetRepositoryProvider.overrideWithValue(_FakeVetRepository()),
      vetAvailabilityProvider.overrideWith(() => _FixedAvail(avail)),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: VetMePage(),
    ),
  ));
  await tester.pumpAndSettle();
}

Color _badgeColor(WidgetTester tester) {
  final badge = tester.widget<Container>(find.byKey(const ValueKey('vetPresenceBadge')));
  return (badge.decoration as BoxDecoration).color!;
}

void main() {
  testWidgets('Online → 角标显示且为主色（绿）', (tester) async {
    await _pump(tester, VetAvailability.online);
    expect(find.byKey(const ValueKey('vetPresenceBadge')), findsOneWidget);
    expect(_badgeColor(tester), AppColors.vetPrimary);
  });

  testWidgets('Busy → 角标仍显示（不再消失）且为黄', (tester) async {
    await _pump(tester, VetAvailability.busy);
    expect(find.byKey(const ValueKey('vetPresenceBadge')), findsOneWidget);
    expect(_badgeColor(tester), AppColors.triageYellow);
  });

  testWidgets('Offline → 角标仍显示且为灰', (tester) async {
    await _pump(tester, VetAvailability.offline);
    expect(find.byKey(const ValueKey('vetPresenceBadge')), findsOneWidget);
    expect(_badgeColor(tester), AppColors.textTertiary);
  });
}
