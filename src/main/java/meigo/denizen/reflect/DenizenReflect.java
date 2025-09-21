package meigo.denizen.reflect;

import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.ObjectType;
import com.denizenscript.denizencore.tags.Attribute;
import com.denizenscript.denizencore.tags.ObjectTagProcessor;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.commands.ImportCommand;
import meigo.denizen.reflect.commands.InvokeCommand;
import meigo.denizen.reflect.object.JavaObjectTag;
import meigo.denizen.reflect.util.JavaExpressionParser;
import meigo.denizen.reflect.util.ReflectionHandler;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
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

    private void injectGlobalTags() {
        int injectedCount = 0;
        for (ObjectType<? extends ObjectTag> type : ObjectFetcher.objectsByClass.values()) {
            ObjectTagProcessor<?> processor = type.tagProcessor;
            if (processor != null) {
                processor.registerTag(ObjectTag.class, "invoke", DenizenReflect::handleInvokeTag);
                processor.registerTag(JavaObjectTag.class, "identify", DenizenReflect::handleIdentifyTag);
                injectedCount++;
            }
        }
        Debug.log("Injected global tags into " + injectedCount + " object types.");
    }

    @Override
    public void onEnable() {
        instance = this;
        Debug.log("Loading DenizenReflect...");
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
            ObjectFetcher.registerWithObjectFetcher(JavaObjectTag.class, JavaObjectTag.tagProcessor);
            DenizenCore.commandRegistry.registerCommand(ImportCommand.class);
            DenizenCore.commandRegistry.registerCommand(InvokeCommand.class);
            injectGlobalTags();
        }
        catch (Throwable e) {
            Debug.echoError("Failed to register DenizenReflect components!");
            Debug.echoError(e);
        }

        Debug.log("DenizenReflect loaded successfully!");
    }
}