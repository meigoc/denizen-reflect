package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.ArgumentHelper;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.generator.ArgDefaultText;
import com.denizenscript.denizencore.scripts.commands.generator.ArgLinear;
import com.denizenscript.denizencore.scripts.commands.generator.ArgName;
import com.denizenscript.denizencore.scripts.commands.generator.ArgPrefixed;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.events.CustomCommandEvent;

import java.util.*;

@SuppressWarnings("all")

public class Command extends AbstractCommand {

    // @Plugin denizen-reflect
    public Command() {
        setName("command");
        setSyntax("command [create/delete/rename] [<command_name>] [with:<args>]");
        setRequiredArguments(2, 3);
        isProcedural = false;
        autoCompile();
    }

    // <--[command]
    // @Name Command
    // @Syntax command [create/delete/rename] [<command_name>] [with:<args>]
    // @Required 2
    // @Maximum 3
    // @Short Command manager.
    // @Group denizen-reflect
    //
    // @Description
    // Creates, deletes, or renames Denizen commands at runtime.
    //
    // @Usage
    // Use to create custom command '- role <player> police'
    // - command create role with:player|role
    //
    // @Usage
    // Use to create custom command '- emote <player> type:<type>'
    // - command create emote with:player|#type
    // -->

    public static void autoExecute(ScriptEntry scriptEntry,
                            @ArgName("action") @ArgLinear String action,
                            @ArgName("command_name") @ArgLinear String command_name,
                            @ArgName("with") @ArgPrefixed @ArgDefaultText("null") String with) {
        if (action.equals("create")) {
            create(command_name, with);
        } else if (action.equals("delete")) {
            if (!DenizenCore.commandRegistry.instances.containsKey(command_name)) {
                Debug.echoError("No such command: " + command_name);
                return;
            }
            DenizenCore.commandRegistry.instances.remove(command_name);
        } else if (action.equals("rename")) {
            if (!DenizenCore.commandRegistry.instances.containsKey(command_name)) {
                Debug.echoError("No such command: " + command_name);
                return;
            }
            if (with.equals("null")) {
                Debug.echoError("Invalid argument: with");
                return;
            }
            AbstractCommand instance = DenizenCore.commandRegistry.instances.get(command_name);
            instance.setName(with);
            instance.setSyntax(instance.syntax.replace(command_name, with));
            instance = DenizenCore.commandRegistry.instances.get(command_name);
            DenizenCore.commandRegistry.instances.remove(command_name);
            DenizenCore.commandRegistry.instances.put(with, instance);
        } else {
            Debug.echoError("Invalid action " + action + ". Expected 'create/rename'.");
        }
    }




    private static void create(String command_name, String to) {
        AbstractCommand command = new AbstractCommand() {
            @Override
            public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
                if (!to.equals("null")) {
                    String[] patterns = to.split("\\|");
                    if (patterns.length > 0) {
                        List<Argument> args = ArgumentHelper.interpret(scriptEntry, scriptEntry.getOriginalArguments());
                        Set<Argument> used = new HashSet<>();
                        int positionalIndex = 0;

                        for (String pattern : patterns) {
                            boolean expectsPrefixed = pattern.startsWith("#");
                            String name = expectsPrefixed ? pattern.substring(1) : pattern;

                            if (expectsPrefixed) {
                                Argument found = args.stream()
                                        .filter(a -> !used.contains(a) && a.matchesPrefix(name))
                                        .findFirst().orElse(null);
                                if (found != null) {
                                    used.add(found);
                                    scriptEntry.addObject(name, TagManager.tagObject(found.getValue(), scriptEntry.getContext()));
                                } else {
                                    throw new InvalidArgumentsException("Invalid argument: " + name);
                                }
                            } else {
                                Argument found = null;
                                while (positionalIndex < args.size()) {
                                    Argument next = args.get(positionalIndex++);
                                    if (!used.contains(next)) {
                                        found = next;
                                        break;
                                    }
                                }
                                if (found == null) {
                                    throw new InvalidArgumentsException("Invalid argument: " + name);
                                }
                                assert found != null;
                                if (found.hasPrefix()) {
                                    throw new InvalidArgumentsException("Invalid argument: " + name);
                                }
                                used.add(found);
                                scriptEntry.addObject(name, TagManager.tagObject(found.getValue(), scriptEntry.getContext()));
                            }
                        }
                    }
                }
            }


            @Override
            public void execute(ScriptEntry scriptEntry) {
                CustomCommandEvent.runCustomCommand(scriptEntry, command_name);
            }
        };

        if (!to.equals("null")) {
            StringBuilder syntax = new StringBuilder();
            for (String string : to.split("\\|")) {
                if (string.startsWith("#")) {
                    syntax.append(string.substring(1) + ":<" + string.substring(1) + "> ");
                } else {
                    syntax.append("<" + string + "> ");
                }
            }
            command.setSyntax(command_name + " "  + syntax.toString());
            command.setRequiredArguments(0, to.split("\\|").length);
        } else {
            command.setSyntax(command_name);
            command.setRequiredArguments(0, 0);
        }


        command.setName(command_name);
        command.setParseArgs(false);
        command.isProcedural = false;
        DenizenCore.commandRegistry.instances.put(command_name, command);
    }
}