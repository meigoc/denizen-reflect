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
    // @Plugin denizen-reflect
    // @Name invoke
    // @Syntax invoke [<java_expression>]
    // @Required 1
    // @Maximum 1
    // @Short
    // @Group denizen-reflect
    //
    // @Description
    // Calls java code.
    //
    // @Usage
    // Use to change player health.
    // - invoke player.setHealth(0)
    // -->

    @SuppressWarnings("unused")
    public static void autoExecute(ScriptEntry scriptEntry, @ArgName("expression") @ArgLinear String expression) {
        try {
            String path = scriptEntry.getScript().getContainer().getRelativeFileName();
            path = path.substring(path.indexOf("scripts/") + "scripts/".length());
            JavaExpressionEngine.execute(expression, scriptEntry, path);
        } catch (Exception e) {
            Debug.echoError(e.getLocalizedMessage());
        }
    }
}