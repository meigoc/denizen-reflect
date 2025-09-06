package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.visitor.GenericVisitorAdapter;
import meigo.denizen.reflect.object.JavaObjectTag;
import meigo.denizen.reflect.util.ReflectionHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InvokeCommand extends AbstractCommand {

    public InvokeCommand() {
        setName("invoke");
        setSyntax("invoke [<java_expression>]");
        setRequiredArguments(1, 1);
        isProcedural = false;
    }

    // Record для хранения результата предобработки
    private record PreprocessedInvoke(String code, Map<String, ObjectTag> tagMap) {}

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        for (Argument arg : scriptEntry) {
            if (!scriptEntry.hasObject("invoke_string")) {
                scriptEntry.addObject("invoke_string", arg.getRawElement());
            } else {
                arg.reportUnhandled();
            }
        }
        if (!scriptEntry.hasObject("invoke_string")) {
            throw new InvalidArgumentsException("Missing invoke string argument!");
        }
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        ElementTag invokeString = scriptEntry.getElement("invoke_string");
        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(), invokeString.debuggable());
        }

        try {
            // 1. Предобработка строки для замены всех объектов Denizen на плейсхолдеры
            PreprocessedInvoke preprocessed = preprocess(invokeString.asString(), scriptEntry);
            if (scriptEntry.dbCallShouldDebug()) {
                Debug.log("Preprocessed code: " + preprocessed.code());
                Debug.log("Tag Map: " + preprocessed.tagMap());
            }

            // 2. Парсинг кода в Abstract Syntax Tree (AST) с помощью JavaParser
            Expression expression = StaticJavaParser.parseExpression(preprocessed.code());

            // 3. Интерпретация AST для выполнения рефлективных вызовов
            AstInterpreter interpreter = new AstInterpreter(preprocessed.tagMap(), scriptEntry);
            Object result = expression.accept(interpreter, null);

            if (scriptEntry.dbCallShouldDebug()) {
                Debug.log("Invocation successful. Result: " + (result != null ? result.toString() : "null"));
            }

        } catch (Exception e) {
            Debug.echoError(scriptEntry, "Failed to execute invoke command.");
            Debug.echoError(scriptEntry, e);
        }
    }

    private PreprocessedInvoke preprocess(String rawString, ScriptEntry scriptEntry) {
        // Сначала раскрываем все стандартные теги <...>
        String initialString = TagManager.tag(rawString, scriptEntry.getContext());
        // Добавлено: замена разделителя аргументов '|' на запятую
        initialString = initialString.replace("|", ", ");

        // Новая обработка: заключаем строки с пробелами в кавычки
        initialString = wrapStringsWithSpaces(initialString);

        // Затем используем наш парсер для замены объектов Denizen (p@, l@, и т.д.)
        // ENHANCED: Now includes support for <[defined_imported_class]> resolution
        return new DenizenObjectParser(scriptEntry.getContext(), scriptEntry).parse(initialString);
    }

    /**
     * Обрабатывает строки с пробелами в аргументах методов, заключая их в кавычки
     * для корректного парсинга JavaParser'ом
     */
    private String wrapStringsWithSpaces(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        StringBuilder result = new StringBuilder();
        char[] chars = input.toCharArray();
        int i = 0;

        while (i < chars.length) {
            char ch = chars[i];

            // Если находим открывающую скобку метода
            if (ch == '(') {
                result.append(ch);
                i++;
                i = processMethodArguments(chars, i, result);
            } else {
                result.append(ch);
                i++;
            }
        }

        return result.toString();
    }

    /**
     * Обрабатывает аргументы внутри скобок метода
     */
    private int processMethodArguments(char[] chars, int start, StringBuilder result) {
        int i = start;
        boolean insideQuotes = false;
        char quoteChar = 0;
        boolean insideArgument = false;
        StringBuilder currentArgument = new StringBuilder();
        int parenDepth = 0;

        while (i < chars.length) {
            char ch = chars[i];

            // Обработка кавычек
            if (!insideQuotes && (ch == '"' || ch == '\'')) {
                insideQuotes = true;
                quoteChar = ch;
                result.append(ch);
                i++;
                continue;
            } else if (insideQuotes && ch == quoteChar) {
                insideQuotes = false;
                result.append(ch);
                i++;
                continue;
            }

            // Если внутри кавычек, просто копируем
            if (insideQuotes) {
                result.append(ch);
                i++;
                continue;
            }

            // Обработка вложенных скобок
            if (ch == '(') {
                parenDepth++;
                if (insideArgument) {
                    currentArgument.append(ch);
                } else {
                    result.append(ch);
                }
                i++;
                continue;
            } else if (ch == ')') {
                if (parenDepth > 0) {
                    parenDepth--;
                    currentArgument.append(ch);
                    i++;
                    continue;
                } else {
                    // Завершаем последний аргумент
                    if (insideArgument) {
                        String arg = currentArgument.toString().trim();
                        if (needsQuoting(arg)) {
                            result.append('"').append(arg).append('"');
                        } else {
                            result.append(arg);
                        }
                    }
                    result.append(ch);
                    return i + 1;
                }
            }

            // Обработка запятых (разделители аргументов)
            if (ch == ',' && parenDepth == 0) {
                if (insideArgument) {
                    String arg = currentArgument.toString().trim();
                    if (needsQuoting(arg)) {
                        result.append('"').append(arg).append('"');
                    } else {
                        result.append(arg);
                    }
                    currentArgument.setLength(0);
                }
                result.append(ch);
                insideArgument = false;
                i++;
                continue;
            }

            // Пропускаем пробелы в начале аргумента
            if (Character.isWhitespace(ch) && !insideArgument) {
                result.append(ch);
                i++;
                continue;
            }

            // Начинаем новый аргумент
            if (!insideArgument) {
                insideArgument = true;
            }

            currentArgument.append(ch);
            i++;
        }

        // Если мы дошли до конца и есть незавершенный аргумент
        if (insideArgument) {
            String arg = currentArgument.toString().trim();
            if (needsQuoting(arg)) {
                result.append('"').append(arg).append('"');
            } else {
                result.append(arg);
            }
        }

        return i;
    }

    /**
     * Определяет, нужно ли заключать строку в кавычки
     */
    private boolean needsQuoting(String arg) {
        if (arg.isEmpty()) {
            return false;
        }

        // Уже в кавычках
        if ((arg.startsWith("\"") && arg.endsWith("\"")) ||
                (arg.startsWith("'") && arg.endsWith("'"))) {
            return false;
        }

        // Содержит пробелы или специальные символы
        if (arg.contains(" ") || arg.contains("\t") || arg.contains("\n")) {
            return true;
        }

        // Проверяем, является ли это валидным Java идентификатором или числом
        if (isValidJavaLiteral(arg)) {
            return false;
        }

        // Проверяем, является ли это denizen placeholder
        if (arg.startsWith("__denizen_obj_")) {
            return false;
        }

        // Проверяем, является ли это валидным Java выражением (класс, метод и т.д.)
        if (isValidJavaIdentifierChain(arg)) {
            return false;
        }

        // Во всех остальных случаях заключаем в кавычки
        return true;
    }

    /**
     * Проверяет, является ли строка валидным Java литералом
     */
    private boolean isValidJavaLiteral(String str) {
        if (str.isEmpty()) return false;

        // Булевы значения
        if ("true".equals(str) || "false".equals(str) || "null".equals(str)) {
            return true;
        }

        // Числа
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            // Не число
        }

        return false;
    }

    /**
     * Проверяет, является ли строка валидной цепочкой Java идентификаторов
     */
    private boolean isValidJavaIdentifierChain(String str) {
        if (str.isEmpty()) return false;

        String[] parts = str.split("\\.");
        for (String part : parts) {
            if (!isValidJavaIdentifier(part)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Проверяет, является ли строка валидным Java идентификатором
     */
    private boolean isValidJavaIdentifier(String str) {
        if (str.isEmpty()) return false;

        if (!Character.isJavaIdentifierStart(str.charAt(0))) {
            return false;
        }

        for (int i = 1; i < str.length(); i++) {
            if (!Character.isJavaIdentifierPart(str.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    // ================================================================================= //
    // ================== ENHANCED парсер для объектов Denizen ======================= //
    // ================================================================================= //

    private static class DenizenObjectParser {

        private final Map<String, ObjectTag> tagMap = new HashMap<>();
        private final TagContext context;
        private final ScriptEntry scriptEntry; // ADDED: Access to ScriptEntry for definition resolution
        private int tagCounter = 0;

        public DenizenObjectParser(TagContext context, ScriptEntry scriptEntry) {
            this.context = context;
            this.scriptEntry = scriptEntry;
        }

        /**
         * Основной метод, который итеративно заменяет "листовые" теги.
         */
        public PreprocessedInvoke parse(String input) {
            if (input == null || input.isEmpty() || (!input.contains("@") && !input.contains("java@"))) {
                return new PreprocessedInvoke(input == null ? "" : input, tagMap);
            }

            String result = input;
            while (true) {
                String newResult = replaceLeafTags(result);
                if (newResult.equals(result)) {
                    break;
                }
                result = newResult;
            }

            return new PreprocessedInvoke(result, tagMap);
        }

        private String replaceLeafTags(String input) {
            List<TagInfo> allTags = findAllTags(input);

            // ENHANCED: Also find JavaObjectTag references that might already be expanded by TagManager
            List<JavaRefInfo> javaRefs = findJavaObjectReferences(input);

            if (allTags.isEmpty() && javaRefs.isEmpty()) {
                return input;
            }

            List<TagInfo> leafTags = new ArrayList<>();
            for (TagInfo tag : allTags) {
                if (!tag.value.contains("@")) {
                    leafTags.add(tag);
                }
            }

            StringBuilder result = new StringBuilder(input);

            // First process JavaObjectTag references (these might be from expanded definitions)
            // Sort by position (right to left) to avoid index shifting
            javaRefs.sort((a, b) -> Integer.compare(b.startPos, a.startPos));

            for (JavaRefInfo javaRef : javaRefs) {
                try {
                    // Try to get the JavaObjectTag by its identifier
                    JavaObjectTag javaObj = JavaObjectTag.valueOf(javaRef.identifier, context);
                    if (javaObj != null) {
                        String placeholder = "__denizen_obj_" + (tagCounter++);
                        tagMap.put(placeholder, javaObj);
                        result.replace(javaRef.startPos, javaRef.endPos, placeholder);
                    }
                } catch (Exception ignored) {
                    // If parsing fails, leave it as is
                }
            }

            // Then process regular Denizen tags
            // Re-sort leaf tags by position after java refs processing
            leafTags.sort((a, b) -> Integer.compare(b.startPos, a.startPos));

            for (TagInfo tag : leafTags) {
                String fullTagString = tag.identifier + "@" + tag.value;

                try {
                    ObjectTag parsed = ObjectFetcher.pickObjectFor(fullTagString, context);
                    if (parsed != null && !(parsed instanceof ElementTag && parsed.identify().equals(fullTagString))) {
                        String placeholder = "__denizen_obj_" + (tagCounter++);
                        tagMap.put(placeholder, parsed);

                        // Adjust positions if they were affected by previous replacements
                        int currentStart = result.indexOf(fullTagString);
                        if (currentStart != -1) {
                            result.replace(currentStart, currentStart + fullTagString.length(), placeholder);
                        }
                    }
                } catch (Exception ignored) {
                    // Игнорируем ошибки, оставляем невалидные теги как есть
                }
            }

            return result.toString();
        }

        /**
         * NEW: Find JavaObjectTag references that might already be expanded
         * (like "java@uuid" or "java@ClassName")
         */
        private List<JavaRefInfo> findJavaObjectReferences(String input) {
            List<JavaRefInfo> refs = new ArrayList<>();
            Pattern javaPattern = Pattern.compile("\\bjava@([a-fA-F0-9-]+|[\\w\\.\\$]+)");
            Matcher matcher = javaPattern.matcher(input);

            while (matcher.find()) {
                refs.add(new JavaRefInfo(matcher.start(), matcher.end(), matcher.group()));
            }

            return refs;
        }

        private List<TagInfo> findAllTags(String input) {
            List<TagInfo> tags = new ArrayList<>();
            char[] chars = input.toCharArray();
            int i = 0;

            while (i < chars.length) {
                if (isIdentifierStart(chars[i])) {
                    int start = i;
                    i = readIdentifier(chars, i);

                    if (i < chars.length && chars[i] == '@') {
                        String identifier = new String(chars, start, i - start);
                        i++; // skip @
                        int valueStart = i;
                        int valueEnd = findValueEnd(chars, i);

                        String value = new String(chars, valueStart, valueEnd - valueStart);
                        tags.add(new TagInfo(start, valueEnd, identifier, value));

                        // Находим вложенные теги
                        findNestedTags(value, valueStart, tags);
                        i = valueEnd;
                    } else {
                        i++;
                    }
                } else {
                    i++;
                }
            }

            return tags;
        }

        private void findNestedTags(String value, int offset, List<TagInfo> tags) {
            if (!value.contains("@")) return;

            String[] parts = value.split("\\|");
            int pos = 0;

            for (int p = 0; p < parts.length; p++) {
                final int currentPos = pos; // делаем переменную final для лямбды
                findAllTags(parts[p]).forEach(tag ->
                        tags.add(new TagInfo(offset + currentPos + tag.startPos, offset + currentPos + tag.endPos, tag.identifier, tag.value))
                );
                pos += parts[p].length() + (p < parts.length - 1 ? 1 : 0);
            }
        }

        private boolean isIdentifierStart(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
        }

        private int readIdentifier(char[] chars, int start) {
            int i = start;
            while (i < chars.length && (isIdentifierStart(chars[i]) || chars[i] == '_')) {
                i++;
            }
            return i;
        }

        private int findValueEnd(char[] chars, int start) {
            int i = start;

            if (i < chars.length && chars[i] == '[') {
                int depth = 1;
                i++;
                while (i < chars.length && depth > 0) {
                    if (chars[i] == '[') depth++;
                    else if (chars[i] == ']') depth--;
                    i++;
                }
                return i;
            }

            while (i < chars.length) {
                char ch = chars[i];
                if (ch <= 32 || ch == '(' || ch == ')' || ch == '[' || ch == ']') break;
                if (ch == '.' && i + 1 < chars.length && Character.isLetter(chars[i + 1])) break;
                i++;
            }
            return i;
        }

        private static class TagInfo {
            final int startPos, endPos;
            final String identifier, value;

            TagInfo(int startPos, int endPos, String identifier, String value) {
                this.startPos = startPos;
                this.endPos = endPos;
                this.identifier = identifier;
                this.value = value;
            }
        }

        /**
         * NEW: Info class for JavaObjectTag references
         */
        private static class JavaRefInfo {
            final int startPos, endPos;
            final String identifier;

            JavaRefInfo(int startPos, int endPos, String identifier) {
                this.startPos = startPos;
                this.endPos = endPos;
                this.identifier = identifier;
            }
        }
    }

    // ================================================================================= //
    // ===================== Обновленный интерпретатор AST ============================= //
    // ================================================================================= //

    private static class AstInterpreter extends GenericVisitorAdapter<Object, Void> {
        private final Map<String, ObjectTag> tagMap;
        private final TagContext context;

        public AstInterpreter(Map<String, ObjectTag> tagMap, ScriptEntry scriptEntry) {
            this.tagMap = tagMap;
            this.context = scriptEntry.getContext();
        }

        @Override
        public Object visit(MethodCallExpr n, Void arg) {
            Object scope = n.getScope().map(s -> s.accept(this, arg)).orElse(null);
            if (scope == null) {
                throw new IllegalStateException("Method call '" + n.getNameAsString() + "' has no scope (object or class).");
            }
            List<ObjectTag> arguments = new ArrayList<>();
            for (Expression paramExpr : n.getArguments()) {
                Object paramValue = paramExpr.accept(this, arg);
                arguments.add(CoreUtilities.objectToTagForm(paramValue, context, false, false, true));
            }

            if (scope instanceof Class) {
                return ReflectionHandler.invokeStaticMethod((Class<?>) scope, n.getNameAsString(), arguments, context);
            } else {
                return ReflectionHandler.invokeMethod(scope, n.getNameAsString(), arguments, context);
            }
        }

        @Override
        public Object visit(NameExpr n, Void arg) {
            String name = n.getNameAsString();
            if (tagMap.containsKey(name)) {
                ObjectTag tag = tagMap.get(name);
                // ENHANCED: Handle JavaObjectTag unwrapping
                if (tag instanceof JavaObjectTag) {
                    return ((JavaObjectTag) tag).getJavaObject();
                }
                return tag != null ? tag.getJavaObject() : null;
            }

            // Попытка найти класс с полным именем (включая пакеты)
            Class<?> clazz = ReflectionHandler.getClassSilent(name);
            if (clazz != null) {
                return clazz;
            }

            // Если это может быть начало пакета, возвращаем как строку для дальнейшей обработки
            return name;
        }

        // Добавляем специальную обработку для точечной нотации пакетов
        @Override
        public Object visit(FieldAccessExpr n, Void arg) {
            Object scope = n.getScope().accept(this, arg);
            String fieldName = n.getNameAsString();

            // Если scope - это строка (потенциальный пакет), попробуем собрать полное имя класса
            if (scope instanceof String) {
                String fullClassName = scope + "." + fieldName;
                Class<?> clazz = ReflectionHandler.getClassSilent(fullClassName);
                if (clazz != null) {
                    return clazz;
                }
                // Если класс не найден, возвращаем составное имя для дальнейшей обработки
                return fullClassName;
            }

            if (scope == null) {
                throw new IllegalStateException("Field access '" + fieldName + "' has no scope (object or class).");
            }

            if (scope instanceof Class) {
                Object staticField = ReflectionHandler.getStaticField((Class<?>) scope, fieldName, context);
                if (staticField != null) {
                    return staticField;
                }
                Class<?> nestedClass = ReflectionHandler.getClassSilent(scope.toString().replace("class ", "") + "." + fieldName);
                if (nestedClass != null) {
                    return nestedClass;
                }
                throw new IllegalStateException("Cannot resolve static field or class: " + n.toString());
            } else {
                return ReflectionHandler.getField(scope, fieldName, context);
            }
        }

        @Override
        public Object visit(ObjectCreationExpr n, Void arg) {
            String className = n.getType().toString();
            List<ObjectTag> arguments = new ArrayList<>();
            for (Expression paramExpr : n.getArguments()) {
                Object paramValue = paramExpr.accept(this, arg);
                arguments.add(CoreUtilities.objectToTagForm(paramValue, context, false, false, true));
            }
            return ReflectionHandler.construct(className, arguments, context);
        }

        // --- Handle Literals ---
        @Override public Object visit(StringLiteralExpr n, Void arg) { return n.getValue(); }
        @Override public Object visit(IntegerLiteralExpr n, Void arg) { return n.asInt(); }
        @Override public Object visit(DoubleLiteralExpr n, Void arg) { return n.asDouble(); }
        @Override public Object visit(BooleanLiteralExpr n, Void arg) { return n.getValue(); }
        @Override public Object visit(LongLiteralExpr n, Void arg) { return n.asLong(); }
        @Override public Object visit(NullLiteralExpr n, Void arg) { return null; }
    }
}