import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_picker_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 multi-pet Story 3.3 · AC5.2：`PetPickerSheet` 公共件（打卡多选；4.1 / 4.2 复用单选）。
void main() {
  const pets = [
    PetListItem(id: 1, name: 'Orenji', petType: 'CAT'),
    PetListItem(id: 2, name: 'Kopi', petType: 'DOG'),
  ];

  Future<List<int>? Function()> pump(WidgetTester tester, {required bool multi}) async {
    List<int>? result;
    var done = false;
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      home: Builder(
        builder: (context) => Scaffold(
          body: TextButton(
            onPressed: () async {
              result = await showPetPickerSheet(context,
                  pets: pets, title: 'T', multiSelect: multi, submitLabel: (n) => 'Go $n');
              done = true;
            },
            child: const Text('open'),
          ),
        ),
      ),
    ));
    return () {
      expect(done, isTrue);
      return result;
    };
  }

  testWidgets('单选：点一行即返回该只，不出主按钮', (tester) async {
    final read = await pump(tester, multi: false);
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('petPickerSubmit')), findsNothing);
    expect(find.text('Cat'), findsOneWidget); // 副标题物种
    await tester.tap(find.byKey(const ValueKey('petPickerRow-2')));
    await tester.pumpAndSettle();
    expect(read(), [2]);
  });

  testWidgets('多选：默认全不选按钮禁用；选后按创建顺序返回；行高 ≥ 60', (tester) async {
    final read = await pump(tester, multi: true);
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    final btn = tester.widget<ButtonStyleButton>(find.byKey(const ValueKey('petPickerSubmit')));
    expect(btn.onPressed, isNull);
    expect(tester.getSize(find.byKey(const ValueKey('petPickerRow-1'))).height, greaterThanOrEqualTo(60));
    await tester.tap(find.byKey(const ValueKey('petPickerRow-2')));
    await tester.tap(find.byKey(const ValueKey('petPickerRow-1')));
    await tester.pump();
    expect(find.text('Go 2'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('petPickerSubmit')));
    await tester.pumpAndSettle();
    expect(read(), [1, 2]);
  });
}
