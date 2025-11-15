package meigo.denizen.reflect.util;

import com.denizenscript.denizen.events.server.ServerStartScriptEvent;
import com.denizenscript.denizencore.events.ScriptEvent;
import com.denizenscript.denizencore.events.core.PreScriptReloadScriptEvent;
import com.denizenscript.denizencore.events.core.ScriptGeneratesErrorScriptEvent;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static meigo.denizen.reflect.DenizenReflect.send;

public class ImportManager {

    private static PreScriptReloadScriptEvent originalPreScriptReloadEvent;
    private static ScriptGeneratesErrorScriptEvent originalScriptGeneratesErrorEvent;
    private static ServerStartScriptEvent originalServerStart;
    private static final Map<String, Map<String, String>> scriptImports = new HashMap<>();
    private static final String SCRIPTS_FOLDER_PATH = "plugins/Denizen/scripts";

    public static ScriptEvent _import() {
            send("denizen-reflect", "Starting import parsing...");
            scriptImports.clear();
            JavaExpressionEngine.clearAllImports();
            Path scriptsDir = Paths.get(SCRIPTS_FOLDER_PATH);
            if (!Files.exists(scriptsDir)) {
                Debug.echoError("Denizen-Reflect: Scripts folder not found: " + scriptsDir.toAbsolutePath().toString());
            } else {
                try (Stream<Path> walk = Files.walk(scriptsDir)) {
                    walk.filter(p -> p.toString().toLowerCase().endsWith(".dsc"))
                            .forEach(filePath -> parseFileForImports(filePath, scriptsDir));
                } catch (IOException e) {
                    Debug.echoError("Denizen-Reflect: Error during file scan: " + e.getMessage());
                }
            }
            if (scriptImports.isEmpty()) {
                send("denizen-reflect", "Warning: No imports found in any .dsc file.");
            }
            for (Map.Entry<String, Map<String, String>> entry : scriptImports.entrySet()) {
                String relativePath = entry.getKey();
                Map<String, String> imports = entry.getValue();
                StringBuilder logBuilder = new StringBuilder();
                logBuilder.append("Imported: (");
                int count = 0;
                for (Map.Entry<String, String> importEntry : imports.entrySet()) {
                    try {
                        String fullClassName = importEntry.getKey();
                        String alias = importEntry.getValue();
                        JavaExpressionEngine.importClass(relativePath, fullClassName, alias);
                    } catch (ClassNotFoundException e) {
                        Debug.echoError("Denizen-Reflect: Failed to import class '" + importEntry.getKey() + "' for script '" + relativePath + "': " + e.getMessage());
                    } catch (Throwable e) {
                        Debug.echoError("Denizen-Reflect: Unexpected error importing class '" + importEntry.getKey() + "' for script '" + relativePath + "':");
                        Debug.echoError(e);
                    }
                    if (count > 0) logBuilder.append(", ");
                    String alias = importEntry.getValue() == null || importEntry.getValue().isEmpty() ? "null" : importEntry.getValue();
                    logBuilder.append(importEntry.getKey()).append(" as ").append(alias);
                    count++;
                }
                logBuilder.append(")");
                send("denizen-reflect", logBuilder.toString() + " in file: " + relativePath);
            }
            ScriptEvent result = null;
            if (originalPreScriptReloadEvent != null) {
                try {
                    result = originalPreScriptReloadEvent.fire();
                } catch (Throwable t) {
                    Debug.echoError("Denizen-Reflect: Error calling original PreScriptReloadScriptEvent: " + t.getMessage());
                    Debug.echoError(t);
                }
            }
            return result;
    }

    public static void registerEventHooks() {
        originalPreScriptReloadEvent = PreScriptReloadScriptEvent.instance;
        PreScriptReloadScriptEvent.instance = new PreScriptReloadScriptEvent() {
            @Override
            public ScriptEvent fire() {
                return _import();
            }
        };

        originalScriptGeneratesErrorEvent = ScriptGeneratesErrorScriptEvent.instance;
        ScriptGeneratesErrorScriptEvent.instance = new ScriptGeneratesErrorScriptEvent() {
            @Override
            public ScriptEvent fire() {
                if (this.message != null && CoreUtilities.toLowerCase(this.message).contains("import")) {
                    if (Objects.equals(this.script != null ? this.script.getName() : "null", "null")) {
                        this.cancelled = true;
                        this.cancellationChanged();
                    }
                }
                ScriptEvent result = null;
                if (originalScriptGeneratesErrorEvent != null) {
                    try {
                        result = originalScriptGeneratesErrorEvent.fire();
                    } catch (Throwable t) {
                        Debug.echoError("Denizen-Reflect: Error calling original ScriptGeneratesErrorScriptEvent: " + t.getMessage());
                        Debug.echoError(t);
                    }
                }
                return result;
            }
        };
    }

    private static String getAlias(String fullClassName, String alias) {
        if (alias != null && !alias.isEmpty() && !CoreUtilities.equalsIgnoreCase(alias, "null")) {
            return alias;
        }
        int lastDot = fullClassName.lastIndexOf('.');
        return lastDot == -1 ? fullClassName : fullClassName.substring(lastDot + 1);
    }

    private static void parseFileForImports(Path filePath, Path scriptsDir) {
        String relativePath = scriptsDir.relativize(filePath).toString().replace(System.getProperty("file.separator"), "/");
        Debug.log("Denizen-Reflect: Parsing file: " + relativePath);
        try {
            String rawContent = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            if (rawContent.startsWith("\uFEFF")) {
                rawContent = rawContent.substring(1);
            }
            rawContent = rawContent.replace("\t", "    ").replace("\r", "");
            String preprocessed = preprocessImportBlockToValidYaml(rawContent);
            if (!preprocessed.equals(rawContent)) {
                try {
                    Files.write(filePath, preprocessed.getBytes(StandardCharsets.UTF_8));
                    rawContent = preprocessed;
                    Debug.log("Denizen-Reflect: Updated file to valid import mapping: " + relativePath);
                } catch (IOException ioe) {
                    Debug.echoError("Denizen-Reflect: Failed to write preprocessed file '" + relativePath + "': " + ioe.getMessage());
                }
            }
            String[] rawLines = rawContent.split("\n");
            Map<String, String> currentFileImports = new HashMap<>();
            for (int i = 0; i < rawLines.length; i++) {
                String line = rawLines[i];
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.matches("(?i)^import\\s*:\\s*\\{.*\\}.*$")) {
                    String inside = trimmed.substring(trimmed.indexOf('{') + 1, trimmed.lastIndexOf('}'));
                    String[] parts = inside.split(",");
                    for (String part : parts) {
                        AbstractMap.SimpleEntry<String, String> e = parseImportEntry(part.trim());
                        if (e != null) currentFileImports.put(e.getKey(), e.getValue());
                    }
                    continue;
                }
                if (trimmed.matches("(?i)^import\\s*:\\s*$") || trimmed.matches("(?i)^import\\s*:\\s*#.*$")) {
                    int baseIndent = -1;
                    int j = i + 1;
                    while (j < rawLines.length) {
                        String next = rawLines[j];
                        if (next.trim().isEmpty()) {
                            j++;
                            continue;
                        }
                        int leading = countLeadingSpaces(next);
                        if (next.trim().startsWith("#")) {
                            j++;
                            continue;
                        }
                        baseIndent = leading;
                        break;
                    }
                    if (baseIndent == -1) {
                        Debug.log("Denizen-Reflect: No indented import entries found in " + relativePath);
                        continue;
                    }
                    int k = j;
                    while (k < rawLines.length) {
                        String entryLine = rawLines[k];
                        if (entryLine.trim().isEmpty()) {
                            k++;
                            continue;
                        }
                        int leading = countLeadingSpaces(entryLine);
                        if (leading < baseIndent || entryLine.trim().startsWith("#")) break;
                        String content = entryLine.substring(Math.min(leading, entryLine.length())).trim();
                        if (content.startsWith("-")) {
                            content = content.substring(1).trim();
                        }
                        AbstractMap.SimpleEntry<String, String> e = parseImportEntry(content);
                        if (e != null) currentFileImports.put(e.getKey(), e.getValue());
                        k++;
                    }
                    i = Math.max(i, j - 1);
                }
            }
            if (!currentFileImports.isEmpty()) {
                scriptImports.put(relativePath, currentFileImports);
            }

        } catch (Exception e) {
            Debug.echoError("Denizen-Reflect: Failed to read/parse file '" + relativePath + "': " + e.getMessage());
            Debug.echoError(e);
        }
    }

    private static String preprocessImportBlockToValidYaml(String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            out.append(line).append('\n');
            String trimmed = line.trim();
            if (trimmed.matches("(?i)^import\\s*:\\s*$") || trimmed.matches("(?i)^import\\s*:\\s*#.*$")) {
                int j = i + 1;
                int baseIndent = -1;
                while (j < lines.length) {
                    String next = lines[j];
                    if (next.trim().isEmpty()) {
                        j++;
                        continue;
                    }
                    if (next.trim().startsWith("#")) {
                        j++;
                        continue;
                    }
                    baseIndent = countLeadingSpaces(next);
                    break;
                }
                if (baseIndent == -1) {
                    i++;
                    continue;
                }
                int k = j;
                while (k < lines.length) {
                    String entryLine = lines[k];
                    if (entryLine.trim().isEmpty()) {
                        out.append(entryLine).append('\n');
                        k++;
                        continue;
                    }
                    int leading = countLeadingSpaces(entryLine);
                    if (leading < baseIndent || entryLine.trim().startsWith("#")) break;
                    String afterIndent = entryLine.substring(Math.min(leading, entryLine.length()));
                    String firstNonWs = afterIndent.trim();
                    if (firstNonWs.startsWith("-")) {
                        out.append(entryLine).append('\n');
                    } else {
                        int colonPos = findColonOutsideQuotes(afterIndent);
                        if (colonPos < 0) {
                            out.append(entryLine).append(":").append('\n');
                        } else {
                            out.append(entryLine).append('\n');
                        }
                    }
                    k++;
                }
                i = k;
                continue;
            }
            i++;
        }
        return out.toString();
    }

    private static int countLeadingSpaces(String s) {
        int c = 0;
        while (c < s.length() && (s.charAt(c) == ' ' || s.charAt(c) == '\t')) c++;
        return c;
    }

    private static AbstractMap.SimpleEntry<String, String> parseImportEntry(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.isEmpty()) return null;
        if (line.startsWith("#")) return null;
        int hash = findHashOutsideQuotes(line);
        if (hash >= 0) {
            line = line.substring(0, hash).trim();
            if (line.isEmpty()) return null;
        }
        int colon = findColonOutsideQuotes(line);
        String fullClassName;
        String alias = null;
        if (colon >= 0) {
            fullClassName = line.substring(0, colon).trim();
            alias = line.substring(colon + 1).trim();
            if ((alias.startsWith("'") && alias.endsWith("'")) || (alias.startsWith("\"") && alias.endsWith("\""))) {
                alias = alias.substring(1, alias.length() - 1);
            }
            if (alias.isEmpty()) alias = null;
        } else {
            fullClassName = line;
        }
        if (fullClassName.isEmpty()) return null;
        alias = getAlias(fullClassName, alias);
        return new AbstractMap.SimpleEntry<>(fullClassName, alias);
    }

    private static int findColonOutsideQuotes(String s) {
        boolean sq = false;
        boolean dq = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'' && !dq) sq = !sq;
            if (ch == '"' && !sq) dq = !dq;
            if (ch == ':' && !sq && !dq) return i;
        }
        return -1;
    }

    private static int findHashOutsideQuotes(String s) {
        boolean sq = false;
        boolean dq = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'' && !dq) sq = !sq;
            if (ch == '"' && !sq) dq = !dq;
            if (ch == '#' && !sq && !dq) return i;
        }
        return -1;
    }
}