# Entry-stack: дизайн-нота (на ревью)

Статус: черновик. Код не начат. Цель — убрать `underflow` (203 на legit) как
причину отказа: блок должен знать, что на его входе лежит на стеке.

## Модель

Абстрактное значение стека (не AST-узел):

    record StackValue(Kind kind, String type, int slot, int size)

- `Kind`: `LITERAL`, `VAR`, `NEW`, `EXPR` (результат вычисления), `EXCEPTION`.
- `type` — friendly-имя (`int`, `long`, `java.lang.String`, `byte[]`).
- `slot` — для `VAR` (иначе -1).
- `size` — 1/2 (категория JVM; уже есть структурно после M2).

Состояние блока: `List<StackValue>` снизу вверх + `EntryKind`
(`NORMAL`, `HANDLER`) — у handler вход ровно `[EXCEPTION]`.

## Fixpoint

Прямой обход CFG (включая exception-рёбра из reachableWithHandlers):

- entry: пустой стек (NORMAL) или `[EXCEPTION]` (HANDLER);
- выход блока = вход минус потреблённое плюс произведённое;
- join: поэлементное слияние списков; длины обязаны совпадать.

Слияние значений:
- равны (kind/type/slot/size) → значение;
- различаются → новый временный слот `tN` типа-объединения (для примитивов —
  более широкий; для ссылок — общий предок или `Object`), и в каждом
  предшественнике дописывается `tN = <значение>` (phi-материализация);
- больше `JOIN_DEPTH = 4` подряд разных слияний на одном ребре → bail
  `join depth mismatch` (уже есть Kind).

## Эмиттер

`runBlock(insns, stopBeforeReturn, entryStack)` получает `List<StackValue>`
вместо `List<Ast.Expr>`; `pop()` возвращает значение, при `VAR` — `Ast.Var`,
при `EXPR` — ранее созданный узел (identity-таблица блок→узел).

Инвариант: высота и размеры стека после блока совпадают с fixpoint-состоянием
наследника; иначе `UndecompilableException(UNDERFLOW/JOIN_DEPTH)`.

## Тернарник

Phi-временная, записанная ровно в обеих ветвях одного `IF_ELSE` и прочитанная
один раз сразу после join, сворачивается в `cond ? a : b` только после проверки
JLS 15.25 (числовое продвижение, boolean, ссылки; primitive+null — запрет).

## Фикстуры (ASM)

1. Ромб с assign+IF во вложенной ветви (S2).
2. PUTFIELD на локале-получателе.
3. cat-2 DUP2 перед вызовом.
4. Nested try внутри handler.
5. Loop-header как entry (back-edge с непустым стеком).
6. Switch с fallthrough и общим телом (уже закрыто дедупом).
7. Try/catch с исключением как единственным входом блока.

## Критерии приёмки

- `underflow` не растёт ни на одном из трёх jar; на legit — падает.
- Новые bails только `join depth mismatch`, каждый с фикстурой.
- `javac-гейт`: real не растёт, `ret.outside.meth`/`int.number.too.large` = 0.
- Идемпотентность: декомпил→рекомпил→декомпил даёт тот же AST.