package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.util.JavaExpressionParser;

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
    // The argument separator for methods is the standard comma ',', but '|' is also supported and will be
    // automatically converted to a comma.
    //
    // This command is powerful but complex. For simpler interactions, consider using the `import` command
    // and the tags available on JavaObjectTag, such as `<[my_object].invoke[...]>` and `<[my_object].field[...]`.
    //
    // The result of the expression is not automatically saved. This command is primarily for actions that
    // have side effects (e.g., modifying an object).
    //
    // @Usage
    // Use to get the system's temporary directory path and echo it.
    // - invoke <def[system_class].getProperty("java.io.tmpdir")>
    //
    // @Usage
    // Use to create a new point object and then move it.
    // - import java.awt.Point constructor:0|0 as:my_point
    // - invoke <[my_point]>.move(10, 20)
    // - narrate "Point is now at <[my_point]>"
    //
    // @Usage
    // Use to call a static method with a Denizen player object as a parameter.
    // - invoke com.example.MyAPI.staticPlayerMethod(<player>)
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

    @Override
    public void execute(ScriptEntry scriptEntry) {
        ElementTag invokeString = scriptEntry.getElement("invoke_string");

        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(), invokeString.debuggable());
        }

        try {
            JavaExpressionParser parser = new JavaExpressionParser(scriptEntry.getContext());
            Object result = parser.execute(invokeString.asString(), null); // No context object for the command

            if (scriptEntry.dbCallShouldDebug()) {
                Debug.log("Invocation successful. Result: " + (result != null ? result.toString() : "null"));
            }
            // Note: The result of the command is not currently saved or used.
            // This might be a future enhancement (e.g., save to an entry).

        } catch (Exception e) {
            Debug.echoError(scriptEntry, "Failed to execute invoke command.");
            Debug.echoError(scriptEntry, e);
        }
    }
}