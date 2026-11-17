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
## s40: cmp-peephole (LCMP) и disclose enclosure

- **Enclosure раннего выхода из цикла** — disclosed как known gap (13 методов):
  natural loop не включает блоки, уходящие из тела через return; фикс требует
  region-enclosure (post-dominance), отдельная задача.
- **LCMP+IFxx**: `LCMP` даёт маркер `lcmp`, `IFxx` сворачивает его в сравнение long
  (==, !=, <, >=, >, <=). Утечек маркера в выводе нет. s40: survived 1608 -> 1635,
  undecompilable 334 -> 307, cmp-bail 179 -> 140 (остались float/double); матрица: 27 survived + 11 underflow + 1 multi-block = 39 переходов, 179-39=140, underflow
  48 -> 59, балл 162 -> 163; три jar одинаковы.
- **Осталось**: FCMPL/FCMPG/DCMPL/DCMPG требуют точной семантики NaN —
  `((Float.isNaN(a)||Float.isNaN(b)) ? -1 : Float.compare(a,b)) OP 0`; пока bail.
## s41: FCMPL/FCMPG/DCMPL/DCMPG — точная NaN-семантика

Точный предикат без Ast.Ternary: NaN = (isNaN(a)||isNaN(b));
CMPL: NaN дает -1, CMPG: +1. Для каждой ветки IFxx строится
`!NaN && a OP b` либо `NaN || a OP b` (IFNE всегда NaN-true; IFLT/IFLE —
CMPL-true; IFGE/IFGT — CMPG-true; IFEQ — никогда). Печать:
`(!((Double.isNaN(a) || Double.isNaN(b))) && (a >= 0.0))`.
Маркеры cmp не утекают (guard в pop: «cmp not fused», 0 случаев).
s41: survived 1635 -> 1730, undecompilable 307 -> 212, cmp-bail 140 -> 0,
underflow 59 -> 93, lost block 15 -> 18, балл 163 -> 238
(deadIvar 111 + return0 16 — больше кода, больше метрик).
### s41: сверка матрицы (140 переходов)

140 cmp-методов s40 -> 140 переходов s40->s41:
95 survived, 34 underflow, 6 multi-block (other), 1 switch default (other),
1 pending new (other), 3 lost block: `It$FNKYJX$3494.C` (BB10),
`IwLWbtaV$5561.d` (BB4), `IqYyGfVt$5189.yURPS` (BB12).
lost block: 13 BB + 2 handler = 15 (s40) -> 16 BB + 2 handler = 18 (s41); +3 ровно
эти три метода. `multi-block try range` — бакет `other`, не lost block.
Парсер матрицы корректен: CSV lostBB 13/16 совпадает с выхлопом.
## s43: раскладка underflow/lost/other

- **underflow 93** = 92 × `cond` (оценка условия через `runExpr`) + 1 bare.
  Значит условия **не подключены к entry-stack**: `condFrom`/`loopCond` считают
  с пустого стека, хотя блок начинается со значений. Нормальные блоки дают 0
  underflow — блоки уже wiring'нуты. Следующий шаг: seed условий через entryExprs.
- **lost block 18** = 16 BB + 2 handler. BB: 6×BB3, 6×BB4, 1×BB12, 1×BB10,
  1×BB6, 1×BB5. Из них 3 новых из cmp (BB10 `It$FNKYJX$3494.C`, BB4
  `IwLWbtaV$5561.d`, BB12 `IqYyGfVt$5189.yURPS`), остальные — enclosure.
- **other 101** = 41 multi-block, 21 pending new, 21 lambda captures,
  11 switch key prelude, 3 switch default, 3 stray <init>, 1 multiple handlers.
- Инструментация: underflow из blockToStmt/condFrom помечается `b=`/`cond`.
## Сессия s52 — sanitizer идентификаторов (не закоммичено)

**Найдено:** else.without.if (1) — каскад от поля с именем `for` (`private long for;`), т.е. ключевое слово в роли идентификатора (`IrG`).

**Сделано (printer):** `JavaPrinter.id()` — простое имя-ключевое слово или невалидный идентификатор получает суффикс `$`; применяется к объявлению поля, методу, локальным, `Ast.Var`/`FieldAccess`/вызову. `this`/`super` и квалифицированные имена (`a.b`) не трогаются.

**Эффект:** `else.without.if` 1 → 0, `expected` 38 → 24. Метрики декомпиляции без изменений: 133/1942, survived 1809, underflow 5, lost block 18, балл 381, три jar идентичны; матрица s48→s52: 1942 строки, 0 переходов.

**Гейт (честные числа):** bad 126, real 73. Промежуточные 119/58 были искажены: `this` → `this$` давал нерезолвимые символы, которые отфильтровывались как каскад. После фикса всплыли настоящие type-ошибки (`prob.found.req` 612 → 723), в основном в `IrG`.
## Сессия s53 — float-литералы + диагноз Z/I (закоммичено e9231bf)

**float-литералы:** `Float` печатался как double (`12.0`) → lossy conversion во всех классах. Добавлен суффикс `f`. `prob.found.req` 723 → **210**, bad 126 → 115, real 73 → 74 (снятый шум открыл другие ошибки). Метрики: 133/1942, балл 381, три jar идентичны, матрица s52→s53 — 0 переходов.

**Диагноз остатка `prob.found.req` (`IrG`):** это дефект декомпилятора, не обфускации. Метод `KbxHRXq` объявлен `private static boolean`, но его вызов присваивается int-локалу (`iVar9 = KbxHRXq(...)`, строка 187), а затем печатается `if (!iVar10)` — отрицание int. Причина: в JVM boolean — это int, и типизация локалов берётся из кадров (`INTEGER`), поэтому признак `Z` теряется; возвращаемый тип `Z` не доезжает до StackValue/объявления локала.

**Следующий шаг:** нести `Z` в StackValue (invoke с дескриптором `)Z` → kind `Z`), из него выводить `boolean` для локала и для phi-temps; тогда `!` и присваивание станут валидны.
## Сессия s54 — Z-тип в TypeInference (закоммичено 4b29c74)

**Про +1 (73 -> 74):** новых bad-классов нет; g53 vs g52 — исправлено 11 (Ibbzg\ и др.), появившихся 0. Рост eal — переклассификация: класс остался плохим, но перестал маскироваться каскадом cant.resolve.

**Z-фикс:** `refineBooleans` теперь считает boolean-локал, если ISTORE идёт после invoke с дескриптором `)Z` или GETFIELD/GETSTATIC `Z`; литералы 0/1 и цепочки `ILOAD -> ISTORE` поддерживаются; конверсия только при `!intStore && !bad` (арифметика/IINC отменяют). boolean-объявлений 48 -> 70, `prob.found.req` 210 -> 197.

**Ограничение:** phi-temps вида `t4_0 = 0` остаются int, поэтому `boolean iVar10 = t4_0` даёт новую ошибку. Нужен перенос boolean на phi-темп: тип из StackValue + конверсия литерала 0/1 в `false`/`true`.

**Метрики s54:** 133/1942, survived 1809, underflow 5, lost block 18, балл 381; три jar идентичны; матрица s53->s54 — 0 переходов; гейт bad 115, real 74.
## Сессия s55 — булевы литералы и phi-темпы (закоммичено ede276b)

**Сделано:** `normalizeBooleanLiterals` в `StructureToAst` после объявления локалов. Правила: (1) temp, которому присваивается boolean-локал (`Assign(Var boolVar, Var temp)`), объявляется boolean; (2) в boolean-контексте литералы 0/1 печатаются `false`/`true`. Обход по Block/If/While/Switch/TryCatch с заменой элементов списков на месте.

**Эффект:** `prob.found.req` 197 -> 166, bad 115 -> 113. `IrG\`: было 3 ошибки, стало 2 (221, 230). boolean-объявлений 70, `= true/false` 260. Метрики: 133/1942, балл 381; тесты зелёные; матрица s54->s55 — 0 переходов.
## Сессия s57 — массивы из вызовов/полей/cast (закоммичено 33aa1dd)

**Найдено:** `array.req.but.found` — локалы-массивы объявлялись `Object`, когда значение приходило из `INVOKE` (`String[] s = str.split(...)`), поля, `CHECKCAST` или другого слота; `classifyArrayStores` знал только `NEWARRAY`.

**Сделано:** `arrayTypeOf` расширен: `MethodInsnNode` (returnType ARRAY), `FieldInsnNode`, `LDC` типа-массива, `CHECKCAST`, `ALOAD` с уже известным типом массива; 3 защиты от RuntimeException.

**ВАЖНО (артефакт):** промежуточный прогон без защиты дал `undecompilable` 133 -> 75, но это ложь: 32 класса падали в принтере, их методы не декомпилировались, а считались survived (`class-level errors: 32`). С защитой честные 133/1942, `class-level errors: 0`.

**Эффект:** `array.req.but.found` 44 -> 5; `oVar4` стал `String[]`. Остаток — phi-темпы: `java.lang.Object t10_0; oVar4 = t10_0;` (тип temps берётся из StackValue, а invoke-результат там без типа массива).

**Метрики s57:** 133/1942, survived 1809, underflow 5, lost block 18, балл 381; три jar идентичны; матрица s55->s57 — 0 переходов; гейт bad 113, real 74.
## Сессия s59 — тип массива у phi-темпов (закоммичено e74733b)

**Сделано:** `normalizeArrayTemps` — локал/темп с типом `Object` получает тип массива, если ему присваивают вызов с дескриптором-массивом или другой локал-массив; `entryExprs` для переменной из стека берёт тип слота из `slotTypes`, если это массив.

**Проверено:** `IZOBxACP`: `java.lang.String[] t10_0;` и `java.lang.String[] oVar4;` — ошибка на `oVar4 = t10_0` ушла.

**Не достигнуто:** `array.req.but.found` остаётся 5 (было 5) — остаток в других классах (`IAzR`, `IwLWbtaV` и др.), там `[]` применяется к чему-то не-массиву по другой причине. `prob.found.req` 168 -> 167.

**Метрики s59:** 133/1942, survived 1809, underflow 5, lost block 18, балл 381; три jar идентичны; матрица s57->s59 — 0 переходов; гейт bad 113, real 74, class-level errors 0.
## Сессия s63 — сэмплы гейта и попытка типизации массивных temps (закоммичено 0cd1c49)

**Сделано:** `JavacGate` печатает до 12 сэмплов на класс (иначе `array.req` не видно — оно глубже трёх ошибок). `normalizeArrayTemps` получил ветки `NewObject` (`"[I"` и `"L...;;[]"`) и usage-эвристику `Object[]`.

**Найдено:** в `IAzR\\` виновники — phi-темпы: `t7_3 = new java.lang.Object[9]; t8_3 = t7_3; t8_3[t8_4] = ...`, объявлены `Object`. `ANEWARRAY` эмитится как `Ast.NewObject("L"+desc+";[]")`, т.е. дескриптор НЕ начинается с `[` — прежний матчер это не ловил.

**Не достигнуто:** `array.req.but.found` остаётся 5. Пасс `normalizeArrayTemps` до этих присваиваний не доходит (обход `Block/If/While/Switch/TryCatch` не видит контейнер, где лежат строки 353-355). Следующий шаг: проверить, в какой узел вложен `t8_3[t8_4] = ...`, и при необходимости применять нормализацию до `DeadStores`/после `lowerPhi`.

**Метрики s63:** 133/1942, survived 1809, underflow 5, lost block 18, балл 381; тесты зелёные; гейт bad 113, real 74, class-level errors 0.
## Сессия s73 — ветка NewObject (закоммичено ae54e7d)

**Причина, почему тип массива не ставился:** ветка `Ast.NewObject` в `normalizeArrayTemps` физически отсутствовала — предыдущая правка не применилась (замена по многострочному шаблону молча не сработала), оставался только отладочный сэмпл. Счётчики показали `newobj=89 fixed=0` при `cur=java.lang.Object` и корректном `type=java.lang.Object[]` — условие верное, ветки нет.

**Сделано:** ветка вставлена; `arrayTypeOfNew` разбирает `Ljava/lang/Object;[]` (ANEWARRAY) и `[I` (NEWARRAY). Проверено: у `IAzR\\` стало `java.lang.Object[] t7_3 / t8_2 / t8_3`.

**Эффект:** `array.req.but.found` 5 -> **2**; `class-level errors` 0 (в промежуточной отладке было 1 из-за `(Ast.Var)` приведения). Появился новый код `illegal.start.of.expr=5` — требует разбора, bad/real не изменились (113/74).

**Метрики s73:** 133/1942, survived 1809, underflow 5, lost block 18, балл 381; три jar идентичны; матрица s64->s73 — 1942 строки, 0 переходов; тесты зелёные.
## Сессия s75 — валидные типы из дескрипторов (закоммичено 12e1b8f)

**Проблема:** объявления печатались как `[I[] oVar1;` и `[F[] oVar27;` — `illegal.start.of.expr` 5 в `IYWw\\` и `IcT\`. Причина: тип слота приходил в дескрипторной форме (`[I`), а в объявление попадал с добавленным `[]`.

**Сделано:** `arrayTypeOfNew` переписан (`[I` -> `int[]`, `[Ljava/lang/Object;[] -> java.lang.Object[]`, вложенные размерности); `safeType` для дескрипторных имён; `fixDeclTypes` нормализует все `VarDecl` с типом, начинающимся на `[`.

**Эффект:** `illegal.start.of.expr` 5 -> **0**; `array.req.but.found` остаётся **2**; метрики без изменений.

**Метрики s75:** 133/1942, survived 1809, underflow 5, lost block 18, балл 381; три jar идентичны; матрица s73->s75 — 0 переходов; гейт bad 113, real 74, class-level 0; тесты зелёные.
## Сессия s76 — enum без констант (закоммичено e9dafe5)

**Найдено:** `enum.constant.expected` (26) — у enum нет напечатанных констант, и по JLS 8.9 при пустом списке констант нужен `;` перед членами тела. Печаталось `enum X {` сразу с полем.

**Сделано:** в `printClass` для `ACC_ENUM` добавляется строка `    ;`. `enum.constant.expected` 26 -> **0**.

**Побочный эффект (раскрытие, не регрессия):** теперь enum парсится и видны настоящие дефекты: `enum.cant.be.instantiated=145`, `call.to.super.not.allowed.in.enum.ctor=25`, `unexpected.type` 100 -> 253. Причина — константы enum не печатаются, а конструктор печатает `super(p0, p1)`. bad/real не изменились (113/74).

**Остаток:** `array.req.but.found` = 2 (`IwLWbtaV\`; диагностики вытеснены первыми 12 сэмплами, поэтому строки не видны — известный пробел, disclose).

**Метрики s76:** 133/1942, survived 1809, underflow 5, lost block 18, балл 381; три jar идентичны; матрица s75->s76 — 0 переходов; тесты зелёные.
## Сессия s77 — константы enum (закоммичено baed3c5)

**Сделано:** список констант печатается из полей `ACC_ENUM` с типом текущего enum (`NAME, NAME;` перед `;`); в конструкторе enum удаляется явный `super(...)` (вызов неявный).

**Не достигнуто:** `enum.cant.be.instantiated` остаётся **145**, добавились `cant.assign.val.to.var=145` и `cant.apply.symbol=155`. Причина установлена: в `<clinit>` остаются присваивания самим константам (`HD = new I\\(...)`), которые после выноса констант в заголовок становятся присваиванием `final`-полю и повторной инициализацией.

**Следующий шаг:** в `staticInitBlock` (или в StructureToAst для enum) выкидывать присваивания полям-константам enum; тогда 145 должно уйти.

**Метрики s77:** 133/1942, survived 1809, underflow 5, lost block 18, балл 381; три jar идентичны; матрица s76->s77 — 0 переходов; тесты зелёные; bad 113, real 74.