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

        // --- ИЗМЕНЕНИЕ: Полностью переписанный парсер для корректной работы ---
        String objectString = null;
        int chainStartIndex = -1;

        int searchEnd = fullString.indexOf('.');
        while (searchEnd != -1) {
            String potentialObject = fullString.substring(0, searchEnd);
            if (getTargetObject(potentialObject, scriptEntry, true) != null) {
                objectString = potentialObject;
                chainStartIndex = searchEnd;
            }
            searchEnd = fullString.indexOf('.', searchEnd + 1);
        }

        if (objectString == null) {
            chainStartIndex = fullString.indexOf('.');
            if (chainStartIndex == -1) {
                // Возможно, это один объект без вызова, например <player>
                Object singleObject = getTargetObject(fullString, scriptEntry, false);
                if (singleObject != null) {
                    // Команда invoke без вызова метода/поля ничего не делает
                    return;
                }
                Debug.echoError(scriptEntry, "Invalid invoke syntax: missing method or field call. Full string: " + fullString);
                return;
            }
            objectString = fullString.substring(0, chainStartIndex);
        }

        Object currentObject = getTargetObject(objectString, scriptEntry, false);
        if (currentObject == null) {
            Debug.echoError(scriptEntry, "Could not resolve initial target object: " + objectString);
            return;
        }

        String chainString = fullString.substring(chainStartIndex);
        Matcher matcher = CHAIN_PART_PATTERN.matcher(chainString);

        while (matcher.find()) {
            String memberName = matcher.group(1);
            String argsString = matcher.group(2); // null, если это поле

            List<ObjectTag> convertedArgs = (argsString != null) ? ListTag.valueOf(argsString, scriptEntry.getContext()).objectForms : new ArrayList<>();
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

            // Проверяем, есть ли еще части в цепочке
            int nextPartStart = matcher.end();
            if (nextPartStart >= chainString.length()) {
                // Это была последняя часть, завершаем команду
                return;
            }

            if (result == null) {
                Debug.echoError(scriptEntry, "Method/field '" + memberName + "' on object '" + currentObject.toString() + "' returned null, breaking the method chain.");
                return;
            }
            currentObject = result;
        }
    }

    private Object getTargetObject(String objectString, ScriptEntry scriptEntry, boolean silent) {
        TagContext context = scriptEntry.getContext();
        if (silent) {
            context = context.clone();
        }

        // 1. Пытаемся распознать как Denizen-объект (например, <player>, <[my_def]>)
        ObjectTag parsed = ObjectFetcher.pickObjectFor(objectString, context);
        // Убеждаемся, что это не просто строка, которая случайно совпала
        if (parsed != null && !(parsed instanceof ElementTag)) {
            if (parsed instanceof JavaObjectTag) {
                return ((JavaObjectTag) parsed).heldObject;
            }
            return parsed.getJavaObject();
        }

        // 2. Если не получилось, пытаемся распознать как имя класса
        Class<?> clazz = silent
                ? ReflectionHandler.getClassSilent(objectString)
                : ReflectionHandler.getClass(objectString, context);
        if (clazz != null) {
            return clazz;
        }

        return null;
    }
}