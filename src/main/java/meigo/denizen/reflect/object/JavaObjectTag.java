package meigo.denizen.reflect.object;

import com.denizenscript.denizencore.objects.*;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.tags.Attribute;
import com.denizenscript.denizencore.tags.ObjectTagProcessor;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.visitor.GenericVisitorAdapter;
import meigo.denizen.reflect.util.ReflectionHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class JavaObjectTag implements ObjectTag, Adjustable {

    public final Object heldObject;
    public final boolean isStatic;
    private UUID uniqueId;

    private static final Map<UUID, JavaObjectTag> persistedInstances = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastAccessTimes = new ConcurrentHashMap<>();
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final long CLEANUP_INTERVAL_MINUTES = 5;
    private static final long EXPIRY_TIME_MINUTES = 5;

    private static ScheduledExecutorService cleanupExecutor;

    static {
        startCleanupTask();
    }

    private static void startCleanupTask() {
        if (cleanupExecutor == null) {
            cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "JavaObjectTag-Cleanup");
                t.setDaemon(true);
                return t;
            });

            cleanupExecutor.scheduleAtFixedRate(() -> {
                try {
                    cleanupExpiredInstances();
                } catch (Exception e) {
                    Debug.echoError("Error during JavaObjectTag cleanup: " + e.getMessage());
                }
            }, CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        }
    }

    private static void cleanupExpiredInstances() {
        long currentTime = System.currentTimeMillis();
        long expiryThreshold = currentTime - TimeUnit.MINUTES.toMillis(EXPIRY_TIME_MINUTES);
        int cleanedCount = 0;

        // --- ИЗМЕНЕНИЕ: Потокобезопасная очистка ---
        Iterator<Map.Entry<UUID, Long>> iterator = lastAccessTimes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() < expiryThreshold) {
                UUID expiredUUID = entry.getKey();
                iterator.remove(); // Атомарно удаляет из lastAccessTimes
                persistedInstances.remove(expiredUUID); // Удаляем из основной карты
                cleanedCount++;
            }
        }
        // --- КОНЕЦ ИЗМЕНЕНИЯ ---

        if (cleanedCount > 0) {
            Debug.log("Cleaned up " + cleanedCount + " expired JavaObjectTag instances");
        }
    }

    public static void shutdown() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            cleanupExecutor = null;
        }
    }

    public JavaObjectTag(Object object) {
        this.heldObject = object;
        this.isStatic = false;
    }

    public JavaObjectTag(Class<?> clazz) {
        this.heldObject = clazz;
        this.isStatic = true;
    }

    public void persist() {
        if (!isStatic && uniqueId == null) {
            uniqueId = UUID.randomUUID();
            persistedInstances.put(uniqueId, this);
            lastAccessTimes.put(uniqueId, System.currentTimeMillis());
        }
    }

    private void updateAccessTime() {
        if (uniqueId != null) {
            lastAccessTimes.put(uniqueId, System.currentTimeMillis());
        }
    }

    @Fetchable("java")
    public static JavaObjectTag valueOf(String string, TagContext context) {
        if (string == null) return null;
        if (string.startsWith("java@")) {
            string = string.substring("java@".length());
        }
        if (UUID_PATTERN.matcher(string).matches()) {
            // --- ИЗМЕНЕНИЕ: Исправлена ошибка NoSuchMethodError ---
            // Замена CoreUtilities.tryParseUUID на стандартный UUID.fromString
            UUID uuid;
            try {
                uuid = UUID.fromString(string);
            } catch (IllegalArgumentException e) {
                uuid = null;
            }
            // --- КОНЕЦ ИЗМЕНЕНИЯ ---
            if (uuid != null && persistedInstances.containsKey(uuid)) {
                JavaObjectTag instance = persistedInstances.get(uuid);
                instance.updateAccessTime();
                return instance;
            }
        }
        Class<?> clazz = ReflectionHandler.getClass(string, context);
        if (clazz != null) {
            return new JavaObjectTag(clazz);
        }
        return null;
    }

    public static boolean matches(String arg) {
        return arg.startsWith("java@");
    }

    public static final ObjectTagProcessor<JavaObjectTag> tagProcessor = new ObjectTagProcessor<>();

    public static void register() {
        tagProcessor.registerTag(ObjectTag.class, "invoke", (attribute, object) -> {
            String param = attribute.getParam();
            String methodName;
            List<ObjectTag> params;
            int bracketIndex = param.indexOf('(');
            if (bracketIndex > -1 && param.endsWith(")")) {
                methodName = param.substring(0, bracketIndex);
                String args = param.substring(bracketIndex + 1, param.length() - 1);
                params = parseArguments(args, attribute.context);
            } else {
                methodName = param;
                params = new ArrayList<>();
            }
            Object result;
            if (object.isStatic) {
                result = ReflectionHandler.invokeStaticMethod((Class<?>) object.heldObject, methodName, params, attribute.context);
            } else {
                object.updateAccessTime();
                result = ReflectionHandler.invokeMethod(object.heldObject, methodName, params, attribute.context);
            }
            return ReflectionHandler.wrapObject(result, attribute.context);
        });

        // NEW: Add invoke[] tag with full InvokeCommand functionality
        tagProcessor.registerTag(ObjectTag.class, "invoke", (attribute, object) -> {
            String invokeExpression = attribute.getParam();
            if (invokeExpression == null || invokeExpression.isEmpty()) {
                Debug.echoError(attribute.context, "invoke[] tag requires a Java expression parameter.");
                return null;
            }

            try {
                // Create a JavaInvoker instance to handle the invocation
                JavaInvoker invoker = new JavaInvoker(attribute.context);
                Object result = invoker.executeInvoke(invokeExpression, object);
                return ReflectionHandler.wrapObject(result, attribute.context);
            } catch (Exception e) {
                Debug.echoError(attribute.context, "Error in invoke[] tag: " + e.getMessage());
                return null;
            }
        });

        tagProcessor.registerTag(ObjectTag.class, "field", (attribute, object) -> {
            String fieldName = attribute.getParam();
            Object result;
            if (object.isStatic) {
                result = ReflectionHandler.getStaticField((Class<?>) object.heldObject, fieldName, attribute.context);
            } else {
                object.updateAccessTime();
                result = ReflectionHandler.getField(object.heldObject, fieldName, attribute.context);
            }
            return ReflectionHandler.wrapObject(result, attribute.context);
        });
        tagProcessor.registerTag(ElementTag.class, "class_name", (attribute, object) -> {
            Class<?> clazz = object.isStatic ? (Class<?>) object.heldObject : object.heldObject.getClass();
            return new ElementTag(clazz.getName());
        });
        tagProcessor.registerTag(ObjectTag.class, "interpret", (attribute, object) -> {
            if (object.isStatic) {
                return object;
            }
            object.updateAccessTime();
            return CoreUtilities.objectToTagForm(object.heldObject, attribute.context);
        });
        tagProcessor.registerTag(ElementTag.class, "identify", (attribute, object) -> new ElementTag(object.identify()));
        tagProcessor.registerTag(ElementTag.class, "debug", (attribute, object) -> new ElementTag(object.debuggable()));
        tagProcessor.registerMechanism("set_field", false, MapTag.class, (object, mechanism, map) -> {
            for (Map.Entry<StringHolder, ObjectTag> entry : map.entrySet()) {
                if (object.isStatic) {
                    ReflectionHandler.setStaticField((Class<?>) object.heldObject, entry.getKey().str, entry.getValue(), mechanism.context);
                } else {
                    object.updateAccessTime();
                    ReflectionHandler.setField(object.heldObject, entry.getKey().str, entry.getValue(), mechanism.context);
                }
            }
        });
    }

    private static List<ObjectTag> parseArguments(String args, TagContext context) {
        if (args.isEmpty()) {
            return Collections.emptyList();
        }
        return ListTag.valueOf(args, context).objectForms;
    }

    @Override public String getPrefix() { return "java"; }
    @Override public ObjectTag setPrefix(String prefix) { return this; }
    @Override public String debuggable() {
        if (isStatic) {
            return "<LG>java@<Y>" + ((Class<?>) heldObject).getName();
        }
        return "<LG>java@<Y>" + (uniqueId != null ? uniqueId : "unpersisted") + " <G>(" + heldObject.getClass().getName() + " instance)";
    }
    @Override public boolean isUnique() { return !isStatic; }
    @Override public String identify() {
        if (isStatic) {
            return "java@" + ((Class<?>) heldObject).getName();
        }
        persist();
        return "java@" + uniqueId.toString();
    }
    @Override public String identifySimple() { return identify(); }
    @Override public Object getJavaObject() { return heldObject; }
    @Override public ObjectTag getObjectAttribute(Attribute attribute) { return tagProcessor.getObjectAttribute(this, attribute); }
    @Override public void adjust(Mechanism mechanism) { tagProcessor.processMechanism(this, mechanism); }
    @Override public void applyProperty(Mechanism mechanism) { Debug.echoError("Cannot apply properties to a JavaObjectTag!"); }
    @Override public String toString() { return identify(); }

    // ================================================================================= //
    // ======================= JavaInvoker for invoke[] tag =========================== //
    // ================================================================================= //

    /**
     * Internal class to handle Java invocation logic for the invoke[] tag
     * This mirrors the functionality of InvokeCommand
     */
    public static class JavaInvoker {
        private final TagContext context;

        public JavaInvoker(TagContext context) {
            this.context = context;
        }

        public Object executeInvoke(String invokeExpression, JavaObjectTag contextObject) {
            try {
                // 1. Preprocess the string to replace Denizen objects with placeholders
                PreprocessedInvoke preprocessed = preprocess(invokeExpression);

                // 2. Parse the code into AST using JavaParser
                Expression expression = StaticJavaParser.parseExpression(preprocessed.code());

                // 3. Interpret AST with context object support
                AstInterpreter interpreter = new AstInterpreter(preprocessed.tagMap(), context, contextObject);
                return expression.accept(interpreter, null);

            } catch (Exception e) {
                Debug.echoError(context, "Failed to execute invoke expression: " + e.getMessage());
                return null;
            }
        }

        private PreprocessedInvoke preprocess(String rawString) {
            // First expand all standard tags <...>
            String initialString = TagManager.tag(rawString, context);
            // Replace argument separator '|' with comma
            initialString = initialString.replace("|", ", ");

            // Wrap strings with spaces in quotes
            initialString = wrapStringsWithSpaces(initialString);
            return null;
        }

        private String wrapStringsWithSpaces(String input) {
            if (input == null || input.isEmpty()) {
                return input;
            }

            StringBuilder result = new StringBuilder();
            char[] chars = input.toCharArray();
            int i = 0;

            while (i < chars.length) {
                char ch = chars[i];
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

        private int processMethodArguments(char[] chars, int start, StringBuilder result) {
            int i = start;
            boolean insideQuotes = false;
            char quoteChar = 0;
            boolean insideArgument = false;
            StringBuilder currentArgument = new StringBuilder();
            int parenDepth = 0;

            while (i < chars.length) {
                char ch = chars[i];

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

                if (insideQuotes) {
                    result.append(ch);
                    i++;
                    continue;
                }

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

                if (Character.isWhitespace(ch) && !insideArgument) {
                    result.append(ch);
                    i++;
                    continue;
                }

                if (!insideArgument) {
                    insideArgument = true;
                }

                currentArgument.append(ch);
                i++;
            }

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

        private boolean needsQuoting(String arg) {
            if (arg.isEmpty()) {
                return false;
            }

            if ((arg.startsWith("\"") && arg.endsWith("\"")) ||
                    (arg.startsWith("'") && arg.endsWith("'"))) {
                return false;
            }

            if (arg.contains(" ") || arg.contains("\t") || arg.contains("\n")) {
                return true;
            }

            if (isValidJavaLiteral(arg) || arg.startsWith("__denizen_obj_") || isValidJavaIdentifierChain(arg)) {
                return false;
            }

            return true;
        }

        private boolean isValidJavaLiteral(String str) {
            if (str.isEmpty()) return false;
            if ("true".equals(str) || "false".equals(str) || "null".equals(str)) {
                return true;
            }
            try {
                Double.parseDouble(str);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

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

        // Record for storing preprocessing results
        private record PreprocessedInvoke(String code, Map<String, ObjectTag> tagMap) {}

        // AST Interpreter with context object support
        private static class AstInterpreter extends GenericVisitorAdapter<Object, Void> {
            private final Map<String, ObjectTag> tagMap;
            private final TagContext context;
            private final JavaObjectTag contextObject;

            public AstInterpreter(Map<String, ObjectTag> tagMap, TagContext context, JavaObjectTag contextObject) {
                this.tagMap = tagMap;
                this.context = context;
                this.contextObject = contextObject;
            }

            @Override
            public Object visit(MethodCallExpr n, Void arg) {
                Object scope = n.getScope().map(s -> s.accept(this, arg)).orElse(contextObject != null ? contextObject.getJavaObject() : null);
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
                    return tag != null ? tag.getJavaObject() : null;
                }

                Class<?> clazz = ReflectionHandler.getClassSilent(name);
                if (clazz != null) {
                    return clazz;
                }

                return name;
            }

            @Override
            public Object visit(FieldAccessExpr n, Void arg) {
                Object scope = n.getScope().accept(this, arg);
                String fieldName = n.getNameAsString();

                if (scope instanceof String) {
                    String fullClassName = scope + "." + fieldName;
                    Class<?> clazz = ReflectionHandler.getClassSilent(fullClassName);
                    if (clazz != null) {
                        return clazz;
                    }
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

            @Override public Object visit(StringLiteralExpr n, Void arg) { return n.getValue(); }
            @Override public Object visit(IntegerLiteralExpr n, Void arg) { return n.asInt(); }
            @Override public Object visit(DoubleLiteralExpr n, Void arg) { return n.asDouble(); }
            @Override public Object visit(BooleanLiteralExpr n, Void arg) { return n.getValue(); }
            @Override public Object visit(LongLiteralExpr n, Void arg) { return n.asLong(); }
            @Override public Object visit(NullLiteralExpr n, Void arg) { return null; }
        }
    }
}