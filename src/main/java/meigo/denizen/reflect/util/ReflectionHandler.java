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
import java.util.List;
import java.util.stream.Collectors;

public class ReflectionHandler {

    /**
     * Intelligently wraps a raw Java object into a Denizen ObjectTag.
     * Attempts to convert to native Denizen types first, falling back to JavaObjectTag.
     */
    public static ObjectTag wrapObject(Object result, TagContext context) {
        if (result == null) {
            return null;
        }

        if (result.getClass().isArray()) {
            ListTag list = new ListTag();
            int length = Array.getLength(result);
            for (int i = 0; i < length; i++) {
                Object item = Array.get(result, i);
                // Рекурсивно оборачиваем каждый элемент массива
                list.addObject(CoreUtilities.objectToTagForm(item, context, false, false, true));
            }
            return list;
        }

        // Let DenizenCore try to convert it to a native type (ElementTag, ListTag, etc.)
        ObjectTag converted = CoreUtilities.objectToTagForm(result, context, false, false, false);
        // Heuristic: If the result is a generic ElementTag whose value is just the toString() of the original object,
        // it's likely a complex type that Denizen doesn't know about. In this case, our JavaObjectTag is more useful.
        if (converted instanceof ElementTag && !(result instanceof String || result instanceof Number || result instanceof Boolean)) {
            if (converted.identify().equals(result.toString())) {
                return new JavaObjectTag(result);
            }
        }
        return converted;
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
        if (denizenParam == null) {
            return javaParam.isPrimitive() ? 1000 : 0; // Null can be passed to non-primitives
        }
        Object javaObject = denizenParam.getJavaObject();
        if (javaObject != null && javaParam.isInstance(javaObject)) {
            return 1; // Direct match
        }
        if (denizenParam instanceof ElementTag element) {
            if ((javaParam == int.class || javaParam == Integer.class) && element.isInt()) return 2;
            if ((javaParam == double.class || javaParam == Double.class) && element.isDouble()) return 2;
            if ((javaParam == long.class || javaParam == Long.class) && element.isInt()) return 2; // Should be isLong, but Denizen's isInt covers long
            if ((javaParam == float.class || javaParam == Float.class) && element.isFloat()) return 3;
            if ((javaParam == boolean.class || javaParam == Boolean.class) && element.isBoolean()) return 2;
            if ((javaParam == short.class || javaParam == Short.class) && element.isInt()) return 4;
            if ((javaParam == byte.class || javaParam == Byte.class) && element.isInt()) return 5;
            // Widening primitive conversions
            if ((javaParam == long.class || javaParam == Long.class) && element.isInt()) return 10;
            if ((javaParam == float.class || javaParam == Float.class) && element.isInt()) return 11;
            if ((javaParam == double.class || javaParam == Double.class) && (element.isInt() || element.isFloat())) return 12;
        }
        if (javaParam == String.class) {
            return 20; // String conversion is a common fallback
        }
        if (javaParam == Object.class) {
            return 100; // Object is the most generic, highest cost
        }
        return 1000; // Not convertible
    }

    private static Object convertDenizenToJava(Class<?> javaParam, ObjectTag denizenParam) {
        if (denizenParam == null) return null;
        Object javaObject = denizenParam.getJavaObject();
        if (javaObject != null && javaParam.isInstance(javaObject)) return javaObject;
        if (denizenParam instanceof ElementTag element) {
            if (javaParam == int.class || javaParam == Integer.class) return element.asInt();
            if (javaParam == double.class || javaParam == Double.class) return element.asDouble();
            if (javaParam == long.class || javaParam == Long.class) return element.asLong();
            if (javaParam == float.class || javaParam == Float.class) return element.asFloat();
            if (javaParam == boolean.class || javaParam == Boolean.class) return element.asBoolean();
            if (javaParam == short.class || javaParam == Short.class) return (short) element.asInt();
            if (javaParam == byte.class || javaParam == Byte.class) return (byte) element.asInt();
        }
        if (javaParam == String.class) {
            return denizenParam.toString();
        }
        return javaObject;
    }

    private static Object[] convertParams(Class<?>[] javaParams, List<ObjectTag> denizenParams) {
        Object[] result = new Object[javaParams.length];
        for (int i = 0; i < javaParams.length; i++) {
            result[i] = convertDenizenToJava(javaParams[i], denizenParams.get(i));
        }
        return result;
    }

    public static Object construct(String className, List<ObjectTag> params, TagContext context) {
        try {
            Class<?> clazz = getClass(className, context);
            if (clazz == null) return null;

            Constructor<?> bestMatch = null;
            int bestCost = Integer.MAX_VALUE;

            for (Constructor<?> constructor : clazz.getConstructors()) {
                Class<?>[] paramTypes = constructor.getParameterTypes();
                if (paramTypes.length != params.size()) continue;

                int currentCost = 0;
                boolean possible = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    int cost = calculateConversionCost(paramTypes[i], params.get(i));
                    if (cost >= 1000) {
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
            }
            else {
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
        return invoke(instance.getClass(), instance, methodName, params, context);
    }

    public static Object invokeStaticMethod(Class<?> clazz, String methodName, List<ObjectTag> params, TagContext context) {
        return invoke(clazz, null, methodName, params, context);
    }

    private static Object invoke(Class<?> clazz, Object instance, String methodName, List<ObjectTag> params, TagContext context) {
        try {
            if (!isClassAllowed(clazz)) {
                Debug.echoError(context, "Access to class '" + clazz.getName() + "' is denied by the DenizenReflect security configuration.");
                return null;
            }

            Method bestMatch = null;
            int bestCost = Integer.MAX_VALUE;

            for (Method method : clazz.getMethods()) {
                if (!method.getName().equals(methodName)) continue;
                if (Modifier.isStatic(method.getModifiers()) != (instance == null)) continue; // Match static/instance

                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length != params.size()) continue;

                int currentCost = 0;
                boolean possible = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    int cost = calculateConversionCost(paramTypes[i], params.get(i));
                    if (cost >= 1000) {
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
            }
            else {
                String paramTypesStr = params.stream().map(p -> p.getDenizenObjectType().toString()).collect(Collectors.joining(", "));
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
        return accessField(instance.getClass(), instance, fieldName, context);
    }

    public static Object getStaticField(Class<?> clazz, String fieldName, TagContext context) {
        return accessField(clazz, null, fieldName, context);
    }

    private static Object accessField(Class<?> clazz, Object instance, String fieldName, TagContext context) {
        try {
            if (!isClassAllowed(clazz)) {
                Debug.echoError(context, "Access to class '" + clazz.getName() + "' is denied.");
                return null;
            }
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
        modifyField(instance.getClass(), instance, fieldName, value, context);
    }

    public static void setStaticField(Class<?> clazz, String fieldName, ObjectTag value, TagContext context) {
        modifyField(clazz, null, fieldName, value, context);
    }

    private static void modifyField(Class<?> clazz, Object instance, String fieldName, ObjectTag value, TagContext context) {
        try {
            if (!isClassAllowed(clazz)) {
                Debug.echoError(context, "Access to class '" + clazz.getName() + "' is denied.");
                return;
            }
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