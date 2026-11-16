# Control Flow Flattening (CFF) — какие виды пережёвывает декомпилятор

Этот файл — единственный источник правды о том, какие разновидности Control Flow
Flattening (и близких искажений потока управления) умеет разворачивать декомпилятор.
Всё про CFF — здесь; в `AGENTS.md` только ссылка.

Общий принцип: у CFF-методов поток управления заменяется либо диспетчером
(switch/state-машина), либо тавтологиями, либо перемешанными блоками. Декомпилятор
строит CFG из реальных переходов (порядок блоков в байткоде не важен), а потом
разворачивает искажение обратно в структуру через доминаторы и пост-доминаторы.

---

## 1. Классическая state-машина (TABLESWITCH, default → self)

Базовый вид, ради которого всё начиналось (обфускатор `ControlFlow.java`).

**Байткод** (метод превращается в цикл-диспетчер):
```
вход:   ICONST_0; ISTORE stateVar            ; state = 0
диспетчер: ILOAD stateVar
          TABLESWITCH(0..B-1, default=dispatch)   ; <-- default на САМ себя
case k:  ...тело;...
         CONST state_j; ISTORE stateVar; GOTO dispatch   ; setState
```

**Как распознаём:** блок с `TABLESWITCH`, у которого `default` ведёт **прямо на себя**
(`CffUnflattenerPass.run` + `successorByLabel().get(ts.dflt) == bb`).

**Как разворачиваем:**
- stdout-рёбра де-флаттенинга (state-переходами: `case -> следующий case`),
- `BasicBlock.rebuildEdges` переписывает граф,
- корень = первый case по `state=0` (`entryIndex`), изолированные блоки вырезаются,
- структура восстанавливается как вложенные `if → return` / циклы.

**Ключевые правки:** `CffUnflattenerPass`, `rebuildEdges`, `entryIndex`,
`StructureRecoverer` (исключение изолированных блоков), `PostDominatorAnalyzer`
(блоки без successor → супер-exit).

Вердикт детектора: `CFF_TABLESWITCH`.

---

## 2. Switch-gate

Разновидность, где `default` диспетчера ведёт **не напрямую** на себя, а через
гейт-блок, который безусловным `GOTO` возвращается в диспетчер. По сути
«одноразовый» диспетчер: `state=0` → `case 0` = реальный код, дальше поток идёт
по обычным рёбрам и больше не возвращается в диспетчер.

**Байткод**:
```
BB0: ICONST_0; ISTORE state            ; вход
BB1: ILOAD state; TABLESWITCH ...      ; диспетчер, preds = BB0, BB2
BB2: ICONST_0; POP; GOTO ->BB1         ; ГЕЙТ: возвращается в диспетчер
BB3: ...реальный код...
```

**Как распознаём:** `TABLESWITCH`, чей `default` **резолвится через цепочку
безусловных `GOTO`-блоков** к самому диспетчеру (`resolvesToViaGotos`). Детект —
`gateMode = true`.

**Как разворачиваем:** «лёгкий» де-флаттенинг — срезаем диспетчер и гейт-блоки,
**сохраняя исходные рёбра** остальных блоков (здесь нет state-машины с возвратом).
Если находим `setState`-переходы — не наш случай, отступаем (чтобы не сломать граф).

Вердикт детектора: `CFF_SWITCH_GATE`.

---

## 3. Непрозрачные предикаты (opaque predicates)

Не switch-диспетчер, а **тавтологии** — условия, которые всегда истинны/ложны, часто с
мёртвой ветвью. Обёртывают тело или вставляют мёртвые куски.

**Пример** (встречался в `slashvisuals-test-obf`):
```
if (1 * 1 + 1 % 2 != 0) {          ; 1*1+1%2 = 2 != 0 → всегда true
    <реальный код>                 ; тело остаётся
}
```

**Как распознаём:** условия блоков прогоняются через эмулятор (`BytecodeToExpr.runExpr`),
затем `Ast.constValue` сворачивает константное выражение (арифметика, сравнения, сдвиги,
int/long/double, логика). Если результат — константа (Boolean) → это непрозрачный предикат.

**Как разворачиваем:** в `StructureToAst` при константном условии подставляем взятую
ветвь; `else` (ложный путь = продолжение/эпилог) дописываем только если взятая ветвь
не терминирует (`return`/`throw`). Мёртвый код после `return`/`throw` не эмитится
(флаг `terminated` в `BytecodeToExpr`).

Также выкидывается анти-тампер `try { } catch (Throwable e) { return <dead>; throw(0); }`
при пустом `try` (catch недостижим).

Вердикт детектора: `OPAQUE_PREDICATES`.

---

## 4. Block-shuffle (перемешивание блоков)

Базовые блоки метода переупорядочиваются физически, связь восстанавливается
безусловными `GOTO`. Чистый шаффл сам по себе **не** ломает декомпиляцию: CFG строится
из реальных целевых переходов, а структуры восстанавливаются по доминаторам —
порядок блоков в байткоде не важен.

**Как распознаём (детектор):** высокая плотность `GOTO`-блоков — `gotoBlocks / totalBlocks`≥ 40% (как химическая калибровка: обычная/чистая обфа ~10%, CFF ~23–28%, при шаффле заметно выше). В структуре порога — 40% и ≥ 1000 блоков.

Вердикт детектора: `BLOCK_SHUFFLE`, либо суффикс `+ block-shuffle` к CFF-вердикту
(если шаффл поверх диспетчера).

---

## 5. Смешанный / кастомный CFF

Если обфускатор намешал несколько техник (tableswitch + switch-gate, или предикаты +
шаффл), детектор вернёт общий вердикт:
- `MIXED_CFF` — и tableswitch, и switch-gate;
- CFF-вердикты с суффиксом `+ block-shuffle`;
- `OPAQUE_PREDICATES` — если флаттенинг нестандартный/без распознанного диспетчера, но
  много константных предикатов.

Обработка таких билдов — комбинация описанных выше pass-ов, применяемых по мере
распознавания.

---

## Как это устроено в коде

- `decompiler/passes/CffUnflattenerPass` — распознавание диспетчера (tableswitch/gate),
  де-флаттенинг (state-машина или лёгкий gate),
  `Result.kind` = `"tableswitch"` | `"gate"`.
- `decompiler/cfganalyzer/BasicBlock.rebuildEdges` — переписывание графа по плоским рёбрам.
- `decompiler/ssa/DominatorAnalyzer.analyze(blocks, entry)` — доминаторы от реального входа.
- `decompiler/structure/StructureRecoverer` — восстановление структур; исключение
  изолированных блоков; `forGraph(blocks, entry)`.
- `decompiler/structure/PostDominatorAnalyzer` — блоки без successor → супер-exit.
- `decompiler/ast/Ast.constValue` — свёртка констант (для непрозрачных предикатов).
- `decompiler/ast/StructureToAst` — подстановка константной ветви, удаление мёртвых
  `if`/анти-тампера.
- `decompiler/ast/BytecodeToExpr` — эмуляция стека, `terminated`-флаг,
  boolean-эвристика, фиксы массива/DUP/MULTIANEWARRAY.
- `decompiler/ast/ObfuscationDetector` — профиль jar и вердикт (NONE / REGULAR /
  CFF_TABLESWITCH / CFF_SWITCH_GATE / OPAQUE_PREDICATES / BLOCK_SHUFFLE / MIXED_CFF).

## Неконтрол-флоу обфускации (не CFF)

- `REGULAR` — обычная (имена/строки/анти-тампер, диспетчера нет).
- `NONE` — обфускации не обнаружено.
- Шифрование строк (`dec.a(String,int)`, AES-GCM) — разворачивается через
  `StringDecryptor` / `EmbeddedSecretFinder`, это не CFF.

## Как проверить

- `java -jar decompiler.jar <jar> --detect` — вердикт + счётчики.
- `java -jar decompiler.jar <jar> --ast` — итоговый Java-код.
- Регресс-тест: `$env:TEMP\gigatool\cfftest\cfftest-cff.jar` (tableswitch),
  `slashvisuals-test-obf.jar` (switch-gate / opaque / block-shuffle), `slashVV.jar`
  (обычная), `cfr-0.152.jar` (без обфы).