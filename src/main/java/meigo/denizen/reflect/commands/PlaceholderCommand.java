package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsRuntimeException;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.generator.ArgDefaultNull;
import com.denizenscript.denizencore.scripts.commands.generator.ArgLinear;
import com.denizenscript.denizencore.scripts.commands.generator.ArgName;
import com.denizenscript.denizencore.scripts.commands.generator.ArgPrefixed;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;
import meigo.denizen.reflect.events.PlaceholderEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class PlaceholderCommand extends AbstractCommand {

    public static LocalExpansionManager localExpansionManager = PlaceholderAPIPlugin.getInstance().getLocalExpansionManager();

    // @Plugin denizen-reflect
    public PlaceholderCommand() {
        setName("placeholder");
        setSyntax("placeholder [create/delete] [<placeholder>] [author:<author>] [version:<version>]");
        setRequiredArguments(2, 4);
        isProcedural = false;
        autoCompile();
    }

    // <--[command]
    // @Name Placeholder
    // @Syntax placeholder [create/delete] [<placeholder>] [author:<author>] [version:<version>]
    // @Required 2
    // @Maximum 4
    // @Short Placeholder manager.
    // @Group denizen-reflect
    //
    // @Description
    // Allows you to create placeholders.
    //
    // @Usage
    // Use to create placeholder %test%.
    // - placeholder create test author:Nybik_YT version:1.0
    // -->

    @SuppressWarnings("unused")
    public static void autoExecute(ScriptEntry scriptEntry,
                                   @ArgName("action") @ArgLinear String action,
                                   @ArgName("placeholder") @ArgLinear String placeholder,
                                   @ArgName("author") @ArgPrefixed @ArgDefaultNull String author,
                                   @ArgName("version") @ArgPrefixed @ArgDefaultNull String version) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) { throw new InvalidArgumentsRuntimeException("Could not find PlaceholderAPI! This plugin is required."); }
        switch (action) {
            case "create":
                if (author == null || version == null) { throw new InvalidArgumentsRuntimeException("Author and version cannot be null."); }
                DExpansion expansion = new DExpansion();
                expansion.author = author;
                expansion.identifier = placeholder;
                expansion.version = version;
                expansion.register();
                break;
            case "delete":
                final PlaceholderExpansion removed = localExpansionManager.getExpansion(placeholder);
                if (removed != null) {
                    removed.unregister();
                } else {
                    throw new InvalidArgumentsRuntimeException("Placeholder '" + placeholder + "' does not exist.");
                }
                break;
            default:
                throw new InvalidArgumentsRuntimeException("Invalid action " + action + ". Expected 'create/delete'");
        }
    }


    public static class DExpansion extends PlaceholderExpansion {

        String author = null;
        String identifier = null;
        String version = null;

        @Override
        @NotNull
        public String getAuthor() {
            return author; //
        }

        @Override
        @NotNull
        public String getIdentifier() {
            return identifier;
        }

        @Override
        @NotNull
        public String getVersion() {
            return version;
        }

        @Override
        public String onRequest(OfflinePlayer player, @NotNull String params) {
            return PlaceholderEvent.runPlaceholder(identifier, params);
        }
    }


}