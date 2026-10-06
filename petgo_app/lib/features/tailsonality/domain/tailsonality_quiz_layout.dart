/// 答题分页（V1.3.2 Story 2.3 · AD-2 · 内容设计 §6.1）：3 页 × 6 题，每页 5 道文字题 + 末尾 1 道图片题。
///
/// 分页只影响呈现，**不影响计分**（计分按题号归轴，服务端算）。
const List<List<String>> kTsQuizPages = [
  ['Q1', 'Q2', 'Q3', 'Q4', 'Q5', 'P1'],
  ['Q6', 'Q7', 'Q8', 'Q9', 'Q10', 'P2'],
  ['Q11', 'Q12', 'Q13', 'Q14', 'Q15', 'P3'],
];

/// 物种 → 题套（`CAT`→CAT、`DOG`→DOG、其余→GENERAL），与后端 `TailsonalityCatalog.forPetType` 一致。
///
/// App 侧**只用于显示**题目文案；计分与落库以服务端按宠物物种再选一次为准。
String tsQuestionSetFor(String? petType) => switch (petType) {
      'CAT' => 'CAT',
      'DOG' => 'DOG',
      _ => 'GENERAL',
    };
