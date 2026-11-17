# Следующая сессия: S2/S4, доказательство return 0, затем javac-гейт и entry-stack

Статус: M1–M8, P4, S1, S3 закрыты. B1–B3 выполнены: инварианты на
exception-замыкании, матрица s11→s15 с clinit сошлась до единицы, все три jar
пересн­яты на текущей сборке. Гейт можно начинать после S2 (вложенные if).

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

## Baseline `slash-obf-legit.jar` (P4 + S1 + S3)

`undecompilable = 475` из 1942 (24.5%), survived = 1467, class-level errors = 0.
Балл качества: **108**.

| Причина | Кол-во |
|---|---|
| underflow | 205 |
| lost block | 22 |
| double emit | 2 |
| dead code | 0 |
| missing return | 0 |
| other | 246 |

`other` включает 19 `pending new without <init>` (S3; 11 методов раньше выживали
с arg-less `new`). diag: `rethrow-dropped=744`, `rethrow-slot: -2=744 gte0=0 -1=26`,
`const-folded=4`, `dead-stores=1`, `empty-roots=0`.
Все три jar (legit, bytecode, renamed) на текущей сборке дают одинаковые
475/1467, балл 108.

Double emit 2 (`_Pk`/`WWq`) — switch-case дублирование, не fold.

## SlashW (sW22, P4+S1+S3)

820 классов, 5833 метода: undecompilable 2359 (40.4%), survived 3474, балл 683.
underflow 279, lost block 452, double emit 0, dead code 0, missing 0,
other 1628, `clinit` 59.

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

1. **S2.** ASM-репро «assign + IF во вложенной ветви» + инвариант «prelude уже
   эмитирован либо bail».
2. **S4.** record equals для float/double компонентов — disclose/поправить.
3. **return 0 (497, SlashW)**: приложить 5 методов + байткод (сейчас найден
   только класс `BytecodeVM/GxgJ`).
4. **Double emit в switch** — дедуп case-блоков/регионы, вернуть 0.
5. **LinkBootstrap.connect** (SlashW) — разобрать bsmArgs/Handle, восстановить вызов.
6. **javac-гейт + идемпотентность**, ASM-фикстуры, дизайн-нота entry-stack.

## Проверка

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12'; .\gradlew.bat --console=plain classes
java -cp "build\classes\java\main;<asm 9.7.1>" decompiler.cli.Main "C:\Users\User\Desktop\slash-obf-legit.jar" --ast 1>s-out.txt 2>s-err.txt
java -cp "..." decompiler.cli.Main "C:\Users\User\Desktop\slashW.jar" --ast 1>sW-out.txt 2>sW-err.txt
```
