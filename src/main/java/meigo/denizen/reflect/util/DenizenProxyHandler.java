package meigo.denizen.reflect.util;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.*;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.containers.core.TaskScriptContainer;
import com.denizenscript.denizencore.scripts.queues.ContextSource;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.ScriptUtilities;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import meigo.denizen.reflect.commands.SectionCommand;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.function.Consumer;

public class DenizenProxyHandler implements InvocationHandler {
    private final MapTag handlers;
    private final ElementTag sectionOrTaskHandler;
    private final TagContext context;

    public DenizenProxyHandler(MapTag handlers, TagContext context) {
        this.handlers = handlers;
        this.sectionOrTaskHandler = null;
        this.context = context;
    }

    public DenizenProxyHandler(ElementTag sectionOrTaskHandler, TagContext context) {
        this.handlers = null;
        this.sectionOrTaskHandler = sectionOrTaskHandler;
        this.context = context;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName().toLowerCase();
        if (method.getDeclaringClass() == Object.class) {
            return this.handleObjectMethod(proxy, method, args);
        } else {
            try {
                ObjectTag result;
                if (this.handlers != null) {
                    ObjectTag handler = this.handlers.getObject(methodName);
                    if (handler == null) {
                        Debug.echoError("No handler found for method: " + methodName);
                        return this.getDefaultReturnValue(method.getReturnType());
                    }

                    result = this.executeHandler(handler, proxy, method, args);
                } else {
                    result = this.executeHandler(this.sectionOrTaskHandler, proxy, method, args);
                }

                return this.convertResult(result, method.getReturnType());
            } catch (Exception var7) {
                Debug.echoError("Error executing proxy method '" + methodName + "':");
                Debug.echoError(var7);
                return this.getDefaultReturnValue(method.getReturnType());
            }
        }
    }

    private Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        String methodName = method.getName();
        byte var6 = -1;
        switch(methodName.hashCode()) {
            case -1776922004:
                if (methodName.equals("toString")) {
                    var6 = 0;
                }
                break;
            case -1295482945:
                if (methodName.equals("equals")) {
                    var6 = 2;
                }
                break;
            case 147696667:
                if (methodName.equals("hashCode")) {
                    var6 = 1;
                }
        }

        switch(var6) {
            case 0:
                String var10000 = proxy.getClass().getName();
                return var10000 + "@" + Integer.toHexString(proxy.hashCode());
            case 1:
                return System.identityHashCode(proxy);
            case 2:
                return proxy == args[0];
            default:
                return null;
        }
    }

    private ObjectTag executeHandler(ObjectTag handler, Object proxy, Method method, Object[] methodArgs) {
        if (handler instanceof ScriptTag) {
            return this.executeTask((ScriptTag)handler, (String)null, proxy, method, methodArgs);
        }
        else if (handler instanceof ElementTag) {
            String handlerStr = handler.toString();
            String path = null;
            if (handlerStr.contains(".")) {
                int dotIndex = handlerStr.indexOf(46);
                path = handlerStr.substring(dotIndex + 1);
                handlerStr = handlerStr.substring(0, dotIndex);
            }
            ScriptTag script = ScriptTag.valueOf(handlerStr, this.context);
            if (script != null && script.getContainer() instanceof TaskScriptContainer) {
                this.executeTask(script, path, proxy, method, methodArgs);
                return null;
            }
        }
        else if (handler.getJavaObject() instanceof SectionCommand.Section) {
            ((SectionCommand.Section) handler.getJavaObject()).run();
            return null;
        }
        Debug.echoError("Unsupported handler type: " + handler.identify());
        return null;
    }

    private ObjectTag executeTask(ScriptTag script, String path, Object proxy, Method method, Object[] methodArgs) {
        ListTag definitions = new ListTag();
        if (methodArgs != null) {
            Object[] var7 = methodArgs;
            int var8 = methodArgs.length;
            for(int var9 = 0; var9 < var8; ++var9) {
                Object arg = var7[var9];
                definitions.addObject(JavaExpressionEngine.wrapObject(arg, this.context));
            }
        }

        Consumer<ScriptQueue> configure = (queuex) -> {
            queuex.addDefinition("proxy", new JavaReflectedObjectTag(proxy));
            queuex.addDefinition("method_name", new ElementTag(method.getName()));
            queuex.addDefinition("args", definitions.duplicate());
        };
        ScriptQueue queue = ScriptUtilities.createAndStartQueue(script.getContainer(), path, this.context.getScriptEntryData(), (ContextSource)null, configure, new DurationTag(0), "PROXY_TASK_" + script.getName(), definitions, this.context.entry != null ? this.context.entry.getResidingQueue() : null);
        if (queue == null) {
            return null;
        } else {
            return queue.determinations != null && !queue.determinations.isEmpty() ? queue.determinations.getObject(0) : queue.getDefinitionObject("result");
        }
    }

    private Object convertResult(ObjectTag result, Class<?> expectedType) {
        if (result == null) {
            return this.getDefaultReturnValue(expectedType);
        } else {
            try {
                return CoreUtilities.objectTagToJavaForm(result, false, true);
            } catch (Exception var4) {
                Debug.echoError("Standard conversion failed, trying raw object fallback. Error: " + var4.getMessage());
                return result instanceof JavaReflectedObjectTag ? ((JavaReflectedObjectTag)result).getJavaObject() : this.getDefaultReturnValue(expectedType);
            }
        }
    }

    private Object getDefaultReturnValue(Class<?> returnType) {
        if (returnType != Void.TYPE && returnType != Void.class) {
            if (returnType != Boolean.TYPE && returnType != Boolean.class) {
                return returnType.isPrimitive() ? 0 : null;
            } else {
                return false;
            }
        } else {
            return null;
        }
    }

    public static Class<?> getClassForName(String className, ScriptEntry scriptEntry) {
        String path = scriptEntry.getScript().getContainer().getRelativeFileName();
        path = path.substring(path.indexOf("scripts/") + "scripts/".length());
        return JavaExpressionEngine.INSTANCE.getImports().get(path).imports.get(className);
    }
}