import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/triage/domain/triage_wording_guard.dart';

void main() {
  group('TriageWordingGuard', () {
    test('检测中/印尼/英终结性表述', () {
      expect(TriageWordingGuard.hasTerminalWording('情况不严重，继续观察'), isTrue);
      expect(TriageWordingGuard.hasTerminalWording('可以放心'), isTrue);
      expect(TriageWordingGuard.hasTerminalWording('Tidak serius kok'), isTrue);
      expect(TriageWordingGuard.hasTerminalWording("It's not serious, don't worry"), isTrue);
    });

    test('正常观察文案不误判', () {
      expect(TriageWordingGuard.hasTerminalWording('请观察精神状态与进食量'), isFalse);
      expect(TriageWordingGuard.hasTerminalWording('Pantau nafsu makan'), isFalse);
    });

    test('sanitize：含终结词 → 回退中性提示；正常 → 原样', () {
      expect(TriageWordingGuard.sanitize('不严重，没事了', fallback: 'NEUTRAL'), 'NEUTRAL');
      expect(TriageWordingGuard.sanitize(null, fallback: 'NEUTRAL'), 'NEUTRAL');
      expect(TriageWordingGuard.sanitize('观察 12 小时', fallback: 'NEUTRAL'), '观察 12 小时');
    });
  });
}
