# Следующая сессия: record→commit для fold, javac-гейт + фикстуры, затем entry-stack

Статус: F1–F5 закрыты. Condition-prelude over-bail снят; PUTFIELD/PUTSTATIC больше
не теряют получателя; категории стека учитываются; const-fold не исполняет мёртвую
ветвь; rethrow-liveness консервативен по всему методу.

## F1–F5: что сделано

- F1. Убран false bail `condition prelude` в IF_ELSE: prelude уже эмитится
  BLOCK-узлом до IF_ELSE. Loop-header prelude и switch-key prelude оставлены —
  там BLOCK-узла нет и prelude реально теряется.
- F2. PUTFIELD: получатель — реальный (`oVar3.x = 1`), а не всегда `this.x`.
  В выводе 10 таких присваиваний. PUTSTATIC тоже добавлен (class.field = v).
- F3. DUP2/POP2/DUP_X2/DUP_X1 категорийные: cat-2 (long/double) — один слот,
  cat-1 — два; не доказали категорию — bail (`DUP2 category` и т.п.).
- F4. Const-fold эмитит только живую ветвь; мёртвый else с сайд-эффектами больше
  не исполняется.
- F5. `slotReadAnywhere`: слот rethrow'а проверяется по всему методу, а не линейно
  после ATHROW (block-shuffle safe).

## Baseline `slash-obf-legit.jar` (F1–F5)

`undecompilable = 455` из 1942 методов (23.4%), survived = 1487.

| Причина | Кол-во |
|---|---|
| underflow | 210 (206 generic + 2 DUP_X1 + 1 DUP2 + 1 DUP_X2) |
| lost block | 25 |
| double emit | 2 |
| dead code | 5 |
| missing return | 4 |
| other | 209 |
| — cmp opcode | 162 |
| — multi-block try range | 27 |
| — switch key prelude | 11 |
| — multiple handlers | 9 |
| join depth / irreducible | 0 |

`class-level errors: 0`, `return 0: 8`, `rethrow-dropped=745`, `empty-roots=0`,
`handler-dups=0`. На `bytecode`/`-renamed` — те же 455 и 0.

**Арифметика F1.** Сняли 271 ложный bail → 581 − 271 = 310. Из этих 271 метода
126 выжили, а 145 вскрыли настоящие дыры (underflow +88, cmp +31, multi-block +9,
multiple handlers +4, lost block +7, missing return +3, double emit +2, switch +1):
310 + 145 = 455. То есть 271 не были «тихой потерей» — они были ложным bail'ом, а
их реальные дефекты теперь видны.

**Double emit 2** (`_Pk(...)V`, `WWq(...)V`) — подозрение на F4: ветви
конвертируются обе, счётчики мёртвой ветви не откатываются (record→commit для fold
не сделан). Инвариант ловит, тихой неверности нет; следующий шаг — откат счётчиков
мёртвой ветви с учётом join.

## Очередь

1. **Record→commit для const-fold**: откатывать normalEmits/catchEmits мёртвой
   ветви и не терять join; снять 2 double emit, не превратив их в ложные LOST_BLOCK.
2. **javac-гейт** (ин-процессный JavaCompiler; фильтр `cant.resolve`/`doesnt.exist`;
   категории missing return / unreachable / definite assignment; идемпотентность).
3. **ASM-фикстуры**: opaque-true ромб с сайд-эффектом в else; PUTFIELD на локале;
   cat-2 DUP2; nested try в handler'е; loop-header entry; switch fallthrough.
4. **Дизайн-нота entry-stack** (сигнатура, fixpoint, unify, JOIN_DEPTH, phi→temp,
   Ternary только JLS 15.25) → ревью → код.
5. Entry-stack + join-depth; затем регионы вместо `switchConsumed`; затем rate-гейт.

## Проверка

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12'; .\gradlew.bat --console=plain classes
java -cp "build\classes\java\main;<asm 9.7.1>" decompiler.cli.Main "C:\Users\User\Desktop\slash-obf-legit.jar" --ast 1>s-out.txt 2>s-err.txt
```

Тест-джарки: `slash-obf-legit.jar`, `slash-obf-bytecode.jar`,
`slash-obf-bytecode-renamed.jar`; `slashW.jar` — отдельный baseline после гейта.
