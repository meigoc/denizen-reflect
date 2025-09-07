package meigo.denizen.reflect.util;

import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.utilities.debugging.Debug;

import java.util.Locale;

/**
 * A utility class to parse typed arguments in the format "type#value".
 * Used by the ImportCommand to correctly type constructor parameters.
 */
public class ArgumentParamParser {

    /**
     * Parses a single argument string into an ObjectTag.
     * Supports explicitly typed primitives (e.g., "int#5") and standard Denizen object fetching.
     *
     * @param argStr  The argument string to parse.
     * @param context The tag context.
     * @return The parsed ObjectTag, or an ElementTag as a fallback.
     */
    public static ObjectTag parse(String argStr, TagContext context) {
        int atIndex = argStr.indexOf('#');
        if (atIndex > 0) {
            String typeName = argStr.substring(0, atIndex);
            String value = argStr.substring(atIndex + 1);
            ObjectTag typedArg = createTypedArgument(typeName, value, context);
            if (typedArg != null) {
                return typedArg;
            }
        }
        // If no type or parsing failed, try to parse as a Denizen object
        if (argStr.contains("@")) {
            try {
                ObjectTag obj = ObjectFetcher.pickObjectFor(argStr, context);
                if (obj != null) {
                    return obj;
                }
            } catch (Exception ignored) {
                // fallback to plain text
            }
        }
        return new ElementTag(argStr);
    }

    private static ObjectTag createTypedArgument(String typeName, String value, TagContext context) {
        try {
            switch (typeName.toLowerCase(Locale.ENGLISH)) {
                case "int": case "integer":
                    return new ElementTag(Integer.parseInt(value));
                case "long":
                    return new ElementTag(Long.parseLong(value));
                case "float":
                    return new ElementTag(Float.parseFloat(value));
                case "double":
                    return new ElementTag(Double.parseDouble(value));
                case "boolean":
                    return new ElementTag(Boolean.parseBoolean(value));
                case "byte":
                    return new ElementTag(Byte.parseByte(value));
                case "short":
                    return new ElementTag(Short.parseShort(value));
                case "string":
                    return new ElementTag(value);
                // For other class types, we primarily rely on Denizen's object fetching.
                // This switch is for explicit primitive casting.
                default:
                    return null;
            }
        } catch (Exception e) {
            Debug.echoError("Failed to convert argument '" + value + "' to type '" + typeName + "': " + e.getMessage());
            return null;
        }
    }
}