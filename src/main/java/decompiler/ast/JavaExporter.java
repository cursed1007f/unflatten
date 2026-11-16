package decompiler.ast;

import decompiler.parser.JarParser;

import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Декомпилирует все классы jar'а и раскладывает результат в выходной каталог
 * по путям пакетов: {@code out/<package>/<Name>.java}, как делает любой
 * декомпилятор. Каждый класс рендерится через {@link JavaClassPrinter}.
 */
public final class JavaExporter {

    private JavaExporter() {
    }

    /** Результат экспорта. */
    public static final class Result {
        public final int classes;
        public final int errors;
        public final Path outDir;

        Result(int classes, int errors, Path outDir) {
            this.classes = classes;
            this.errors = errors;
            this.outDir = outDir;
        }
    }

    /**
     * Экспортирует все классы jar в {@code outDir}.
     *
     * @return статистику: сколько классов записано и сколько упало.
     */
    public static Result export(Path jar, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger err = new AtomicInteger();
        AtomicInteger exceptionCount = new AtomicInteger();
        JarParser.parse(jar, (p, entry, cn) -> {
            try {
                writeClass(outDir, cn);
                ok.incrementAndGet();
            } catch (Exception ex) {
                exceptionCount.incrementAndGet();
            }
        });
        return new Result(ok.get(), exceptionCount.get(), outDir);
    }

    /** Пишет один класс по пути пакета. */
    private static void writeClass(Path outDir, ClassNode cn) throws IOException {
        String internal = cn.name;          // foo/bar/Baz
        String pkg = "";
        String simple = internal;
        int slash = internal.lastIndexOf('/');
        if (slash >= 0) {
            pkg = internal.substring(0, slash);       // foo/bar
            simple = internal.substring(slash + 1);   // Baz
        }
        // путь: out/<pkg-dirs>/<simple>.java
        Path dir = outDir;
        if (!pkg.isEmpty()) {
            dir = outDir.resolve(pkg);
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(simple + ".java");

        String body = JavaClassPrinter.printClass(cn);
        // вставить package-объявление если есть
        String header = pkg.isEmpty() ? "" : "package " + pkg.replace('/', '.') + ";\n\n";
        Files.writeString(file, header + body);
    }
}