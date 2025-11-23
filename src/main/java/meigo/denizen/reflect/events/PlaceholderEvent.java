package meigo.denizen.reflect.events;

import com.denizenscript.denizencore.events.ScriptEvent;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;

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
    // @Triggers when a custom command is executed.
    //
    // @Context
    // <context.id> returns the ElementTag of the id command.
    // <context.params> returns the ObjectTag of the params.
    //
    // @Determine
    // ElementTag to fill the placeholder.
    // -->

    public static PlaceholderEvent instance;

    public String id;
    public String params;
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
    public ObjectTag getContext(String name) {
        return switch (name) {
            case "id" -> new ElementTag(id);
            case "params" -> new ElementTag(params);
            default -> super.getContext(name);
        };
    }

    public static String runPlaceholder(String id, String params) {

        instance.id = id;
        instance.params = params;

        instance.fire();
        return instance.determination;
    }
}
