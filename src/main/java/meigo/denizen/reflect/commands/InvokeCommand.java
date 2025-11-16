package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.generator.*;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.util.JavaExpressionEngine;

public class InvokeCommand extends AbstractCommand {

    // @Plugin denizen-reflect
    public InvokeCommand() {
        setName("invoke");
        setSyntax("invoke [<java_expression>]");
        setRequiredArguments(1, 1);
        isProcedural = true;
        autoCompile();
    }

    // <--[command]
    // @Name Invoke
    // @Syntax invoke [<java_expression>]
    // @Required 1
    // @Maximum 1
    // @Short Calls Java code.
    // @Group denizen-reflect
    //
    // @Description
    // Executes a Java string: methods, fields, private methods and fields, constructors, ..
    //
    // @Usage
    // Use to change player health.
    // - invoke player.setHealth(0)
    //
    // -->

    @SuppressWarnings("unused")
    public static void autoExecute(ScriptEntry scriptEntry, @ArgName("expression") @ArgLinear String expression) {
        try {
            JavaExpressionEngine.execute(expression, scriptEntry);
        } catch (Exception e) {
            Debug.echoError(e.getLocalizedMessage());
        }
    }
}