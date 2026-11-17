package decompiler.cli;

import decompiler.ast.JavaClassPrinter;
import decompiler.ast.JavaExporter;
import decompiler.ast.ClassHierarchy;
import decompiler.ast.EmbeddedSecretFinder;
import decompiler.ast.QualityChecker;
import decompiler.ast.StringDecryptor;
import decompiler.cfganalyzer.CfgBuilder;
import decompiler.cfganalyzer.CfgDumper;
import decompiler.dumper.ClassStructureDumper;
import decompiler.passes.CffUnflattenerPass;
import decompiler.parser.JarParser;
import decompiler.ssa.SsaDumper;
import decompiler.structure.StructureDumper;
import decompiler.structure.StructureRecoverer;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Точка входа декомпилятора (Этапы 1-5).
 *
 * Принимает путь к JAR-файлу. По умолчанию печатает базовую структуру классов
 * (Этап 2); флаг {@code --cfg} — CFG (Этап 3); {@code --ssa} — SSA (Этап 4);
 * {@code --deflat} — CFF-де-флаттенинг (Этап 5, запуск 1).
 *
 * Использование:
 *   java -jar java-decompiler.jar <путь-к-jar> [--cfg|--ssa|--deflat]
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Usage: java -jar java-decompiler.jar <path-to-jar> [--cfg|--ssa|--deflat|--structure]");
            System.err.println("Пример: java -jar java-decompiler.jar app.jar");
            System.err.println("        java -jar java-decompiler.jar app.jar --cfg");
            System.err.println("        java -jar java-decompiler.jar app.jar --ssa");
            System.err.println("        java -jar java-decompiler.jar app.jar --deflat");
            System.err.println("        java -jar java-decompiler.jar app.jar --structure");
            System.err.println("        java -jar java-decompiler.jar app.jar --export <выходная-папка>");
            System.exit(2);
        }

        Path jar = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            System.err.println("Файл не найден или это не обычный файл: " + jar);
            System.exit(2);
        }

        boolean cfgMode = false;
        boolean ssaMode = false;
        boolean deflatMode = false;
        boolean structureMode = false;
        boolean astMode = false;
        boolean detectMode = false;
        String exportDir = null;
        Path keyPath = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--cfg")) cfgMode = true;
            if (args[i].equals("--ssa")) ssaMode = true;
            if (args[i].equals("--deflat")) deflatMode = true;
            if (args[i].equals("--structure")) structureMode = true;
            if (args[i].equals("--ast")) astMode = true;
            if (args[i].equals("--detect")) detectMode = true;
            if (args[i].equals("--export") && i + 1 < args.length) {
                exportDir = args[i + 1];
                i++;
            }
            if (args[i].equals("--key") && i + 1 < args.length) {
                keyPath = Path.of(args[i + 1]);
                i++;
            }
        }

        // дешифратор строк: создаём если задан --key, иначе пробуем извлечь
        // вшитый в байткод секрет (non-native схема с master()).
        StringDecryptor decryptor = null;
        try {
            if (keyPath != null && Files.isRegularFile(keyPath)) {
                decryptor = StringDecryptor.fromKeyFile(keyPath.toFile());
                decryptor.load(findDatResource(jar));
                System.err.println("Строки будут расшифрованы (ключ: " + keyPath + ")");
            } else if (keyPath != null) {
                System.err.println("Предупреждение: --key файл не найден, строки будут помечены, не расшифрованы");
            } else {
                byte[] embedded = EmbeddedSecretFinder.findSecret(jar);
                if (embedded != null) {
                    decryptor = new StringDecryptor(embedded);
                    decryptor.load(findDatResource(jar));
                    System.err.println("Найден вшитый в байткод секрет, строки будут расшифрованы");
                }
            }
        } catch (Exception ex) {
            System.err.println("Предупреждение: не удалось инициализировать дешифратор строк: " + ex);
        }

        // иерархия классов из входного jar — для точного вывода ссылочных типов
        ClassHierarchy hierarchy = null;
        try {
            hierarchy = ClassHierarchy.fromJar(jar);
        } catch (Exception ignored) {
            hierarchy = null;
        }

        // переименование обфусцированных классов в осмысленные имена (пункт 1)
        try {
            decompiler.ast.ClassRenamer.build(jar);
        } catch (Exception ex) {
            System.err.println("Предупреждение: переименование классов не удалось: " + ex);
        }

        // Детектор обфускации: профиль и вердикт перед декомпиляцией
        try {
            var prof = decompiler.ast.ObfuscationDetector.analyze(jar);
            System.err.print(prof.summary());
        } catch (Exception ex) {
            System.err.println("Детектор обфускации: " + ex);
        }
        if (detectMode) return;

        try {
            if (exportDir != null) {
                var res = JavaExporter.export(jar, Path.of(exportDir), decryptor, hierarchy);
                System.err.printf("Exported %d class(es) (errors=%d) to %s%n", res.classes, res.errors, res.outDir);
            } else if (astMode) {
                int n = runAst(jar, decryptor, hierarchy);
                System.err.printf("Java AST generated for %d class(es) from %s%n", n, jar);
            } else if (structureMode) {
                int n = runStructure(jar);
                System.err.printf("Structures recovered for %d method(s) from %s%n", n, jar);
            } else if (deflatMode) {
                int n = runDeflat(jar);
                System.err.printf("CFF de-flattened %d method(s) from %s%n", n, jar);
            } else if (ssaMode) {
                int n = runSsa(jar);
                System.err.printf("SSA built for %d method(s) from %s%n", n, jar);
            } else if (cfgMode) {
                int n = runCfg(jar);
                System.err.printf("CFG built for %d method(s) from %s%n", n, jar);
            } else {
                ClassStructureDumper dumper = new ClassStructureDumper();
                int n = JarParser.parse(jar, dumper::dump);
                System.err.printf("Parsed %d class(es) from %s%n", n, jar);
                dumper.printSummary();
            }
        } catch (Exception e) {
            System.err.println("Не удалось разобрать JAR: " + jar);
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /** Печатает CFG всех методов всех классов JAR. */
    private static int runCfg(Path jar) throws Exception {
        final int[] methods = {0};
        JarParser.parse(jar, (p, entry, node) -> {
            ClassNode cn = node;
            if (cn.methods != null) {
                for (MethodNode mn : cn.methods) {
                    if (mn.instructions == null || mn.instructions.size() == 0) continue;
                    System.out.println("== " + entry + " :: " + mn.name + mn.desc);
                    System.out.print(CfgDumper.dump(mn, true));
                    System.out.println();
                    methods[0]++;
                }
            }
        });
        return methods[0];
    }

    /** Печатает SSA-форму всех методов всех классов JAR. */
    private static int runSsa(Path jar) throws Exception {
        final int[] methods = {0};
        JarParser.parse(jar, (p, entry, node) -> {
            ClassNode cn = node;
            if (cn.methods != null) {
                for (MethodNode mn : cn.methods) {
                    if (mn.instructions == null || mn.instructions.size() == 0) continue;
                    System.out.println("== " + entry + " :: " + mn.name + mn.desc);
                    try {
                        System.out.print(SsaDumper.dump(mn));
                    } catch (Exception ex) {
                        System.out.println("  (SSA не построился: " + ex + ")");
                    }
                    System.out.println();
                    methods[0]++;
                }
            }
        });
        return methods[0];
    }

    /** Прогоняет CFFUnflattenerPass по всем методам и печатает результат. */
    private static int runDeflat(Path jar) throws Exception {
        final int[] methods = {0};
        JarParser.parse(jar, (p, entry, node) -> {
            ClassNode cn = node;
            if (cn.methods != null) {
                for (MethodNode mn : cn.methods) {
                    if (mn.instructions == null || mn.instructions.size() == 0) continue;
                    System.out.println("== " + entry + " :: " + mn.name + mn.desc);
                    var blocks = CfgBuilder.build(mn);
                    var res = CffUnflattenerPass.run(blocks);
                    if (!res.unflattened) {
                        System.out.println("  [not CFF] " + res.reason);
                    } else {
                        System.out.println("  [CFF found] stateSlot=" + res.stateSlot
                                + ", dispatch=BB" + res.dispatch.index()
                                + ", cases=" + (res.blocks.size() - 1) + ", edges=" + res.edges.size());
                        dumpEdges(res);
                    }
                    methods[0]++;
                }
            }
        });
        return methods[0];
    }

    /** Восстановление структур: де-флаттенинг (если CFF) + дерево циклов/if-else. */
    private static int runStructure(Path jar) throws Exception {
        final int[] methods = {0};
        JarParser.parse(jar, (p, entry, node) -> {
            ClassNode cn = node;
            if (cn.methods != null) {
                for (MethodNode mn : cn.methods) {
                    if (mn.instructions == null || mn.instructions.size() == 0) continue;
                    System.out.println("== " + entry + " :: " + mn.name + mn.desc);
                    var blocks = CfgBuilder.build(mn);
                    // Если это CFF — сначала делаем граф плоским.
                    var deflat = CffUnflattenerPass.run(blocks);
                    if (deflat.unflattened) {
                        System.out.println("  [CFF de-flattened] dispatch=BB" + deflat.dispatch.index()
                                + ", entry=BB" + deflat.entryIndex);
                        blocks = deflat.blocks;
                    }
                    try {
                        int centry = blocks.isEmpty() ? -1 : blocks.get(0).index();
                        if (deflat.unflattened) centry = deflat.entryIndex;
                        var rec = StructureRecoverer.forGraph(blocks, centry);
                        var root = rec.build(centry);
                        System.out.print(StructureDumper.render(root));
                    } catch (Exception ex) {
                        System.out.println("  (structure: " + ex + ")");
                    }
                    System.out.println();
                    methods[0]++;
                }
            }
        });
        return methods[0];
    }

    /** Генерация Java-кода: печать полного класса (поля + методы через пайплайн). */
    private static int runAst(Path jar, StringDecryptor decryptor, ClassHierarchy hierarchy) throws Exception {
        final int[] classes = {0};
        final int[] classErrors = {0};
        final int[] methodsWithCode = {0};
        // Сброс диагностики прогона (статическая, чтобы не тащить счётчики через API).
        decompiler.ast.StructureToAst.HANDLER_DUPLICATES.set(0);
        decompiler.ast.StructureToAst.EMPTY_ROOTS.set(0);
        decompiler.ast.StructureToAst.PRUNED_STATEMENTS.set(0);
        decompiler.ast.StructureToAst.PRUNED_METHODS.set(0);
        decompiler.ast.StructureToAst.RETHROW_DROPPED.set(0);
        decompiler.ast.StructureToAst.CONST_FOLDED.set(0);
        decompiler.ast.StructureToAst.DEAD_STORES.set(0);
        // Чекер качества агрегирует маркеры по всем классам и печатает отчёт в stderr.
        final QualityChecker.Report q = new QualityChecker.Report();
        final int[] siteBudget = {8}; // печатаем места >= this только для первых N классов
        JarParser.parse(jar, (p, entry, node) -> {
            ClassNode cn = node;
            for (MethodNode m : cn.methods) {
                if (m.instructions != null && m.instructions.size() > 0) methodsWithCode[0]++;
            }
            String internal = entry.endsWith(".class")
                    ? entry.substring(0, entry.length() - 6) : entry;
            String display = decompiler.ast.ClassRenamer.renameInternal(internal);
            System.out.println("// ===== " + display + ".class =====");
            try {
                String java = JavaClassPrinter.printClass(cn, decryptor, hierarchy);
                System.out.print(java);
                QualityChecker.Report rep = QualityChecker.scan(java);
                rep.className = display;
                if (!rep.cmpThisSites.isEmpty() && siteBudget[0]-- > 0) rep.printCmpThisSites();
                q.merge(rep);
            } catch (Exception ex) {
                classErrors[0]++;
                StackTraceElement at = ex.getStackTrace().length > 0 ? ex.getStackTrace()[0] : null;
                System.out.println("  // class: " + ex + (at != null ? "  @ " + at : ""));
            }
            System.out.println();
            classes[0]++;
        });
        q.classErrors = classErrors[0];
        System.err.println(q.summary());
        System.err.println(String.format("methods: total=%d survived=%d undecompilable=%d",
                methodsWithCode[0], methodsWithCode[0] - q.undecompilable, q.undecompilable));
        System.err.println(String.format(
                "diag: empty-roots=%d handler-dups=%d rethrow-dropped=%d const-folded=%d dead-stores=%d pruned-stmts=%d pruned-methods=%d",
                decompiler.ast.StructureToAst.EMPTY_ROOTS.get(),
                decompiler.ast.StructureToAst.HANDLER_DUPLICATES.get(),
                decompiler.ast.StructureToAst.RETHROW_DROPPED.get(),
                decompiler.ast.StructureToAst.CONST_FOLDED.get(),
                decompiler.ast.StructureToAst.DEAD_STORES.get(),
                decompiler.ast.StructureToAst.PRUNED_STATEMENTS.get(),
                decompiler.ast.StructureToAst.PRUNED_METHODS.get()));
        System.err.println(String.format("rethrow-slot: -2=%d gte0=%d -1=%d",
                decompiler.ast.StructureToAst.RETHROW_SLOT_MINUS2.get(),
                decompiler.ast.StructureToAst.RETHROW_SLOT_GTE0.get(),
                decompiler.ast.StructureToAst.RETHROW_SLOT_MINUS1.get()));
        if (!decompiler.ast.StructureToAst.EMPTY_ROOT_METHODS.isEmpty()) {
            System.err.println("empty-roots: " + decompiler.ast.StructureToAst.EMPTY_ROOT_METHODS);
        }
        return classes[0];
    }

    private static void dumpEdges(CffUnflattenerPass.Result res) {
        // печатаем плоский граф: рёбра в порядке (from -> to)
        StringBuilder sb = new StringBuilder("  flat edges:\n");
        for (int[] e : res.edges) {
            sb.append("    BB").append(e[0]).append(" -> BB").append(e[1]).append('\n');
        }
        System.out.print(sb);
    }

    /** Ищет .dat-ресурс со строками в jar и возвращает его байты (или null).
     *  Сначала пробует .dat у которого magic 0x53565342; иначе первый попавшийся.
     *  Метаданные META-INF пропускаем. */
    private static byte[] findDatResource(Path jar) throws Exception {
        try (var zf = new java.util.jar.JarFile(jar.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                var e = en.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".dat")
                        || e.getName().startsWith("META-INF")) continue;
                byte[] data = zf.getInputStream(e).readAllBytes();
                if (data.length >= 4) {
                    int magic = ((data[0] & 0xff) << 24) | ((data[1] & 0xff) << 16)
                            | ((data[2] & 0xff) << 8) | (data[3] & 0xff);
                    if (magic == 0x53565342) return data; // это таблица строк
                }
            }
        }
        return null;
    }
}