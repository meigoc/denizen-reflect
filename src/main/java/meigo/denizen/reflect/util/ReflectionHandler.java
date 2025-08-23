package meigo.denizen.reflect.util;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.DenizenReflect;
import meigo.denizen.reflect.object.JavaObjectTag;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ReflectionHandler {

    // Константы для читаемости
    private static final int CONVERSION_IMPOSSIBLE = 1000;
    private static final int CONVERSION_DIRECT_MATCH = 1;
    private static final int CONVERSION_PRIMITIVE = 2;
    private static final int CONVERSION_WIDENING = 10;
    private static final int CONVERSION_STRING_FALLBACK = 20;
    private static final int CONVERSION_NULL = 50;
    private static final int CONVERSION_OBJECT_FALLBACK = 100;

    // Кэш для методов (улучшение производительности)
    private static final Map<String, Method[]> methodCache = new ConcurrentHashMap<>();
    private static final Map<String, Constructor<?>[]> constructorCache = new ConcurrentHashMap<>();

    public static ObjectTag wrapObject(Object result, TagContext context) {
        if (result == null) {
            return null;
        }
        if (result.getClass().isArray()) {
            ListTag list = new ListTag();
            int length = Array.getLength(result);
            for (int i = 0; i < length; i++) {
                Object item = Array.get(result, i);
                list.addObject(CoreUtilities.objectToTagForm(item, context, false, false, true));
            }
            return list;
        }
        ObjectTag converted = CoreUtilities.objectToTagForm(result, context, false, false, false);
        if (converted instanceof ElementTag && !isSimpleType(result)) {
            if (converted.identify().equals(result.toString())) {
                return new JavaObjectTag(result);
            }
        }
        return converted;
    }

    private static boolean isSimpleType(Object obj) {
        return obj instanceof String || obj instanceof Number || obj instanceof Boolean;
    }

    public static boolean isClassAllowed(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        String className = clazz.getName();
        return DenizenReflect.allowedPackages.stream().anyMatch(className::startsWith);
    }

    public static Class<?> getClass(String className, TagContext context) {
        try {
            Class<?> clazz = Class.forName(className);
            if (!isClassAllowed(clazz)) {
                Debug.echoError(context, "Access to class '" + className + "' is denied by the DenizenReflect security configuration.");
                return null;
            }
            return clazz;
        } catch (ClassNotFoundException e) {
            Debug.echoError(context, "Could not find class: " + className);
            return null;
        }
    }

    private static int calculateConversionCost(Class<?> javaParam, ObjectTag denizenParam) {
        // --- НАЧАЛО ИЗМЕНЕНИЙ ---
        // Если нам передали JavaObjectTag, "распаковываем" его и рекурсивно вызываем
        // оценку для содержимого. Это делает подбор методов более точным.
        if (denizenParam instanceof JavaObjectTag) {
            Object heldObject = ((JavaObjectTag) denizenParam).getJavaObject();
            // CoreUtilities.noDebugContext используется, чтобы избежать спама в консоль при внутренней конвертации
            return calculateConversionCost(javaParam, CoreUtilities.objectToTagForm(heldObject, CoreUtilities.noDebugContext));
        }
        // --- КОНЕЦ ИЗМЕНЕНИЙ ---

        if (denizenParam == null) {
            return javaParam.isPrimitive() ? CONVERSION_IMPOSSIBLE : CONVERSION_NULL;
        }
        Object javaObject = denizenParam.getJavaObject();
        if (javaObject != null && javaParam.isInstance(javaObject)) {
            return CONVERSION_DIRECT_MATCH;
        }

        if (javaParam.isEnum() && denizenParam instanceof ElementTag) {
            try {
                Enum.valueOf((Class<Enum>) javaParam, denizenParam.toString().toUpperCase(Locale.ENGLISH));
                return CONVERSION_PRIMITIVE;
            } catch (IllegalArgumentException e) {
                // Not a valid enum constant for this type
            }
        }

        if (denizenParam instanceof ElementTag element) {
            if ((javaParam == int.class || javaParam == Integer.class) && element.isInt()) return CONVERSION_PRIMITIVE;
            if ((javaParam == double.class || javaParam == Double.class) && element.isDouble()) return CONVERSION_PRIMITIVE;
            if ((javaParam == long.class || javaParam == Long.class) && element.isInt()) return CONVERSION_PRIMITIVE;
            if ((javaParam == float.class || javaParam == Float.class) && element.isFloat()) return CONVERSION_PRIMITIVE + 1;
            if ((javaParam == boolean.class || javaParam == Boolean.class) && element.isBoolean()) return CONVERSION_PRIMITIVE;
            if ((javaParam == short.class || javaParam == Short.class) && element.isInt()) return CONVERSION_PRIMITIVE + 2;
            if ((javaParam == byte.class || javaParam == Byte.class) && element.isInt()) return CONVERSION_PRIMITIVE + 3;
            if ((javaParam == long.class || javaParam == Long.class) && element.isInt()) return CONVERSION_WIDENING;
            if ((javaParam == float.class || javaParam == Float.class) && element.isInt()) return CONVERSION_WIDENING + 1;
            if ((javaParam == double.class || javaParam == Double.class) && (element.isInt() || element.isFloat())) return CONVERSION_WIDENING + 2;
        }

        if (javaParam == String.class) {
            return CONVERSION_STRING_FALLBACK;
        }

        if (javaParam == Object.class) {
            return CONVERSION_OBJECT_FALLBACK;
        }

        return CONVERSION_IMPOSSIBLE;
    }

    private static Object tryConvertElementToEnum(Class<?> javaParam, ElementTag element) {
        if (!javaParam.isEnum()) {
            return null;
        }
        try {
            String enumName = element.asString().toUpperCase(Locale.ENGLISH);
            return Enum.valueOf((Class<Enum>) javaParam, enumName);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Object convertDenizenToJava(Class<?> javaParam, ObjectTag denizenParam) {
        if (denizenParam == null) return null;

        Object javaObject = denizenParam.getJavaObject();
        if (javaObject != null && javaParam.isInstance(javaObject)) return javaObject;

        if (denizenParam instanceof ElementTag element) {
            Object enumValue = tryConvertElementToEnum(javaParam, element);
            if (enumValue != null) {
                return enumValue;
            }
            if (javaParam == int.class || javaParam == Integer.class) return element.asInt();
            if (javaParam == double.class || javaParam == Double.class) return element.asDouble();
            if (javaParam == long.class || javaParam == Long.class) return element.asLong();
            if (javaParam == float.class || javaParam == Float.class) return element.asFloat();
            if (javaParam == boolean.class || javaParam == Boolean.class) return element.asBoolean();
            if (javaParam == short.class || javaParam == Short.class) {
                int value = element.asInt();
                if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                    throw new IllegalArgumentException("Value " + value + " out of range for short");
                }
                return (short) value;
            }
            if (javaParam == byte.class || javaParam == Byte.class) {
                int value = element.asInt();
                if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                    throw new IllegalArgumentException("Value " + value + " out of range for byte");
                }
                return (byte) value;
            }
        }

        if (javaParam == String.class) {
            return denizenParam.toString();
        }
        return javaObject;
    }

    private static Object[] convertParams(Class<?>[] javaParams, List<ObjectTag> denizenParams) {
        Object[] result = new Object[javaParams.length];
        for (int i = 0; i < javaParams.length; i++) {
            try {
                result[i] = convertDenizenToJava(javaParams[i], denizenParams.get(i));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Parameter " + i + ": " + e.getMessage());
            }
        }
        return result;
    }

    private static Constructor<?>[] getCachedConstructors(Class<?> clazz) {
        return constructorCache.computeIfAbsent(clazz.getName(), k -> clazz.getConstructors());
    }

    public static Object construct(String className, List<ObjectTag> params, TagContext context) {
        try {
            Class<?> clazz = getClass(className, context);
            if (clazz == null) return null;

            Constructor<?> bestMatch = null;
            int bestCost = Integer.MAX_VALUE;

            Constructor<?>[] constructors = getCachedConstructors(clazz);
            for (Constructor<?> constructor : constructors) {
                Class<?>[] paramTypes = constructor.getParameterTypes();
                if (paramTypes.length != params.size()) continue;

                int currentCost = 0;
                boolean possible = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    int cost = calculateConversionCost(paramTypes[i], params.get(i));
                    if (cost >= CONVERSION_IMPOSSIBLE) {
                        possible = false;
                        break;
                    }
                    currentCost += cost;
                }

                if (possible && currentCost < bestCost) {
                    bestCost = currentCost;
                    bestMatch = constructor;
                }
            }
            if (bestMatch != null) {
                Object[] javaParams = convertParams(bestMatch.getParameterTypes(), params);
                return bestMatch.newInstance(javaParams);
            } else {
                Debug.echoError(context, "Could not find a matching constructor for class '" + className + "' with the provided parameters.");
                return null;
            }
        } catch (Exception e) {
            Debug.echoError(context, "Error during Java object construction:");
            Debug.echoError(e);
            return null;
        }
    }

    public static Object invokeMethod(Object instance, String methodName, List<ObjectTag> params, TagContext context) {
        if (!isClassAllowed(instance.getClass())) {
            Debug.echoError(context, "Access to class '" + instance.getClass().getName() + "' is denied by the DenizenReflect security configuration.");
            return null;
        }
        return invoke(instance.getClass(), instance, methodName, params, context);
    }

    public static Object invokeStaticMethod(Class<?> clazz, String methodName, List<ObjectTag> params, TagContext context) {
        if (!isClassAllowed(clazz)) {
            Debug.echoError(context, "Access to class '" + clazz.getName() + "' is denied by the DenizenReflect security configuration.");
            return null;
        }
        return invoke(clazz, null, methodName, params, context);
    }

    private static Method[] getCachedMethods(Class<?> clazz, String methodName, boolean isStatic, int paramCount) {
        String key = clazz.getName() + ":" + methodName + ":" + isStatic + ":" + paramCount;
        return methodCache.computeIfAbsent(key, k ->
                Arrays.stream(clazz.getMethods())
                        .filter(m -> m.getName().equals(methodName))
                        .filter(m -> Modifier.isStatic(m.getModifiers()) == isStatic)
                        .filter(m -> m.getParameterCount() == paramCount)
                        .toArray(Method[]::new)
        );
    }

    private static Object invoke(Class<?> clazz, Object instance, String methodName, List<ObjectTag> params, TagContext context) {
        try {
            Method bestMatch = null;
            int bestCost = Integer.MAX_VALUE;
            boolean isStatic = (instance == null);

            Method[] candidateMethods = getCachedMethods(clazz, methodName, isStatic, params.size());

            for (Method method : candidateMethods) {
                Class<?>[] paramTypes = method.getParameterTypes();
                int currentCost = 0;
                boolean possible = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    int cost = calculateConversionCost(paramTypes[i], params.get(i));
                    if (cost >= CONVERSION_IMPOSSIBLE) {
                        possible = false;
                        break;
                    }
                    currentCost += cost;
                }
                if (possible && currentCost < bestCost) {
                    bestCost = currentCost;
                    bestMatch = method;
                }
            }

            if (bestMatch != null) {
                Object[] javaParams = convertParams(bestMatch.getParameterTypes(), params);
                return bestMatch.invoke(instance, javaParams);
            } else {
                String paramTypesStr = params.stream()
                        .map(p -> p != null ? p.getDenizenObjectType().toString() : "null")
                        .collect(Collectors.joining(", "));
                Debug.echoError(context, "Could not find a matching method '" + methodName + "(" + paramTypesStr + ")' for class '" + clazz.getName() + "'.");
                return null;
            }
        } catch (Exception e) {
            Debug.echoError(context, "Error during Java method invocation:");
            Debug.echoError(e);
            return null;
        }
    }

    public static Object getField(Object instance, String fieldName, TagContext context) {
        if (!isClassAllowed(instance.getClass())) {
            Debug.echoError(context, "Access to class '" + instance.getClass().getName() + "' is denied.");
            return null;
        }
        return accessField(instance.getClass(), instance, fieldName, context);
    }

    public static Object getStaticField(Class<?> clazz, String fieldName, TagContext context) {
        if (!isClassAllowed(clazz)) {
            Debug.echoError(context, "Access to class '" + clazz.getName() + "' is denied.");
            return null;
        }
        return accessField(clazz, null, fieldName, context);
    }

    private static Object accessField(Class<?> clazz, Object instance, String fieldName, TagContext context) {
        try {
            Field field = clazz.getField(fieldName);
            if (Modifier.isStatic(field.getModifiers()) != (instance == null)) {
                Debug.echoError(context, "Static/instance mismatch for field '" + fieldName + "'.");
                return null;
            }
            return field.get(instance);
        } catch (NoSuchFieldException e) {
            Debug.echoError(context, "Could not find a public field named '" + fieldName + "' in class '" + clazz.getName() + "'.");
        } catch (Exception e) {
            Debug.echoError(context, "Error reading field '" + fieldName + "':");
            Debug.echoError(e);
        }
        return null;
    }

    public static void setField(Object instance, String fieldName, ObjectTag value, TagContext context) {
        if (!isClassAllowed(instance.getClass())) {
            Debug.echoError(context, "Access to class '" + instance.getClass().getName() + "' is denied.");
            return;
        }
        modifyField(instance.getClass(), instance, fieldName, value, context);
    }

    public static void setStaticField(Class<?> clazz, String fieldName, ObjectTag value, TagContext context) {
        if (!isClassAllowed(clazz)) {
            Debug.echoError(context, "Access to class '" + clazz.getName() + "' is denied.");
            return;
        }
        modifyField(clazz, null, fieldName, value, context);
    }

    private static void modifyField(Class<?> clazz, Object instance, String fieldName, ObjectTag value, TagContext context) {
        try {
            Field field = clazz.getField(fieldName);
            if (Modifier.isStatic(field.getModifiers()) != (instance == null)) {
                Debug.echoError(context, "Static/instance mismatch for field '" + fieldName + "'.");
                return;
            }
            if (Modifier.isFinal(field.getModifiers())) {
                Debug.echoError(context, "Cannot modify final field '" + fieldName + "'.");
                return;
            }
            Object javaValue = convertDenizenToJava(field.getType(), value);
            field.set(instance, javaValue);
        } catch (NoSuchFieldException e) {
            Debug.echoError(context, "Could not find a public field named '" + fieldName + "' in class '" + clazz.getName() + "'.");
        } catch (Exception e) {
            Debug.echoError(context, "Error setting field '" + fieldName + "':");
            Debug.echoError(e);
        }
    }
}