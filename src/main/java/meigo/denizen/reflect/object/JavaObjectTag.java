package meigo.denizen.reflect.object;

import com.denizenscript.denizencore.objects.*;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.tags.Attribute;
import com.denizenscript.denizencore.tags.ObjectTagProcessor;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import meigo.denizen.reflect.util.ReflectionHandler;

import java.util.*;
import java.util.regex.Pattern;

public class JavaObjectTag implements ObjectTag, Adjustable {

    public final Object heldObject;
    public final boolean isStatic;
    private UUID uniqueId;

    // Persisted instances (storage only — automatic cleanup was removed).
    private static final Map<UUID, JavaObjectTag> persistedInstances = new HashMap<>();
    private static final Map<UUID, Long> lastAccessTimes = new HashMap<>();
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public JavaObjectTag(Object object) {
        this.heldObject = object;
        this.isStatic = false;
    }

    public JavaObjectTag(Class<?> clazz) {
        this.heldObject = clazz;
        this.isStatic = true;
    }

    public void persist() {
        if (!isStatic && uniqueId == null) {
            uniqueId = UUID.randomUUID();
            persistedInstances.put(uniqueId, this);
            lastAccessTimes.put(uniqueId, System.currentTimeMillis());
        }
    }

    private void updateAccessTime() {
        if (uniqueId != null) {
            lastAccessTimes.put(uniqueId, System.currentTimeMillis());
        }
    }

    @Fetchable("java")
    public static JavaObjectTag valueOf(String string, TagContext context) {
        if (string == null) return null;
        if (string.startsWith("java@")) {
            string = string.substring("java@".length());
        }

        if (UUID_PATTERN.matcher(string).matches()) {
            UUID uuid = CoreUtilities.tryParseUUID(string);
            if (uuid != null && persistedInstances.containsKey(uuid)) {
                JavaObjectTag instance = persistedInstances.get(uuid);
                instance.updateAccessTime(); // Update access time for storage record
                return instance;
            }
        }

        try {
            Class<?> clazz = Class.forName(string);
            if (!ReflectionHandler.isClassAllowed(clazz)) {
                if (context == null || context.showErrors()) {
                    Debug.echoError("Access to class '" + clazz.getName() + "' is denied.");
                }
                return null;
            }
            return new JavaObjectTag(clazz);
        } catch (ClassNotFoundException e) {
            return null; // Not a class name; that's okay.
        }
    }

    public static boolean matches(String arg) {
        return arg.startsWith("java@");
    }

    public static final ObjectTagProcessor<JavaObjectTag> tagProcessor = new ObjectTagProcessor<>();

    public static void register() {
        // --- TAGS ---
        tagProcessor.registerTag(ObjectTag.class, "invoke", (attribute, object) -> {
            String param = attribute.getParam();
            String methodName;
            List<ObjectTag> params;

            int bracketIndex = param.indexOf("(");
            if (bracketIndex > -1 && param.endsWith(")")) {
                methodName = param.substring(0, bracketIndex);
                String args = param.substring(bracketIndex + 1, param.length() - 1);
                params = parseArguments(args, attribute.context);
            } else {
                methodName = param;
                params = new ArrayList<>();
            }

            Object result;
            if (object.isStatic) {
                result = ReflectionHandler.invokeStaticMethod((Class<?>) object.heldObject, methodName, params, attribute.context);
            } else {
                object.updateAccessTime();
                result = ReflectionHandler.invokeMethod(object.heldObject, methodName, params, attribute.context);
            }

            return ReflectionHandler.wrapObject(result, attribute.context);
        });

        tagProcessor.registerTag(ObjectTag.class, "field", (attribute, object) -> {
            String fieldName = attribute.getParam();
            Object result;

            if (object.isStatic) {
                result = ReflectionHandler.getStaticField((Class<?>) object.heldObject, fieldName, attribute.context);
            } else {
                object.updateAccessTime();
                result = ReflectionHandler.getField(object.heldObject, fieldName, attribute.context);
            }

            return ReflectionHandler.wrapObject(result, attribute.context);
        });

        tagProcessor.registerTag(ElementTag.class, "class_name", (attribute, object) -> {
            Class<?> clazz = object.isStatic ? (Class<?>) object.heldObject : object.heldObject.getClass();
            return new ElementTag(clazz.getName());
        });

        tagProcessor.registerTag(ObjectTag.class, "interpret", (attribute, object) -> {
            if (object.isStatic) {
                return object;
            }
            object.updateAccessTime();
            // For non-simple Java objects, return JavaObjectTag so methods remain accessible.
            Object held = object.heldObject;
            if (held == null) return null;
            // If CoreUtilities can convert it to a Denizen native form, prefer it for simple types.
            ObjectTag converted = CoreUtilities.objectToTagForm(held, attribute.context, false, false, false);
            if (converted instanceof ElementTag) {
                // If result is simple (string/number/bool), return converted, otherwise return JavaObjectTag
                if (held instanceof String || held instanceof Number || held instanceof Boolean) {
                    return converted;
                } else {
                    return object;
                }
            }
            return converted;
        });

        // --- MECHANISMS ---
        tagProcessor.registerMechanism("set_field", false, MapTag.class, (object, mechanism, map) -> {
            for (Map.Entry<StringHolder, ObjectTag> entry : map.entrySet()) {
                if (object.isStatic) {
                    ReflectionHandler.setStaticField((Class<?>) object.heldObject, entry.getKey().str, entry.getValue(), mechanism.context);
                } else {
                    object.updateAccessTime();
                    ReflectionHandler.setField(object.heldObject, entry.getKey().str, entry.getValue(), mechanism.context);
                }
            }
        });
    }

    // Improved parsing of arguments: always use ListTag parser to correctly understand Denizen escaping / tags.
    private static List<ObjectTag> parseArguments(String args, TagContext context) {
        if (args == null || args.isEmpty()) {
            return Collections.emptyList();
        }
        return ListTag.valueOf(args, context).objectForms;
    }

    @Override public String getPrefix() { return "java"; }
    @Override public ObjectTag setPrefix(String prefix) { return this; }
    @Override public String debuggable() {
        if (isStatic) {
            return "<LG>java@<Y>" + ((Class<?>) heldObject).getName();
        }
        return "<LG>java@<Y>" + (uniqueId != null ? uniqueId : "unpersisted") + " <G>(" + heldObject.getClass().getName() + " instance)";
    }
    @Override public boolean isUnique() { return !isStatic; }
    @Override public String identify() {
        if (isStatic) {
            return "java@" + ((Class<?>) heldObject).getName();
        }
        persist();
        return "java@" + uniqueId.toString();
    }
    @Override public String identifySimple() { return identify(); }
    @Override public Object getJavaObject() { return heldObject; }
    @Override public ObjectTag getObjectAttribute(Attribute attribute) { return tagProcessor.getObjectAttribute(this, attribute); }
    @Override public void adjust(Mechanism mechanism) { tagProcessor.processMechanism(this, mechanism); }
    @Override public void applyProperty(Mechanism mechanism) { Debug.echoError("Cannot apply properties to a JavaObjectTag!"); }
}