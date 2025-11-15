package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.JavaReflectedObjectTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.ScriptEntryData;
import com.denizenscript.denizencore.scripts.commands.generator.ArgName;
import com.denizenscript.denizencore.scripts.commands.generator.ArgPrefixed;
import com.denizenscript.denizencore.scripts.queues.ContextSource;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.scripts.commands.BracedCommand;
import com.denizenscript.denizencore.utilities.ScriptUtilities;
import com.denizenscript.denizencore.utilities.text.StringHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class SectionCommand extends BracedCommand {


    public SectionCommand() {
        setName("section");
        setSyntax("section [as:<name>]");
        setRequiredArguments(1, 1);
        isProcedural = false;
        autoCompile();
    }

    // <--[command]
    // @Plugin denizen-reflect
    // @Name section
    // @Syntax section [as:<name>]
    // @Required 1
    // @Short
    // @Group denizen-reflect
    //
    // @Description
    // A group of commands inside.
    //
    // @Usage
    // - section:
    //     - narrate 123
    // -->

    public static class Section {
        public List<ScriptEntry> directEntries;
        public ContextSource contextSource;
        public String queueId;
        public ScriptEntryData entryData;
        public MapTag defMap;

        @SuppressWarnings("unused")
        public void run() {

            Consumer<ScriptQueue> configure = (queue) -> {
                if (this.defMap != null) {
                    for (Map.Entry<StringHolder, ObjectTag> val : defMap.entrySet()) {
                        queue.addDefinition(val.getKey().str, val.getValue());
                    }
                }
            };

            ScriptUtilities.createAndStartQueueArbitrary(queueId, directEntries, entryData, contextSource, configure);

        }

    }

    @SuppressWarnings("unused")
    public static void autoExecute(ScriptEntry scriptEntry,
                                   @ArgName("as") @ArgPrefixed String define) {
        UUID id = UUID.randomUUID();

        Section section = new Section();
        section.directEntries = getBracedCommandsDirect(scriptEntry, scriptEntry);
        section.queueId = "SECTION_" + scriptEntry.getScript().getContainer().getName();
        section.entryData = scriptEntry.entryData;
        section.contextSource = scriptEntry.context.contextSource;
        section.defMap = scriptEntry.queue.definitions.duplicate();

        scriptEntry.getResidingQueue().addDefinition(define, new JavaReflectedObjectTag(section));
    }
}