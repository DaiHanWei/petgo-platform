import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/triage/data/triage_repository.dart';

void main() {
  group('TriageResult.fromJson', () {
    test('处理中态仅 status，级别/建议为空', () {
      final r = TriageResult.fromJson({'status': 'PROCESSING'});
      expect(r.status, TriageStatus.processing);
      expect(r.dangerLevel, isNull);
      expect(r.advice, isNull);
      expect(r.isTerminal, isFalse);
    });

    test('DONE 态映射完整结构 + 危险级别', () {
      final r = TriageResult.fromJson({
        'status': 'DONE',
        'dangerLevel': 'YELLOW',
        'advice': '尽快就医',
        'disclaimer': '仅供参考',
      });
      expect(r.status, TriageStatus.done);
      expect(r.dangerLevel, DangerLevel.yellow);
      expect(r.advice, '尽快就医');
      expect(r.isTerminal, isTrue);
    });

    test('FAILED 态为终态供降级', () {
      final r = TriageResult.fromJson({'status': 'FAILED'});
      expect(r.status, TriageStatus.failed);
      expect(r.isTerminal, isTrue);
    });

    test('三态级别全映射', () {
      expect(TriageResult.fromJson({'status': 'DONE', 'dangerLevel': 'GREEN'}).dangerLevel,
          DangerLevel.green);
      expect(TriageResult.fromJson({'status': 'DONE', 'dangerLevel': 'RED'}).dangerLevel,
          DangerLevel.red);
    });
  });
}
