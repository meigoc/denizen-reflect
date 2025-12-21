/*
 * Copyright 2025 Meigo™ Corporation
 * SPDX-License-Identifier: Apache-2.0
 */

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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
        } catch (Exception e) {
            Debug.echoError("Failed to load external libraries for DenizenReflect:");
            Debug.echoError(e);
        }
        new Thread(() -> {

            while (PreScriptReloadScriptEvent.instance == null
                    || ScriptGeneratesErrorScriptEvent.instance == null
                    || ObjectFetcher.objectsByPrefix.isEmpty()) {

                try {
                    //noinspection BusyWait
                    Thread.sleep(10);
                }
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

        // Optimization: Check if bStats is enabled locally before making network requests
        if (isBStatsEnabled()) {
            int serviceId = getBStatsId();
            if (serviceId != -1) {
                Metrics metrics = new Metrics(this, serviceId);
                metrics.addCustomChart(
                        new Metrics.SimplePie("Denizen", () -> {
                            @SuppressWarnings("deprecation")
                            String version = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Denizen"))
                                    .getDescription()
                                    .getVersion();
                            return version;
                        })
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
            }
        }

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

    private int getBStatsId() {
        try {
            URL url = URI.create("https://bstats-id.meigo.pw/get/denizen-reflect").toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);

            if (connection.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String response = reader.readLine();
                    if (response != null && !response.isEmpty()) {
                        return Integer.parseInt(response.trim());
                    }
                }
            }
        } catch (Exception ignored) {
            // Service might be sleeping or unreachable, ignore
        }
        return -1;
    }

    /**
     * Checks if bStats is enabled in the global configuration to avoid unnecessary network calls.
     */
    private boolean isBStatsEnabled() {
        try {
            File bStatsFolder = new File(getDataFolder().getParentFile(), "bStats");
            File configFile = new File(bStatsFolder, "config.yml");
            if (!configFile.exists()) {
                return true;
            }
            return YamlConfiguration.loadConfiguration(configFile).getBoolean("enabled", true);
        } catch (Exception e) {
            return true;
        }
    }
}