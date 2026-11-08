package io.github.tonymontanov.bookmap.moex.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Правило слоёв из docs/architecture.md: слой импортирует только нижележащие,
 * {@code provider → source → feed → transport → shared}, и только провайдер
 * знает о Bookmap ({@code velox.*}).
 *
 * <p>Проверяется по исходникам, а не по байткоду: так нарушение видно с
 * номером строки, и тест не зависит от сборки.
 */
class LayerDependencyTest {

    private static final String ROOT_PACKAGE = "io.github.tonymontanov.bookmap.moex";
    private static final Path SOURCES = Path.of("src/main/java/io/github/tonymontanov/bookmap/moex");

    /** Ранг слоя: импорт разрешён только в слой с меньшим рангом или в свой. */
    private static final Map<String, Integer> RANK = Map.of(
            "shared", 0,
            "transport", 1,
            "feed", 2,
            "source", 3,
            "provider", 4);

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?" + Pattern.quote(ROOT_PACKAGE) + "\\.([a-z]+)\\b");
    private static final Pattern BOOKMAP_IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?velox\\.");

    @Test
    void importsGoOnlyDownAndBookmapStaysInProvider() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String layer = SOURCES.relativize(file).getName(0).toString();
                Integer rank = RANK.get(layer);
                if (rank == null) {
                    violations.add(file + ": пакет вне слоёв " + RANK.keySet());
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    Matcher matcher = IMPORT.matcher(line);
                    if (matcher.find()) {
                        Integer target = RANK.get(matcher.group(1));
                        if (target == null || target > rank) {
                            violations.add(file + ":" + (i + 1) + " " + layer + " → " + matcher.group(1));
                        }
                    }
                    if (!layer.equals("provider") && BOOKMAP_IMPORT.matcher(line).find()) {
                        violations.add(file + ":" + (i + 1) + " " + layer + " импортирует Bookmap");
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }
}
