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
import java.util.regex.MatchResult;
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
        PreprocessedExpression preprocessed = preprocess(expressionString);
        Expression ast = StaticJavaParser.parseExpression(preprocessed.code());
        AstInterpreter interpreter = new AstInterpreter(preprocessed.tagMap(), context, Optional.ofNullable(contextObject));
        return ast.accept(interpreter, null);
    }

    private PreprocessedExpression preprocess(String rawString) {
        String processedString = TagManager.tag(rawString, context);
        return new DenizenObjectPlaceholderParser(context).parse(processedString);
    }

    public record PreprocessedExpression(String code, Map<String, ObjectTag> tagMap) {
    }

    private static class DenizenObjectPlaceholderParser {

        private final Map<String, ObjectTag> tagMap = new HashMap<>();
        private final TagContext context;
        private int tagCounter = 0;
        private static final Pattern JAVA_OBJECT_PATTERN = Pattern.compile("\\bjava@([a-fA-F0-9-]+|[\\w.\\$]+)");
        private static final Pattern OTHER_OBJECT_PATTERN = Pattern.compile("([a-zA-Z0-9_]+)@(\\[.*?\\]|[^\\s(),.\\[\\]]+)");


        public DenizenObjectPlaceholderParser(TagContext context) {
            this.context = context;
        }

        public PreprocessedExpression parse(String input) {
            String afterJavaPass = resolveAndReplaceJavaTags(input);
            String afterOtherPass = replaceOtherTags(afterJavaPass);
            return new PreprocessedExpression(afterOtherPass, tagMap);
        }

        private String resolveAndReplaceJavaTags(String input) {
            StringBuilder sb = new StringBuilder(input);
            Matcher matcher = JAVA_OBJECT_PATTERN.matcher(sb);
            List<MatchResult> matches = new ArrayList<>();
            while (matcher.find()) {
                matches.add(matcher.toMatchResult());
            }

            for (int i = matches.size() - 1; i >= 0; i--) {
                MatchResult match = matches.get(i);
                String content = match.group(1);
                String currentPath = content;
                Class<?> foundClass = null;

                while (true) {
                    if (currentPath.isEmpty()) break;
                    Class<?> clazz = ReflectionHandler.getClassSilent(currentPath);
                    if (clazz != null) {
                        foundClass = clazz;
                        break;
                    }
                    int lastDot = currentPath.lastIndexOf('.');
                    if (lastDot == -1) break;
                    currentPath = currentPath.substring(0, lastDot);
                }

                if (foundClass != null) {
                    String placeholder = "__denizen_java_" + (tagCounter++);
                    tagMap.put(placeholder, new JavaObjectTag(foundClass));
                    int replaceStart = match.start();
                    int replaceEnd = match.start() + "java@".length() + currentPath.length();
                    sb.replace(replaceStart, replaceEnd, placeholder);
                }
            }
            return sb.toString();
        }

        private String replaceOtherTags(String input) {
            StringBuilder sb = new StringBuilder(input);
            List<MatchResult> matches = new ArrayList<>();
            Matcher matcher = OTHER_OBJECT_PATTERN.matcher(sb);
            while (matcher.find()) {
                matches.add(matcher.toMatchResult());
            }

            for (int i = matches.size() - 1; i >= 0; i--) {
                MatchResult match = matches.get(i);
                //if (match.group(1).equals("java")) {
                //    continue;
                //}
                if (match.start() > 0 && sb.charAt(match.start() - 1) == '.') {
                    continue;
                }
                String fullTag = match.group(0);
                try {
                    ObjectTag parsed = ObjectFetcher.pickObjectFor(fullTag, context);
                    if (parsed != null && !(parsed instanceof ElementTag && parsed.identify().equals(fullTag))) {
                        String placeholder = "__denizen_other_" + (tagCounter++);
                        tagMap.put(placeholder, parsed);
                        sb.replace(match.start(), match.end(), placeholder);
                    }
                } catch (Exception ignored) {
                }
            }
            return sb.toString();
        }
    }

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
                ObjectTag denizenArg;
                if (paramValue instanceof ObjectTag) {
                    denizenArg = (ObjectTag) paramValue;
                } else if (paramValue == null || paramValue instanceof String || paramValue instanceof Number || paramValue instanceof Boolean) {
                    denizenArg = CoreUtilities.objectToTagForm(paramValue, context, false, false, true);
                } else {
                    denizenArg = new JavaObjectTag(paramValue);
                }
                arguments.add(denizenArg);
            }
            if (scope instanceof Class) {
                return ReflectionHandler.invokeStaticMethod((Class<?>) scope, n.getNameAsString(), arguments, context);
            } else {
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
        @Override
        public Object visit(StringLiteralExpr n, Void arg) {
            return n.getValue();
        }

        @Override
        public Object visit(IntegerLiteralExpr n, Void arg) {
            return Integer.valueOf(n.getValue());
        }

        @Override
        public Object visit(DoubleLiteralExpr n, Void arg) {
            return Double.valueOf(n.getValue());
        }

        @Override
        public Object visit(BooleanLiteralExpr n, Void arg) {
            return n.getValue();
        }

        @Override
        public Object visit(LongLiteralExpr n, Void arg) {
            return Long.valueOf(n.getValue().replace("L", ""));
        }

        @Override
        public Object visit(NullLiteralExpr n, Void arg) {
            return null;
        }
    }
}