package meigo.denizen.reflect.util;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import meigo.denizen.reflect.DenizenReflect;
import meigo.denizen.reflect.object.JavaObjectTag;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ReflectionHandler {

    // Conversion cost constants
    private static final int CONVERSION_IMPOSSIBLE = 1000;
    private static final int CONVERSION_DIRECT_MATCH = 1;
    private static final int CONVERSION_PRIMITIVE = 2;
    private static final int CONVERSION_WIDENING = 10;
    private static final int CONVERSION_STRING_FALLBACK = 20;
    private static final int CONVERSION_NULL = 50;
    private static final int CONVERSION_OBJECT_FALLBACK = 100;

    // Caches (method cache keys include target param count to allow varargs-aware caching)
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
        // If CoreUtilities returned a simple ElementTag for a non-simple Java object, prefer exposing it as JavaObjectTag
        if (converted instanceof ElementTag && !isSimpleType(result)) {
            return new JavaObjectTag(result);
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

    // Calculate conversion "cost" for matching a Denizen ObjectTag to a Java parameter type.
    private static int calculateConversionCost(Class<?> javaParam, ObjectTag denizenParam) {
        // If we received a JavaObjectTag, first try direct instance match using its held Java object.
        if (denizenParam instanceof JavaObjectTag) {
            Object held = ((JavaObjectTag) denizenParam).getJavaObject();
            if (held != null && javaParam.isInstance(held)) {
                return CONVERSION_DIRECT_MATCH;
            }
            // Fall back to analyzing the denizen representation of the held object
            ObjectTag rep = CoreUtilities.objectToTagForm(held, CoreUtilities.noDebugContext, false, false, false);
            if (rep == denizenParam) {
                // avoid infinite recursion
                return CONVERSION_IMPOSSIBLE;
            }
            return calculateConversionCost(javaParam, rep);
        }

        if (denizenParam == null) {
            return javaParam.isPrimitive() ? CONVERSION_IMPOSSIBLE : CONVERSION_NULL;
        }

        Object javaObject = denizenParam.getJavaObject();
        if (javaObject != null && javaParam.isInstance(javaObject)) {
            return CONVERSION_DIRECT_MATCH;
        }

        // Enum handling: first exact match, then case-insensitive uppercase
        if (javaParam.isEnum() && denizenParam instanceof ElementTag) {
            String val = denizenParam.toString();
            try {
                Enum.valueOf((Class<Enum>) javaParam, val);
                return CONVERSION_PRIMITIVE;
            } catch (IllegalArgumentException ignored) {
            }
            try {
                Enum.valueOf((Class<Enum>) javaParam, val.toUpperCase(Locale.ENGLISH));
                return CONVERSION_PRIMITIVE + 1;
            } catch (IllegalArgumentException ignored) {
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

        // Strings are a fallback for many types (low priority)
        if (javaParam == String.class) {
            return CONVERSION_STRING_FALLBACK;
        }

        // Object is a generic fallback
        if (javaParam == Object.class) {
            return CONVERSION_OBJECT_FALLBACK;
        }

        // If javaParam is an interface/supertype of Number/CharSequence etc, allow lower-cost
        if (CharSequence.class.isAssignableFrom(javaParam) && denizenParam instanceof ElementTag) {
            return CONVERSION_STRING_FALLBACK;
        }
        if (Number.class.isAssignableFrom(javaParam) && denizenParam instanceof ElementTag && ((ElementTag) denizenParam).isFloat()) {
            return CONVERSION_PRIMITIVE + 5;
        }

        return CONVERSION_IMPOSSIBLE;
    }

    private static Object tryConvertElementToEnum(Class<?> javaParam, ElementTag element) {
        if (!javaParam.isEnum()) {
            return null;
        }
        // try exact, then uppercase
        try {
            return Enum.valueOf((Class<Enum>) javaParam, element.asString());
        } catch (IllegalArgumentException ignored) {
        }
        try {
            return Enum.valueOf((Class<Enum>) javaParam, element.asString().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    // Convert a Denizen ObjectTag to a Java object suitable for assignment to javaParam
    private static Object convertDenizenToJava(Class<?> javaParam, ObjectTag denizenParam) {
        if (denizenParam == null) return null;

        // If it's a JavaObjectTag, prefer its held object
        if (denizenParam instanceof JavaObjectTag) {
            Object held = ((JavaObjectTag) denizenParam).getJavaObject();
            if (held == null) return null;
            if (javaParam.isInstance(held)) return held;
            // numeric conversions from held Number to primitives
            if (held instanceof Number) {
                Number n = (Number) held;
                if (javaParam == int.class || javaParam == Integer.class) return n.intValue();
                if (javaParam == long.class || javaParam == Long.class) return n.longValue();
                if (javaParam == double.class || javaParam == Double.class) return n.doubleValue();
                if (javaParam == float.class || javaParam == Float.class) return n.floatValue();
                if (javaParam == short.class || javaParam == Short.class) return n.shortValue();
                if (javaParam == byte.class || javaParam == Byte.class) return n.byteValue();
            }
            if (javaParam == String.class) return held.toString();
            // else fall through to attempt conversion below using objectToTagForm
        }

        // If the ObjectTag carries a direct Java object and it's assignable — use it
        Object javaObject = denizenParam.getJavaObject();
        if (javaObject != null && javaParam.isInstance(javaObject)) return javaObject;

        // ElementTag based conversions (primitive/strings/enums)
        if (denizenParam instanceof ElementTag element) {
            Object enumValue = tryConvertElementToEnum(javaParam, element);
            if (enumValue != null) return enumValue;
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

        // ListTag => arrays or collections
        if (denizenParam instanceof ListTag listTag) {
            if (javaParam.isArray()) {
                Class<?> comp = javaParam.getComponentType();
                Object arr = Array.newInstance(comp, listTag.objectForms.size());
                for (int i = 0; i < listTag.objectForms.size(); i++) {
                    ObjectTag itemTag = listTag.objectForms.get(i);
                    Object conv = convertDenizenToJava(comp, itemTag);
                    Array.set(arr, i, conv);
                }
                return arr;
            }
            // If expecting a Collection or List
            if (Collection.class.isAssignableFrom(javaParam)) {
                List<Object> newList = new ArrayList<>();
                // Try to get generic component type is impossible here at runtime; use raw Object conversion.
                for (ObjectTag item : listTag.objectForms) {
                    if (item instanceof JavaObjectTag) {
                        newList.add(((JavaObjectTag) item).getJavaObject());
                    } else if (item.getJavaObject() != null) {
                        newList.add(item.getJavaObject());
                    } else {
                        newList.add(item.toString());
                    }
                }
                return newList;
            }
        }

        // MapTag => Map
        if (denizenParam instanceof MapTag mapTag) {
            if (Map.class.isAssignableFrom(javaParam) || javaParam == Object.class) {
                Map<String, Object> newMap = new HashMap<>();
                for (Map.Entry<StringHolder, ObjectTag> e : mapTag.entrySet()) {
                    ObjectTag val = e.getValue();
                    if (val instanceof JavaObjectTag) {
                        newMap.put(String.valueOf(e.getKey()), ((JavaObjectTag) val).getJavaObject());
                    } else if (val.getJavaObject() != null) {
                        newMap.put(String.valueOf(e.getKey()), val.getJavaObject());
                    } else {
                        newMap.put(String.valueOf(e.getKey()), val.toString());
                    }
                }
                return newMap;
            }
        }

        // If expecting String
        if (javaParam == String.class) {
            return denizenParam.toString();
        }

        // Fallback: if denizenParam had java object, return it
        return denizenParam.getJavaObject();
    }

    // Convert parameters for invocation; supports varargs packaging.
    private static Object[] convertParamsForExecutable(Class<?>[] javaParams, List<ObjectTag> denizenParams, boolean isVarArgs) {
        if (!isVarArgs) {
            Object[] result = new Object[javaParams.length];
            for (int i = 0; i < javaParams.length; i++) {
                try {
                    result[i] = convertDenizenToJava(javaParams[i], denizenParams.get(i));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Parameter " + i + ": " + e.getMessage());
                }
            }
            return result;
        } else {
            // For varargs: the last javaParam is an array type; we need to pack remaining denizenParams into it.
            int fixed = javaParams.length - 1;
            Class<?> varArrayType = javaParams[javaParams.length - 1];
            Class<?> compType = varArrayType.getComponentType();
            Object[] result = new Object[javaParams.length];
            // Convert fixed params
            for (int i = 0; i < fixed; i++) {
                try {
                    result[i] = convertDenizenToJava(javaParams[i], denizenParams.get(i));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Parameter " + i + ": " + e.getMessage());
                }
            }
            // Remaining params go to varargs array
            int varCount = Math.max(0, denizenParams.size() - fixed);
            Object varArr = Array.newInstance(compType, varCount);
            for (int i = 0; i < varCount; i++) {
                ObjectTag tag = denizenParams.get(fixed + i);
                Object conv = convertDenizenToJava(compType, tag);
                Array.set(varArr, i, conv);
            }
            result[javaParams.length - 1] = varArr;
            return result;
        }
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
                boolean isVarArgs = constructor.isVarArgs();
                // For varargs constructor, allow params.size() >= paramTypes.length - 1
                if (!isVarArgs && paramTypes.length != params.size()) continue;
                if (isVarArgs && params.size() < paramTypes.length - 1) continue;

                int currentCost = 0;
                boolean possible = true;
                // compute cost for non-varargs and varargs (tail items use component type)
                for (int i = 0; i < paramTypes.length; i++) {
                    if (isVarArgs && i == paramTypes.length - 1) {
                        Class<?> comp = paramTypes[i].getComponentType();
                        for (int j = i; j < params.size(); j++) {
                            int cost = calculateConversionCost(comp, params.get(j));
                            if (cost >= CONVERSION_IMPOSSIBLE) {
                                possible = false;
                                break;
                            }
                            currentCost += cost;
                        }
                        break;
                    } else {
                        int cost = calculateConversionCost(paramTypes[i], params.get(i));
                        if (cost >= CONVERSION_IMPOSSIBLE) {
                            possible = false;
                            break;
                        }
                        currentCost += cost;
                    }
                }

                if (possible && currentCost < bestCost) {
                    bestCost = currentCost;
                    bestMatch = constructor;
                }
            }
            if (bestMatch != null) {
                Object[] javaParams = convertParamsForExecutable(bestMatch.getParameterTypes(), params, bestMatch.isVarArgs());
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

    // Get cached methods for a given parameter count (allows varargs-aware candidate selection)
    private static Method[] getCachedMethods(Class<?> clazz, String methodName, boolean isStatic, int paramCount) {
        String key = clazz.getName() + ":" + methodName + ":" + isStatic + ":" + paramCount;
        return methodCache.computeIfAbsent(key, k ->
                Arrays.stream(clazz.getMethods())
                        .filter(m -> m.getName().equals(methodName))
                        .filter(m -> Modifier.isStatic(m.getModifiers()) == isStatic)
                        // keep methods with exact parameter count OR varargs methods that can accept paramCount
                        .filter(m -> {
                            if (m.getParameterCount() == paramCount) return true;
                            if (m.isVarArgs()) {
                                // varargs can accept paramCount >= (paramCount-1)
                                return paramCount >= m.getParameterCount() - 1;
                            }
                            return false;
                        })
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
                boolean varArgs = method.isVarArgs();
                if (!varArgs && paramTypes.length != params.size()) continue;
                if (varArgs && params.size() < paramTypes.length - 1) continue;

                int currentCost = 0;
                boolean possible = true;

                for (int i = 0; i < paramTypes.length; i++) {
                    if (varArgs && i == paramTypes.length - 1) {
                        Class<?> comp = paramTypes[i].getComponentType();
                        for (int j = i; j < params.size(); j++) {
                            int cost = calculateConversionCost(comp, params.get(j));
                            if (cost >= CONVERSION_IMPOSSIBLE) {
                                possible = false;
                                break;
                            }
                            currentCost += cost;
                        }
                        break;
                    } else {
                        int cost = calculateConversionCost(paramTypes[i], params.get(i));
                        if (cost >= CONVERSION_IMPOSSIBLE) {
                            possible = false;
                            break;
                        }
                        currentCost += cost;
                    }
                }

                if (possible && currentCost < bestCost) {
                    bestCost = currentCost;
                    bestMatch = method;
                }
            }

            if (bestMatch != null) {
                Object[] javaParams = convertParamsForExecutable(bestMatch.getParameterTypes(), params, bestMatch.isVarArgs());
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

    public static Class<?> getClassSilent(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            if (!isClassAllowed(clazz)) {
                return null;
            }
            return clazz;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

}