# Следующая сессия: double emit в switch, LinkBootstrap, javac-гейт и фикстуры

Статус: M1–M8, P4, S1, S3 закрыты. B1–B3 выполнены: инварианты на
exception-замыкании, матрица s11→s15 с clinit сошлась до единицы, все три jar
пересн­яты на текущей сборке. Гейт разблокирован: S2 уходит в набор фикстур, S4 — disclose, S6 закрыт по provenance.

## Сделано (M-раунд)

- **M1.** `Ast.constValue` больше не врёт: сворачиваются только int/long
  (точные маски сдвигов 0x1F/0x3F, `>>>` от отрицательных корректно, сравнения без
  double), float/double/строки не сворачиваются. `const-folded=4`.
- **M3.** `Ast.Assign(Expr target, Expr value)`: PUTFIELD пишет в реальный ресивер
  (`arr[i].x = v`, `oVar3.x = v`), PUTSTATIC — `Class.field = v`. DeadStores/
  collectLocals/provenance обновлены под Expr-таргет.
- **M4.** `dead-stores` в diag (счётчик удалённых write-only констант) — сейчас 1.
- **M5.** LambdaMetafactory с захваченными аргументами — bail `lambda captures`
  (22 на slash-obf-legit); без захватов — method-ref.
- **M6.** ObjectMethods: handle'ы — аксессоры; компоненты record печатаются
  вызовом (`this.time()`), field-handle — полем. Больше нет `this.time` при поле
  `Ag_$a`.
- **M2.** Категория значения (1/2) хранится структурно при push
  (`IdentityHashMap<Expr,Integer>`), все `stack.push` переведены на `pushCat`;
  `category2` больше не угадывает по имени. DUP2/DUP_X1/POP2/SWAP работают по факту.
- **M7.** `TypeInference.classifyArrayStores`: слот, куда кладут `new T[]`,
  получает тип массива (`RT.dStr` → `byte[]` вместо `Object`): BasicVerifier
  отдаёт для NEWARRAY/ANEWARRAY ссылку на Object и теряет размерность.
- **M8.** `verifyAllPathsTerminate` ищет последнюю настоящую инструкцию блока
  (хвостовой label после ARETURN больше не «теряет» возврат); `cannotCompleteNormally`
  рекурсивно принимает try/catch и if, где все ветви завершаются throw.
  `missing return` 4 → 0 (Krndc, qr, phwoCn, HYFuq восстановлены).
- **P4 (F7/F8).** `verifyEmittedBlocks` и `verifyAllPathsTerminate` переведены на
  `reachableWithHandlers()`; handler закрывается только `catchEmits`/drop,
  normal-only в `verifyHandlerCoverage` больше не принимается. Поймано 2 метода
  (`dMUWHE`, `skrdLr`, lost block +1).
- **S1.** const-fold снапшотит и откатывает `droppedHandlers`/`switchConsumed`
  вместе со счётчиками (мёртвая ветвь больше не съедает handler живого try).
- **S3.** `pendingNew` без `<init>` на выходе блока — bail, если слот где-либо
  читается (`withSlotReads(slotReadAnywhere)`); мёртвые записи отбрасываются.
  19 отказов на legit, 11 из них раньше «выживали» с arg-less `new`.
- **Switch-дедуп.** Несколько ключей на одно тело (`TABLESWITCH L24 L24 L32 L32`)
  больше не эмитят тело на каждый ключ: тело — у последнего ключа группы,
  остальные пустые (fallthrough). Double emit 2 → 0 (`_Pk`, `WWq` восстановлены).
  `default`, делящий тело с case, — явный bail `switch default shares case body`
  (у `k(BI)I`, `_jaixjV(BI)I` причина сменилась с underflow, новых потерь нет).
- **LinkBootstrap.connect.** Восстановлен guard-indy: `bsmArgs[0]` — ConstantDynamic
  `symbol` с парой (Base64, key); алгоритм `decode` (Base64 + xorshift32:
  `st ^= st<<13; st ^= st>>>17; st ^= st<<5`, XOR по байтам) снят из байткода и
  повторён в `BytecodeToExpr.decodeGuardString`. Строка `<owner>\0<method>`;
  tag 184 — INVOKESTATIC (печатается `owner.method(...)`), иначе получатель —
  первый аргумент desc. void-вызов эмитится `ExprStmt` (иначе терялся на стеке).
  SlashW: `unknown bootstrap` 1036 → **0**, survived 3474 → 3858.

## Baseline `slash-obf-legit.jar` (P4 + S1 + S3)

`undecompilable = 473` из 1942 (24.4%), survived = 1469, class-level errors = 0.
Балл качества: **108**.

| Причина | Кол-во |
|---|---|
| underflow | 203 |
| lost block | 22 |
| double emit | 0 |
| dead code | 0 |
| missing return | 0 |
| other | 248 |

`other` включает 19 `pending new without <init>` (S3; 11 методов раньше выживали
с arg-less `new`). diag: `rethrow-dropped=744`, `rethrow-slot: -2=744 gte0=0 -1=26`,
`const-folded=4`, `dead-stores=1`, `empty-roots=0`.
Все три jar (legit, bytecode, renamed) на текущей сборке дают одинаковые
475/1467, балл 108.

Double emit 2 (`_Pk`/`WWq`) — switch-case дублирование, не fold.

## SlashW (sW24, guard восстановлен)

820 классов, 5833 метода: undecompilable 1975 (33.9%), survived 3858, балл 687.
underflow 419, lost block 725, double emit 0, dead code 0, missing 0,
other 831, `clinit` 59.

Рост underflow/lost block (279→419, 452→725) — не регресс: методы, которые
раньше падали на первом же guard-indy, теперь декомпилируются дальше и
вскрывают реальные дыры структуры. `rethrow-slot: -1=71`.
`empty-roots=4` — те же четыре andromeda-метода.

Диагностика:
- `rethrow-slot: -2=0 gte0=0 -1=65` — `rethrow-dropped=0` реален: все 65
  multi-block try имеют handler не-чистый-rethrow (-1), детектор не молчит.
- `empty-roots=4`: `APdFNjAFvR.AAFhddEmUE(String,FI)String`,
  `AhtXIqxNJu.AAFhddEmUE(OutputStream,I)V`, `AyQWuvVUDM.AAFhddEmUE(Throwable)String`,
  `ItemFlowMixin.andromeda$restoreItemPose(...)`.
- Lost block: 452 (было 467) — S3/P4 перевели часть в `pending new`; новых
  lost-block маркеров после M7 три: `AAFHddEmUE(AhLZOoJbTX,I)V` (BB2),
  `AAFHddEmUE(Matrix4f,FFFI)V` (BB2), `ABrWdLXFkH()V` (BB1).

## Матрица s11→s15

`migration-s11-s15-v2.csv` — 1942 строки, `<clinit>` включён (69 строк).
Сходимость: bail→survived **115** (111 + 3 underflow + 1 lost block из clinit),
survived→bail **49**, net **66** = 1487−1421 ✓.

## Очередь

1. **javac-гейт + идемпотентность** (ин-процессный JavaCompiler, фильтр
   `cant.resolve`/`doesnt.exist`, категории missing return/unreachable/definite
   assignment; декомпил→рекомпил→декомпил→AST-diff).
2. **ASM-фикстуры** (там же S2): opaque-true ромб с сайд-эффектом в else,
   PUTFIELD на локале, cat-2 DUP2, nested try в handler'е, loop-header как entry,
   switch fallthrough, **assign+IF во вложенной ветви**. Если фикстура S2 не
   воспроизводит потерю prelude — recoverer корректен, инвариант не нужен.
3. **S4 (disclose, не блокер).** record `equals` для float/double компонентов
   печатается через `Objects.equals`, а не `Float.compare` — семантика отличается
   для NaN/±0.0; зафиксировать в доках.
4. **Дизайн-нота entry-stack** после фикстур.

## Проверка

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12'; .\gradlew.bat --console=plain classes
java -cp "build\classes\java\main;<asm 9.7.1>" decompiler.cli.Main "C:\Users\User\Desktop\slash-obf-legit.jar" --ast 1>s-out.txt 2>s-err.txt
java -cp "..." decompiler.cli.Main "C:\Users\User\Desktop\slashW.jar" --ast 1>sW-out.txt 2>sW-err.txt
```
