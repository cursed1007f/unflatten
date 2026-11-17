# Следующая сессия: типы локальных (slotTypes → usage-based)

Статус на конец сессии: всё закоммичено, дерево чистое. Последний коммит —
`5a3b064` (Java-каст `((Type)x)`). Перед началом работы: `git log --oneline -1`,
`git status`.

## Что уже сделано в эмиттере (для контекста)

- `GETFIELD` (корень `>= this`), `ATHROW`-примитив, мёртвый код после return/throw,
  boolean `return false/true`, `super()/this()`, склейка `new+init`, dead-store
  (`iVar=const`), `ARETURN`→`null`, `invokedynamic` (StringConcat/Lambda/ObjectMethods),
  `extends Enum/Record`, объявление локальных (`Ast.VarDecl`), Java-каст `Ast.Cast`.
- Чекер: `THIS_INDEX`/`CMP_THIS` без фолс-позитивов; `--ast` даёт сводку в stderr.
- Тест-джарки на рабочем столе: `slash-obf-bytecode.jar`, `slash-obf-bytecode-renamed.jar`,
  `slash-obf-legit.jar` (все ~224 класса). Чистая сборка: `D:\sadnes\build\libs\slashvisuals-1.0-SNAPSHOT.jar`.

## Задача следующей сессии: точность типов объектных локальных

### Как сейчас выводится тип (`decompiler/ast/TypeInference.java`)

1. Параметры и `this` — точно из дескриптора метода.
2. Локали — тип из кадров `BasicVerifier` в точке `store` (точно для объектов),
   при падении анализа — фолбэк `BasicInterpreter`.
3. Примитивы — по STORE-опкоду (`ISTORE→int`, `FSTORE→float`, `DSTORE→double`,
   `LSTORE→long`), но берётся **последний** store (нет слияния по ветвям).
4. `boolean` — эвристика (`refineBooleans`: только литералы 0/1 и только в `IFEQ/IFNE`).
5. Дефолт для всего остального — `"Object"`.

### Замер на `slash-obf-legit.jar` (224 класса)

- объявлений локальных ~1823 (регекс иногда ловит `return iVarN;` — это не объявления);
- из них **656 = `java.lang.Object`** (фолбэк ~34%);
- примитивы точные: `float` 639, `int` 388, `double` 104, `boolean` 23;
- конкретные объектные классы — единицы (`Vec3d`, `CallbackInfo`, несколько `I…$…`).

### Проблема

**527 вызовов `oVarN.method(`, из них 515 — методы вне `Object`.** То есть
`Object`-локали используются с методами своего реального класса → в текущем виде
этот код **не компилируется**. Причина: `BasicVerifier` на де-флаттенинг-мешанине
не строит кадры → фолбэк → `Object` (неверно).

### Предлагаемый фикс — usage-based вывод типа объекта

Идея: тип объектной локали брать из её использования как получателя:

- эмиттер (`BytecodeToExpr`) при `INVOKEVIRTUAL/INVOKEINTERFACE/GETFIELD owner.name`
  уже знает `owner` (класс метода/поля);
- запоминать «слот N использован как получатель члена из класса `owner`»;
- при объявлении локали брать самый специфичный `owner` (или наиболее частый) как тип;
- при конфликте нескольких owners — общий супертип (можно `Object` только тогда).

Куда встраивать:
- `BytecodeToExpr`: при формировании вызова/доступа собирать `slot → owner`
  (слот получателя — из `VarInsnNode`/`ALOAD` перед вызовом; либо из `Ast.Var` имени,
  где имя = `oVarN` → N это слот).
- Прокинуть собранную карту в `StructureToAst` (через `newEmu()`/сеттер), а оттуда —
  в объявления локалей (`convert` уже собирает `locals` из AST; взять тип из карты,
  если `slotTypes[slot]` = `Object`).

### Ограничения/риски

- Слот может переиспользоваться под разные классы в разных ветвях — брать общий супертип
  по иерархии (`ClassHierarchy` уже есть в проекте).
- Замена `Object` на конкретный класс может конфликтовать с реальным использованием,
  если owner — интерфейс/суперкласс. Тогда лучше `owner` (компилируется по крайней мере
  для вызова этого метода), а не `Object`.

### Быстрый рецепт проверки прогресса

```
# сборка
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12'; .\gradlew.bat --console=plain classes
# прогон
java -cp "build\classes\java\main;<asm 9.10.1 jars>" decompiler.cli.Main "C:\Users\User\Desktop\slash-obf-legit.jar" --ast 1>s0-out.txt 2>s0-err.txt
```
Метрики цели: `java.lang.Object`-объявлений ↓, вызовов `oVar.method` вне `Object` ↓,
ошибок классов = 0. Считать:
```
# Object-объявлений
[regex]::Matches($t,'(?m)^\s+java\.lang\.Object\s+[iofd]Var\d+\s*;').Count
# вызовов oVar.method( вне Object
```
(готовые однострочники — в истории чата этой сессии).
