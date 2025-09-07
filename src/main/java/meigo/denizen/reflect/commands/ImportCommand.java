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
import meigo.denizen.reflect.util.ArgumentParamParser;
import meigo.denizen.reflect.util.ReflectionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ImportCommand extends AbstractCommand {

    public ImportCommand() {
        setName("import");
        setSyntax("import [<class_name>] (constructor:[<param>|...]) (as:<definition_name>)");
        setRequiredArguments(1, 3);
        isProcedural = false;
    }

    // <--[command]
    // @Name import
    // @Syntax import [<class_name>] [constructor:[<param>|...]] [as:<definition_name>]
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
    // The 'constructor' argument is optional. If you provide it (even with no value, like `constructor:`),
    // the command will try to create a new instance of the class by finding a constructor that matches the provided parameters.
    // Parameters are a ListTag of objects to pass to the constructor. You can explicitly type primitive parameters
    // using the format "type#value", for example: "int#10", "double#3.14".
    //
    // If you DO NOT provide the 'constructor' argument AT ALL, the command returns a static reference to the class itself,
    // which can be used for static method/field access.
    //
    // The 'as' argument is optional and specifies the name of the definition to save the created JavaObjectTag into.
    //
    // @Tags
    // <entry[saveName].created_object> returns the created JavaObjectTag.
    //
    // @Usage
    // Use to create a new ArrayList using its no-arg constructor and save it to 'my_list'.
    // - import java.util.ArrayList constructor as:my_list
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
            // All other arguments are handled in execute
        }
        if (!scriptEntry.hasObject("class_name")) {
            throw new InvalidArgumentsException("Missing class name argument!");
        }
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        ElementTag className = scriptEntry.getElement("class_name");
        ListTag constructorArgsList = scriptEntry.argForPrefix("constructor", ListTag.class, true);
        ElementTag defName = scriptEntry.argForPrefixAsElement("as", null);

        boolean constructorPresent = scriptEntry.argForPrefix("constructor") != null;

        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(),
                    className.debuggable(),
                    constructorPresent ? (constructorArgsList != null ? constructorArgsList.debuggable() : "constructor (no args)") : "",
                    (defName != null ? defName.debuggable() : ""));
        }

        JavaObjectTag resultObject = null;

        if (!constructorPresent) {
            // Case 1: no constructor argument, return static reference to class
            Class<?> staticClass = ReflectionHandler.getClass(className.asString(), scriptEntry.getContext());
            if (staticClass != null) {
                resultObject = new JavaObjectTag(staticClass);
            }
        }
        else {
            // Case 2: constructor present, create new instance
            List<ObjectTag> params = new ArrayList<>();
            if (constructorArgsList != null) {
                params = constructorArgsList.stream()
                        .map(argStr -> ArgumentParamParser.parse(argStr, scriptEntry.getContext()))
                        .collect(Collectors.toList());
            }
            Object newInstance = ReflectionHandler.construct(className.asString(), params, scriptEntry.getContext());
            if (newInstance != null) {
                resultObject = new JavaObjectTag(newInstance);
            }
        }

        if (resultObject != null) {
            scriptEntry.addObject("created_object", resultObject);
            if (defName != null) {
                scriptEntry.getResidingQueue().addDefinition(defName.asString(), resultObject);
            }
        }
    }
}