package meigo.denizen.reflect;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.tags.Attribute;
import com.denizenscript.denizencore.tags.core.UtilTagBase;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.commands.ImportCommand;
import meigo.denizen.reflect.commands.InvokeCommand;
import meigo.denizen.reflect.commands.ProxyCommand;
import meigo.denizen.reflect.commands.SectionCommand;
import meigo.denizen.reflect.object.JavaObjectTag;
import meigo.denizen.reflect.util.JavaExpressionParser;
import meigo.denizen.reflect.util.LibraryLoader;
import meigo.denizen.reflect.util.ReflectionHandler;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class DenizenReflect extends JavaPlugin {

    public static DenizenReflect instance;
    public static List<String> allowedPackages;

    private static ObjectTag handleInvokeTag(Attribute attribute, ObjectTag object) {
        String expression = attribute.getParam();
        if (expression == null || expression.isEmpty()) {
            Debug.echoError(attribute.context, "The invoke[] tag requires a Java expression.");
            return null;
        }
        try {
            if (object instanceof JavaObjectTag) {
                ((JavaObjectTag) object).updateAccessTime();
            }
            Object javaObject = object.getJavaObject();
            if (javaObject == null) {
                Debug.echoError(attribute.context, "Cannot invoke on a null object.");
                return null;
            }
            JavaExpressionParser parser = new JavaExpressionParser(attribute.context);
            Object result = parser.execute(expression, javaObject);
            return ReflectionHandler.wrapObject(result, attribute.context);
        }
        catch (Exception e) {
            Debug.echoError(attribute.context, "Error within invoke[] tag: " + e.getMessage());
            return null;
        }
    }

    private static JavaObjectTag handleIdentifyTag(Attribute attribute, ObjectTag object) {
        if (object instanceof JavaObjectTag) {
            attribute.echoError("Cannot re-identify an object that is already a JavaObjectTag.");
            return null;
        }
        JavaObjectTag newJavaTag = new JavaObjectTag(object.getJavaObject());
        newJavaTag.persist();
        return newJavaTag;
    }

    private static JavaObjectTag handleImportTag(Attribute attribute, ObjectTag object) {
        return ReflectionHandler.importClass(attribute.getParamElement(), attribute.getScriptEntry());
    }

    @Override
    public void onEnable() {
        instance = this;
        Debug.log("Loading DenizenReflect..");
        saveDefaultConfig();

        allowedPackages = getConfig().getStringList("security.allowed-packages").stream()
                .map(p -> p.endsWith(".") ? p : p + ".")
                .collect(Collectors.toList());

        if (allowedPackages.isEmpty()) {
            Debug.echoError("Security warning: No packages are whitelisted in config.yml.");
        }
        else {
            Debug.log("Allowed packages: " + String.join(", ", allowedPackages));
        }

        try {
            Path libsFolder = getDataFolder().toPath().resolve("libs");
            LibraryLoader.loadLibraries(libsFolder);
        } catch (IOException e) {
            Debug.echoError("Failed to load external libraries for DenizenReflect:");
            Debug.echoError(e);
        }

        try {
            ObjectFetcher.registerWithObjectFetcher(JavaObjectTag.class, JavaObjectTag.tagProcessor);
            DenizenCore.commandRegistry.registerCommand(ImportCommand.class);
            DenizenCore.commandRegistry.registerCommand(InvokeCommand.class);
            DenizenCore.commandRegistry.registerCommand(SectionCommand.class);
            DenizenCore.commandRegistry.registerCommand(ProxyCommand.class);
            ObjectFetcher.getType(UtilTagBase.class).tagProcessor.registerTag(ObjectTag.class, "import", DenizenReflect::handleImportTag);
            ObjectFetcher.objectsByClass.values().forEach(object -> {
                object.tagProcessor.registerTag(ObjectTag.class, "invoke", DenizenReflect::handleInvokeTag);
                object.tagProcessor.registerTag(JavaObjectTag.class, "identify", DenizenReflect::handleIdentifyTag);
            });
        }
        catch (Throwable e) {
            Debug.echoError("Failed to register DenizenReflect components!");
            Debug.echoError(e);
        }

        Debug.log("DenizenReflect loaded successfully!");
    }
}