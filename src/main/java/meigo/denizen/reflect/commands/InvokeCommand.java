package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.tags.TagContext;
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
        setSyntax("invoke [<object>.<method>([<args>]).<method2>...]");
        setRequiredArguments(1, 1);
        isProcedural = false;
    }

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

        String objectString;
        String chainString;

        int firstDot = fullString.indexOf('.');
        if (firstDot == -1) {
            Debug.echoError(scriptEntry, "Invalid invoke syntax: missing method or field call. Full string: " + fullString);
            return;
        }
        objectString = fullString.substring(0, firstDot);
        chainString = fullString.substring(firstDot);

        Object currentObject = getTargetObject(objectString, scriptEntry);
        if (currentObject == null) {
            Debug.echoError(scriptEntry, "Could not resolve initial target object: " + objectString);
            return;
        }

        Matcher matcher = CHAIN_PART_PATTERN.matcher(chainString);

        while (matcher.find()) {
            if (currentObject == null) {
                Debug.echoError(scriptEntry, "Cannot continue method chain: previous call returned null.");
                return;
            }
            String memberName = matcher.group(1);
            String argsString = matcher.group(2);

            Object result;

            if (argsString != null) {
                List<ObjectTag> convertedArgs = ListTag.valueOf(argsString, scriptEntry.getContext()).objectForms;
                result = invokeMember(currentObject, memberName, convertedArgs, scriptEntry.getContext());
            } else {
                result = invokeMember(currentObject, memberName, null, scriptEntry.getContext());
            }

            int nextPartStart = matcher.end();
            if (nextPartStart >= chainString.length()) {
                return;
            }

            currentObject = result;
        }
    }

    private Object invokeMember(Object target, String memberName, List<ObjectTag> args, TagContext context) {
        boolean isMethodCall = args != null;
        if (target instanceof Class) {
            return isMethodCall
                    ? ReflectionHandler.invokeStaticMethod((Class<?>) target, memberName, args, context)
                    : ReflectionHandler.getStaticField((Class<?>) target, memberName, context);
        }
        else {
            return isMethodCall
                    ? ReflectionHandler.invokeMethod(target, memberName, args, context)
                    : ReflectionHandler.getField(target, memberName, context);
        }
    }

    private Object getTargetObject(String objectString, ScriptEntry scriptEntry) {
        TagContext context = scriptEntry.getContext();
        ObjectTag parsed = ObjectFetcher.pickObjectFor(objectString, context);

        if (parsed != null) {
            return (parsed instanceof JavaObjectTag) ? ((JavaObjectTag) parsed).getJavaObject() : parsed.getJavaObject();
        }

        Class<?> clazz = ReflectionHandler.getClass(objectString, context);
        if (clazz != null) {
            return clazz;
        }

        return null;
    }
}