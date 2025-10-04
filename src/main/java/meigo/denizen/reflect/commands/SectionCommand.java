package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.BracedCommand;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.object.ScriptEntryListTag;

import java.util.List;

public class SectionCommand extends BracedCommand {

    public SectionCommand() {
        this.setName("section");
        this.setSyntax("section as:<id> (def:<name>|...) [<commands>]");
        this.setRequiredArguments(1, -1);
        this.setPrefixesHandled("as", "def");
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        ElementTag id = scriptEntry.argForPrefixAsElement("as", null);
        ListTag defNames = scriptEntry.argForPrefix("def", ListTag.class, false);

        if (id == null) {
            Debug.echoError(scriptEntry, "Must specify an ID with 'as:' prefix for the section!");
            return;
        }

        String idString = id.asString().toLowerCase();

        List<ScriptEntry> entries = getBracedCommandsDirect(scriptEntry, scriptEntry);

        if (entries == null || entries.isEmpty()) {
            Debug.echoError(scriptEntry, "Cannot define an empty section. Braces must contain commands.");
            return;
        }

        ScriptEntryListTag commandList = new ScriptEntryListTag(entries);

        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, this.getName(), id, defNames, db("entries", entries.size()));
        }

        MapTag sectionData = new MapTag();
        sectionData.putObject("commands", commandList);
        if (defNames != null) {
            sectionData.putObject("definitions", defNames);
        }

        ScriptQueue queue = scriptEntry.getResidingQueue();
        if (queue != null) {
            queue.addDefinition(idString, sectionData);
        } else {
            Debug.echoError(scriptEntry, "ScriptEntry does not belong to a queue. Cannot save section.");
        }
    }
}