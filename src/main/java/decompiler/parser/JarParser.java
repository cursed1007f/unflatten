package decompiler.parser;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Этап 1 пайплайна декомпилятора.
 *
 * Открывает JAR, перебирает записи {@code *.class} и для каждой строит
 * {@link ClassNode} через ASM Tree API, передавая его потребителю.
 * Архитектура рассчитана на последующие этапы: JarParser остаётся единственной
 * точкой входа в байткод, а что делать с {@link ClassNode} решает потребитель
 * (дампер структуры сейчас, CFG/SSA-анализ на следующих этапах).
 */
public final class JarParser {

    private JarParser() {
    }

    /**
     * Потребитель разобранных классов. Реализации могут накапливать состояния
     * между вызовами (например, дампер подсчитывает классы до печати итогов).
     */
    @FunctionalInterface
    public interface ClassConsumer {
        void accept(Path jarPath, String entryName, ClassNode classNode);
    }

    /**
     * Разбирает все {@code *.class}-файлы JAR-архива и передаёт их потребителю.
     *
     * @param jar       путь к JAR-файлу
     * @param consumer  куда отдавать разобранные классы
     * @return количество обработанных class-записей
     * @throws IOException если JAR невозможно открыть или прочитать запись
     */
    public static int parse(Path jar, ClassConsumer consumer) throws IOException {
        if (jar == null) {
            throw new IllegalArgumentException("jar path must not be null");
        }
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("not a regular file: " + jar.toAbsolutePath());
        }

        int classes = 0;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }

                // ASM не читает вложенные/jar-из-модулей, но тут нам достаточно
                // обычных записей. ClassReader сам загрузит constant pool лениво.
                try (InputStream in = jarFile.getInputStream(entry)) {
                    ClassNode classNode = new ClassNode();
                    ClassReader reader = new ClassReader(in);
                    // SKIP_FRAMES ускоряет чтение (фреймы нам на этапах 1-2 не нужны,
                    // на этапах CFG/SSA они пересчитываются анализом при необходимости).
                    reader.accept(classNode, ClassReader.SKIP_FRAMES);
                    consumer.accept(jar, entry.getName(), classNode);
                    classes++;
                }
            }
        }
        return classes;
    }
}