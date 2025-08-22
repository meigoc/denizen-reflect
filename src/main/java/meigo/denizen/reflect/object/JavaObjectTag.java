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

    // Автоматическая очистка каждые 5 минут для объектов, которые не использовались 5 минут
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

        Set<UUID> expiredUUIDs = new HashSet<>();

        for (Map.Entry<UUID, Long> entry : lastAccessTimes.entrySet()) {
            if (entry.getValue() < expiryThreshold) {
                expiredUUIDs.add(entry.getKey());
            }
        }

        for (UUID expiredUUID : expiredUUIDs) {
            persistedInstances.remove(expiredUUID);
            lastAccessTimes.remove(expiredUUID);
        }

        if (!expiredUUIDs.isEmpty()) {
            Debug.log("Cleaned up " + expiredUUIDs.size() + " expired JavaObjectTag instances");
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
                instance.updateAccessTime(); // Обновляем время доступа
                return instance;
            }
        }

        try {
            Class<?> clazz = Class.forName(string);
            if (!ReflectionHandler.isClassAllowed(clazz)) {
                if (context == null || context.showErrors()) {
                    Debug.echoError("Access to class '" + clazz.getName() + "' is denied.");
                }
                return null; // ИСПРАВЛЕНО: return здесь, а не после
            }
            return new JavaObjectTag(clazz);
        } catch (ClassNotFoundException e) {
            return null; // It's not a class name, which is fine.
        }
    }

    public static boolean matches(String arg) {
        return arg.startsWith("java@");
    }

    public static final ObjectTagProcessor<JavaObjectTag> tagProcessor = new ObjectTagProcessor<>();

    public static void register() {
        // --- TAGS ---
        tagProcessor.registerTag(ObjectTag.class, "invoke", (attribute, object) -> {
            if (object.isStatic) {
                attribute.echoError("Cannot invoke an instance method on a static class reference. Use 'static_invoke' instead.");
                return null;
            }
            object.updateAccessTime(); // Обновляем время доступа при использовании

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

            Object result = ReflectionHandler.invokeMethod(object.heldObject, methodName, params, attribute.context);
            return ReflectionHandler.wrapObject(result, attribute.context);
        });

        tagProcessor.registerTag(ObjectTag.class, "static_invoke", (attribute, object) -> {
            if (!object.isStatic) {
                attribute.echoError("Cannot invoke a static method on an object instance. Use 'invoke' instead.");
                return null;
            }

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

            Object result = ReflectionHandler.invokeStaticMethod((Class<?>) object.heldObject, methodName, params, attribute.context);
            return ReflectionHandler.wrapObject(result, attribute.context);
        });

        tagProcessor.registerTag(ObjectTag.class, "field", (attribute, object) -> {
            if (object.isStatic) {
                attribute.echoError("Cannot read an instance field from a static class reference. Use 'static_field' instead.");
                return null;
            }
            object.updateAccessTime();

            String fieldName = attribute.getParam();
            Object result = ReflectionHandler.getField(object.heldObject, fieldName, attribute.context);
            return ReflectionHandler.wrapObject(result, attribute.context);
        });

        tagProcessor.registerTag(ObjectTag.class, "static_field", (attribute, object) -> {
            if (!object.isStatic) {
                attribute.echoError("Cannot read a static field from an object instance. Use 'field' instead.");
                return null;
            }
            String fieldName = attribute.getParam();
            Object result = ReflectionHandler.getStaticField((Class<?>) object.heldObject, fieldName, attribute.context);
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

        // --- MECHANISMS ---
        tagProcessor.registerMechanism("set_field", false, MapTag.class, (object, mechanism, map) -> {
            if (object.isStatic) {
                mechanism.echoError("Cannot set an instance field on a static class reference.");
                return;
            }
            object.updateAccessTime();
            for (Map.Entry<StringHolder, ObjectTag> entry : map.entrySet()) {
                ReflectionHandler.setField(object.heldObject, entry.getKey().str, entry.getValue(), mechanism.context);
            }
        });

        tagProcessor.registerMechanism("set_static_field", false, MapTag.class, (object, mechanism, map) -> {
            if (!object.isStatic) {
                mechanism.echoError("Cannot set a static field on an object instance.");
                return;
            }
            for (Map.Entry<StringHolder, ObjectTag> entry : map.entrySet()) {
                ReflectionHandler.setStaticField((Class<?>) object.heldObject, entry.getKey().str, entry.getValue(), mechanism.context);
            }
        });
    }

    // Улучшенный парсинг аргументов
    private static List<ObjectTag> parseArguments(String args, TagContext context) {
        if (args.isEmpty()) {
            return Collections.emptyList();
        }

        // Для простых случаев без разделителей используем более легкий подход
        if (!args.contains("|")) {
            return List.of(new ElementTag(args));
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
    @Override public void applyProperty(Mechanism mechanism) { Debug.echoError("Cannot apply properties to a JavaObjectTag!");
    }}