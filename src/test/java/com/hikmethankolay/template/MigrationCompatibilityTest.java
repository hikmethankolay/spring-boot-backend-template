package com.hikmethankolay.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * During a rolling update the previous version keeps running against the new schema, and a rollback starts it
 * against that schema for good. A column that becomes NOT NULL on an existing table therefore needs a default,
 * or the previous version's inserts, which do not know the column, fail.
 */
class MigrationCompatibilityTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    private static final Pattern ALTER_TABLE = Pattern.compile("(?s)^alter table (\\w+)\\s+(.*)$");

    private static final Pattern ADD_NOT_NULL = Pattern.compile("^add column (\\w+) .*\\bnot null\\b.*$");

    private static final Pattern SET_NOT_NULL = Pattern.compile("^alter column (\\w+) set not null$");

    private static final Pattern SET_DEFAULT = Pattern.compile("^alter column (\\w+) set default .*$");

    @Test
    void columnsMadeNotNullOnExistingTablesHaveADefault() throws IOException {
        Set<String> needDefault = new HashSet<>();
        Set<String> haveDefault = new HashSet<>();
        for (String statement : statements()) {
            Matcher alter = ALTER_TABLE.matcher(statement);
            if (!alter.matches()) {
                continue;
            }
            String table = alter.group(1);
            for (String clause : alter.group(2).split(",\\s*(?=add |alter |drop )")) {
                String normalized = clause.strip().replaceAll("\\s+", " ");
                Matcher matcher;
                if ((matcher = ADD_NOT_NULL.matcher(normalized)).matches() && !normalized.contains(" default ")) {
                    needDefault.add(table + "." + matcher.group(1));
                } else if ((matcher = SET_NOT_NULL.matcher(normalized)).matches()) {
                    needDefault.add(table + "." + matcher.group(1));
                } else if ((matcher = SET_DEFAULT.matcher(normalized)).matches()) {
                    haveDefault.add(table + "." + matcher.group(1));
                }
            }
        }

        needDefault.removeAll(haveDefault);
        assertThat(needDefault).as("NOT NULL columns without a default").isEmpty();
    }

    /** Every statement of every migration in version order, lower case and without comments. */
    private static List<String> statements() throws IOException {
        StringBuilder sql = new StringBuilder();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.sorted(Comparator.comparingInt(MigrationCompatibilityTest::version))
                    .toList()) {
                sql.append(Files.readString(file).replaceAll("--[^\\n]*", "")).append(';');
            }
        }
        return Stream.of(sql.toString().toLowerCase(Locale.ROOT).split(";"))
                .map(String::strip)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    private static int version(Path file) {
        return Integer.parseInt(file.getFileName().toString().replaceAll("^V(\\d+)__.*$", "$1"));
    }
}
