package decompiler.structure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Узел восстановленной структуры потока управления.
 *
 * <p>Это представление «синтаксического» дерева поверх плоского CFG: узел
 * либо базовый блок ({@link Kind#BLOCK}), либо цикл ({@link Kind#LOOP}), либо
 * условное ветвление ({@link Kind#IF_ELSE}). Для циклов хранится header-блок и
 * тело; для if-else — условие и ветви then/else.
 *
 * <p>Структуры НЕ опираются на байткод: работают по графу (блоки, рёбра,
 * доминаторы), поэтому их можно строить на де-флаттененном графе из
 * {@code CffUnflattenerPass} или на обычном CFG.
 */
public final class StructureNode {

    public enum Kind { BLOCK, LOOP, IF_ELSE, SEQUENCE }

    private final Kind kind;
    private final List<StructureNode> children;
    private Integer blockIndex;           // для BLOCK
    private Integer loopHeader;           // для LOOP
    private List<Integer> loopBody;       // для LOOP
    private Integer ifCondIndex;          // для IF_ELSE: блок с условием
    private StructureNode thenBranch;     // для IF_ELSE
    private StructureNode elseBranch;     // для IF_ELSE (может быть null)

    private StructureNode(Kind kind) {
        this.kind = kind;
        this.children = new ArrayList<>();
    }

    public static StructureNode block(int index) {
        StructureNode n = new StructureNode(Kind.BLOCK);
        n.blockIndex = index;
        return n;
    }

    public static StructureNode loop(int header, List<Integer> body) {
        StructureNode n = new StructureNode(Kind.LOOP);
        n.loopHeader = header;
        n.loopBody = new ArrayList<>(body);
        return n;
    }

    public static StructureNode sequence() {
        return new StructureNode(Kind.SEQUENCE);
    }

    public static StructureNode ifElse(int cond, StructureNode then, StructureNode els) {
        StructureNode n = new StructureNode(Kind.IF_ELSE);
        n.ifCondIndex = cond;
        n.thenBranch = then;
        n.elseBranch = els;
        if (then != null) n.children.add(then);
        if (els != null) n.children.add(els);
        return n;
    }

    public Kind kind() {
        return kind;
    }

    public Integer blockIndex() {
        return blockIndex;
    }

    public Integer loopHeader() {
        return loopHeader;
    }

    public List<Integer> loopBody() {
        return loopBody;
    }

    public Integer ifCondIndex() {
        return ifCondIndex;
    }

    public StructureNode thenBranch() {
        return thenBranch;
    }

    public StructureNode elseBranch() {
        return elseBranch;
    }

    public List<StructureNode> children() {
        return children;
    }

    public void addChild(StructureNode child) {
        children.add(child);
    }

    /** Множество блоков, накрытых этим узлом (включая потомков). */
    public Set<Integer> coveredBlocks() {
        Set<Integer> covered = new LinkedHashSet<>();
        collectBlocks(this, covered);
        return covered;
    }

    private static void collectBlocks(StructureNode n, Set<Integer> out) {
        if (n.kind == Kind.BLOCK) {
            out.add(n.blockIndex);
        }
        for (StructureNode c : n.children) {
            collectBlocks(c, out);
        }
        if (n.loopBody != null) {
            out.addAll(n.loopBody);
        }
    }

    @Override
    public String toString() {
        return switch (kind) {
            case BLOCK -> "block[" + blockIndex + "]";
            case LOOP -> "loop(header=" + loopHeader + ", body=" + loopBody + ")";
            case IF_ELSE -> "if(cond=" + ifCondIndex + (elseBranch != null ? ", else" : "") + ")";
            case SEQUENCE -> "sequence";
        };
    }
}