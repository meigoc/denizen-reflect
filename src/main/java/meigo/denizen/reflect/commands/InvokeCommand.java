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

    // Note: CHAIN_PART_PATTERN is intentionally simple — full parsing is done by findFirstDot() and by
    // parse of arguments via ListTag.valueOf in convertArguments.
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

        String objectString = null;
        int chainStartIndex = -1;

        // Iteratively search for the longest valid starting object (supports <player> etc.)
        int nextDot = -1;
        while ((nextDot = fullString.indexOf('.', nextDot + 1)) != -1) {
            String potentialObject = fullString.substring(0, nextDot);
            // Quiet check: don't spam console
            if (getTargetObjectSilent(potentialObject, scriptEntry) != null) {
                objectString = potentialObject;
                chainStartIndex = nextDot;
            }
        }

        // If nothing found (e.g. <player>.getInventory()), use simpler first-dot logic.
        if (objectString == null) {
            chainStartIndex = findFirstDot(fullString);
            if (chainStartIndex == -1) {
                // It's a field access without methods, e.g. "java.lang.System.out"
                objectString = fullString;
            } else {
                objectString = fullString.substring(0, chainStartIndex);
            }
        }

        Object currentObject = getTargetObject(objectString, scriptEntry);
        if (currentObject == null) {
            Debug.echoError(scriptEntry, "Could not resolve initial target object: " + objectString);
            return;
        }

        if (chainStartIndex == -1) { // Access to field only, no method chain; nothing to do here.
            return;
        }

        String chainString = fullString.substring(chainStartIndex);
        Matcher matcher = CHAIN_PART_PATTERN.matcher(chainString);
        int lastMatchEnd = 0;

        while (matcher.find()) {
            lastMatchEnd = matcher.end();
            String memberName = matcher.group(1);
            String argsString = matcher.group(2); // null if this part is a field access

            List<ObjectTag> convertedArgs = convertArguments(argsString, scriptEntry);
            boolean isLastPart = (lastMatchEnd == chainString.length());
            Object result;

            if (currentObject instanceof Class) {
                result = (argsString == null)
                        ? ReflectionHandler.getStaticField((Class<?>) currentObject, memberName, scriptEntry.getContext())
                        : ReflectionHandler.invokeStaticMethod((Class<?>) currentObject, memberName, convertedArgs, scriptEntry.getContext());
            }
            else {
                result = (argsString == null)
                        ? ReflectionHandler.getField(currentObject, memberName, scriptEntry.getContext())
                        : ReflectionHandler.invokeMethod(currentObject, memberName, convertedArgs, scriptEntry.getContext());
            }

            if (isLastPart) {
                // finished evaluation chain — nothing to return from command context
                return;
            }
            if (result == null) {
                Debug.echoError(scriptEntry, "Method/field '" + memberName + "' on object '" + currentObject.toString() + "' returned null, breaking the method chain.");
                return;
            }
            currentObject = result;
        }

        if (lastMatchEnd != chainString.length()) {
            Debug.echoError(scriptEntry, "Invalid invoke syntax. Could not parse the part after: '" + chainString.substring(0, lastMatchEnd) + "'");
        }
    }

    private int findFirstDot(String str) {
        int parenLevel = 0;
        boolean inQuote = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '"') inQuote = !inQuote;
            if (inQuote) continue;
            if (c == '(') parenLevel++;
            else if (c == ')') parenLevel--;
            else if (c == '.' && parenLevel == 0) return i;
        }
        return -1;
    }

    // Quiet resolution: try to resolve object without emitting debug/errors.
    private Object getTargetObjectSilent(String objectString, ScriptEntry scriptEntry) {
        ObjectTag parsed = ObjectFetcher.pickObjectFor(objectString, scriptEntry.getContext());
        if (parsed != null && !(parsed instanceof ElementTag)) {
            return parsed;
        }
        // Try class name quietly
        return ReflectionHandler.getClassSilent(objectString);
    }

    private Object getTargetObject(String objectString, ScriptEntry scriptEntry) {
        ObjectTag parsed = ObjectFetcher.pickObjectFor(objectString, scriptEntry.getContext());
        if (parsed != null && !(parsed instanceof ElementTag)) {
            if (parsed instanceof JavaObjectTag) {
                return ((JavaObjectTag) parsed).getJavaObject();
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
        // Use ListTag parser to properly parse Denizen nested tags, escaping and lists.
        ListTag argList = ListTag.valueOf(argumentsString, scriptEntry.getContext());
        List<ObjectTag> convertedArgs = new ArrayList<>();
        for (ObjectTag arg : argList.objectForms) {
            // IMPORTANT: Do NOT re-wrap or convert JavaObjectTag back into ElementTag.
            // If arg is JavaObjectTag — keep it as-is (it carries the real Java object).
            if (arg instanceof JavaObjectTag) {
                convertedArgs.add(arg);
                continue;
            }
            // If arg.getJavaObject() is present (some custom object that exposes java object), keep original ObjectTag.
            if (arg.getJavaObject() != null && !(arg instanceof ElementTag)) {
                convertedArgs.add(arg);
                continue;
            }

            // If argument is a typed string like int#5 or java.lang.String#hello — try to create a typed argument
            String argStr = arg.toString();
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

            // Default: keep parsed ObjectTag (likely ElementTag or other Denizen object)
            convertedArgs.add(arg);
        }
        return convertedArgs;
    }

    private ObjectTag createTypedArgument(String typeName, String value, ScriptEntry scriptEntry) {
        try {
            switch (typeName.toLowerCase(Locale.ENGLISH)) {
                case "int": case "integer": return new ElementTag(Integer.parseInt(value));
                case "long": return new ElementTag(Long.parseLong(value));
                case "float": return new ElementTag(Float.parseFloat(value));
                case "double": return new ElementTag(Double.parseDouble(value));
                case "boolean": return new ElementTag(Boolean.parseBoolean(value));
                case "byte": return new ElementTag(Byte.parseByte(value));
                case "short": return new ElementTag(Short.parseShort(value));
                case "string": case "java.lang.string": return new ElementTag(value);
                default:
                    // If the typeName resolves to a Class, return a JavaObjectTag wrapping that Class object.
                    Class<?> clazz = ReflectionHandler.getClass(typeName, scriptEntry.getContext());
                    if (clazz != null) {
                        return new JavaObjectTag(clazz);
                    }
                    break;
            }
        } catch (Exception e) {
            Debug.echoError(scriptEntry, "Failed to convert argument '" + value + "' to type '" + typeName + "': " + e.getMessage());
        }
        return null;
    }
}