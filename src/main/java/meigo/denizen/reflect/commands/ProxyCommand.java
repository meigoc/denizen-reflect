package meigo.denizen.reflect.commands;

import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.JavaReflectedObjectTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.ScriptEntry.ArgumentIterator;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import meigo.denizen.reflect.util.DenizenProxyHandler;
import meigo.denizen.reflect.util.LibraryLoader;

public class ProxyCommand extends AbstractCommand {

    // @Plugin denizen-reflect
    public ProxyCommand() {
        this.setName("proxy");
        this.setSyntax("proxy [<interfaces>|...] [using:<handler_map>/<section_name>/<task_script>] [as:<name>]");
        this.setRequiredArguments(2, 3);
        this.isProcedural = false;
    }

    // <--[command]
    // @Name Proxy
    // @Syntax proxy [<interfaces>|...] [using:<map>] [as:<name>]
    // @Required 2
    // @Maximum 3
    // @Short Creates a proxy for given interfaces.
    // @Group denizen-reflect
    //
    // @Description
    // Creates a new proxy object using the specified interfaces.
    //
    // @Usage
    // Use to create proxy of Runnable.
    // - proxy java.lang.Runnable using:[run=task]g
    // -->

    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        ArgumentIterator var2 = scriptEntry.iterator();

        while(true) {
            while(var2.hasNext()) {
                Argument arg = (Argument)var2.next();
                if (!scriptEntry.hasObject("interfaces") && !arg.matchesPrefix(new String[]{"using", "as"})) {
                    scriptEntry.addObject("interfaces", arg.asType(ListTag.class));
                } else if (arg.matchesPrefix("using")) {
                    scriptEntry.addObject("handler_object", arg.object);
                } else if (arg.matchesPrefix("as")) {
                    scriptEntry.addObject("as", arg.asElement());
                } else {
                    arg.reportUnhandled();
                }
            }

            if (!scriptEntry.hasObject("interfaces")) {
                throw new InvalidArgumentsException("Must specify interface classes!");
            }

            if (!scriptEntry.hasObject("handler_object")) {
                throw new InvalidArgumentsException("Must specify handlers with 'using' argument!");
            }

            return;
        }
    }

    public void execute(ScriptEntry scriptEntry) {
        ListTag interfacesList = (ListTag)scriptEntry.getObjectTag("interfaces");
        ObjectTag handlerObject = (ObjectTag)scriptEntry.getObjectTag("handler_object");
        ElementTag asName = scriptEntry.getElement("as");
        MapTag handlers = null;
        ElementTag sectionOrTaskHandler = null;
        if (handlerObject instanceof MapTag) {
            handlers = (MapTag)handlerObject;
        } else {
            if (!(handlerObject instanceof ElementTag)) {
                Debug.echoError(scriptEntry, "Invalid handler type specified! Must be a MapTag, section name, or task script.");
                return;
            }

            sectionOrTaskHandler = (ElementTag)handlerObject;
        }

        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, this.getName(), new Object[]{interfacesList, db("handler_object", handlerObject.identify()), asName});
        }

        try {
            List<Class<?>> interfaceClasses = new ArrayList();
            Iterator var8 = interfacesList.iterator();

            while(var8.hasNext()) {
                String interfaceName = (String)var8.next();
                Class<?> ifaceClass = DenizenProxyHandler.getClassForName(interfaceName, scriptEntry);
                if (ifaceClass == null) {
                    Debug.echoError(scriptEntry, "Interface class not found: " + interfaceName);
                    return;
                }

                if (!ifaceClass.isInterface()) {
                    Debug.echoError(scriptEntry, "Class is not an interface: " + interfaceName);
                    return;
                }

                interfaceClasses.add(ifaceClass);
            }

            DenizenProxyHandler handler;
            if (handlers != null) {
                handler = new DenizenProxyHandler(handlers, scriptEntry.getContext());
            } else {
                handler = new DenizenProxyHandler(sectionOrTaskHandler, scriptEntry.getContext());
            }

            Object proxy = Proxy.newProxyInstance(LibraryLoader.getClassLoader(), (Class[])interfaceClasses.toArray(new Class[0]), handler);
            JavaReflectedObjectTag proxyObject = new JavaReflectedObjectTag(proxy);
            proxyObject.persist();
            if (asName != null) {
                scriptEntry.getResidingQueue().addDefinition(asName.asString(), proxyObject);
            }
        } catch (Exception var11) {
            Debug.echoError(scriptEntry, "Failed to create proxy:");
            Debug.echoError(scriptEntry, var11);
        }

    }
}