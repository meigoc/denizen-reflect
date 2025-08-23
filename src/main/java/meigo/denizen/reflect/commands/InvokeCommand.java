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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InvokeCommand extends AbstractCommand {

    public InvokeCommand() {
        setName("invoke");
        setSyntax("invoke [<object>.<method>([<args>]).<method2>...]");
        setRequiredArguments(1, 1);
        isProcedural = false;
    }

    // <--[command]
    // @Name invoke
    // @Syntax invoke [<object>.<method>([<args>]).<method2>...]
    // @Required 1
    // @Maximum 1
    // @Short Invokes a Java method or a chain of methods on an object or static class.
    // @Group reflection
    //
    // @Description
    // This command invokes a Java method on an object or static class reference.
    // It now supports chaining methods together. The result of the first method call becomes the object for the second, and so on.
    // The final method in the chain is executed, but its return value is discarded. All intermediate methods in the chain MUST return an object.
    //
    // The syntax is: <object>.<method>([args]).<method2>([args2])...
    //
    // The object can be:
    // - Any Denizen ObjectTag (like <player>, <entity>, etc.) - will be converted to its underlying Java object.
    // - A JavaObjectTag (from the 'import' command or as a definition).
    //
    // Arguments are separated by pipes (|) and can be any Denizen ObjectTag, including definitions and JavaObjectTags.
    // Arguments can also be typed using a hash (#), for example: int#42, boolean#true, String#hello.
    //
    // @Tags
    // None
    //
    // @Usage
    // Use to call a simple method on a player object.
    // - invoke "<player>.setHealth(10.0)"
    //
    // @Usage
    // Use to call a chain of methods to get the plugin manager and disable a plugin.
    // - invoke "org.bukkit.Bukkit.getPluginManager().disablePlugin(<plugin[MyPlugin]>)"
    //
    // @Usage
    // Use to get a player's inventory and clear it.
    // - invoke "<player>.getInventory().clear()"
    // -->

    // Regex to find a single method call part like ".methodName(arguments)" or ".fieldName"
    private static final Pattern CHAIN_PART_PATTERN = Pattern.compile("\\.([^.()]+)(?:\\((.*?)\\))?");

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
        int firstDot = findFirstDot(fullString);

        if (firstDot == -1) {
            Debug.echoError(scriptEntry, "Invalid invoke syntax: missing a '.' to separate the object from the method/field. Input: " + fullString);
            return;
        }

        String objectString = fullString.substring(0, firstDot);
        String chainString = fullString.substring(firstDot);

        Object currentObject = getTargetObject(objectString, scriptEntry);
        if (currentObject == null) {
            Debug.echoError(scriptEntry, "Could not resolve initial target object: " + objectString);
            return;
        }

        Matcher matcher = CHAIN_PART_PATTERN.matcher(chainString);
        int lastMatchEnd = 0;

        while (matcher.find()) {
            lastMatchEnd = matcher.end();
            String methodName = matcher.group(1);
            String argsString = matcher.group(2); // Can be null if no parentheses

            List<ObjectTag> convertedArgs = convertArguments(argsString, scriptEntry);

            boolean isLastPart = (lastMatchEnd == chainString.length());
            Object result;

            if (currentObject instanceof Class) { // Static call
                if (argsString == null) { // It's a field access
                    result = ReflectionHandler.getStaticField((Class<?>) currentObject, methodName, scriptEntry.getContext());
                }
                else { // It's a method call
                    result = ReflectionHandler.invokeStaticMethod((Class<?>) currentObject, methodName, convertedArgs, scriptEntry.getContext());
                }
            }
            else { // Instance call
                if (argsString == null) { // It's a field access
                    result = ReflectionHandler.getField(currentObject, methodName, scriptEntry.getContext());
                }
                else { // It's a method call
                    result = ReflectionHandler.invokeMethod(currentObject, methodName, convertedArgs, scriptEntry.getContext());
                }
            }

            if (isLastPart) {
                // This is the end of the chain, we are done.
                return;
            }

            if (result == null) {
                Debug.echoError(scriptEntry, "Method/field '" + methodName + "' on object '" + currentObject + "' returned null, breaking the method chain.");
                return;
            }
            currentObject = result;
        }

        if (lastMatchEnd != chainString.length()) {
            Debug.echoError(scriptEntry, "Invalid invoke syntax. Could not parse the part after: '" + chainString.substring(0, lastMatchEnd) + "'");
        }
    }

    // Finds the first dot that is not inside parentheses to correctly separate the initial object from the method chain.
    private int findFirstDot(String str) {
        int parenLevel = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '(') {
                parenLevel++;
            }
            else if (c == ')') {
                parenLevel--;
            }
            else if (c == '.' && parenLevel == 0) {
                return i;
            }
        }
        return -1;
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
                processedArg = CoreUtilities.objectToTagForm(heldObject, scriptEntry.getContext(), false, false, true);
            }
            String argStr = processedArg.toString();
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
            switch (typeName.toLowerCase(Locale.ENGLISH)) {
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