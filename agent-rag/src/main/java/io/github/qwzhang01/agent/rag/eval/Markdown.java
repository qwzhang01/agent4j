package io.github.qwzhang01.agent.rag.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

final class Markdown {

    private static final List<String> TYPE_ORDER = List.of(
            EvalCase.SINGLE_HOP, EvalCase.MULTI_TURN, EvalCase.MULTI_HOP, EvalCase.CONFLICT, EvalCase.UNANSWERABLE);

    private Markdown() {
    }

    static String num(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.3f", v);
    }

    static String pct(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    static String cell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    static void row(StringBuilder sb, List<String> cells) {
        sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
    }

    static void header(StringBuilder sb, List<String> cells) {
        row(sb, cells);
        List<String> sep = new ArrayList<>();
        cells.forEach(c -> sep.add("---"));
        row(sb, sep);
    }

    /** Known case types in a fixed order, then any others alphabetically. */
    static List<String> orderTypes(Collection<String> types) {
        List<String> out = new ArrayList<>();
        for (String t : TYPE_ORDER) {
            if (types.contains(t)) {
                out.add(t);
            }
        }
        for (String t : new TreeSet<>(types)) {
            if (!TYPE_ORDER.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }
}
