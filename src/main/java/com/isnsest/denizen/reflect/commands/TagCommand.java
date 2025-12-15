package com.isnsest.denizen.reflect.commands;

import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.generator.*;
import com.denizenscript.denizencore.tags.ObjectTagProcessor;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.isnsest.denizen.reflect.events.CustomTagEvent;

public class TagCommand extends AbstractCommand {

    // @Plugin denizen-reflect
    public TagCommand() {
        setName("tag");
        setSyntax("tag [create/delete] [<tag_name>] (static:true/false) (in:<object_name>)");
        setRequiredArguments(2, 4);
        isProcedural = false;
        autoCompile();
    }

    // <--[command]
    // @Name Tag
    // @Syntax tag [create/delete] [<tag_name>] (static:true/false) (in:<object_name>)
    // @Required 2
    // @Maximum 4
    // @Short Tag manager.
    // @Group denizen-reflect
    //
    // @Description
    // Creates or deletes a custom tag.
    //
    // @Usage
    // Use to create tag <hello>.
    // - tag create hello
    //
    // @Usage
    // Use to create tag <player.hello>.
    // - tag create hello in:player
    //
    // -->

    @SuppressWarnings("unused")
    public static void autoExecute(ScriptEntry scriptEntry,
                            @ArgName("action") @ArgLinear String action,
                            @ArgName("tag_name") @ArgLinear String tag_name,
                            @ArgName("static") @ArgPrefixed @ArgDefaultText("false") boolean _static,
                            @ArgName("in") @ArgPrefixed @ArgDefaultText("null") String in) {
        switch (action) {
            case "create":
                if (!in.equals("null")) {
                    try {
                        ObjectTagProcessor<? extends ObjectTag> processor = TagManager.baseTags.get(in).processor;
                        if (processor.registeredObjectTags.containsKey(tag_name)) {
                            Debug.echoError("Tag '" + tag_name + "' already created in " + in);
                        } else {
                            processor.registerTagInternal(ObjectTag.class, tag_name, (attribute, object) -> {
                                CustomTagEvent ranEvent = CustomTagEvent.runCustomTag(scriptEntry.entryData, attribute, ObjectFetcher.pickObjectFor(object.identify(), attribute.context), tag_name);
                                return ranEvent.determination;
                            }, _static, new String[0]);
                        }
                    } catch (Exception e) {
                        Debug.echoError("Base Tag '" + in + "' not found. (Exception)");
                    }
                } else {
                    if (TagManager.baseTags.containsKey(tag_name)) {
                        Debug.echoError("Base Tag '" + tag_name + "' already created.");
                    } else {
                        TagManager.internalRegisterTagHandler(ObjectTag.class, tag_name, (attribute) -> {
                            CustomTagEvent ranEvent = CustomTagEvent.runCustomTag(scriptEntry.entryData, attribute, new ElementTag("null"), tag_name);
                            return ranEvent.determination;
                        }, _static);
                    }
                }
                break;
            case "delete":
                if (!in.equals("null")) {
                    try {
                        ObjectTagProcessor<? extends ObjectTag> processor = TagManager.baseTags.get(in).processor;
                        if (!processor.registeredObjectTags.containsKey(tag_name)) {
                            Debug.echoError("Tag '" + tag_name + "' not found in " + in);
                        } else {
                            processor.registeredObjectTags.remove(tag_name);
                        }
                    } catch (Exception e) {
                        Debug.echoError("Base Tag '" + in + "' not found. (Exception[2])");
                    }
                } else {
                    if (!TagManager.baseTags.containsKey(tag_name)) {
                        Debug.echoError("Base Tag '" + tag_name + "' not found.");
                    } else {
                        TagManager.baseTags.remove(tag_name);
                    }
                }
                break;
            default:
                Debug.echoError("Invalid action " + action + ". Expected 'create' or 'delete'.");
                break;

        }
    }
}