/*
 * Copyright 2025 Meigo™ Corporation
 * SPDX-License-Identifier: Apache-2.0
 */

package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsRuntimeException;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.generator.*;
import me.clip.placeholderapi.events.ExpansionsLoadedEvent;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import meigo.denizen.reflect.DenizenReflect;
import meigo.denizen.reflect.events.PlaceholderEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;

public class PlaceholderCommand extends AbstractCommand implements Listener {

    public static HashMap<String, DExpansion> expansions = new HashMap<>();

    // @Plugin denizen-reflect
    public PlaceholderCommand() {
        setName("placeholder");
        setSyntax("placeholder [create/delete] [<placeholder>] [author:<author>] [version:<version>] (executor:{event}/<section>)");
        setRequiredArguments(2, 5);
        isProcedural = false;
        autoCompile();
        Bukkit.getPluginManager().registerEvents(this, DenizenReflect.getInstance());
    }

    // <--[command]
    // @Name Placeholder
    // @Syntax placeholder [create/delete] [<placeholder>] [author:<author>] [version:<version>] (executor:{event}/<section>)
    // @Required 2
    // @Maximum 5
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
                                   @ArgName("version") @ArgPrefixed @ArgDefaultNull String version,
                                   @ArgName("executor") @ArgPrefixed @ArgDefaultText("event") ObjectTag executor) {
        switch (action) {
            case "create":
                if (author == null || version == null) { throw new InvalidArgumentsRuntimeException("Author and version cannot be null."); }
                DExpansion expansion = new DExpansion();
                expansion.author = author;
                expansion.identifier = placeholder;
                expansion.version = version;
                expansion.executor = executor.getJavaObject();
                expansion.register();
                expansions.put(placeholder, expansion);
                break;
            case "delete":
                if (expansions.containsKey(placeholder)) {
                    expansions.get(placeholder).unregister();
                    expansions.remove(placeholder);
                } else {
                    throw new InvalidArgumentsRuntimeException("Placeholder '" + placeholder + "' does not exist.");
                }
                break;
            default:
                throw new InvalidArgumentsRuntimeException("Invalid action " + action + ". Expected 'create/delete'");
        }
    }

    @EventHandler
    public void onPapiReload(ExpansionsLoadedEvent event) {
        for (DExpansion expansion : expansions.values()) { expansion.register(); }
    }

    public static class DExpansion extends PlaceholderExpansion {

        String author = null;
        String identifier = null;
        String version = null;
        Object executor = null;

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
            if (executor.toString().equals("event")) {
                return PlaceholderEvent.runPlaceholder(identifier, params, player);
            }
            return null;
        }
    }


}