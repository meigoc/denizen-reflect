package meigo.denizen.reflect;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.commands.ReflectCommand;
import meigo.denizen.reflect.object.JavaObjectTag;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Collectors;

public class DenizenReflect extends JavaPlugin {

    public static DenizenReflect instance;
    public static List<String> allowedPackages;

    @Override
    public void onEnable() {
        instance = this;
        Debug.log("DenizenReflect loading...");
        saveDefaultConfig();

        // Load and process the allowed packages from config.yml
        allowedPackages = getConfig().getStringList("security.allowed-packages").stream()
                .map(p -> p.endsWith(".")? p : p + ".")
                .collect(Collectors.toList());

        if (allowedPackages.isEmpty()) {
            Debug.echoError(" Security warning: No packages are whitelisted in config.yml. The plugin will not be able to reflect any classes.");
        }
        else {
            Debug.log("Allowed packages: " + String.join(", ", allowedPackages));
        }

        try {
            ObjectFetcher.registerWithObjectFetcher(JavaObjectTag.class, JavaObjectTag.tagProcessor);
            DenizenCore.commandRegistry.registerCommand(ReflectCommand.class);
        }
        catch (Throwable e) {
            Debug.echoError("Error registering DenizenReflect components!");
            Debug.echoError(e);
        }

        Debug.log("DenizenReflect loaded successfully!");
    }
}