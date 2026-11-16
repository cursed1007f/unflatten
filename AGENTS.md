# Декомпилятор Java-байткода (MultiTool-проект)

Универсальный декомпилятор JAR-файлов с пайплайном (как Fernflower/CFR).
Стек: Java 21+, ASM 9.7.1 Tree API, Gradle 9.5.0 wrapper.
Цель — бороться с обфускацией пользователя (CFF, шифрование строк, анти-тампер).

## Сборка и запуск

- Сборка классов: `.\gradlew.bat classes`
- CLI-проверки: `.\gradlew.bat run --console=plain --args="<jar> --<flag>"`
- Флаги: `--cfg` (CFG), `--structure` (структуры), `--ast` (Java-код), `--deflat` (CFF-граф), `--export <out>`.
- GUI открывается через fatJar (`decompiler.gui.DecompilerGui`).

ВНИМАНИЕ: не гонять `-jar/--cfg/--ast` через bash-инструмент синхронно на больших jar —
зависает stdout. Проверять через `cmd /c "gradlew ... > файл"` или GUI.

## Пайплайн (этапы)

1. JarParser — разбор JAR, извлечение ClassNode.
2. ClassStructureDumper — парсинг класса (ASM Tree).
3. CfgBuilder — CFG на базовых блоках (`decompiler.cfganalyzer`).
4. SSA + доминаторы (`DominatorAnalyzer`, `SsaBuilder`).
5. CffUnflattenerPass — де-флаттенинг CFF (см. ниже).
6. StructureRecoverer / StructureAnalyzer — структуры (циклы, if/else, try/catch).
7. StructureToAst + BytecodeToExpr — AST.
8. JavaPrinter / JavaClassPrinter — вывод Java.

## CFF-формат обфускатора (ликвидирован)

Метод превращается в state-машину: `ILOAD stateVar; TABLESWITCH(0..B-1, default=dispatch)`.
Вход пишет `ICONST_0; ISTORE stateVar`. Каждый case-блок либо заканчивается return'ом
(выходной), либо `setState` (`CONST state; ISTORE stateVar; GOTO dispatch`). Траншишн-блоки
содержат только setState. Строки шифруются AES-256-GCM (`dec.a(String,int)`).

## Полное восстановление CFF — как это работает

Ключевые правки (все проверены на cfftest-cff.jar):

1. `BasicBlock.rebuildEdges(blocks, edges)` — переписывает successors/predecessors по
   плоским рёбрам `CffUnflattenerPass`. Без этого рековер структур видел старый
   зацикленный граф с диспетчером.
2. `CffUnflattenerPass.Result.entryIndex` — корень плоского графа = первый case по
   state=0 (`entryCase`), а не входной BB0 с мёртвой init-state. Ребро `BB0->case`
   НЕ добавляется — BB0 остаётся изолированным.
3. `StructureRecoverer.build()` — исключает изолированные блоки (без predecessor И без
   successor, не entry). Иначе BB0 становится join-точкой pdom и эмитит мёртвое
   `iVarN = state`.
4. `PostDominatorAnalyzer` — для блоков без successor сразу ipdom = супер-exit (n);
   иначе dom={все} делает их универсальными пост-доминаторами.

Результат: state-машина разворачивается обратно в структурные if→return без следов
stateVar. Пример (`branches`): вложенные `if` с `return` вместо `iVar4 = 3/1`.

## План универсальности (следующие шаги)

1. **Опиц/декорреляция switch-case**: распознавать настоящие `switch` (не CFF-диспетчер),
   табличные/поисковые switch за пределами CFF, восстанавливать `default` и проваливание.
2. **Свёртка if/else и циклов**: убрать инвертированные условия (`if(p0<=0)` → `if(p0>0)`
   с зеркальным телом), слить цепочки в `&&`/`||`, улучшить вывод while/for.
3. **Типы и generics**: подстановка типов аргументов для коллекций (List<T>), приведение
   локальных var к параметрам, вывод обобщённых сигнатур.
4. **Строки/ресурсы**: автодешифровка встроенных секретов (уже есть EmbeddedSecretFinder),
   константные интерналы, восстановление enum/serialize.
5. **try/catch/finally**: полное восстановление (сейчас упрощено — try-body = блок, catch =
   handler).
6. **Лямбды/анонимные классы**: распознавание invokedynamic LambdaMetafactory, вывод
   `->` вместо вложенного класса.
7. **Реверс анти-тампера**: идентификация проверок целостности jar/структуры, чтобы
   выдавать читаемый код без защиты.

## Прочее

- `StringDecryptor` (AES-GCM по формату StringCrypto), `--key`; авто-поиск вшитого
  секрета — `EmbeddedSecretFinder` (читает `master()` base64 из класса-дешифратора).
- Секрет non-native-билда вшит в байткод `master()`; отдельный `slashvisuals.key` не
  подходит.
- findDatResource ищет `.dat` по magic 0x53565342 (не только в корне).
- Типы — через `TypeInference` (BasicVerifier, не SimpleVerifier — тот запрещает
  наследование в ASM 9.7.1).