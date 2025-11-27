package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.*;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.ScriptEntryData;
import com.denizenscript.denizencore.scripts.ScriptRegistry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.generator.ArgLinear;
import com.denizenscript.denizencore.scripts.commands.generator.ArgName;
import com.denizenscript.denizencore.scripts.commands.generator.ArgPrefixed;
import com.denizenscript.denizencore.scripts.containers.ScriptContainer;
import com.denizenscript.denizencore.scripts.queues.core.InstantQueue;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.utilities.text.StringHolder;
import meigo.denizen.reflect.util.LibraryLoader;

import java.lang.reflect.*;
import java.util.*;

public class ProxyCommand extends AbstractCommand {

    public ProxyCommand() {
        setName("proxy");
        setSyntax("proxy [<interfaces>|...] using:<map> as:<name>");
        setRequiredArguments(3, 3);
        autoCompile();
    }

    @SuppressWarnings("unused")
    public static void autoExecute(
            ScriptEntry entry,
            @ArgName("interfaces") @ArgLinear ListTag interfaces,
            @ArgName("using") @ArgPrefixed MapTag using,
            @ArgName("as") @ArgPrefixed String name) {

        List<Class<?>> interfaceList = new ArrayList<>();
        for (String interfaceName : interfaces) {
            try {
                Class<?> clazz = Class.forName(interfaceName, true, LibraryLoader.getClassLoader());
                if (clazz.isInterface()) {
                    interfaceList.add(clazz);
                } else {
                    Debug.echoError("Class is not an interface: " + interfaceName);
                }
            } catch (ClassNotFoundException e) {
                Debug.echoError("Interface not found: " + interfaceName);
            }
        }

        if (interfaceList.isEmpty()) {
            Debug.echoError("No valid interfaces provided for proxy.");
            return;
        }

        Map<String, ObjectTag> scriptHandlers = new HashMap<>();
        for (Map.Entry<StringHolder, ObjectTag> mapEntry : using.map.entrySet()) {
            scriptHandlers.put(mapEntry.getKey().low, mapEntry.getValue());
        }

        Object proxyInstance = Proxy.newProxyInstance(
                LibraryLoader.getClassLoader(),
                interfaceList.toArray(new Class[0]),
                new DenizenInvocationHandler(scriptHandlers, entry)
        );

        entry.getResidingQueue().addDefinition(name, new JavaReflectedObjectTag(proxyInstance));
    }

    private record DenizenInvocationHandler(Map<String, ObjectTag> scriptHandlers,
                                            ScriptEntry creationEntry) implements InvocationHandler {

            private static final Method INVOKE_DEFAULT;

            static {
                Method method;
                try {
                    method = InvocationHandler.class.getDeclaredMethod("invokeDefault", Object.class, Method.class, Object[].class);
                    method.setAccessible(true);
                } catch (NoSuchMethodException e) {
                    method = null;
                }
                INVOKE_DEFAULT = method;
            }

        @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String methodName = method.getName();
                String handlerKey = methodName.toLowerCase();
                ObjectTag scriptName = scriptHandlers.get(handlerKey);

                if (scriptName == null) {
                        if (method.isDefault() && INVOKE_DEFAULT != null) {
                            return INVOKE_DEFAULT.invoke(this, proxy, method, args);
                        }

                    if (methodName.equals("toString") && method.getParameterCount() == 0) {
                            return proxy.getClass().getName() + "@" + Integer.toHexString(proxy.hashCode());
                        }
                        if (methodName.equals("hashCode") && method.getParameterCount() == 0) {
                            return System.identityHashCode(proxy);
                        }
                        if (methodName.equals("equals") && method.getParameterCount() == 1 && method.getParameterTypes()[0] == Object.class) {
                            return proxy == args[0];
                        }

                        return null;
                    }

                int i = 0;

                ListTag definitions;
                ListTag argsList = new ListTag();
                if (args != null) {
                    for (Object arg : args) {
                        argsList.addObject(new JavaReflectedObjectTag(arg));
                    }
                }

                if (scriptName.getJavaObject() instanceof String) {
                    ScriptContainer container = ScriptRegistry.getScriptContainer((String) scriptName.getJavaObject());
                    definitions = new ListTag(container.getString("definitions"));
                    ScriptEntryData data = creationEntry.entryData.clone();

                    InstantQueue queue = new InstantQueue("PROXY_");
                    queue.addEntries(container.getBaseEntries(data));
                    for (String arg : argsList) {
                        try {
                            queue.addDefinition(definitions.get(i), arg);
                        } catch (Exception e) {
                            queue.addDefinition(String.valueOf(i + 1), arg);
                        } i++;
                    }
                    queue.addDefinition("proxy", new JavaReflectedObjectTag(proxy));
                    queue.addDefinition("method", new ElementTag(methodName));
                    queue.start();
                } else if (scriptName.getJavaObject() instanceof SectionCommand.Section section) {
                    definitions = section.definitions;
                    section.allDefinitions = new MapTag();
                    section.allDefinitions.putAll(section.defMap);
                    section.allDefinitions.putObject("proxy", new JavaReflectedObjectTag(proxy));
                    section.allDefinitions.putObject("method", new ElementTag(methodName));
                    for (ObjectTag arg : argsList.objectForms) {
                        try {
                            section.allDefinitions.putObject(definitions.get(i), arg);
                        } catch (Exception e) {
                            section.allDefinitions.putObject(String.valueOf(i + 1), arg);
                        } i++;
                    section.run();
                    }
                } else {
                    Debug.echoError("Proxy handler refers to missing script: " + scriptName);
                    return null;
                }
                return proxy;
            }
        }
}