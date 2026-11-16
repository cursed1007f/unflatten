package decompiler.structure;

/**
 * Выводит восстановленное дерево структур в виде псевдо-Java (отступы,
 * if/else, циклы), чтобы визуально проверять результат на CFG (обычном и
 * де-флаттененном).
 */
public final class StructureDumper {

    private StructureDumper() {
    }

    /** Рендерит дерево структур с отступами (0-й уровень = без отступа). */
    public static String render(StructureNode root) {
        StringBuilder sb = new StringBuilder();
        renderNode(root, sb, 0);
        return sb.toString();
    }

    private static void renderNode(StructureNode n, StringBuilder sb, int depth) {
        String pad = "  ".repeat(depth);
        switch (n.kind()) {
            case BLOCK -> sb.append(pad).append("block ").append(n.blockIndex()).append("\n");
            case SEQUENCE -> {
                for (StructureNode c : n.children()) {
                    renderNode(c, sb, depth + 1);
                }
            }
            case LOOP -> {
                sb.append(pad).append("loop (header=").append(n.loopHeader()).append("):\n");
                for (StructureNode c : n.children()) {
                    renderNode(c, sb, depth + 1);
                }
            }
            case IF_ELSE -> {
                sb.append(pad).append("if (cond=").append(n.ifCondIndex()).append(")\n");
                if (n.thenBranch() != null) {
                    renderNode(n.thenBranch(), sb, depth + 1);
                }
                if (n.elseBranch() != null) {
                    sb.append(pad).append("else\n");
                    renderNode(n.elseBranch(), sb, depth + 1);
                }
            }
        }
    }
}