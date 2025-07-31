package meigo.denizenreflect;

import com.denizenscript.denizen.Denizen;
import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import meigo.denizenreflect.commands.ReflectCommand;
import meigo.denizenreflect.objects.DenizenReflectedTag;
import meigo.denizenreflect.util.ReflectionUtil;
import org.bukkit.plugin.java.JavaPlugin;

public final class DenizenReflect extends JavaPlugin {

    @Override
    public void onEnable() {
        ReflectionUtil.init(Denizen.getInstance());
        ObjectFetcher.registerWithObjectFetcher(DenizenReflectedTag.class, DenizenReflectedTag.tagProcessor);
        DenizenCore.commandRegistry.registerCommand(ReflectCommand.class);
        getLogger().info("DenizenReflect has been enabled!");
    }

    @Override
    public void onDisable() {
        DenizenReflectedTag.cleanCache();
        getLogger().info("DenizenReflect has been disabled and object cache cleared.");
    }
}
