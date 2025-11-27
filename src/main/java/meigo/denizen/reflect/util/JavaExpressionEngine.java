package meigo.denizen.reflect.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.denizenscript.denizen.tags.BukkitTagContext;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.JavaReflectedObjectTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.tags.core.EscapeTagUtil;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.DenizenTagFinder;

public final class JavaExpressionEngine {

    private JavaExpressionEngine() {
    }

    public static final JavaExpressionEngine INSTANCE = new JavaExpressionEngine();


    public static void importClass(String path, String className, String alias) throws ClassNotFoundException {
        INSTANCE.doImportClass(path, className, alias);
    }

    public static void clearAllImports() {
        INSTANCE.importContexts.clear();
    }

    public static String unescape(String expression) {
        expression = expression.replace("&com", ",");
        return EscapeTagUtil.unEscape(expression);
    }
    public static Object execute(String expression, ScriptEntry scriptEntry) {
        String path = scriptEntry.getScript().getContainer().getRelativeFileName();
        path = path.substring(path.indexOf("scripts/") + "scripts/".length());
        for (String string : DenizenTagFinder.findTags(expression)) {
            expression = expression.replace(string, EscapeTagUtil.escape(string).replace(",", "&com"));
        }
        try {
            return INSTANCE.doExecute(expression, scriptEntry, path);
        }
        catch (Throwable t) {

            if (t instanceof NullPointerException || t.getMessage() == null) {
                return null;
            }
            Debug.echoError("Error evaluating Java expression: " + unescape(expression));
            Debug.echoError(t.getMessage());
            return null;
        }
    }

    public static boolean isSimple(Object obj, TagContext context) {
        if (obj == null) {
            return false;
        }
        if (obj instanceof ObjectTag) {
            return true;
        }
        if (obj instanceof String
                || obj instanceof Number
                || obj instanceof Boolean
                || obj instanceof List
                || obj instanceof Map) {
            return true;
        }
        try {
            if (!(CoreUtilities.objectToTagForm(obj, context).getJavaObject() instanceof String)) {
                return true;
            }
        }
        catch (Throwable t) {
            return false;
        }
        return false;
    }

    public static ObjectTag wrapObject(Object result, TagContext context) {
        if (isSimple(result, context)) {
            return CoreUtilities.objectToTagForm(result, context);
        }
        else {
            return new JavaReflectedObjectTag(result);
        }
    }

    private final Map<String, ImportContext> importContexts = new ConcurrentHashMap<>();

    private void doImportClass(String path, String className, String alias) throws ClassNotFoundException {
        String keyPath = (path == null || path.isEmpty()) ? "<global>" : path;
        Class<?> cls = Class.forName(className, true, LibraryLoader.getClassLoader());
        ImportContext ctx = importContexts.computeIfAbsent(keyPath, p -> new ImportContext());
        String key = (alias == null || alias.isEmpty()) ? cls.getSimpleName() : alias;
        ctx.addImport(key, cls);
    }

    private Object doExecute(String expression, ScriptEntry scriptEntry, String path) throws Throwable {
        if (expression == null) {
            return null;
        }

        expression = expression.trim();


        if (expression.length() >= 2 && expression.charAt(0) == '%' && expression.charAt(expression.length() - 1) == '%') {
            if (expression.length() == 2) {
                return "%";
            }

            String inner = expression.substring(1, expression.length() - 1).trim();
            return doExecute(inner, scriptEntry, path);
        }


        String keyPath = (path == null || path.isEmpty()) ? "<global>" : path;
        ImportContext imports = importContexts.getOrDefault(keyPath, ImportContext.EMPTY);
        EvalContext ctx = new EvalContext(imports, scriptEntry);


        if (expression.indexOf('%') >= 0) {
            Object templated = evalTemplate(expression, ctx);
            if (isSimple(templated, scriptEntry.context)) {
                return CoreUtilities.objectToTagForm(templated, scriptEntry.context);
            }
            else {
                return new JavaReflectedObjectTag(templated);
            }
        }

        Parser parser = new Parser(expression);
        Node root = parser.parse();
        Object result = root.eval(ctx);

        return wrapObject(result, scriptEntry.context);
    }





    private Object evalTemplate(String template, EvalContext ctx) throws Throwable {
        StringBuilder out = new StringBuilder();
        int idx = 0;
        int len = template.length();

        while (idx < len) {
            int start = template.indexOf('%', idx);
            if (start < 0) {
                out.append(template, idx, len);
                break;
            }

            if (start + 1 < len && template.charAt(start + 1) == '%') {
                out.append(template, idx, start).append('%');
                idx = start + 2;
                continue;
            }

            int end = template.indexOf('%', start + 1);
            if (end < 0) {
                out.append(template, idx, len);
                break;
            }

            out.append(template, idx, start);

            String inner = template.substring(start + 1, end).trim();
            if (!inner.isEmpty()) {
                if (inner.startsWith("<") && inner.endsWith(">")) {
                    inner = inner.substring(1, inner.length() - 1);
                }

                Parser p = new Parser(inner);
                Node expr = p.parse();
                Object val = expr.eval(ctx);

                if (val instanceof ObjectTag) {
                    val = ((ObjectTag) val).getJavaObject();
                }
                out.append(val == null ? "null" : String.valueOf(val));
            }

            idx = end + 1;
        }
        return out.toString();
    }


    public static final class ImportContext {
        static final ImportContext EMPTY = new ImportContext(Collections.emptyMap());

        public final Map<String, Class<?>> imports;

        ImportContext() {
            this.imports = new ConcurrentHashMap<>();
        }

        ImportContext(Map<String, Class<?>> imports) {
            this.imports = imports;
        }

        void addImport(String alias, Class<?> cls) {
            imports.put(alias, cls);
        }

        Class<?> resolveType(String name) {
            Class<?> cls = imports.get(name);
            if (cls != null) {
                return cls;
            }
            for (Class<?> c : imports.values()) {
                if (c.getSimpleName().equals(name)) {
                    return c;
                }
            }
            return null;
        }
    }

    private static final class EvalContext {
        final ImportContext imports;
        final ScriptEntry scriptEntry;

        EvalContext(ImportContext imports, ScriptEntry scriptEntry) {
            this.imports = imports;
            this.scriptEntry = scriptEntry;
        }
    }

    private static final class Token {
        final TokenType type;
        final String lexeme;
        final Object literal;

        Token(TokenType type, String lexeme, Object literal) {
            this.type = type;
            this.lexeme = lexeme;
            this.literal = literal;
        }
    }

    private enum TokenType {
        LEFT_PAREN, RIGHT_PAREN,
        LEFT_BRACKET, RIGHT_BRACKET,
        COMMA, DOT,
        IDENTIFIER, NUMBER, STRING,
        NEW, TRUE, FALSE, NULL,
        EOF
    }

    private static final class Lexer {
        private final String source;
        private final int length;
        private int start = 0;
        private int current = 0;
        private final List<Token> tokens = new ArrayList<>();

        Lexer(String source) {
            this.source = source;
            this.length = source.length();
        }

        List<Token> tokenize() {
            while (!isAtEnd()) {
                start = current;
                scanToken();
            }
            tokens.add(new Token(TokenType.EOF, "", null));
            return tokens;
        }

        private boolean isAtEnd() {
            return current >= length;
        }

        private char advance() {
            return source.charAt(current++);
        }

        private char peek() {
            return isAtEnd() ? '\0' : source.charAt(current);
        }

        private char peekNext() {
            return (current + 1 >= length) ? '\0' : source.charAt(current + 1);
        }

        private boolean match(char expected) {
            if (isAtEnd() || source.charAt(current) != expected) {
                return false;
            }
            current++;
            return true;
        }

        private void addToken(TokenType type) {
            addToken(type, null);
        }

        private void addToken(TokenType type, Object literal) {
            String text = source.substring(start, current);
            tokens.add(new Token(type, text, literal));
        }

        private void scanToken() {
            char c = advance();
            switch (c) {
                case '(':
                    addToken(TokenType.LEFT_PAREN);
                    return;
                case ')':
                    addToken(TokenType.RIGHT_PAREN);
                    return;
                case '[':
                    addToken(TokenType.LEFT_BRACKET);
                    return;
                case ']':
                    addToken(TokenType.RIGHT_BRACKET);
                    return;
                case ',':
                    addToken(TokenType.COMMA);
                    return;
                case '.':
                    addToken(TokenType.DOT);
                    return;
                case ' ':
                case '\r':
                case '\t':
                case '\n':
                    return;
                case '"':
                    string();
                    return;
                default:
                    if (isDigit(c)) {
                        number();
                        return;
                    }
                    if (isAlpha(c)) {
                        identifier();
                        return;
                    }
                    return;
            }
        }

        private boolean isAlpha(char c) {
            return (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || c == '_' || c == '$'
                    || c == '@' || c == '-' || c == '|';
        }

        @SuppressWarnings("unused")
        private boolean isAlphaNumeric(char c) {
            return isAlpha(c) || isDigit(c);
        }

        private void string() {
            while (!isAtEnd() && peek() != '"') {
                if (peek() == '\\' && current + 1 < length) {
                    current += 2;
                }
                else {
                    advance();
                }
            }
            if (isAtEnd()) {
                throw new RuntimeException("Unterminated string literal");
            }
            advance();

            String raw = source.substring(start + 1, current - 1);
            String value = raw.replace("\\\"", "\"").replace("\\\\", "\\");
            addToken(TokenType.STRING, value);
        }

        private void number() {
            while (isDigit(peek())) {
                advance();
            }
            if (peek() == '.' && isDigit(peekNext())) {
                advance();
                while (isDigit(peek())) {
                    advance();
                }
            }
            String text = source.substring(start, current);
            Object literal;
            if (text.indexOf('.') >= 0) {
                literal = Double.parseDouble(text);
            }
            else {
                try {
                    long l = Long.parseLong(text);
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                        literal = (int) l;
                    }
                    else {
                        literal = l;
                    }
                }
                catch (NumberFormatException e) {
                    literal = Double.parseDouble(text);
                }
            }
            addToken(TokenType.NUMBER, literal);
        }

        private void identifier() {
            while (!isAtEnd()) {
                char c = peek();
                if (c == '.' || c == '(' || c == ')' ||
                        c == '[' || c == ']' ||
                        c == ',' || Character.isWhitespace(c)) {
                    break;
                }
                advance();
            }

            String text = source.substring(start, current);

            switch (text) {
                case "new":
                    addToken(TokenType.NEW);
                    return;
                case "true":
                    addToken(TokenType.TRUE, Boolean.TRUE);
                    return;
                case "false":
                    addToken(TokenType.FALSE, Boolean.FALSE);
                    return;
                case "null":
                    addToken(TokenType.NULL, null);
                    return;
                default:
                    addToken(TokenType.IDENTIFIER);
                    return;
            }
        }

        private boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }


    private static final class Parser {
        private final List<Token> tokens;
        private int current = 0;

        Parser(String source) {
            Lexer lexer = new Lexer(source);
            this.tokens = lexer.tokenize();
        }

        Node parse() {
            return expression();
        }


        private Node expression() {
            return postfix();
        }

        private Node postfix() {
            Node node = primary();
            while (true) {
                if (match(TokenType.DOT)) {
                    Token nameTok = consume(TokenType.IDENTIFIER, "Expected identifier after '.'");
                    String name = nameTok.lexeme;
                    if (match(TokenType.LEFT_PAREN)) {
                        List<Node> args = argumentList();
                        node = new MethodCallNode(node, name, args);
                    }
                    else {
                        node = new FieldAccessNode(node, name);
                    }
                    continue;
                }
                if (match(TokenType.LEFT_BRACKET)) {
                    String inside = readBracketRawString();
                    node = new BracketInitNode(node, inside);
                    continue;
                }
                break;
            }
            return node;
        }

        private String readBracketRawString() {
            StringBuilder sb = new StringBuilder();
            int depth = 1;

            while (!isAtEnd() && depth > 0) {
                Token t = advance();
                if (t.type == TokenType.LEFT_BRACKET) {
                    depth++;
                }
                else if (t.type == TokenType.RIGHT_BRACKET) {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                sb.append(t.lexeme);
            }

            if (depth > 0) {
                throw error(previous(), "Unterminated '[' literal");
            }

            int s = 0, e = sb.length();
            while (s < e && Character.isWhitespace(sb.charAt(s))) s++;
            while (e > s && Character.isWhitespace(sb.charAt(e - 1))) e--;
            return sb.substring(s, e);
        }

        private List<Node> argumentList() {
            List<Node> args = new ArrayList<>();
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    args.add(expression());
                }
                while (match(TokenType.COMMA));
            }
            consume(TokenType.RIGHT_PAREN, "Expected ')' after arguments");
            return args;
        }

        private Node primary() {
            if (match(TokenType.NUMBER)) {
                return new LiteralNode(previous().literal);
            }
            if (match(TokenType.STRING)) {
                return new LiteralNode(previous().literal);
            }
            if (match(TokenType.TRUE)) {
                return new LiteralNode(Boolean.TRUE);
            }
            if (match(TokenType.FALSE)) {
                return new LiteralNode(Boolean.FALSE);
            }
            if (match(TokenType.NULL)) {
                return new LiteralNode(null);
            }
            if (match(TokenType.NEW)) {
                Token typeTok = consume(TokenType.IDENTIFIER, "Expected type name after 'new'");
                String typeName = typeTok.lexeme;
                consume(TokenType.LEFT_PAREN, "Expected '(' after type name");
                List<Node> args = argumentList();
                return new NewNode(typeName, args);
            }
            if (match(TokenType.IDENTIFIER)) {
                return new VariableNode(previous().lexeme);
            }
            if (match(TokenType.LEFT_PAREN)) {
                Node expr = expression();
                consume(TokenType.RIGHT_PAREN, "Expected ')' after expression");
                return expr;
            }
            throw error(peek(), "Unexpected token: " + peek().lexeme);
        }

        private boolean match(TokenType... types) {
            for (TokenType t : types) {
                if (check(t)) {
                    advance();
                    return true;
                }
            }
            return false;
        }

        private Token consume(TokenType type, String message) {
            if (check(type)) {
                return advance();
            }
            throw error(peek(), message);
        }

        private boolean check(TokenType type) {
            if (isAtEnd()) {
                return false;
            }
            return peek().type == type;
        }

        private Token advance() {
            if (!isAtEnd()) {
                current++;
            }
            return previous();
        }

        private boolean isAtEnd() {
            return peek().type == TokenType.EOF;
        }

        private Token peek() {
            return tokens.get(current);
        }

        private Token previous() {
            return tokens.get(current - 1);
        }

        private RuntimeException error(Token token, String message) {
            return new RuntimeException(message);
        }
    }

    private abstract static class Node {
        abstract Object eval(EvalContext ctx) throws Throwable;
    }

    private static final class LiteralNode extends Node {
        private final Object value;

        LiteralNode(Object value) {
            this.value = value;
        }

        @Override
        Object eval(EvalContext ctx) {
            return value;
        }
    }

    private static final class BracketInitNode extends Node {
        private final Node target;
        private final String inside;

        BracketInitNode(Node target, String inside) {
            this.target = target;
            this.inside = inside == null ? "" : inside.trim();
        }

        @Override
        Object eval(EvalContext ctx) throws Throwable {
            Object base = target.eval(ctx);


            if (base instanceof String) {
                String name = (String) base;
                String full = name + "[" + inside + "]";

                try {
                    Class<?> cls = Class.forName(name, true, LibraryLoader.getClassLoader());
                    return evalJavaBracketLiteral(cls);
                }
                catch (ClassNotFoundException ex) {
                    try {
                        ObjectTag tag = ObjectFetcher.pickObjectFor(full, ctx.scriptEntry.context);
                        return tag.getJavaObject();
                    }
                    catch (Exception ex2) {
                        return full;
                    }
                }
            }

            if (base instanceof Class<?>) {
                Class<?> cls = (Class<?>) base;
                return evalJavaBracketLiteral(cls);
            }

            throw new RuntimeException("Bracket literal requires class name or string on the left, got: " + base);
        }

        private Object evalJavaBracketLiteral(Class<?> cls) throws Throwable {
            if (inside.isEmpty()) {
                Constructor<?> ctor = findNoArgCtor(cls);
                if (ctor == null) {
                    throw new RuntimeException("No-arg constructor not found for " + cls.getName());
                }
                ctor.setAccessible(true);
                return ctor.newInstance();
            }

            boolean named = inside.contains("=") || inside.contains(":");

            if (named) {
                Object obj = instantiateOrThrow(cls);

                for (String part : splitTopLevel(inside)) {
                    String[] kv = splitOnce(part, "[=:]");
                    if (kv == null) {
                        continue;
                    }
                    String fieldName = kv[0].trim();
                    String raw = kv[1].trim();

                    Field f = findFieldDeep(cls, fieldName);
                    if (f == null) {
                        throw new RuntimeException("Field '" + fieldName + "' not found in " + cls.getName());
                    }
                    f.setAccessible(true);
                    Object val = convertFromString(raw, f.getType());
                    f.set(obj, val);
                }
                return obj;
            }
            else {
                String[] parts = splitTopLevel(inside);
                for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                    Class<?>[] pt = ctor.getParameterTypes();
                    if (pt.length != parts.length) {
                        continue;
                    }
                    try {
                        Object[] args = new Object[pt.length];
                        for (int i = 0; i < pt.length; i++) {
                            args[i] = convertFromString(parts[i].trim(), pt[i]);
                        }
                        ctor.setAccessible(true);
                        return ctor.newInstance(args);
                    }
                    catch (Throwable ignore) {
                    }
                }
                throw new RuntimeException("No matching constructor found for " + cls.getName() + " with args: " + inside);
            }
        }

        private static Constructor<?> findNoArgCtor(Class<?> cls) {
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                if (c.getParameterCount() == 0) return c;
            }
            return null;
        }

        private static Object instantiateOrThrow(Class<?> cls) throws Throwable {
            Constructor<?> ctor = findNoArgCtor(cls);
            if (ctor == null) {
                throw new RuntimeException("No-arg constructor required for named initialization of " + cls.getName());
            }
            ctor.setAccessible(true);
            return ctor.newInstance();
        }

        private static String[] splitTopLevel(String s) {
            List<String> out = new ArrayList<>();
            int start = 0, depthPar = 0;
            boolean inStr = false;
            char strQ = 0;

            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inStr) {
                    if (c == strQ && s.charAt(i - 1) != '\\') inStr = false;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    inStr = true;
                    strQ = c;
                    continue;
                }
                if (c == '(') depthPar++;
                else if (c == ')') depthPar = Math.max(0, depthPar - 1);
                else if (depthPar == 0 && (c == ',' || c == ';')) {
                    out.add(s.substring(start, i).trim());
                    start = i + 1;
                }
            }
            out.add(s.substring(start).trim());
            return out.toArray(new String[0]);
        }

        private static String[] splitOnce(String s, String regex) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '=' || c == ':') {
                    return new String[]{s.substring(0, i), s.substring(i + 1)};
                }
            }
            return null;
        }

        private static Field findFieldDeep(Class<?> type, String name) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    return c.getDeclaredField(name);
                }
                catch (NoSuchFieldException ignored) {
                }
            }
            return null;
        }

        private static Object convertFromString(String text, Class<?> toType) throws Exception {
            if (text == null) return null;
            text = text.trim();

            if (text.length() >= 2 &&
                    ((text.startsWith("\"") && text.endsWith("\"")) ||
                            (text.startsWith("'") && text.endsWith("'")))) {
                text = text.substring(1, text.length() - 1);
            }

            if (toType == Boolean.class || toType == boolean.class) {
                if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("yes") || text.equals("1")) return true;
                if (text.equalsIgnoreCase("false") || text.equalsIgnoreCase("no") || text.equals("0")) return false;
                return false;
            }
            if (toType == String.class) return text;

            if (toType == int.class || toType == Integer.class) return Integer.parseInt(text);
            if (toType == long.class || toType == Long.class) return Long.parseLong(text);
            if (toType == double.class || toType == Double.class) return Double.parseDouble(text);
            if (toType == float.class || toType == Float.class) return Float.parseFloat(text);
            if (toType == short.class || toType == Short.class) return Short.parseShort(text);
            if (toType == byte.class || toType == Byte.class) return Byte.parseByte(text);
            if (toType == char.class || toType == Character.class)
                return text.isEmpty() ? '\0' : text.charAt(0);

            if (toType.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Enum<?> e = Enum.valueOf((Class<Enum>) toType, text);
                return e;
            }

            return text;
        }
    }

    private static final class VariableNode extends Node {

        private final String originalName;

        VariableNode(String name) {
            this.originalName = name;
        }

        @Override
        Object eval(EvalContext ctx) throws Throwable {
            String name = EscapeTagUtil.unEscape(this.originalName);


            if (!name.contains("@")) {
                Class<?> imported = ctx.imports.resolveType(name);
                if (imported != null) {
                    return imported;
                }
                try {
                    return Class.forName(name, true, LibraryLoader.getClassLoader());
                }
                catch (ClassNotFoundException ignored) {
                    if (name.matches("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$")) {
                    }
                    else {
                    }
                }
            }

            if (name.equals("player")) {
                try {
                    return ((BukkitTagContext) ctx.scriptEntry.context).player.getJavaObject();
                }
                catch (Exception ignored1) {
                }
            }
            if (name.equals("entry")) {
                return ctx.scriptEntry;
            }

            try {
                if (ctx.scriptEntry != null && ctx.scriptEntry.getResidingQueue() != null
                        && ctx.scriptEntry.getResidingQueue().definitions.containsKey(name)) {
                    return ctx.scriptEntry.getResidingQueue().getDefinitionObject(name).getJavaObject();
                }
                if (ctx.scriptEntry != null && ctx.scriptEntry.getContext() != null
                        && ctx.scriptEntry.getContext().contextSource != null
                        && ctx.scriptEntry.getContext().contextSource.getContext(name) != null) {
                    return ctx.scriptEntry.getContext().contextSource.getContext(name).getJavaObject();
                }
            }
            catch (Exception ignored2) {
            }

            try {
                ObjectTag result = ObjectFetcher.pickObjectFor(name, ctx.scriptEntry.context);
                return result.getJavaObject();
            }
            catch (Exception ignored3) {
            }

            return name;
        }

        private static Object parseLiteral(Class<?> type, String text) {
            if (text == null) {
                return null;
            }

            if (text.length() >= 2 && (
                    (text.startsWith("\"") && text.endsWith("\"")) ||
                            (text.startsWith("'") && text.endsWith("'")))) {
                text = text.substring(1, text.length() - 1);
            }

            try {
                if (type == String.class) return text;
                if (type == int.class || type == Integer.class) return Integer.parseInt(text);
                if (type == long.class || type == Long.class) return Long.parseLong(text);
                if (type == double.class || type == Double.class) return Double.parseDouble(text);
                if (type == float.class || type == Float.class) return Float.parseFloat(text);
                if (type == short.class || type == Short.class) return Short.parseShort(text);
                if (type == byte.class || type == Byte.class) return Byte.parseByte(text);
                if (type == boolean.class || type == Boolean.class) {
                    if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("yes") || text.equals("1")) return true;
                    if (text.equalsIgnoreCase("false") || text.equalsIgnoreCase("no") || text.equals("0")) return false;
                }
                if (type == char.class || type == Character.class) {
                    return text.isEmpty() ? '\0' : text.charAt(0);
                }

                if (Number.class.isAssignableFrom(type)) {
                    if (text.contains(".")) {
                        return Double.parseDouble(text);
                    }
                    return Integer.parseInt(text);
                }

                if (type.isEnum()) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Enum<?> val = Enum.valueOf((Class<Enum>) type, text);
                    return val;
                }
            }
            catch (Exception ignored) {
            }

            return text;
        }
    }

    private static final class NewNode extends Node {
        private final String typeName;
        private final List<Node> args;

        NewNode(String typeName, List<Node> args) {
            this.typeName = typeName;
            this.args = args;
        }

        @Override
        Object eval(EvalContext ctx) throws Throwable {
            Class<?> type = ctx.imports.resolveType(typeName);
            if (type == null) {
                try {
                    type = Class.forName(typeName, true, LibraryLoader.getClassLoader());
                }
                catch (ClassNotFoundException e) {
                    throw new RuntimeException("Unknown type: " + typeName, e);
                }
            }
            Object[] values = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                values[i] = args.get(i).eval(ctx);
            }
            return ReflectionUtil.construct(type, values);
        }
    }

    private static final class FieldAccessNode extends Node {
        private final Node targetNode;
        private final String fieldName;

        FieldAccessNode(Node targetNode, String fieldName) {
            this.targetNode = targetNode;
            this.fieldName = fieldName;
        }

        @Override
        Object eval(EvalContext ctx) throws Throwable {
            Object base = targetNode.eval(ctx);

            if (base instanceof String) {
                String left = (String) base;
                String full = left + "." + fieldName;
                try {
                    return Class.forName(full, true, LibraryLoader.getClassLoader());
                }
                catch (ClassNotFoundException ignored) {
                    return full;
                }
            }

            if (base instanceof Class<?>) {
                Class<?> cls = (Class<?>) base;

                if (fieldName.startsWith("[") && fieldName.endsWith("]")) {
                    String inside = fieldName.substring(1, fieldName.length() - 1);
                    Object obj;
                    try {
                        Constructor<?> ctor = cls.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        obj = ctor.newInstance();
                    }
                    catch (NoSuchMethodException ignored) {
                        obj = ReflectionUtil.construct(cls, new Object[0]);
                    }

                    String[] parts = inside.split("[,;]");
                    for (String part : parts) {
                        String[] kv = part.split("[=:]");
                        if (kv.length != 2) continue;
                        String fName = kv[0].trim();
                        String rawVal = kv[1].trim();

                        Field f = ReflectionUtil.findFieldDeep(cls, fName);
                        if (f == null) continue;
                        f.setAccessible(true);
                        Object val = ReflectionUtil.adaptArgument(f.getType(),
                                VariableNode.parseLiteral(f.getType(), rawVal));
                        f.set(obj, val);
                    }
                    return obj;
                }

                try {
                    return ReflectionUtil.getField(cls, fieldName);
                }
                catch (Throwable ex) {
                    throw new RuntimeException("No static field '" + fieldName + "' in " + cls.getName());
                }
            }

            if (base != null) {
                try {
                    return ReflectionUtil.getField(base, fieldName);
                }
                catch (Throwable ex) {
                    throw new RuntimeException("No such field '" + fieldName + "' in " + base.getClass().getName());
                }
            }

            throw new RuntimeException("Cannot access field '" + fieldName + "' on null target");
        }
    }

    private static final class MethodCallNode extends Node {
        private final Node target;
        private final String methodName;
        private final List<Node> args;

        MethodCallNode(Node target, String methodName, List<Node> args) {
            this.target = target;
            this.methodName = methodName;
            this.args = args;
        }

        @Override
        Object eval(EvalContext ctx) throws Throwable {
            Object obj = target.eval(ctx);
            Object[] values = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                Object arg = args.get(i).eval(ctx);
                if (arg instanceof String) {
                    arg = unescape((String) arg);
                }
                values[i] = arg;
            }
            return ReflectionUtil.invokeMethod(obj, methodName, values);
        }
    }


    public static final class ReflectionUtil {
        private static final MethodHandles.Lookup ROOT_LOOKUP = MethodHandles.lookup();

        static Object construct(Class<?> type, Object[] args) throws Throwable {
            Constructor<?> ctor = findConstructorDeep(type, args);
            if (ctor == null) {
                throw new RuntimeException("No suitable constructor found for " + type.getName()
                        + " with " + args.length + " args");
            }

            MethodHandles.Lookup lookup;
            if (Modifier.isPublic(ctor.getModifiers()) && Modifier.isPublic(type.getModifiers())) {
                lookup = MethodHandles.lookup();
            }
            else {
                try {
                    lookup = MethodHandles.privateLookupIn(ctor.getDeclaringClass(), ROOT_LOOKUP);
                }
                catch (IllegalAccessException ex) {
                    lookup = MethodHandles.lookup();
                }
            }

            ctor.setAccessible(true);
            MethodHandle handle = lookup.unreflectConstructor(ctor);
            Class<?>[] paramTypes = ctor.getParameterTypes();
            Object[] adapted = adaptArguments(paramTypes, args);
            return handle.invokeWithArguments(adapted);
        }


        static Object invokeMethod(Object targetOrClass, String name, Object[] args) throws Throwable {
            Class<?> owner;
            Object receiver = null;
            if (targetOrClass instanceof Class<?>) {
                owner = (Class<?>) targetOrClass;
            }
            else {
                owner = targetOrClass.getClass();
                receiver = targetOrClass;
            }

            Method method = findMethodDeep(owner, name, args);
            if (method == null) {
                throw new RuntimeException("No suitable method " + name + " found in " + owner.getName());
            }

            MethodHandles.Lookup lookup;
            if (Modifier.isPublic(method.getModifiers()) && Modifier.isPublic(owner.getModifiers())) {
                lookup = MethodHandles.lookup();
            }
            else {
                try {
                    lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), ROOT_LOOKUP);
                }
                catch (IllegalAccessException ex) {
                    lookup = MethodHandles.lookup();
                }
            }

            method.setAccessible(true);
            if (method.isDefault() && method.getDeclaringClass().isInterface()) {
                method.setAccessible(true);
                return method.invoke(receiver, adaptArguments(method.getParameterTypes(), args));
            }




            MethodHandle handle = lookup.unreflect(method);
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] adapted = adaptArguments(paramTypes, args);

            if (Modifier.isStatic(method.getModifiers())) {
                return handle.invokeWithArguments(adapted);
            }
            else {
                if (receiver == null) {
                    throw new RuntimeException("Instance method " + name + " requires target instance");
                }
                Object[] finalArgs = new Object[adapted.length + 1];
                finalArgs[0] = receiver;
                System.arraycopy(adapted, 0, finalArgs, 1, adapted.length);
                return handle.invokeWithArguments(finalArgs);
            }
        }


        static Object getField(Object targetOrClass, String name) throws Throwable {
            Class<?> owner;
            Object receiver = null;
            if (targetOrClass instanceof Class<?>) {
                owner = (Class<?>) targetOrClass;
            }
            else {
                owner = targetOrClass.getClass();
                receiver = targetOrClass;
            }

            Field field = findFieldDeep(owner, name);
            if (field == null) {
                throw new RuntimeException("No such field " + name + " in " + owner.getName());
            }

            field.setAccessible(true);
            MethodHandles.Lookup lookup;
            if (Modifier.isPublic(field.getModifiers()) && Modifier.isPublic(owner.getModifiers())) {
                lookup = MethodHandles.lookup();
            }
            else {
                try {
                    lookup = MethodHandles.privateLookupIn(field.getDeclaringClass(), ROOT_LOOKUP);
                }
                catch (IllegalAccessException ex) {
                    lookup = MethodHandles.lookup();
                }
            }

            MethodHandle getter = lookup.unreflectGetter(field);

            if (Modifier.isStatic(field.getModifiers())) {
                return getter.invoke();
            }
            if (receiver == null) {
                throw new RuntimeException("Instance field " + name + " requires target instance");
            }
            return getter.invoke(receiver);
        }


        static Field findFieldDeep(Class<?> type, String name) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    return c.getDeclaredField(name);
                }
                catch (NoSuchFieldException ignored) {
                }
            }
            return null;
        }

        static Method findMethodDeep(Class<?> type, String name, Object[] args) {

            for (Method m : type.getMethods()) {
                if (m.getName().equals(name) && isApplicable(m.getParameterTypes(), args)) {
                    return m;
                }
            }

            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && isApplicable(m.getParameterTypes(), args)) {
                        return m;
                    }
                }
                for (Class<?> iface : c.getInterfaces()) {
                    Method m = findMethodDeep(iface, name, args);
                    if (m != null) {
                        return m;
                    }
                }
            }
            return null;
        }


        static Constructor<?> findConstructorDeep(Class<?> type, Object[] args) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    if (isApplicable(ctor.getParameterTypes(), args)) {
                        return ctor;
                    }
                }
            }
            return null;
        }

        private static Object parseLiteral(Class<?> type, String text) {
            if (text == null) {
                return null;
            }

            if (text.length() >= 2 && (
                    (text.startsWith("\"") && text.endsWith("\"")) ||
                            (text.startsWith("'") && text.endsWith("'")))) {
                text = text.substring(1, text.length() - 1);
            }

            try {
                if (type == String.class) return text;
                if (type == int.class || type == Integer.class) return Integer.parseInt(text);
                if (type == long.class || type == Long.class) return Long.parseLong(text);
                if (type == double.class || type == Double.class) return Double.parseDouble(text);
                if (type == float.class || type == Float.class) return Float.parseFloat(text);
                if (type == short.class || type == Short.class) return Short.parseShort(text);
                if (type == byte.class || type == Byte.class) return Byte.parseByte(text);
                if (type == boolean.class || type == Boolean.class) {
                    if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("yes") || text.equals("1")) return true;
                    if (text.equalsIgnoreCase("false") || text.equalsIgnoreCase("no") || text.equals("0")) return false;
                }
                if (type == char.class || type == Character.class) {
                    return text.isEmpty() ? '\0' : text.charAt(0);
                }

                if (Number.class.isAssignableFrom(type)) {
                    if (text.contains(".")) {
                        return Double.parseDouble(text);
                    }
                    return Integer.parseInt(text);
                }

                if (type.isEnum()) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Enum<?> val = Enum.valueOf((Class<Enum>) type, text);
                    return val;
                }
            }
            catch (Exception ignored) {
            }

            return text;
        }


        static boolean isApplicable(Class<?>[] paramTypes, Object[] args) {
            if (paramTypes.length != args.length) return false;
            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> p = paramTypes[i];
                Object arg = args[i];
                if (arg == null) {
                    if (p.isPrimitive()) return false;
                    continue;
                }
                Class<?> a = arg.getClass();
                if (p.isPrimitive()) {
                    Class<?> wrapper = primitiveToWrapper(p);
                    if (wrapper.isAssignableFrom(a)) continue;
                    if (Number.class.isAssignableFrom(wrapper) && Number.class.isAssignableFrom(a)) continue;
                    return false;
                }
                else {
                    if (p.isAssignableFrom(a)) continue;
                    if (Number.class.isAssignableFrom(p) && Number.class.isAssignableFrom(a)) continue;
                    return false;
                }
            }
            return true;
        }

        static Object[] adaptArguments(Class<?>[] paramTypes, Object[] args) {
            Object[] out = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                out[i] = adaptArgument(paramTypes[i], args[i]);
            }
            return out;
        }

        public static Object adaptArgument(Class<?> paramType, Object arg) {
            if (arg == null) {
                return null;
            }

            if (arg instanceof com.denizenscript.denizencore.objects.ObjectTag) {
                Object javaObj = ((com.denizenscript.denizencore.objects.ObjectTag) arg).getJavaObject();
                if (paramType.isInstance(javaObj)) {
                    return javaObj;
                }
                return adaptArgument(paramType, javaObj);
            }

            if (paramType.isPrimitive()) {
                Class<?> wrapper = primitiveToWrapper(paramType);
                if (wrapper.isInstance(arg)) {
                    return arg;
                }
                if (arg instanceof Number && Number.class.isAssignableFrom(wrapper)) {
                    Number n = (Number) arg;
                    if (paramType == int.class) return n.intValue();
                    if (paramType == long.class) return n.longValue();
                    if (paramType == double.class) return n.doubleValue();
                    if (paramType == float.class) return n.floatValue();
                    if (paramType == short.class) return n.shortValue();
                    if (paramType == byte.class) return n.byteValue();
                    if (paramType == char.class) return (char) n.intValue();
                }
                if (paramType == boolean.class && arg instanceof Boolean) {
                    return arg;
                }
                if (paramType == char.class && arg instanceof Character) {
                    return arg;
                }
                return arg;
            }

            if (paramType.isInstance(arg)) {
                return arg;
            }

            if (Number.class.isAssignableFrom(paramType) && arg instanceof Number) {
                Number n = (Number) arg;
                if (paramType == Integer.class) return n.intValue();
                if (paramType == Long.class) return n.longValue();
                if (paramType == Double.class) return n.doubleValue();
                if (paramType == Float.class) return n.floatValue();
                if (paramType == Short.class) return n.shortValue();
                if (paramType == Byte.class) return n.byteValue();
            }

            if (arg instanceof String && Number.class.isAssignableFrom(paramType)) {
                try {
                    String s = (String) arg;
                    if (paramType == Integer.class) return Integer.parseInt(s);
                    if (paramType == Long.class) return Long.parseLong(s);
                    if (paramType == Double.class) return Double.parseDouble(s);
                    if (paramType == Float.class) return Float.parseFloat(s);
                    if (paramType == Short.class) return Short.parseShort(s);
                    if (paramType == Byte.class) return Byte.parseByte(s);
                }
                catch (Throwable ignored) {
                }
            }

            if (paramType.isEnum() && arg instanceof String) {
                try {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    Object e = Enum.valueOf((Class<Enum>) paramType, (String) arg);
                    return e;
                }
                catch (IllegalArgumentException ignored) {
                }
            }

            try {
                if (arg instanceof Iterable && !paramType.isAssignableFrom(arg.getClass())) {
                    Iterable<?> it = (Iterable<?>) arg;
                    List<Object> list = new ArrayList<>();
                    for (Object o : it) {
                        list.add(o instanceof com.denizenscript.denizencore.objects.ObjectTag
                                ? ((com.denizenscript.denizencore.objects.ObjectTag) o).getJavaObject()
                                : o);
                    }
                    if (paramType.isInstance(list)) {
                        return list;
                    }
                }
            }
            catch (Throwable ignored) {
            }

            return arg;
        }

        static Class<?> primitiveToWrapper(Class<?> primitive) {
            if (primitive == int.class) return Integer.class;
            if (primitive == long.class) return Long.class;
            if (primitive == double.class) return Double.class;
            if (primitive == float.class) return Float.class;
            if (primitive == short.class) return Short.class;
            if (primitive == byte.class) return Byte.class;
            if (primitive == boolean.class) return Boolean.class;
            if (primitive == char.class) return Character.class;
            return primitive;
        }
    }
}
