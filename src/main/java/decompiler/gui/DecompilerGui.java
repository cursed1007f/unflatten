package decompiler.gui;

import decompiler.ast.ClassHierarchy;
import decompiler.ast.ClassRenamer;
import decompiler.ast.EmbeddedSecretFinder;
import decompiler.ast.JavaExporter;
import decompiler.ast.NativeKeyExtractor;
import decompiler.ast.StringDecryptor;
import decompiler.cfganalyzer.CfgBuilder;
import decompiler.cfganalyzer.CfgDumper;
import decompiler.dumper.ClassStructureDumper;
import decompiler.parser.JarParser;
import decompiler.ssa.SsaDumper;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GUI декомпилятора. Тёмная тема, карточки-секции, drag&drop для jar'а,
 * прогресс-бар и неблокирующий запуск (SwingWorker) — в том же стиле,
 * что и {@code RenamerGui} обфускатора.
 *
 * Пока выполняет Этап 1-2 (структура классов); кнопки и статусы заложены так,
 * чтобы на следующих этапах (CFG/SSA/AST) добавить сюда выбор глубины вывода
 * и дамп в файл без переделки каркаса.
 */
public class DecompilerGui extends JFrame {

    // Палитра как у лоадера Unflatten Decompiler
    private static final Color BG = new Color(0x0a122e);
    private static final Color CARD = new Color(0x0f1b3f);
    private static final Color CARD_HI = new Color(0x16264f);
    private static final Color LINE = new Color(0x2a3a66);
    private static final Color ACCENT = new Color(0x2f6fff);
    private static final Color ACCENT_HI = new Color(0x4f86ff);
    private static final Color TEXT = new Color(0xe8efff);
    private static final Color MUTED = new Color(0x8a9bc8);
    private static final Color GOOD = new Color(0x3ce89a);
    private static final Color BAD = new Color(0xff6b6b);

    private final JLabel pathLabel = new JLabel("Перетащи jar сюда или выбери файл");
    private final JButton chooseBtn = new GhostButton("Выбрать jar…");

    private final JTextArea log = new JTextArea();
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel("Перетащи jar — дальше всё само");
    private final AccentButton runBtn = new AccentButton("Разобрать заново");
    private final GhostButton openBtn = new GhostButton("Открыть папку");
    private final GhostButton saveBtn = new GhostButton("Сохранить отчёт");
    private final GhostButton clearBtn = new GhostButton("Очистить");
    private Path lastOutDir;

    private File inputFile;
    private Path outputDir = Path.of(".").toAbsolutePath().normalize();

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> {
            DecompilerGui gui = new DecompilerGui();
            if (args.length > 0) {
                File f = new File(args[0]);
                if (f.isFile()) {
                    gui.inputFile = f;
                }
            }
            gui.updatePath();
            gui.setVisible(true);
            if (gui.inputFile != null) {
                gui.analyze();
            }
        });
    }

    public DecompilerGui() {
        super("Unflatten Decompiler");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 700));
        setSize(1150, 860);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(18, 20, 18, 20));
        root.add(header(), BorderLayout.NORTH);
        root.add(body(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);

        wire();
        updatePath();
    }

    // ------------------------------------------------------------------ UI

    private JComponent header() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setBackground(BG);
        JLabel logo = new JLabel();
        ImageIcon icon = loadLogo(38);
        if (icon != null) {
            logo.setIcon(icon);
        } else {
            logo.setText("UD");
            logo.setForeground(Color.WHITE);
            logo.setFont(logo.getFont().deriveFont(Font.BOLD, 16f));
            logo.setHorizontalAlignment(SwingConstants.CENTER);
            logo.setOpaque(true);
            logo.setBackground(ACCENT);
            logo.setPreferredSize(new Dimension(38, 38));
        }
        JLabel title = new JLabel("Unflatten Decompiler");
        title.setForeground(TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        JLabel sub = new JLabel("декомпилятор jar-файлов");
        sub.setForeground(MUTED);
        sub.setFont(sub.getFont().deriveFont(13f));
        left.add(logo);
        left.add(title);
        left.add(sub);
        p.add(left, BorderLayout.WEST);
        JLabel ver = new JLabel("v0.1 · ASM 9 · Java 21");
        ver.setForeground(MUTED);
        ver.setFont(ver.getFont().deriveFont(12f));
        p.add(ver, BorderLayout.EAST);
        return p;
    }

    /** Логотип из ресурсов jar (тот же, что в лоадере). */
    private static ImageIcon loadLogo(int size) {
        try (java.io.InputStream in = DecompilerGui.class.getResourceAsStream("/logo.png")) {
            if (in == null) return null;
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(in);
            if (src == null) return null;
            java.awt.Image scaled = src.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Exception e) {
            return null;
        }
    }

    private JComponent body() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(BG);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 12, 0);

        p.add(optionsCard(), g);
        g.gridy++;
        g.weighty = 1;
        g.fill = GridBagConstraints.BOTH;
        p.add(logCard(), g);
        return p;
    }

    private JComponent optionsCard() {
        RoundedPanel card = new RoundedPanel(CARD, LINE, 14);
        card.setLayout(new BorderLayout());
        card.setBorder(new EmptyBorder(14, 16, 14, 16));

        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setBackground(CARD);
        top.add(section("ВХОДНОЙ JAR"), BorderLayout.WEST);
        top.add(chooseBtn, BorderLayout.EAST);
        card.add(top, BorderLayout.NORTH);

        pathLabel.setForeground(MUTED);
        pathLabel.setFont(pathLabel.getFont().deriveFont(13f));
        pathLabel.setBorder(new EmptyBorder(6, 0, 0, 0));
        card.add(pathLabel, BorderLayout.CENTER);

        return card;
    }

    private JComponent logCard() {
        RoundedPanel card = new RoundedPanel(CARD, LINE, 14);
        card.setLayout(new BorderLayout(8, 8));
        card.setBorder(new EmptyBorder(14, 16, 14, 16));

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(CARD);
        top.add(section("ЖУРНАЛ"), BorderLayout.WEST);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setBackground(CARD);
        btns.add(openBtn);
        btns.add(saveBtn);
        btns.add(clearBtn);
        top.add(btns, BorderLayout.EAST);
        card.add(top, BorderLayout.NORTH);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setBackground(new Color(0x0c0f13));
        log.setForeground(new Color(0xc9d2e0));
        log.setCaretColor(ACCENT);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setBorder(new EmptyBorder(8, 10, 8, 10));
        log.setText("Здесь появится дамп структуры.\n"
                + "Перетащи jar или выбери его — и жми «Разобрать».\n");
        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createLineBorder(LINE));
        scroll.getViewport().setBackground(new Color(0x0c0f13));
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private JComponent footer() {
        JPanel p = new JPanel(new BorderLayout(10, 6));
        p.setBackground(BG);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setBackground(BG);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(140, 8));
        progress.setBackground(CARD_HI);
        progress.setForeground(ACCENT);
        status.setForeground(MUTED);
        status.setFont(status.getFont().deriveFont(12f));
        left.add(progress);
        left.add(status);
        p.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setBackground(BG);
        right.add(runBtn);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JLabel section(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(MUTED);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        return l;
    }

    // ------------------------------------------------------------- логика

    private void wire() {
        chooseBtn.addActionListener(e -> chooseFile());
        runBtn.addActionListener(e -> analyze());
        clearBtn.addActionListener(e -> {
            log.setText("");
            status.setForeground(MUTED);
            status.setText("Готов");
        });
        saveBtn.addActionListener(e -> saveReport());
        openBtn.addActionListener(e -> openOutDir());
        openBtn.setEnabled(false);
        setDropTarget(new DropTarget() {
            @Override
            public void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = e.getTransferable();
                    if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return;
                    Object data = t.getTransferData(DataFlavor.javaFileListFlavor);
                    if (data instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof File f
                            && f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                        inputFile = f;
                        updatePath();
                        analyze();
                    }
                } catch (Exception ignored) {
                } finally {
                    e.dropComplete(true);
                }
            }
        });
    }

    private void chooseFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("JAR files", "jar"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            inputFile = fc.getSelectedFile();
            updatePath();
            analyze();
        }
    }

    private void updatePath() {
        if (inputFile == null) {
            pathLabel.setText("Перетащи jar сюда или выбери файл");
            pathLabel.setForeground(MUTED);
        } else {
            pathLabel.setText(inputFile.getAbsolutePath());
            pathLabel.setForeground(TEXT);
        }
    }

    /** Настройки вывода структуры (галочки убраны — включено всё нужное). */
    private ClassStructureDumper.Options currentOptions() {
        ClassStructureDumper.Options o = new ClassStructureDumper.Options();
        o.fields = true;
        o.methods = true;
        o.methodCounts = true;
        o.accessFlags = false;
        o.showInterfaces = true;
        return o;
    }

    /** Полный конвейер: профиль → дешифратор → переименование → Java → ключи. */
    private void analyze() {
        if (inputFile == null) {
            JOptionPane.showMessageDialog(this, "Сначала перетащи jar", "Нет файла", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final File src = inputFile;
        final Path jarPath = src.toPath();
        final long started = System.currentTimeMillis();

        progress.setVisible(true);
        status.setForeground(MUTED);
        status.setText("Разбираю…");
        runBtn.setEnabled(false);
        openBtn.setEnabled(false);
        log.append("\n===== " + src.getName() + " =====\n");

        final StringBuilder report = new StringBuilder();

        new SwingWorker<Void, String>() {
            private String outDirText = "";
            private int classes = 0;
            private int errors = 0;

            @Override
            protected Void doInBackground() {
                try {
                    try {
                        var prof = decompiler.ast.ObfuscationDetector.analyze(jarPath);
                        publish(prof.summary().trim());
                    } catch (Exception ex) {
                        publish("детектор обфускации: " + ex);
                    }

                    StringDecryptor decryptor = buildDecryptor(jarPath);
                    publish(decryptor != null
                            ? "строки: ключ найден, расшифровываю"
                            : "строки: ключ не найден, оставляю как есть");

                    try {
                        ClassRenamer.build(jarPath, decryptor);
                    } catch (Exception ex) {
                        publish("переименование классов: " + ex);
                    }

                    ClassHierarchy hierarchy = null;
                    try {
                        hierarchy = ClassHierarchy.fromJar(jarPath);
                    } catch (Exception ignored) {
                    }

                    Path outDir = src.toPath().toAbsolutePath().getParent()
                            .resolve(stripJar(src.getName()) + "-decompiled");
                    var res = JavaExporter.export(jarPath, outDir, decryptor, hierarchy);
                    classes = res.classes;
                    errors = res.errors;
                    outDirText = res.outDir.toString();
                    publish("исходники: " + classes + " классов → " + outDirText
                            + (errors > 0 ? " (ошибок: " + errors + ")" : ""));

                    publish(scanKeys(jarPath, decryptor));
                } catch (Exception ex) {
                    publish("!!! " + ex);
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String s : chunks) {
                    log.append(s + "\n");
                    report.append(s).append("\n");
                }
                log.setCaretPosition(log.getDocument().getLength());
            }

            @Override
            protected void done() {
                progress.setVisible(false);
                runBtn.setEnabled(true);
                double secs = (System.currentTimeMillis() - started) / 1000.0;
                if (!outDirText.isEmpty()) {
                    lastOutDir = Path.of(outDirText);
                    openBtn.setEnabled(true);
                    status.setForeground(GOOD);
                    status.setText(String.format("Готово: %d классов за %.1f с", classes, secs));
                } else {
                    status.setForeground(BAD);
                    status.setText("Не удалось разобрать");
                }
                report.append(String.format("время: %.1f с%n", secs));
                lastResult = report.toString();
            }
        }.execute();
    }

    private static String stripJar(String name) {
        return name.replaceAll("(?i)\\.jar$", "");
    }

    /** Ищет ключ: нативный мастер-секрет → вшитый секрет в байткоде. */
    private static StringDecryptor buildDecryptor(Path jar) {
        try {
            NativeKeyExtractor.Result found = NativeKeyExtractor.discover(jar);
            if (found != null) return found.decryptor();
        } catch (Exception ignored) {
        }
        try {
            byte[] embedded = EmbeddedSecretFinder.findSecret(jar);
            if (embedded != null) {
                StringDecryptor d = new StringDecryptor(embedded);
                d.load(findDatResource(jar));
                return d;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Таблица строк *.dat (magic 0x53565342) из jar. */
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
                    if (magic == 0x53565342) return data;
                }
            }
        }
        return null;
    }

    /** Ищет в постоянных строках секреты и ключи, похожие на лицензионные. */
    private static String scanKeys(Path jar, StringDecryptor decryptor) {
        final Pattern licenseKey = Pattern.compile("^[A-Z0-9]{4}(-[A-Z0-9]{4}){3,}$");
        final Pattern hexKey = Pattern.compile("^[0-9a-fA-F]{32,}$");
        final Pattern b64Key = Pattern.compile("^[A-Za-z0-9+/]{24,}={0,2}$");
        final Set<String> hits = new LinkedHashSet<>();
        try {
            JarParser.parse(jar, (p, entry, cn) -> {
                if (cn.fields != null) {
                    for (FieldNode f : cn.fields) {
                        if (f.value instanceof String s) addKeyLike(s, hits, licenseKey, hexKey, b64Key, entry);
                    }
                }
                if (cn.methods != null) {
                    for (MethodNode m : cn.methods) {
                        if (m.instructions == null) continue;
                        for (AbstractInsnNode ins : m.instructions) {
                            if (ins instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                                addKeyLike(s, hits, licenseKey, hexKey, b64Key, entry);
                            }
                        }
                    }
                }
            });
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ключи и секреты в строках: ").append(hits.size()).append("\n");
        int shown = 0;
        for (String h : hits) {
            if (shown++ >= 40) { sb.append("… (ещё ").append(hits.size() - 40).append(")\n"); break; }
            sb.append("  ").append(h);
        }
        return sb.toString().trim();
    }

    private static void addKeyLike(String s, Set<String> hits, Pattern licenseKey,
                                   Pattern hexKey, Pattern b64Key, String entry) {
        if (s == null) return;
        String t = s.trim();
        if (t.length() < 12 || t.length() > 200) return;
        if (licenseKey.matcher(t).matches() || hexKey.matcher(t).matches() || b64Key.matcher(t).matches()) {
            hits.add(t + "   [" + entry + "]");
        }
    }

    private String lastResult = "";

    /** Показывает CFG всех методов всех классов в журнале. */
    private void showCfg() {
        if (inputFile == null) {
            JOptionPane.showMessageDialog(this, "Сначала выбери jar", "Нет файла", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final File src = inputFile;
        final long started = System.currentTimeMillis();
        log.append("\n--- CFG " + src.getName() + " ---\n");
        new SwingWorker<Void, String>() {
            private final StringBuilder out = new StringBuilder();
            private int methods = 0;

            @Override
            protected Void doInBackground() throws Exception {
                JarParser.parse(src.toPath(), (p, entry, node) -> {
                    ClassNode cn = node;
                    if (cn.methods != null) {
                        for (MethodNode mn : cn.methods) {
                            if (mn.instructions == null || mn.instructions.size() == 0) continue;
                            out.append("== ").append(entry).append(" :: ").append(mn.name).append(mn.desc).append("\n");
                            out.append(CfgDumper.dump(mn, true));
                            out.append("\n");
                            methods++;
                        }
                    }
                });
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String s : chunks) log.append(s + "\n");
                log.setCaretPosition(log.getDocument().getLength());
            }

            @Override
            protected void done() {
                progress.setVisible(false);
                runBtn.setEnabled(true);
                double secs = (System.currentTimeMillis() - started) / 1000.0;
                try {
                    get();
                    int preview = Math.min(8000, out.length());
                    log.append(out.substring(0, preview));
                    if (out.length() > preview) {
                        log.append("... (ещё " + (out.length() - preview) + " символов)\n");
                    }
                    status.setForeground(GOOD);
                    status.setText(String.format("CFG: %d методов за %.1f с", methods, secs));
                    lastResult = out.toString();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    status.setForeground(BAD);
                    status.setText("Ошибка");
                    log.append("\n!!! " + cause + "\n");
                }
            }
        }.execute();
    }

    /** Показывает SSA-форму всех методов всех классов в журнале. */
    private void showSsa() {
        if (inputFile == null) {
            JOptionPane.showMessageDialog(this, "Сначала выбери jar", "Нет файла", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final File src = inputFile;
        final long started = System.currentTimeMillis();
        log.append("\n--- SSA " + src.getName() + " ---\n");
        new SwingWorker<Void, String>() {
            private final StringBuilder out = new StringBuilder();
            private int methods = 0;

            @Override
            protected Void doInBackground() throws Exception {
                JarParser.parse(src.toPath(), (p, entry, node) -> {
                    ClassNode cn = node;
                    if (cn.methods != null) {
                        for (MethodNode mn : cn.methods) {
                            if (mn.instructions == null || mn.instructions.size() == 0) continue;
                            out.append("== ").append(entry).append(" :: ").append(mn.name).append(mn.desc).append("\n");
                            try {
                                out.append(SsaDumper.dump(mn));
                            } catch (Exception ex) {
                                out.append("  (SSA не построился: ").append(ex).append(")\n");
                            }
                            out.append("\n");
                            methods++;
                        }
                    }
                });
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String s : chunks) log.append(s + "\n");
                log.setCaretPosition(log.getDocument().getLength());
            }

            @Override
            protected void done() {
                progress.setVisible(false);
                runBtn.setEnabled(true);
                double secs = (System.currentTimeMillis() - started) / 1000.0;
                try {
                    get();
                    int preview = Math.min(8000, out.length());
                    log.append(out.substring(0, preview));
                    if (out.length() > preview) {
                        log.append("... (ещё " + (out.length() - preview) + " символов)\n");
                    }
                    status.setForeground(GOOD);
                    status.setText(String.format("SSA: %d методов за %.1f с", methods, secs));
                    lastResult = out.toString();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    status.setForeground(BAD);
                    status.setText("Ошибка");
                    log.append("\n!!! " + cause + "\n");
                }
            }
        }.execute();
    }

    private void saveReport() {
        if (lastResult.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Сначала разбери jar", "Нет результата",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser(outputDir.toFile());
        fc.setDialogTitle("Сохранить отчёт");
        String base = inputFile != null ? inputFile.getName().replaceAll("(?i)\\.jar$", "") : "decompile";
        fc.setSelectedFile(new File(base + "-report.txt"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
                f = new File(f.getParentFile(), f.getName() + ".txt");
            }
            try {
                java.nio.file.Files.writeString(f.toPath(), lastResult);
                outputDir = f.getParentFile().toPath();
                status.setForeground(GOOD);
                status.setText("Сохранено: " + f.getName());
            } catch (Exception ex) {
                status.setForeground(BAD);
                status.setText("Не удалось сохранить");
            }
        }
    }

    /** Открывает папку с декомпилированными исходниками. */
    private void openOutDir() {
        if (lastOutDir == null) return;
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(lastOutDir.toFile());
            } else {
                status.setText("Папка: " + lastOutDir);
            }
        } catch (Exception ex) {
            status.setForeground(BAD);
            status.setText("Не удалось открыть папку");
        }
    }

    // --------------------------------------------------------- компоненты

    private static class RoundedPanel extends JPanel {
        private final Color fill;
        private final Color line;
        private final int radius;

        RoundedPanel(Color fill, Color line, int radius) {
            this.fill = fill;
            this.line = line;
            this.radius = radius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.setColor(line);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class AccentButton extends JButton {
        AccentButton(String text) {
            super(text);
            setForeground(Color.WHITE);
            setFont(getFont().deriveFont(Font.BOLD, 13f));
            setFocusPainted(false);
            setBorder(new EmptyBorder(9, 18, 9, 18));
            setContentAreaFilled(false);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isEnabled() ? (getModel().isRollover() ? ACCENT_HI : ACCENT) : CARD_HI);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class GhostButton extends JButton {
        GhostButton(String text) {
            super(text);
            setForeground(TEXT);
            setFont(getFont().deriveFont(13f));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setBorder(new EmptyBorder(7, 12, 7, 12));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(CARD);
            g2.setColor(LINE);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 9, 9);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}