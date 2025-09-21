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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class JavaObjectTag implements ObjectTag, Adjustable {

    public final Object heldObject;
    public final boolean isStatic;
    private UUID uniqueId;

    private static final Map<UUID, JavaObjectTag> persistedInstances = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastAccessTimes = new ConcurrentHashMap<>();
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final long CLEANUP_INTERVAL_MINUTES = 5;
    private static final long EXPIRY_TIME_MINUTES = 5;

    private static ScheduledExecutorService cleanupExecutor;

    static {
        startCleanupTask();
    }

    private static void startCleanupTask() {
        if (cleanupExecutor == null) {
            cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "JavaObjectTag-Cleanup");
                t.setDaemon(true);
                return t;
            });

            cleanupExecutor.scheduleAtFixedRate(() -> {
                try {
                    cleanupExpiredInstances();
                } catch (Exception e) {
                    Debug.echoError("Error during JavaObjectTag cleanup: " + e.getMessage());
                }
            }, CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        }
    }

    private static void cleanupExpiredInstances() {
        long currentTime = System.currentTimeMillis();
        long expiryThreshold = currentTime - TimeUnit.MINUTES.toMillis(EXPIRY_TIME_MINUTES);
        int cleanedCount = 0;

        Iterator<Map.Entry<UUID, Long>> iterator = lastAccessTimes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() < expiryThreshold) {
                UUID expiredUUID = entry.getKey();
                iterator.remove();
                persistedInstances.remove(expiredUUID);
                cleanedCount++;
            }
        }

        if (cleanedCount > 0) {
            //Debug.log("Cleaned up " + cleanedCount + " expired JavaObjectTag instances");
        }
    }

    public static void shutdown() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            cleanupExecutor = null;
        }
    }

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

    public void updateAccessTime() {
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
            UUID uuid;
            try {
                uuid = UUID.fromString(string);
            } catch (IllegalArgumentException e) {
                uuid = null;
            }
            if (uuid != null && persistedInstances.containsKey(uuid)) {
                JavaObjectTag instance = persistedInstances.get(uuid);
                instance.updateAccessTime();
                return instance;
            }
        }
        Class<?> clazz = ReflectionHandler.getClass(string, context);
        if (clazz != null) {
            return new JavaObjectTag(clazz);
        }
        return null;
    }

    public static boolean matches(String arg) {
        return arg.startsWith("java@");
    }

    public static final ObjectTagProcessor<JavaObjectTag> tagProcessor = new ObjectTagProcessor<>();

    public static void register() {
        // A simple method invoker that takes "methodName(arg1|arg2|...)"
        // Kept for backward compatibility or simple use cases.
        tagProcessor.registerTag(ObjectTag.class, "invoke_simple", (attribute, object) -> {
            String param = attribute.getParam();
            String methodName;
            List<ObjectTag> params;
            int bracketIndex = param.indexOf('(');
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
            return CoreUtilities.objectToTagForm(object.heldObject, attribute.context);
        });
        //tagProcessor.registerTag(ElementTag.class, "identify", (attribute, object) -> new ElementTag(object.identify()));
        tagProcessor.registerTag(ElementTag.class, "debug", (attribute, object) -> new ElementTag(object.debuggable()));
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

    private static List<ObjectTag> parseArguments(String args, TagContext context) {
        if (args.isEmpty()) {
            return Collections.emptyList();
        }
        // Use comma as a separator to align with Java syntax. Pipe is no longer supported in expressions.
        return ListTag.valueOf(args.replace('|', ','), context).objectForms;
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
    @Override public String toString() { return identify(); }
}