# STATUS — текущее состояние декомпилятора

Сводка на конец сессии 172 (s167 try-регион откачен; s168 — декодер RT.dStr; s170–s172 — NativeKeyExtractor и ключ в DLL). Историю по сессиям см. в `NEXT-SESSION.md`.

## Метрики (замер на текущем HEAD)

| Набор | Методов | survived | undecompilable | Балл (ниже=лучше) | БАЛЛ 0-100 | Гейт bad / real |
|---|---|---|---|---|---|---|
| slash-obf-legit / bytecode / renamed | 1942 | **1816** | 126 | 2 | **91** | 60 / 14 |
| cfr-0.152 | 8039 | 7616 | 423 | 0 | 85 | — |
| slashW | 5833 | **4255** | 1578 | 786 | — | — |
| SpaceVisuals | 5178 | 4509 | 669 | 1245 | — | 233 / 122 |

Три jar'а `slash-obf-*` дают идентичные метрики. `class-level errors` = 0 на slash-наборах и CFR.

## Что сделано и принято

- **Switch prelude** — операторы из вычисления ключа `switch` печатаются перед ним (+7 survived).
- **Loop header prelude** — то же для условия цикла: на `slashW` метки `loop header prelude` 190 → 0 (+~190 survived).
- **Slot splitting** — слоты с записями «массив + примитив» (4 из 2918) печатаются двумя переменными (`x`, `x_a`); `array.req` 15 → 13, `cant.deref` 31 → 30, без регрессий.
- **Канонические дескрипторы ANEWARRAY** и материализация темпов для `new T[n]` в `[]=`; объявления темпов — на уровне метода.
- **Переименование классов** (AGENTS п.1): раньше **не работало** (все имена с `$` пропускались). Теперь: сигналы из методов/полей/строк/**внешних типов**, суффикс по супертипу, агрессивная детекция, санитизация имён. Гейт real 39 → 23.
- **Пакеты** (AGENTS п.2): темы по внешним типам + вывод `package`; 8 тем вместо одной (`mod` 130, `util` 49, `math` 16, `ui` 9, `data` 9, `world` 8). Гейт bad 68 → 59, real 23 → 14.
- **Декодер строк `RT.dStr`** (s168): `StringXorDecoder` (алгоритм снят с байткода, ключ не нужен), свёртка `X.dStr("…")` → литерал в `BytecodeToExpr.doCall`, декодирование LDC-строк в `ClassRenamer.Signals.of`. Маркеров `enc:` 0, пакеты `mod` 130 → 114, `ui` 9 → 18, `world` 8 → 14; гейт bad 59 → 60 (+1 принят по прецеденту s131), real 14, survived 1816 без изменений.
- **NativeKeyExtractor** (s170): секрет `StringCrypto` найден в байткоде (`stringcrypto`-класс, `master()` = base64 32 Б), ключ `SHA-256(secret || "strings" || 0x00 || className)`, SVSB-блоб ищется в jar, кандидаты проверяются GCM-тегом. `slashvisuals-1.0-SNAPSHOT-obf`: маркеров `enc:` 0, `slashvisuals` в выводе 911 раз, survived 8066/8870, class-level 0.
- **Чекер качества** (AGENTS п.4): маркеры `/* ? */`, `.<init>`, `UnsupportedOperationException`, пустые тела; балл 0-100; вывод `worst:` — топ-12 классов.
- **`return;` в `<clinit>`** — снимается, если стоит перед `}` (slashW: class-level 1 → 0).
- **ASM 9.11** — поддержка классов major 72 (SpaceVisuals).

## Метрики, которые были ложными (исправлено)

- `deadIVarConst`: считал ЛЮБОЕ `iVarN = 0;` дефектом. После исправления (полное имя, единственное вхождение) score 381 → 21.
- `returnZero`: `return 0;` в int-методах законен; теперь учитывается только вне int-методов. score 21 → 2.

## Отклонено (проверено, откачено)

| Попытка | Результат |
|---|---|
| Символический проход типов (линейный, блочный, по кадрам, TypeFlow с join) | bad 80+ при включении, целевой `array.req` не чинится |
| `FORCE_ARRAY` для конфликтных слотов | array.req 15→13, но `operator.cant.be.applied` 4→33 |
| Инверсия пустого `then` | ломала печать (121 class-level), откачено |
| `multi-block try range` (конкатенация и linear-guard) | survived 0, lost block растёт (21→23 / 21→58) |
| Расширение словаря тем по строкам | строки в slash-obf зашифрованы, сигнала нет |
| try-регион s167 (`Kind.TRY` + поддерево по блокам диапазона) | survived +12, но lost block +26 и real +1 — хуже «честного отказа»; откачено |

## Known gaps

1. **Регион-структура для try** — `multi-block try range` требует объединения блоков в регион, иначе теряются блоки. Попытка s167 (Kind.TRY + поддерево по блокам диапазона) дала +12 survived, но +26 lost block и +1 real — откачено. Причины: `buildFrom` при >2 преемниках идёт только в первого, свёртка константных предикатов, латчи циклов за диапазоном; `coveredBlocks()` не гарантирует эмиссию.
2. **Нативный ключ — работает (s172).** Секрет берётся из таблицы деобфускации в `slashnative.dll` по адресу RVA `0x366080` (ссылка `lea r10` в `sv_native_init`), деобфусцируется циклом из той же функции; запасная константа `0x3660b0` — приманка `deadbeef…`. `NativeKeyExtractor` проверяет также base64-константы байткода и файлы `*.key`, победитель определяется GCM-тегом. Формула: `SHA-256(secret || "strings" || 0x00 || name)`. Старые адреса (0x363de4/0x363d58) оставлены как legacy-кандидаты.
3. **Типы для `array.req`/`cant.deref`** — массив попадает в слот через `aastore; astore_N` (значение в стеке), шаблон `ALOAD…AALOAD…ASTORE` не срабатывает.
4. **`emptyBodies` 55** — пустые `else` устранены (133 → 55), остаток: `static { }` и ветви, печатающиеся в ноль.
5. `SpaceVisuals` — `is.preview=100` (preview-фичи Java 28) и тяжёлые дженерики.

## Как проверять

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12'
cmd /c ".\gradlew.bat --console=plain classes > build-log.txt 2>&1"
cmd /c ".\gradlew.bat --console=plain test --rerun-tasks > test-log.txt 2>&1"
$cp=(Get-Content asmcp.txt -Raw).Trim()
cmd /c "`"C:\Program Files\Java\jdk-21.0.12\bin\java.exe`" -cp `"$cp`" decompiler.cli.Main `"<jar>`" --ast 1>out.txt 2>err.txt"
cmd /c "`"C:\Program Files\Java\jdk-21.0.12\bin\java.exe`" -cp `"$cp`" decompiler.cli.Main `"<jar>`" --gate 1>g.txt 2>gerr.txt"
```

Обязательные поля отчёта: имя лога, балл, clinit (secondary), три jar'а, матрица миграции при изменениях.
