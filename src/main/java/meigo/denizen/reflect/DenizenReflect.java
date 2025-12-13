package meigo.denizen.reflect;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.events.ScriptEvent;
import com.denizenscript.denizencore.events.core.PreScriptReloadScriptEvent;
import com.denizenscript.denizencore.events.core.ScriptGeneratesErrorScriptEvent;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.commands.*;
import meigo.denizen.reflect.events.CustomCommandEvent;
import meigo.denizen.reflect.events.CustomTagEvent;
import meigo.denizen.reflect.events.PlaceholderEvent;
import meigo.denizen.reflect.util.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class DenizenReflect extends JavaPlugin {

    public static DenizenReflect instance;

    public static DenizenReflect getInstance() {
        return instance;
    }

    @Override
    public void onLoad() {
        try {
            Path libsFolder = getDataFolder().toPath().resolve("libs");
            LibraryLoader.loadLibraries(libsFolder);
        } catch (IOException e) {
            Debug.echoError("Failed to load external libraries for DenizenReflect:");
            Debug.echoError(e);
        }
        new Thread(() -> {

            while (PreScriptReloadScriptEvent.instance == null
                    || ScriptGeneratesErrorScriptEvent.instance == null
                    || ObjectFetcher.objectsByPrefix.isEmpty()) {

                try { Thread.sleep(10); }
                catch (InterruptedException ignored) { return; }
            }

            ImportManager.registerEventHooks();

        }, "Denizen-Reflect-Init").start();
    }


    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        Debug.log("denizen-reflect", "Loading..");

        Metrics metrics = new Metrics(this, 27365);
        metrics.addCustomChart(
                new Metrics.SimplePie("Denizen", () -> Bukkit.getPluginManager().getPlugin("Denizen").getDescription().getVersion())
        );
        metrics.addCustomChart(
                new Metrics.AdvancedPie("libraries", () -> {
                    Map<String, Integer> data = new HashMap<>();
                    for (String libraryName : LibraryLoader.libraries) {
                        data.put(libraryName, 1);
                    }
                    return data;
                })
        );

        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                DenizenCore.commandRegistry.registerCommand(PlaceholderCommand.class);
                ScriptEvent.registerScriptEvent(PlaceholderEvent.class);
            }
            DenizenCore.commandRegistry.registerCommand(AsyncWhileCommand.class);
            DenizenCore.commandRegistry.registerCommand(InvokeCommand.class);
            DenizenCore.commandRegistry.registerCommand(TagCommand.class);
            DenizenCore.commandRegistry.registerCommand(Command.class);
            DenizenCore.commandRegistry.registerCommand(EventCommand.class);
            DenizenCore.commandRegistry.registerCommand(SectionCommand.class);
            DenizenCore.commandRegistry.registerCommand(ProxyCommand.class);

            //

            ScriptEvent.registerScriptEvent(CustomTagEvent.class);
            ScriptEvent.registerScriptEvent(CustomCommandEvent.class);

        }
        catch (Throwable e) {
            Debug.echoError("Failed to register denizen-reflect components!");
            Debug.echoError(e.getMessage());
        }

        Debug.log("denizen-reflect", "Loaded successfully!");
    }

    @Override
    public void onDisable() {
    }
}