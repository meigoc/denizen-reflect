package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.object.JavaObjectTag;
import meigo.denizen.reflect.util.ReflectionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InvokeCommand extends AbstractCommand {

    public InvokeCommand() {
        setName("invoke");
        setSyntax("invoke [<object>.<method>([args])]");
        setRequiredArguments(1, 1);
        isProcedural = false;
    }

    // <--[command]
    // @Name invoke
    // @Syntax invoke [<object>.<method>([args])]
    // @Required 1
    // @Maximum 1
    // @Short Invokes a Java method on an object or static class.
    // @Group reflection
    //
    // @Description
    // This command invokes a Java method on an object or static class reference.
    // The syntax is: <object>.<method>([args])
    //
    // The object can be:
    // - Any Denizen ObjectTag (like <player>, <entity>, etc.) - will be converted to Java object
    // - A JavaObjectTag (from import command or definitions)
    // - A definition tag like <[my_object]>
    //
    // Arguments are separated by pipes (|) and can be:
    // - Simple values: method(arg1|arg2|arg3)
    // - Typed values: method(java.lang.String@arg1|int@42|boolean@true)
    //
    // This command does not return any value - it only executes the method.
    //
    // @Tags
    // None
    //
    // @Usage
    // Use to call a method on a player object.
    // - invoke "<player>.sendMessage(Hello World!)"
    //
    // @Usage
    // Use to call a static method.
    // - invoke "<[system_class]>.currentTimeMillis()"
    //
    // @Usage
    // Use to call a method with typed arguments.
    // - invoke "<[my_list]>.add(java.lang.String@Hello|int@42)"
    // -->

    private static final Pattern INVOKE_PATTERN = Pattern.compile("^(.+?)\\.([^.()]+)\\((.*)\\)$");
    private static final Pattern SIMPLE_METHOD_PATTERN = Pattern.compile("^(.+?)\\.([^.()]+)$");

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        for (Argument arg : scriptEntry) {
            if (!scriptEntry.hasObject("invoke_string")) {
                scriptEntry.addObject("invoke_string", arg.getRawElement());
            }
            else {
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

        String fullString = invokeString.asString();

        // Parse the invoke string
        ParsedInvoke parsed = parseInvokeString(fullString, scriptEntry);
        if (parsed == null) {
            Debug.echoError(scriptEntry, "Invalid invoke syntax: " + fullString);
            return;
        }

        // Get the target object
        Object targetObject = getTargetObject(parsed.objectString, scriptEntry);
        if (targetObject == null) {
            Debug.echoError(scriptEntry, "Could not resolve target object: " + parsed.objectString);
            return;
        }

        // Convert arguments
        List<ObjectTag> convertedArgs = convertArguments(parsed.arguments, scriptEntry);

        // Invoke the method
        if (targetObject instanceof Class<?>) {
            // Static method call
            ReflectionHandler.invokeStaticMethod((Class<?>) targetObject, parsed.methodName, convertedArgs, scriptEntry.getContext());
        } else {
            // Instance method call
            ReflectionHandler.invokeMethod(targetObject, parsed.methodName, convertedArgs, scriptEntry.getContext());
        }
    }

    private static class ParsedInvoke {
        String objectString;
        String methodName;
        String arguments;

        ParsedInvoke(String objectString, String methodName, String arguments) {
            this.objectString = objectString;
            this.methodName = methodName;
            this.arguments = arguments;
        }
    }

    private ParsedInvoke parseInvokeString(String fullString, ScriptEntry scriptEntry) {
        // Try pattern with arguments: object.method(args)
        Matcher matcher = INVOKE_PATTERN.matcher(fullString);
        if (matcher.matches()) {
            return new ParsedInvoke(matcher.group(1), matcher.group(2), matcher.group(3));
        }

        // Try pattern without arguments: object.method
        matcher = SIMPLE_METHOD_PATTERN.matcher(fullString);
        if (matcher.matches()) {
            return new ParsedInvoke(matcher.group(1), matcher.group(2), "");
        }

        return null;
    }

    private Object getTargetObject(String objectString, ScriptEntry scriptEntry) {
        ObjectTag parsed = ObjectFetcher.pickObjectFor(objectString, scriptEntry.getContext());
        if (parsed != null && !(parsed instanceof ElementTag)) {
            if (parsed instanceof JavaObjectTag) {
                return ((JavaObjectTag) parsed).heldObject;
            }
            Object javaObject = parsed.getJavaObject();
            if (javaObject != null) {
                return javaObject;
            }
            return parsed;
        }
        Class<?> clazz = ReflectionHandler.getClass(objectString, scriptEntry.getContext());
        if (clazz != null) {
            return clazz;
        }
        if (parsed != null) {
            return parsed.getJavaObject();
        }

        return null;
    }

    private List<ObjectTag> convertArguments(String argumentsString, ScriptEntry scriptEntry) {
        if (argumentsString == null || argumentsString.trim().isEmpty()) {
            return new ArrayList<>();
        }

        ListTag argList = ListTag.valueOf(argumentsString, scriptEntry.getContext());
        List<ObjectTag> convertedArgs = new ArrayList<>();

        for (ObjectTag arg : argList.objectForms) {
            ObjectTag processedArg = arg;
            if (processedArg instanceof JavaObjectTag) {
                Object heldObject = ((JavaObjectTag) processedArg).getJavaObject();
                processedArg = CoreUtilities.objectToTagForm(heldObject, scriptEntry.getContext());
            }

            String argStr = processedArg.toString();

            // Check for typed argument (type#value)
            int atIndex = argStr.indexOf('#');
            if (atIndex > 0) {
                String typeName = argStr.substring(0, atIndex);
                String value = argStr.substring(atIndex + 1);

                ObjectTag typedArg = createTypedArgument(typeName, value, scriptEntry);
                if (typedArg != null) {
                    convertedArgs.add(typedArg);
                    continue;
                }
            }

            convertedArgs.add(processedArg);
        }

        return convertedArgs;
    }

    private ObjectTag createTypedArgument(String typeName, String value, ScriptEntry scriptEntry) {
        try {
            // Handle primitive types
            switch (typeName.toLowerCase()) {
                case "int":
                case "integer":
                    return new ElementTag(Integer.parseInt(value));
                case "long":
                    return new ElementTag(Long.parseLong(value));
                case "float":
                    return new ElementTag(Float.parseFloat(value));
                case "double":
                    return new ElementTag(Double.parseDouble(value));
                case "boolean":
                    return new ElementTag(Boolean.parseBoolean(value));
                case "byte":
                    return new ElementTag(Byte.parseByte(value));
                case "short":
                    return new ElementTag(Short.parseShort(value));
                case "string":
                case "java.lang.string":
                    return new ElementTag(value);
                default:
                    // Try to create object of specified class
                    Class<?> clazz = ReflectionHandler.getClass(typeName, scriptEntry.getContext());
                    if (clazz != null) {
                        return new ElementTag(value);
                    }
                    break;
            }
        } catch (Exception e) {
            Debug.echoError(scriptEntry, "Failed to convert argument '" + value + "' to type '" + typeName + "': " + e.getMessage());
        }
        return null;
    }
}