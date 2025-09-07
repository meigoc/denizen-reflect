package meigo.denizen.reflect.util;

import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.visitor.GenericVisitorAdapter;
import meigo.denizen.reflect.object.JavaObjectTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles parsing and interpretation of Java-like expressions within Denizen.
 * This class is responsible for:
 * 1. Pre-processing the expression string to replace Denizen tags with placeholders.
 * 2. Parsing the cleaned string into an Abstract Syntax Tree (AST) using JavaParser.
 * 3. Interpreting the AST to perform reflection calls via ReflectionHandler.
 */
public class JavaExpressionParser {

    private final TagContext context;

    public JavaExpressionParser(TagContext context) {
        this.context = context;
    }

    /**
     * Executes a Java expression.
     *
     * @param expressionString The raw expression string from the script.
     * @param contextObject    An optional context object (e.g., for tags like <java_object.invoke[...]>)
     * which acts as the implicit 'this'.
     * @return The result of the invocation.
     */
    public Object execute(String expressionString, Object contextObject) throws Exception {
        // 1. Preprocess to handle Denizen tags
        PreprocessedExpression preprocessed = preprocess(expressionString);

        // 2. Parse into an AST
        Expression ast = StaticJavaParser.parseExpression(preprocessed.code());

        // 3. Interpret the AST
        AstInterpreter interpreter = new AstInterpreter(preprocessed.tagMap(), context, Optional.ofNullable(contextObject));
        return ast.accept(interpreter, null);
    }

    private PreprocessedExpression preprocess(String rawString) {
        // First, parse standard Denizen tags <...>
        String processedString = TagManager.tag(rawString, context);
        // Replace Denizen argument separator with Java's
        processedString = processedString.replace("|", ", ");

        // Then, replace Denizen objects like p@, l@, java@ with placeholders
        return new DenizenObjectPlaceholderParser(context).parse(processedString);
    }

    /**
     * Stores the result of preprocessing.
     */
    public record PreprocessedExpression(String code, Map<String, ObjectTag> tagMap) {
    }

    // ================================================================================= //
    // ================== Denizen Object Placeholder Parser ============================ //
    // ================================================================================= //

    /**
     * Parses a string to replace Denizen-specific object notations (like "p@Player", "java@...")
     * with unique placeholders, returning the modified string and a map of placeholders to the original ObjectTags.
     */
    private static class DenizenObjectPlaceholderParser {

        private final Map<String, ObjectTag> tagMap = new HashMap<>();
        private final TagContext context;
        private int tagCounter = 0;
        private static final Pattern JAVA_OBJECT_PATTERN = Pattern.compile("\\bjava@([a-fA-F0-9-]+|[\\w.\\$]+)");

        public DenizenObjectPlaceholderParser(TagContext context) {
            this.context = context;
        }

        public PreprocessedExpression parse(String input) {
            String result = input;
            while (true) {
                String newResult = replaceLeafTags(result);
                if (newResult.equals(result)) {
                    break;
                }
                result = newResult;
            }
            return new PreprocessedExpression(result, tagMap);
        }

        private String replaceLeafTags(String input) {
            StringBuilder result = new StringBuilder(input);
            List<TagInfo> allTags = findTags(input);

            // Sort right-to-left to avoid index shifting during replacement
            allTags.sort((a, b) -> Integer.compare(b.start, b.start));

            for (TagInfo tag : allTags) {
                try {
                    ObjectTag parsed = ObjectFetcher.pickObjectFor(tag.fullTag, context);
                    // Ensure it's a real object, not just an ElementTag of the same string
                    if (parsed != null && !(parsed instanceof ElementTag && parsed.identify().equals(tag.fullTag))) {
                        String placeholder = "__denizen_obj_" + (tagCounter++);
                        tagMap.put(placeholder, parsed);
                        result.replace(tag.start, tag.end, placeholder);
                    }
                } catch (Exception ignored) {
                    // Ignore parsing errors, leave invalid tags as is
                }
            }
            return result.toString();
        }

        private List<TagInfo> findTags(String input) {
            List<TagInfo> tags = new ArrayList<>();
            // Regex to find "identifier@value" or "identifier@[value]"
            Pattern denizenTagPattern = Pattern.compile("([a-zA-Z0-9_]+)@(\\[.*?\\]|[^\\s(),.\\[\\]]+)");
            Matcher matcher = denizenTagPattern.matcher(input);
            while (matcher.find()) {
                // Heuristic to avoid matching email addresses
                if (matcher.start() > 0 && input.charAt(matcher.start() - 1) == '.') {
                    continue;
                }
                tags.add(new TagInfo(matcher.group(0), matcher.start(), matcher.end()));
            }

            // Also find expanded java objects like "java@uuid"
            Matcher javaMatcher = JAVA_OBJECT_PATTERN.matcher(input);
            while (javaMatcher.find()) {
                tags.add(new TagInfo(javaMatcher.group(0), javaMatcher.start(), javaMatcher.end()));
            }
            return tags;
        }

        private record TagInfo(String fullTag, int start, int end) {}
    }


    // ================================================================================= //
    // ===================== AST Interpreter =========================================== //
    // ================================================================================= //

    /**
     * Visits nodes of the JavaParser AST and executes corresponding reflection calls.
     */
    public static class AstInterpreter extends GenericVisitorAdapter<Object, Void> {

        private final Map<String, ObjectTag> tagMap;
        private final TagContext context;
        private final Optional<Object> contextObject;

        public AstInterpreter(Map<String, ObjectTag> tagMap, TagContext context, Optional<Object> contextObject) {
            this.tagMap = tagMap;
            this.context = context;
            this.contextObject = contextObject;
        }

        @Override
        public Object visit(MethodCallExpr n, Void arg) {
            Object scope = n.getScope().map(s -> s.accept(this, arg)).orElse(contextObject.orElse(null));
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
            }
            else {
                return ReflectionHandler.invokeMethod(scope, n.getNameAsString(), arguments, context);
            }
        }

        @Override
        public Object visit(FieldAccessExpr n, Void arg) {
            Object scope = n.getScope().accept(this, arg);
            String fieldName = n.getNameAsString();
            if (scope instanceof String) {
                String fullClassName = scope + "." + fieldName;
                Class<?> clazz = ReflectionHandler.getClassSilent(fullClassName);
                return clazz != null ? clazz : fullClassName;
            }
            if (scope == null) {
                throw new IllegalStateException("Field access '" + fieldName + "' has no scope (object or class).");
            }
            if (scope instanceof Class) {
                return ReflectionHandler.getStaticField((Class<?>) scope, fieldName, context);
            }
            else {
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

        @Override
        public Object visit(NameExpr n, Void arg) {
            String name = n.getNameAsString();
            if (tagMap.containsKey(name)) {
                ObjectTag tag = tagMap.get(name);
                if (tag instanceof JavaObjectTag) {
                    return ((JavaObjectTag) tag).getJavaObject();
                }
                return tag != null ? tag.getJavaObject() : null;
            }
            Class<?> clazz = ReflectionHandler.getClassSilent(name);
            return clazz != null ? clazz : name;
        }

        // --- Handle Literals ---
        @Override public Object visit(StringLiteralExpr n, Void arg) { return n.getValue(); }
        @Override public Object visit(IntegerLiteralExpr n, Void arg) { return Integer.valueOf(n.getValue()); }
        @Override public Object visit(DoubleLiteralExpr n, Void arg) { return Double.valueOf(n.getValue()); }
        @Override public Object visit(BooleanLiteralExpr n, Void arg) { return n.getValue(); }
        @Override public Object visit(LongLiteralExpr n, Void arg) { return Long.valueOf(n.getValue().replace("L", "")); }
        @Override public Object visit(NullLiteralExpr n, Void arg) { return null; }
    }
}