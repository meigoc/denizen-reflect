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

    // <--[command]
    // @Name import
    // @Syntax import [<class_name>] [constructor:<param>|...] [as:<definition_name>]
    // @Required 1
    // @Maximum 3
    // @Short Creates a new Java object instance or gets a static class reference.
    // @Group reflection
    //
    // @Description
    // This command is the entry point for Java reflection in Denizen.
    // It allows you to create an instance of a Java class or get a reference to the class itself for static access.
    //
    // The first argument is required and must be the fully qualified name of the class (e.g., "java.util.ArrayList").
    // Access to classes is restricted by the 'allowed-packages' list in the config.yml for security.
    //
    // The 'constructor' argument is optional. If provided, the command will attempt to create a new instance
    // of the class by finding a constructor that matches the provided parameters. Parameters can be any ObjectTag,
    // including typed arguments like 'int#42'.
    // If omitted, the command will attempt to use the default no-argument constructor. If no constructor argument is given
    // at all, the command returns a static reference to the class itself, which can be used for static method/field access.
    //
    // The 'as' argument is optional and specifies the name of the definition to save the created JavaObjectTag into.
    //
    // @Tags
    // <entry[saveName].created_object> returns the created JavaObjectTag.
    //
    // @Usage
    // Use to create a new ArrayList and save it to the definition 'my_list'.
    // - import java.util.ArrayList as:my_list
    //
    // @Usage
    // Use to get a static reference to the 'java.lang.System' class.
    // - import java.lang.System as:system
    //
    // @Usage
    // Use to create a new 'java.awt.Point' object with a specific constructor.
    // - import java.awt.Point constructor:int#10|int#20 as:my_point
    // -->

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