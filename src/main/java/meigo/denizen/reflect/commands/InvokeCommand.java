package meigo.denizen.reflect.commands;


import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.object.JavaObjectTag;
import meigo.denizen.reflect.util.JavaExpressionParser;
import meigo.denizen.reflect.util.ReflectionHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InvokeCommand extends AbstractCommand {

    public InvokeCommand() {
        setName("invoke");
        setSyntax("invoke [<java_expression>]");
        setRequiredArguments(1, 1);
        isProcedural = false;
    }

    // <--[command]
    // @Name invoke
    // @Syntax invoke [<java_expression>]
    // @Required 1
    // @Maximum 1
    // @Short Executes a Java expression using reflection.
    // @Group reflection
    //
    // @Description
    // Executes a Java expression, allowing for complex interactions with Java objects and classes.
    // This command can perform method calls, access fields, and create new objects.
    //
    // The expression should follow Java-like syntax. You can reference Denizen objects (like p@, l@, etc.)
    // and saved JavaObjectTags directly within the expression. The command will automatically parse them.
    //
    // The argument separator for methods is the standard comma ','. The pipe character '|' is not supported here
    // to avoid conflicts with Java's bitwise OR operator.
    //
    // This command is powerful but complex. For simpler interactions, consider using the `import` command
    // and the tags available on JavaObjectTag, such as `<[my_object].invoke[...]>` and `<[my_object].field[...]`.
    //
    // The result of the expression is not automatically saved. This command is primarily for actions that
    // have side effects (e.g., modifying an object).
    //
    // @Usage
    // Use to get the system's temporary directory path and echo it.
    // - invoke 'System.getProperty("java.io.tmpdir")'
    //
    // @Usage
    // Use to create a new point object and then move it.
    // - import java.awt.Point as:my_point constructor:0,0
    // - invoke '<[my_point]>.move(10, 20)'
    // - narrate "Point is now at <[my_point]>"
    //
    // @Usage
    // Use to call a static method with a Denizen player object as a parameter.
    // - invoke 'com.example.MyAPI.staticPlayerMethod(<player>)'
    // -->
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

    /**
     * Attempts to find a class by its name without printing errors to the console.
     * @param className The fully qualified name of the class.
     * @return The Class object or null if not found.
     */
    private Class<?> findClassSilently(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | NullPointerException e) {
            return null;
        }
    }

    private ObjectTag resolveDottedTarget(String targetPart, TagContext context) {
        String[] parts = targetPart.split("\\.");
        for (int i = parts.length; i > 0; i--) {
            String className = String.join(".", Arrays.copyOfRange(parts, 0, i));
            // Use our new silent method instead of ReflectionHandler.getClass
            Class<?> clazz = findClassSilently(className);
            if (clazz != null) {
                Object current = clazz;
                for (int j = i; j < parts.length; j++) {
                    Object fieldValue;
                    if (current instanceof Class) {
                        fieldValue = ReflectionHandler.getStaticField((Class<?>) current, parts[j], context);
                    } else {
                        fieldValue = ReflectionHandler.getField(current, parts[j], context);
                    }
                    if (fieldValue == null) {
                        // If a field is not found, this path is invalid, return null and let the loop try a shorter class name
                        current = null;
                        break;
                    }
                    current = fieldValue;
                }
                if (current != null) {
                    return ReflectionHandler.wrapObject(current, context);
                }
            }
        }
        return ObjectFetcher.pickObjectFor(targetPart, context);
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        ElementTag invokeString = scriptEntry.getElement("invoke_string");

        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(), invokeString.debuggable());
        }

        try {
            try {
                JavaExpressionParser parser = new JavaExpressionParser(scriptEntry.getContext());
                Object result = parser.execute(invokeString.asString(), null);

                if (scriptEntry.dbCallShouldDebug()) {
                    //Debug.log("Invocation successful via JavaExpressionParser. Result: " + (result != null ? result.toString() : "null"));
                }
                return; // End execution if successful
            } catch (Throwable e) {
                if (scriptEntry.dbCallShouldDebug()) {
                    //Debug.log("JavaExpressionParser failed (as expected for simple syntax), falling back to Denizen-style parser...");
                }

                String commandString = invokeString.asString();
                int lastDot = commandString.lastIndexOf('.');
                if (lastDot == -1) throw e;

                String targetPart = commandString.substring(0, lastDot);
                String methodPart = commandString.substring(lastDot + 1);

                int openParen = methodPart.indexOf('(');
                if (openParen == -1 || !methodPart.endsWith(")")) throw e;

                String methodName = methodPart.substring(0, openParen);
                String argsPart = methodPart.substring(openParen + 1, methodPart.length() - 1);

                List<ObjectTag> arguments = new ArrayList<>();
                if (!argsPart.isEmpty()) {
                    // Split by comma, but not inside nested parentheses (basic handling)
                    // For robust parsing, a more complex splitter would be needed, but this covers most cases.
                    String[] argStrings = argsPart.split(",");
                    for (String argStr : argStrings) {
                        arguments.add(ObjectFetcher.pickObjectFor(argStr.trim(), scriptEntry.getContext()));
                    }
                }

                ObjectTag targetObject = resolveDottedTarget(targetPart, scriptEntry.getContext());
                if (targetObject == null) {
                    throw new RuntimeException("Could not resolve target: " + targetPart);
                }

                Object javaTarget;
                if (targetObject instanceof JavaObjectTag) {
                    javaTarget = ((JavaObjectTag) targetObject).getJavaObject();
                } else {
                    // Also use our silent method here to avoid errors on non-class ElementTags
                    Class<?> clazz = findClassSilently(targetObject.toString());
                    if (clazz != null) {
                        javaTarget = clazz;
                    } else {
                        javaTarget = targetObject.getJavaObject();
                    }
                }

                if (javaTarget == null) {
                    throw new RuntimeException("Resolved target is null or does not have a Java object representation: " + targetPart);
                }

                if (javaTarget instanceof Class) {
                    ReflectionHandler.invokeStaticMethod((Class<?>) javaTarget, methodName, arguments, scriptEntry.getContext());
                } else {
                    ReflectionHandler.invokeMethod(javaTarget, methodName, arguments, scriptEntry.getContext());
                }

                if (scriptEntry.dbCallShouldDebug()) {
                    //Debug.log("Invocation successful via Denizen-style fallback parser.");
                }
            }
        } catch (Exception e) {
            Debug.echoError(scriptEntry, "Failed to execute invoke command.");
            Debug.echoError(scriptEntry, e);
        }
    }
}