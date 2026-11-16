package decompiler.gui;

import decompiler.cfganalyzer.CfgBuilder;
import decompiler.cfganalyzer.CfgDumper;
import decompiler.dumper.ClassStructureDumper;
import decompiler.parser.JarParser;
import decompiler.ssa.SsaDumper;

import org.objectweb.asm.tree.ClassNode;
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
import java.util.List;
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

    // Общая с обфускатором палитра
    private static final Color BG = new Color(0x101318);
    private static final Color CARD = new Color(0x181c23);
    private static final Color CARD_HI = new Color(0x212734);
    private static final Color LINE = new Color(0x2b3340);
    private static final Color ACCENT = new Color(0x4c8dff);
    private static final Color ACCENT_HI = new Color(0x6ba3ff);
    private static final Color TEXT = new Color(0xe8ebf2);
    private static final Color MUTED = new Color(0x8a93a6);
    private static final Color GOOD = new Color(0x3ecf8e);
    private static final Color BAD = new Color(0xff6b6b);

    private final JLabel pathLabel = new JLabel("Перетащи jar сюда или выбери файл");
    private final JButton chooseBtn = new GhostButton("Выбрать jar…");

    // Настройки вывода структуры
    private final JCheckBox fieldsBox = new JCheckBox("Поля", true);
    private final JCheckBox methodsBox = new JCheckBox("Методы", true);
    private final JCheckBox countsBox = new JCheckBox("Счётчик инструкций", true);
    private final JCheckBox accessBox = new JCheckBox("Access-флаги", false);
    private final JCheckBox interfacesBox = new JCheckBox("Интерфейсы", true);

    private final JTextArea log = new JTextArea();
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel("Готов");
    private final AccentButton runBtn = new AccentButton("Разобрать");
    private final GhostButton cfgBtn = new GhostButton("Показать CFG");
    private final GhostButton ssaBtn = new GhostButton("SSA форма");
    private final GhostButton saveBtn = new GhostButton("Сохранить в файл");
    private final GhostButton clearBtn = new GhostButton("Очистить журнал");

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
        });
    }

    public DecompilerGui() {
        super("Java Decompiler");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(720, 640));
        setSize(820, 780);
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
        JLabel logo = new JLabel("JD");
        logo.setForeground(Color.WHITE);
        logo.setFont(logo.getFont().deriveFont(Font.BOLD, 18f));
        logo.setHorizontalAlignment(SwingConstants.CENTER);
        logo.setOpaque(true);
        logo.setBackground(ACCENT);
        logo.setPreferredSize(new Dimension(38, 38));
        JLabel title = new JLabel("Java Decompiler");
        title.setForeground(TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        JLabel sub = new JLabel("этап 1-2 · структура классов");
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

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        opts.setBackground(CARD);
        for (JCheckBox cb : new JCheckBox[]{fieldsBox, methodsBox, countsBox, accessBox, interfacesBox}) {
            cb.setForeground(TEXT);
            cb.setOpaque(false);
            cb.setFocusPainted(false);
            cb.setFont(cb.getFont().deriveFont(13f));
            opts.add(cb);
        }
        card.add(opts, BorderLayout.SOUTH);
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
        btns.add(cfgBtn);
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
        runBtn.addActionListener(e -> start());
        clearBtn.addActionListener(e -> {
            log.setText("");
            status.setForeground(MUTED);
            status.setText("Готов");
        });
        saveBtn.addActionListener(e -> saveOutput());
        cfgBtn.addActionListener(e -> showCfg());
        ssaBtn.addActionListener(e -> showSsa());
        setDropTarget(new DropTarget() {
            @Override
            public void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = e.getTransferable();
                    if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return;
                    Object data = t.getTransferData(DataFlavor.javaFileListFlavor);
                    if (data instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof File f
                            && f.isFile() && f.getName().endsWith(".jar")) {
                        inputFile = f;
                        updatePath();
                    }
                } catch (Exception ignored) {
                } finally {
                    e.dropComplete(false);
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

    /** Собирает текущие настройки вывода. */
    private ClassStructureDumper.Options currentOptions() {
        ClassStructureDumper.Options o = new ClassStructureDumper.Options();
        o.fields = fieldsBox.isSelected();
        o.methods = methodsBox.isSelected();
        o.methodCounts = countsBox.isSelected();
        o.accessFlags = accessBox.isSelected();
        o.showInterfaces = interfacesBox.isSelected();
        return o;
    }

    private void start() {
        if (inputFile == null) {
            JOptionPane.showMessageDialog(this, "Сначала выбери jar", "Нет файла", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final File src = inputFile;
        final Path jarPath = src.toPath();
        final AtomicInteger classCount = new AtomicInteger();
        final ClassStructureDumper.Options opts = currentOptions();
        final long started = System.currentTimeMillis();

        progress.setVisible(true);
        status.setForeground(MUTED);
        status.setText("Обработка…");
        runBtn.setEnabled(false);
        log.append("\n--- Разбор " + src.getName() + " ---\n");

        new SwingWorker<Void, String>() {
            private String result = "";

            @Override
            protected Void doInBackground() throws Exception {
                ClassStructureDumper dumper = new ClassStructureDumper(opts);
                JarParser.parse(jarPath, (p, entry, node) -> {
                    dumper.dump(p, entry, node);
                    classCount.incrementAndGet();
                });
                result = dumper.result();
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String s : chunks) {
                    log.append(s + "\n");
                }
                log.setCaretPosition(log.getDocument().getLength());
            }

            @Override
            protected void done() {
                progress.setVisible(false);
                runBtn.setEnabled(true);
                double secs = (System.currentTimeMillis() - started) / 1000.0;
                try {
                    get();
                    // Показываем только начало в журнал, весь дамп доступен кнопкой «Сохранить».
                    log.append(String.format(">>> Классов разобрано: %d за %.1f с%n", classCount.get(), secs));
                    if (classCount.get() > 0) {
                        int preview = Math.min(4000, result.length());
                        log.append("\n" + result.substring(0, preview) + "\n");
                        if (result.length() > preview) {
                            log.append("... (ещё " + (result.length() - preview) + " символов)\n");
                        }
                    }
                    status.setForeground(GOOD);
                    status.setText(String.format("Готово: %d классов за %.1f с", classCount.get(), secs));
                    lastResult = result;
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    status.setForeground(BAD);
                    status.setText("Ошибка");
                    log.append("\n!!! " + cause + "\n");
                }
            }
        }.execute();
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

    private void saveOutput() {
        if (lastResult.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Сначала разбери jar", "Нет результата",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser(outputDir.toFile());
        fc.setDialogTitle("Сохранить дамп");
        String base = inputFile != null ? inputFile.getName().replaceAll("(?i)\\.jar$", "") : "decompile";
        fc.setSelectedFile(new File(base + "-structure.txt"));
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