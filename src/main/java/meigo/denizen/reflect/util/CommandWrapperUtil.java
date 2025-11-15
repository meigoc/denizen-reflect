package meigo.denizen.reflect.util;

import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.generator.CommandExecutionGenerator;
import com.denizenscript.denizencore.scripts.queues.ScriptQueue;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.tags.TagManager;

import java.lang.reflect.Field;
import java.util.Map;

public class CommandWrapperUtil {

    public static void interceptAll(Map<String, AbstractCommand> commands) {
        for (Map.Entry<String, AbstractCommand> entry : commands.entrySet()) {
            String regName = entry.getKey();
            AbstractCommand original = entry.getValue();

            try {
                if (original == null) {
                    continue;
                }

                if (original.generatedExecutor != null) {
                    CommandExecutionGenerator.CommandExecutor oldExec = original.generatedExecutor;

                    original.generatedExecutor = new CommandExecutionGenerator.CommandExecutor() {
                        {
                            this.args = oldExec.args;
                        }

                        @Override
                        public void execute(ScriptEntry scriptEntry, ScriptQueue queue) {
                            try {
                                oldExec.execute(scriptEntry, queue);
                            }
                            catch (Throwable ex) {
                                Debug.echoError(scriptEntry, "⚠ Error in " + regName + ": " + ex);
                            }
                        }
                    };
                }
                else {
                    AbstractCommand wrapper = new AbstractCommand() {

                        {
                            this.syntax = original.syntax;
                            this.minimumArguments = original.minimumArguments;
                            this.maximumArguments = original.maximumArguments;
                            this.isProcedural = original.isProcedural;
                            this.forceHold = original.forceHold;
                            this.generateDebug = original.generateDebug;
                            this.allowedDynamicPrefixes = original.allowedDynamicPrefixes;
                            this.anyPrefixSymbolAllowed = original.anyPrefixSymbolAllowed;
                            this.prefixesThusFar = original.prefixesThusFar;
                            this.prefixesHandled = original.prefixesHandled;
                            this.booleansHandled = original.booleansHandled;
                            this.prefixRemapper = original.prefixRemapper;
                            this.enumsHandled = original.enumsHandled;
                            this.enumPrefixes = original.enumPrefixes;
                            this.linearHandledCount = original.linearHandledCount;

                            String origName = original.getName();
                            if (origName != null) {
                                this.setName(origName);
                            }
                        }

                        @Override
                        public void parseArgs(ScriptEntry scriptEntry) {
                            try {
                                original.parseArgs(scriptEntry);
                            }
                            catch (Throwable ex) {
                                Debug.echoError(scriptEntry, "⚠ parseArgs in " + regName + ": " + ex);
                            }
                        }

                        @Override
                        public void execute(ScriptEntry scriptEntry) {
                            try {

                                String path = "ex_command";
                                if (scriptEntry.getScript() != null && scriptEntry.getScript().getContainer() != null) {
                                    path = scriptEntry.getScript().getContainer().getRelativeFileName();
                                    path = path.substring(path.indexOf("scripts/") + "scripts/".length());
                                }


                                Field f = ScriptEntry.class.getDeclaredField("objects");
                                f.setAccessible(true);

                                @SuppressWarnings("unchecked")
                                Map<String, Object> map = (Map<String, Object>) f.get(scriptEntry);
                                if (map != null) {
                                    for (Map.Entry<String, Object> obj : map.entrySet()) {
                                        Object value = obj.getValue();
                                        if (value != null && value.toString().contains("%")) {
                                            String raw = value.toString();
                                            // парсим теги, чтобы <...> стали готовыми объектами
                                            raw = TagManager.tag(raw, scriptEntry.getContext());
                                            Object result = JavaExpressionEngine.execute(raw, scriptEntry, path);
                                            if (result != null) {
                                                map.put(obj.getKey(), new ElementTag(result.toString()));
                                            }
                                        }
                                    }
                                }


                                for (int i = 0; i < scriptEntry.internal.all_arguments.length; i++) {
                                    String argText = scriptEntry.internal.all_arguments[i].aHArg.toString();
                                    if (argText.contains("%")) {

                                        argText = TagManager.tag(argText, scriptEntry.getContext());
                                        Object result = JavaExpressionEngine.execute(argText, scriptEntry, path);
                                        if (result != null) {
                                            scriptEntry.internal.all_arguments[i].aHArg =
                                                    Argument.valueOf(result.toString());
                                        }
                                    }
                                }


                                original.execute(scriptEntry);
                            }
                            catch (Throwable ex) {
                                Debug.echoError(scriptEntry, "⚠ Error in " + regName + ": " + ex);
                            }
                        }
                    };

                    entry.setValue(wrapper);
                }
            }
            catch (Throwable ex) {
                Debug.echoError("⚠ Error #2 in " + regName + ": " + ex);
            }
        }
    }
}
