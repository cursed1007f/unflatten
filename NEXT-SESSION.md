# Состояние: entry-stack landed (s35), nested try — диагноз, ждём решения по фиксу

Дата: 2026-11-17. Базовая сборка зелёная (`gradlew classes`/`test`), дерево чистое.

## Baseline `slash-obf-legit.jar` (s35)

`undecompilable = 337` из 1942 (17.4%), survived = **1605**, class-errors = 0.
Балл: **162** (deadIvar 76×2 + return0 10 = 162). clinit (secondary) = 0,
`clinit-return-stripped` = 68.

| Причина | Кол-во |
|---|---|
| underflow | 45 |
| lost block | 22 |
| join depth | 0 |
| double emit | 0 |
| dead code | 0 |
| missing return | 0 |
| other | 270 |

Все три jar (legit / bytecode / renamed) дают одинаковые 337/1942, survived 1605.
Матрицы: `migration-s33-s34.csv` (136 underflow→join depth), `migration-s34-s35.csv`
(136 join depth→survived, survived→bail = 0).

## Entry-stack: что сделано

Файлы:
- `decompiler/ast/StackValue.java` — Kind/type/slot/size + `merge`
  (разные примитивы и size mismatch → `MergeFailure`; ссылки → Object);
- `decompiler/ast/StackInference.java` — входные стеки по индексу инструкции
  из кадров `BasicVerifier` (handler-вход: stack=1, тип Object);
- `decompiler/ast/StackSimulator.java` — exit-stack блока без AST
  (push/pop/dup с категориями; неизвестный опкод/underflow — IllegalStateException);
- двухфазная эмиссия: `blockStmts` (списки блоков) + `Ast.Block`-обёртка,
  `blockResults` (`BytecodeToExpr.stackSnapshot()`);
- post-pass phi: блок с непустым входом эмитится с `tN`, `lowerPhi` вставляет
  `tN = <exit-выражение>` в предшественников; нет значения — `join depth mismatch`.

Ключевой фикс: `case GOTO` больше не чистит стек (`BytecodeToExpr`) — иначе
exit-stack блока с GOTO был пуст и phi нечего было брать. После этого:
- тернарник `t(IZ)I` собирается (`int t0; if … t0 = …; return t0;`), фикстура зелёная;
- survived 1469 → 1605, join depth 136 → 0.

Тесты: 16, **15 pass, 1 skipped** (nested try).

## Nested try: диагноз (не закрыт)

Фикстура `n()V` (try внутри catch) падает: `lost block: handler BB2 emitted without catch`.
Лог `tryBuildTryCatch`/`verifyHandlerCoverage`:
```
NLOG try b=0 matches=1
NLOG try b=0 handler=1 single=true
```
Внутренний try-start (`l2`) не логируется вообще: catch-тело эмитится
`runHandlerBlock` (сырой эмулятор по одному блоку), а `l2` — метка **внутри**
блока хендлера (не лидер), поэтому `tryBuildTryCatch` его не видит; coverage
через exception-замыкание видит BB2 и честно падает.

Варианты фикса (оба структурные, ждём выбора):
1. резать блоки по границам try-range — метка старта try становится лидером,
   тогда `tryBuildTryCatch` увидит `l2`;
2. конвертировать catch-тело через `convertNode`/структуру, а не `runHandlerBlock`.

Lost block 22 (цель ~10) — тот же класс: handler-структура, не entry-stack.

## javac-гейт

`--gate` (in-process javac, jar в classpath, фильтр resolve/`_`) + каскадный фильтр
(cant.resolve ⇒ type-check ошибки не в счёт). Последний замер — s32, **до** phi:
bad 86 / real 47; `ret.outside.meth` и `int.number.too.large` = 0 после printer-правок.
После phi гейт не перезапускался — нужен новый замер.

## Коммиты (после f0e1a61)

a4f5df7 P4/M2/M7/M8/S1/S3 · b95e3e4 switch-дедуп · a0fb4f1 LinkBootstrap
394b668 gate · d449cc6 каскадный фильтр + score · 60f7f7b L/clinit + тесты
62f7c91 7 фикстур + guard · daeeaa9 StackValue/StackInference · 296be32 1a реестр
af6b3e5 1b Ast.Block · c980c8a StackSimulator · 0e4f279 3a exit-выражения
cc5cdda post-pass phi · c352247 detail-сообщение · 6893447 revert · 3ff9797 GOTO-фикс

## Очередь

1. **Nested try** — выбрать вариант фикса (1 или 2) и закрыть фикстуру.
2. **Lost block** 22 → ~10 после nested-try.
3. **Перезамер**: три jar + `--gate` (bad/real) + матрица s35→s36.
4. **S4 (disclose).** record equals для float/double — Objects.equals вместо
   Float.compare, различие для NaN/±0.0.
5. Идемпотентность: декомпил→рекомпил→декомпил→AST-diff.

## Проверка

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12'; .\gradlew.bat --console=plain classes; .\gradlew.bat test
java -cp "build\classes\java\main;<asm 9.7.1>" decompiler.cli.Main <jar> --ast 1>sN-out.txt 2>sN-err.txt
java -cp "..." decompiler.cli.Main <jar> --gate 1>gate-out.txt 2>gate-err.txt
```
## Lost block 22 (s38): классификация

Список (класс :: метод [деталь]): 20 × «BBn не эмитирован» (BB3 ×8, BB4 ×5,
BB6 ×3, BB8/BB9/BB5/BB12 ×1) + 2 × «handler без catch» (`IprNDA$2397.dMUWHE`
BB3, `skrdLr` BB9).

Причина 20 «BBn» (подтверждено дампом `IAzR$zQG$6211.MHBxFR`):
структура `block 0; loop(header=1){ block 2 }; block 15`, при том что в CFG
BB2 → BB3 (ребро внутрь тела). Recoverer **обрывает тело цикла на первом блоке**
(BB2), остальные блоки тела (BB3…) в структуру не попадают → normalEmits=0 →
честный bail. Это не бухгалтерия условий и не entry-stack: дыра в enclosure
тела цикла/ветвлений внутри него. Аналогичная картина у `IGDVkXd$8812.x`,
`INAW$6302.oes$erN`, `IrDWgNkQy$1260.qeEzCF`, `IfrJYYSTS$6860.zjlWLnB`.

Причина 2 handler-случаев: catch-путь вне структуры (P4 их поймал; отдельный класс).

Решение: **disclose как known gap** — bail честный, тихой потери нет.
Фикс относится к StructureRecoverer (enclosure тела цикла), отдельная задача,
не entry-stack.