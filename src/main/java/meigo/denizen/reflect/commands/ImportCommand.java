package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.object.JavaObjectTag;
import meigo.denizen.reflect.util.ReflectionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ImportCommand extends AbstractCommand {

    public ImportCommand() {
        setName("import");
        setSyntax("import [<class_name>] [constructor:<param>|...] [as:<definition_name>]");
        setRequiredArguments(1, 3);
        isProcedural = false;
    }

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        for (Argument arg : scriptEntry) {
            if (!scriptEntry.hasObject("class_name")
                    && !arg.matchesPrefix("constructor", "as")) {
                scriptEntry.addObject("class_name", arg.getRawElement());
            }
            // Other arguments are handled in execute
        }
        if (!scriptEntry.hasObject("class_name")) {
            throw new InvalidArgumentsException("Missing class name argument!");
        }
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        ElementTag className = scriptEntry.getElement("class_name");
        ListTag constructorArgs = scriptEntry.argForPrefix("constructor", ListTag.class, true);
        ElementTag defName = scriptEntry.argForPrefixAsElement("as", null);

        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(),
                    className.debuggable(),
                    (constructorArgs != null ? constructorArgs.debuggable() : ""),
                    (defName != null ? defName.debuggable() : ""));
        }

        // If 'constructor' argument is not present at all, we want a static class reference.
        if (constructorArgs == null && scriptEntry.argForPrefix("constructor") == null) {
            Class<?> staticClass = ReflectionHandler.getClass(className.asString(), scriptEntry.getContext());
            if (staticClass != null) {
                JavaObjectTag classObject = new JavaObjectTag(staticClass);
                scriptEntry.addObject("created_object", classObject);
                if (defName != null) {
                    scriptEntry.getResidingQueue().addDefinition(defName.asString(), classObject);
                }
            }
            return;
        }

        // If 'constructor' argument is present (even if empty), create an instance.
        // IMPORTANT: Do NOT pre-convert constructor args here; pass raw ObjectTag list to ReflectionHandler.construct.
        List<ObjectTag> params = convertConstructorArgs(constructorArgs, scriptEntry);
        Object newInstance = ReflectionHandler.construct(className.asString(), params, scriptEntry.getContext());

        if (newInstance != null) {
            JavaObjectTag newObject = new JavaObjectTag(newInstance);
            scriptEntry.addObject("created_object", newObject);
            if (defName != null) {
                scriptEntry.getResidingQueue().addDefinition(defName.asString(), newObject);
            }
        }
    }

    private List<ObjectTag> convertConstructorArgs(ListTag args, ScriptEntry scriptEntry) {
        if (args == null) {
            return new ArrayList<>();
        }
        // We still parse typed literals like int#5, string#foo etc.
        List<ObjectTag> convertedArgs = new ArrayList<>();
        for (ObjectTag arg : args.objectForms) {
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
            // Keep the original ObjectTag (so JavaObjectTag stays JavaObjectTag)
            convertedArgs.add(arg);
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
                    // If typeName resolves to a Class, return JavaObjectTag wrapping that Class (so constructor can receive Class<?>)
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