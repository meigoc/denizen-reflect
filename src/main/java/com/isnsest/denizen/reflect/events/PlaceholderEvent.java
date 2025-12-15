package com.isnsest.denizen.reflect.events;

import com.denizenscript.denizen.objects.PlayerTag;
import com.denizenscript.denizen.utilities.implementation.BukkitScriptEntryData;
import com.denizenscript.denizencore.events.ScriptEvent;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntryData;
import org.bukkit.OfflinePlayer;

public class PlaceholderEvent extends ScriptEvent {

    // <--[event]
    // @Events
    // placeholder
    //
    // @Plugin denizen-reflect
    //
    // @Switch id:<id> to only process the event if a specific action is performed.
    //
    // @Group denizen-reflect
    //
    // @Triggers when a custom placeholder is filled in.
    //
    // @Context
    // <context.id> returns the ElementTag of the id command.
    // <context.params> returns the ElementTag of the params.
    //
    // @Determine
    // ElementTag to fill the placeholder.
    // -->

    public static PlaceholderEvent instance;

    public String id;
    public String params;
    public PlayerTag player;
    public String determination = null;



    public PlaceholderEvent() {
        instance = this;
        registerCouldMatcher("placeholder");
        registerSwitches("id");
        this.<PlaceholderEvent, ObjectTag>registerDetermination(null, ObjectTag.class, (evt, context, output) -> determination = output.toString());
    }

    @Override
    public boolean matches(ScriptPath path) {
        return runGenericSwitchCheck(path, "id", id);
    }

    @Override
    public ScriptEntryData getScriptEntryData() {
        return new BukkitScriptEntryData(player, null);
    }

    @Override
    public ObjectTag getContext(String name) {
        return switch (name) {
            case "id" -> new ElementTag(id);
            case "params" -> new ElementTag(params);
            default -> super.getContext(name);
        };
    }

    public static String runPlaceholder(String id, String params, OfflinePlayer player) {

        instance.determination = null;
        instance.id = id;
        instance.params = params;
        if (player != null) { instance.player = new PlayerTag(player); }

        instance.fire();
        return instance.determination;
    }
}
