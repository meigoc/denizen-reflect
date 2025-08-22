package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
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

public class ImportCommand extends AbstractCommand {

    public ImportCommand() {
        setName("import");
        setSyntax("import class:<class_name> [constructor:<param>|...] [as:<definition_name>]");
        setRequiredArguments(1, 3);
        isProcedural = false;
    }

    // <--[command]
    // @Name import
    // @Syntax import class:<class_name> [constructor:<param>|...] [as:<definition_name>]
    // @Required 1
    // @Maximum 3
    // @Short Creates a new Java object instance or gets a static class reference.
    // @Group reflection
    //
    // @Description
    // This command is the entry point for Java reflection in Denizen.
    // It allows you to create an instance of a Java class or get a reference to the class itself for static access.
    //
    // The 'class' argument is required and must be the fully qualified name of the class (e.g., "java.util.ArrayList").
    // Access to classes is restricted by the 'allowed-packages' list in the config.yml for security.
    //
    // The 'constructor' argument is optional. If provided, the command will attempt to create a new instance
    // of the class by finding a constructor that matches the provided parameters. Parameters should be a ListTag.
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
    // - reflect class:java.util.ArrayList as:my_list
    //
    // @Usage
    // Use to get a static reference to the 'java.lang.System' class.
    // - reflect class:java.lang.System as:system
    //
    // @Usage
    // Use to create a new 'java.awt.Point' object with a specific constructor.
    // - reflect class:java.awt.Point constructor:10|20 as:my_point
    // -->

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {

    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        ElementTag className = scriptEntry.requiredArgForPrefixAsElement("class");
        ListTag constructorArgs = scriptEntry.argForPrefix("constructor", ListTag.class, true);
        ElementTag defName = scriptEntry.argForPrefixAsElement("as", null);

        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(),
                    className.debuggable(),
                    (constructorArgs != null ? constructorArgs.debuggable() : ""),
                    (defName != null ? defName.debuggable() : ""));
        }

        // If 'constructor' argument is not present at all, we want a static class reference.
        // The modern way to check for argument presence is to see if its value is null.
        if (scriptEntry.argForPrefix("constructor") == null) {
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
        List<ObjectTag> params = constructorArgs != null ? constructorArgs.objectForms : new ArrayList<>();
        Object newInstance = ReflectionHandler.construct(className.asString(), params, scriptEntry.getContext());

        if (newInstance != null) {
            JavaObjectTag newObject = new JavaObjectTag(newInstance);
            scriptEntry.addObject("created_object", newObject);
            if (defName != null) {
                scriptEntry.getResidingQueue().addDefinition(defName.asString(), newObject);
            }
        }
    }
}