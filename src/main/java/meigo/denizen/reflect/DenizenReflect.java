package meigo.denizen.reflect;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.events.ScriptEvent;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.commands.*;
import meigo.denizen.reflect.events.CustomCommandEvent;
import meigo.denizen.reflect.events.CustomTagEvent;
import meigo.denizen.reflect.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;

public class DenizenReflect extends JavaPlugin {

    public static DenizenReflect instance;
    private static final String ANSI_BRIGHT_RED = "\u001B[91m";
    private static final String ANSI_RESET = "\u001B[0m";



    public static void send(String tag, String msg) {
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        String coloredTag = ANSI_BRIGHT_RED + "+> [" + tag + "]" + ANSI_RESET;
        String out = coloredTag + " " + msg;
        console.sendRawMessage(out);
    }

    private static void run() {
        int lastSize = -1;
        int stableIterations = 0;
        final String pluginName = "[]";

        while (true) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            int currentSize;
            try {
                currentSize = ObjectFetcher.objectsByPrefix.size();
            } catch (Exception e) {
                System.out.println(pluginName + " Error accessing objectByPrefix: " + e.getMessage());
                break;
            }

            if (currentSize == lastSize) {
                stableIterations++;
            } else {
                stableIterations = 0;
                lastSize = currentSize;
            }

            if (currentSize > 0 && stableIterations >= 3) {
                send("denizen-reflect", "Import-syntax support enabled.");

                try {
                    ImportManager.registerEventHooks();
                } catch (Exception e) {
                    System.out.println(pluginName + " CRITICAL ERROR while running registerEventHooks from async thread!");
                    e.printStackTrace();
                }

                break;
            }
        }
    }

    @Override
    public void onLoad() {
        new Thread(DenizenReflect::run).start();
    }

    @Override
    public void onEnable() {
        instance = this;
        Debug.log("Loading DenizenReflect..");
        saveDefaultConfig();

        if (getConfig().getBoolean("experimental.invoke-in-commands")) {
            CommandWrapperUtil.interceptAll(DenizenCore.commandRegistry.instances);
        }

        try {
            Path libsFolder = getDataFolder().toPath().resolve("libs");
            LibraryLoader.loadLibraries(libsFolder);
        } catch (IOException e) {
            Debug.echoError("Failed to load external libraries for DenizenReflect:");
            Debug.echoError(e);
        }

        try {
            DenizenCore.commandRegistry.registerCommand(InvokeCommand.class);
            DenizenCore.commandRegistry.registerCommand(TagCommand.class);
            DenizenCore.commandRegistry.registerCommand(Command.class);
            DenizenCore.commandRegistry.registerCommand(EventCommand.class);
            DenizenCore.commandRegistry.registerCommand(SectionCommand.class);
            DenizenCore.commandRegistry.registerCommand(ProxyCommand.class);

            //

            ScriptEvent.registerScriptEvent(CustomTagEvent.class);
            ScriptEvent.registerScriptEvent(CustomCommandEvent.class);

            // <--[tag]
            // @attribute <invoke[<java_expression>]>
            // @returns ObjectTag
            // @description
            // Calls java code and returns result.
            // -->
            TagManager.registerTagHandler(ObjectTag.class, "invoke", (attribute) -> {
                    String path = attribute.getScriptEntry().getScript().getContainer().getRelativeFileName();
                    path = path.substring(path.indexOf("scripts/") + "scripts/".length());
                    String result = JavaExpressionEngine.execute(attribute.getParam(), attribute.getScriptEntry()).toString();
                    return ObjectFetcher.pickObjectFor(result, attribute.context);
            });


        }
        catch (Throwable e) {
            Debug.echoError("Failed to register DenizenReflect components!");
            Debug.echoError(e);
        }

        Debug.log("DenizenReflect loaded successfully!");
        ImportManager._import();
    }

    @Override
    public void onDisable() {
    }
}