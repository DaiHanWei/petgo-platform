import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/data/me_repository.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/me/presentation/phone_edit_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.1.6 Story 7.1：手机号编辑抽屉。
///
/// <p>守两件事：**清空即撤回、不做二次确认**，以及**失败时的提示与埋点**
/// （那条埋点是判断"校验规则是不是太严在挡人"的唯一依据）。
class _FakeMeRepo implements MeRepository {
  _FakeMeRepo({this.failWith});

  /// 非空则 updatePhone 抛这个异常。
  final DioException? failWith;
  final List<String> savedPhones = [];

  @override
  Future<UserProfile> updatePhone(String phone) async {
    if (failWith != null) throw failWith!;
    savedPhones.add(phone);
    return UserProfile(phone: phone.isEmpty ? null : phone);
  }

  @override
  Future<UserProfile> getMe() async => const UserProfile();
  @override
  Future<UserProfile> updateNickname(String nickname) async => const UserProfile();
  @override
  Future<UserProfile> updatePetStatus(String petStatus) async => const UserProfile();
  @override
  Future<UserProfile> updateAvatar(String avatarUrl) async => const UserProfile();
  @override
  Future<UserProfile> updateProfile({String? nickname, String? signature}) async =>
      const UserProfile();
}

DioException _dio(int status) => DioException(
      requestOptions: RequestOptions(path: '/api/v1/me'),
      response: Response(requestOptions: RequestOptions(path: '/api/v1/me'), statusCode: status),
    );

Future<_FakeMeRepo> _open(
  WidgetTester tester, {
  String? existingPhone,
  DioException? failWith,
  String entry = 'me_page',
}) async {
  final repo = _FakeMeRepo(failWith: failWith);
  final container = ProviderContainer(overrides: [
    meRepositoryProvider.overrideWithValue(repo),
  ]);
  addTearDown(container.dispose);
  container.read(authControllerProvider.notifier).applyProfile(
        UserProfile(id: 1, nickname: 'Budi', phone: existingPhone),
      );

  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () => PhoneEditSheet.open(ctx, entry: entry),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return repo;
}

/// ⚠️ toast 是带定时器的浮层：断言完必须把它的计时走完，
/// 否则用例结束时会抛「A Timer is still pending」。
Future<void> _letToastExpire(WidgetTester tester) async {
  await tester.pump(const Duration(seconds: 3));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() => Analytics.debugCaptureSink = null);

  group('AC2 保存', () {
    /// 编辑时展示完整号码 —— 但 **`+62` 由常驻前缀承担**（bug 20260826 对齐设计稿）。
    ///
    /// 输入框里只留国内部分：国家码已经在左侧芯片上常驻，再留在框里就是「+62 +62」。
    /// ⚠️ 这是**有意的行为变化**，不是把断言改松：`+62` 仍然出现在这一屏上（芯片里），
    /// 变的只是它归谁渲染。两条都断言，避免哪天前缀被删掉而这里照样绿。
    testWidgets('编辑时展示完整号码（+62 在常驻前缀里，输入框只放国内部分）', (tester) async {
      await _open(tester, existingPhone: '+6281234567890');
      expect(find.text('81234567890'), findsOneWidget,
          reason: '输入框应只放国内部分');
      expect(find.textContaining('+62'), findsOneWidget,
          reason: '🔴 国家码前缀不见了 —— 用户会以为要自己带 +62，反而填出两种形态');
    });

    testWidgets('保存成功显示成功提示', (tester) async {
      final repo = await _open(tester);
      await tester.enterText(find.byKey(const ValueKey('phoneInput')), '0812 3456 7890');
      await tester.tap(find.byKey(const ValueKey('phoneSave')));
      await tester.pumpAndSettle();

      expect(repo.savedPhones, ['0812 3456 7890']);
      expect(find.textContaining('saved'), findsOneWidget);
      await _letToastExpire(tester);
    });

    /// 🔴 格式失败 → 提示 + 上报。
    ///
    /// 那条埋点是判断「校验规则是不是太严在挡人」的**唯一依据** ——
    /// 失败率偏高说明是我们卡太严，而不是用户填错。
    testWidgets('格式失败显示失败提示并上报', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

      await _open(tester, failWith: _dio(422));
      await tester.enterText(find.byKey(const ValueKey('phoneInput')), '12345');
      await tester.tap(find.byKey(const ValueKey('phoneSave')));
      await tester.pumpAndSettle();

      expect(find.textContaining('format'), findsOneWidget);
      final err = seen.where((e) => e.$1 == 'me_phone_save_error_shown').toList();
      expect(err, hasLength(1));
      expect(err.first.$2?['entry'], 'me_page');
      await _letToastExpire(tester);
    });

    /// 网络类错误不该被当成"格式不对" —— 那会把用户往改号码的方向误导。
    testWidgets('网络失败给的是另一种提示，且不报格式失败', (tester) async {
      final seen = <String>[];
      Analytics.debugCaptureSink = (e, p) => seen.add(e);

      await _open(tester, failWith: _dio(500));
      await tester.enterText(find.byKey(const ValueKey('phoneInput')), '081234567890');
      await tester.tap(find.byKey(const ValueKey('phoneSave')));
      await tester.pumpAndSettle();

      expect(find.textContaining('try again'), findsOneWidget);
      expect(seen.where((e) => e.contains('error_shown')), isEmpty);
      await _letToastExpire(tester);
    });
  });

  group('AC4 🛡 清空即撤回', () {
    /// 🔴 清空**照样发出去** —— 那是撤回语义。
    /// 若在客户端"空就不发"，用户以为删了、其实没删，而且提示还说成功。
    testWidgets('清空后保存会把空值发给后端', (tester) async {
      final repo = await _open(tester, existingPhone: '+6281234567890');
      await tester.enterText(find.byKey(const ValueKey('phoneInput')), '');
      await tester.tap(find.byKey(const ValueKey('phoneSave')));
      await tester.pumpAndSettle();

      expect(repo.savedPhones, [''], reason: '空串必须发出去，服务端据此写回空值');
      await _letToastExpire(tester);
    });

    /// 🛡 **不触发任何二次确认** —— 撤回是用户的权利，不该被"你确定吗"劝阻一次。
    testWidgets('清空保存不弹二次确认', (tester) async {
      await _open(tester, existingPhone: '+6281234567890');
      await tester.enterText(find.byKey(const ValueKey('phoneInput')), '');
      await tester.tap(find.byKey(const ValueKey('phoneSave')));
      await tester.pump();

      expect(find.byType(AlertDialog), findsNothing);
      expect(find.byType(Dialog), findsNothing);
      await tester.pumpAndSettle();
      await _letToastExpire(tester);
    });

    testWidgets('输入框为空时保存按钮仍可点', (tester) async {
      await _open(tester);
      final btn = tester.widget<FilledButton>(find.byKey(const ValueKey('phoneSave')));
      expect(btn.onPressed, isNotNull, reason: '空 = 清空保存，不是无效操作');
    });
  });

  group('埋点：首次填写 vs 修改', () {
    testWidgets('首次填写标记为 true，修改标记为 false', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

      await _open(tester); // 原本没号码
      await tester.enterText(find.byKey(const ValueKey('phoneInput')), '081234567890');
      await tester.tap(find.byKey(const ValueKey('phoneSave')));
      await tester.pumpAndSettle();
      expect(seen.last.$2?['is_first_time'], isTrue);
      await _letToastExpire(tester);

      seen.clear();
      await _open(tester, existingPhone: '+6281111111111'); // 原本有号码
      await tester.enterText(find.byKey(const ValueKey('phoneInput')), '082222222222');
      await tester.tap(find.byKey(const ValueKey('phoneSave')));
      await tester.pumpAndSettle();
      expect(seen.last.$2?['is_first_time'], isFalse);
      await _letToastExpire(tester);
    });
  });
}
