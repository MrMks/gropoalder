package com.github.mrmks.mc.gropoadler;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import groovy.lang.MissingMethodException;

import javax.script.*;
import java.io.Reader;
import java.io.Writer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GvyLoaderEngineFactory implements ScriptEngineFactory {
    @Override
    public String getEngineName() {
        return "GvyLoader";
    }

    @Override
    public String getEngineVersion() {
        return "0.1.0";
    }

    @Override
    public List<String> getExtensions() {
        return Collections.singletonList("json");
    }

    @Override
    public List<String> getMimeTypes() {
        return Collections.singletonList("x/plain-text");
    }

    @Override
    public List<String> getNames() {
        return Collections.singletonList("GvyLoader");
    }

    @Override
    public String getLanguageName() {
        return "GvyLoader";
    }

    @Override
    public String getLanguageVersion() {
        return "0.1.0";
    }

    @Override
    public Object getParameter(String key) {
        switch (key) {
            case ScriptEngine.NAME:
                return getEngineName();
            case ScriptEngine.ENGINE_VERSION:
                return getEngineVersion();
            case ScriptEngine.LANGUAGE:
                return getLanguageName();
            case ScriptEngine.LANGUAGE_VERSION:
                return getLanguageVersion();
        }
        return null;
    }

    @Override
    public String getMethodCallSyntax(String obj, String m, String... args) {
        return "";
    }

    @Override
    public String getOutputStatement(String toDisplay) {
        return "";
    }

    @Override
    public String getProgram(String... statements) {
        return "";
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return new EngineImpl();
    }

    private class EngineImpl extends AbstractGroovyEngine {

        private final ConcurrentHashMap<String, ScriptContext> perScriptContext = new ConcurrentHashMap<>();
        private final Set<String> scriptMethodExcludes = Collections.synchronizedSet(new HashSet<>());

        @Override
        public ScriptEngineFactory getFactory() {
            return GvyLoaderEngineFactory.this;
        }

        @Override
        protected Object delegateEval(String script, ScriptContext ctx0) throws ScriptException {
            Gson gson = new Gson();
            HashMap<String, HashMap<String, Object>> prop = gson.fromJson(script, new TypeToken<HashMap<String, HashMap<String, Object>>>(){}.getType());
            List<Object> re = new ArrayList<>();
            for (Map.Entry<String, HashMap<String, Object>> entry : prop.entrySet()) {
                String scriptName = entry.getKey();
                ScriptContext ctx = new DelegateScriptContext(ctx0, entry.getValue());

                if (scriptName.charAt(0) == '#') {
                    continue;
                }

                Class<?> klass = SharedScriptPool.INSTANCE.loadCache(scriptName);
                if (klass != null) {
                    try {
                        re.add(delegateEval(klass, ctx, scriptName));
                        perScriptContext.put(scriptName, ctx);
                    } catch (Exception e) {/* ignore */}
                } else {
                    throw new ScriptException(new ClassNotFoundException(scriptName));
                }
            }
            return re;
        }

        @Override
        protected String generateClosureName(String script, String method) {
            return script == null ? method : script + "#" + method;
        }

        @Override
        protected Object delegateCallGlobal(String scriptName, String name, Object[] args, ScriptContext ctx) {
            if (scriptName != null) return super.delegateCallGlobal(scriptName, name, args, ctx);

            List<Object> list = new ArrayList<>();
            int count = 0;
            for (Map.Entry<String, ScriptContext> entry : perScriptContext.entrySet()) {
                String sn = entry.getKey();
                ScriptContext ctx1 = entry.getValue();
                String fn = generateClosureName(sn, name);

                if (!scriptMethodExcludes.contains(fn)) {
                    try {
                        list.add(super.delegateCallGlobal(sn, name, args, ctx1));
                        continue;
                    } catch (MissingMethodException e) {
                        if (e.getType() != getClass()) throw e;
                    } catch (Exception e) {
                        Throwable tr = new ScriptException("Error while executing script: " + sn)
                                .initCause(e);
                        tr.setStackTrace(new StackTraceElement[0]);
                        throw new RuntimeException(tr);
                    }
                    scriptMethodExcludes.add(fn);
                }
                list.add(null);
                count++;
            }
            if (count == perScriptContext.size()) {
                throw new MissingMethodException(name, getClass(), args);
            }
            return list;
        }
    }

    private static class DelegateScriptContext implements ScriptContext {

        private final ScriptContext context;
        private Bindings engineBindings;

        private DelegateScriptContext(ScriptContext context, Map<String, Object> inserts) {
            this.context = context;
            engineBindings = new SimpleBindings();
            engineBindings.putAll(context.getBindings(ScriptContext.ENGINE_SCOPE));
            engineBindings.putAll(inserts);
        }

        @Override
        public void setBindings(Bindings bindings, int scope) {
            switch (scope) {
                case ENGINE_SCOPE:
                    engineBindings = bindings;
                    break;
                case GLOBAL_SCOPE:
                    context.setBindings(bindings, scope);
            }
        }

        @Override
        public Bindings getBindings(int scope) {
            return scope == ENGINE_SCOPE ? engineBindings : context.getBindings(scope);
        }

        @Override
        public void setAttribute(String name, Object value, int scope) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("name cannot be empty");
            if (scope == ENGINE_SCOPE) engineBindings.put(name, value);
            else {
                context.setAttribute(name, value, scope);
            }
        }

        @Override
        public Object getAttribute(String name, int scope) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("name cannot be empty");
            return scope == ENGINE_SCOPE ? engineBindings.get(name) : context.getAttribute(name, scope);
        }

        @Override
        public Object removeAttribute(String name, int scope) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("name cannot be empty");
            return scope == ENGINE_SCOPE ? engineBindings.remove(name) : context.removeAttribute(name, scope);
        }

        @Override
        public Object getAttribute(String name) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("name cannot be empty");
            if (engineBindings.containsKey(name)) return getAttribute(name, ENGINE_SCOPE);
            else return context.getAttribute(name);
        }

        @Override
        public int getAttributesScope(String name) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("name cannot be empty");
            if (engineBindings.containsValue(name)) {
                return ENGINE_SCOPE;
            } else {
                return context.getAttributesScope(name);
            }
        }

        @Override
        public Writer getWriter() {
            return context.getWriter();
        }

        @Override
        public Writer getErrorWriter() {
            return context.getErrorWriter();
        }

        @Override
        public void setWriter(Writer writer) {
            context.setWriter(writer);
        }

        @Override
        public void setErrorWriter(Writer writer) {
            context.setErrorWriter(writer);
        }

        @Override
        public Reader getReader() {
            return context.getReader();
        }

        @Override
        public void setReader(Reader reader) {
            context.setReader(reader);
        }

        @Override
        public List<Integer> getScopes() {
            return context.getScopes();
        }
    }

}
