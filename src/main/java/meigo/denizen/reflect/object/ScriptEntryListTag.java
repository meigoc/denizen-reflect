package meigo.denizen.reflect.object;

import com.denizenscript.denizencore.objects.Fetchable;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.tags.Attribute;
import com.denizenscript.denizencore.tags.TagContext;

import java.util.List;

public class ScriptEntryListTag implements ObjectTag {

    public final List<ScriptEntry> entries;
    private String prefix = "ScriptEntryList";

    @Fetchable("scriptentrylist")
    public static ScriptEntryListTag valueOf(String string, TagContext context) {
        return null; // Not intended to be fetched from a string.
    }

    public static boolean matches(String arg) {
        return false;
    }

    public ScriptEntryListTag(List<ScriptEntry> entries) {
        this.entries = entries;
    }

    @Override
    public String getPrefix() {
        return prefix;
    }

    @Override
    public ObjectTag setPrefix(String prefix) {
        this.prefix = prefix;
        return this;
    }

    public String debug() {
        return "<G>" + prefix + " containing " + entries.size() + " entries.";
    }

    @Override
    public boolean isUnique() {
        return true;
    }

    public String getObjectType() {
        return "ScriptEntryList";
    }

    @Override
    public String identify() {
        return "scriptentrylist@" + hashCode();
    }

    @Override
    public String identifySimple() {
        return identify();
    }

    @Override
    public ObjectTag getObjectAttribute(Attribute attribute) {
        return null;
    }
}