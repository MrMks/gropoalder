package com.github.mrmks.mc.gropoadler;

import groovy.lang.GroovySystem;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public class GvyPoolEngineFactory implements ScriptEngineFactory {

    public static final String CACHE_METHOD = "cacheScript";

    @Override
    public String getEngineName() {
        return "Groovy Script Pool";
    }

    @Override
    public String getEngineVersion() {
        return "0.1.0";
    }

    @Override
    public List<String> getNames() {
        return Collections.singletonList("GvyPool");
    }

    @Override
    public String getLanguageName() {
        return "GvyPool";
    }

    @Override
    public String getLanguageVersion() {
        return "GvyPool";
    }

    @Override
    public List<String> getExtensions() {
        return Collections.singletonList("groovy");
    }

    @Override
    public List<String> getMimeTypes() {
        return Collections.singletonList("application/x-groovy");
    }

    @Override
    public Object getParameter(String key) {

        if (ScriptEngine.NAME.equals(key)) {
            return "GvyPool";
        } else if (ScriptEngine.ENGINE.equals(key)) {
            return getEngineName();
        } else if (ScriptEngine.ENGINE_VERSION.equals(key)) {
            return "0.1.0";
        } else if (ScriptEngine.LANGUAGE.equals(key)) {
            return getLanguageName();
        } else if (ScriptEngine.LANGUAGE_VERSION.equals(key)) {
            return GroovySystem.getVersion();
        } else if ("THREADING".equals(key)) {
            return "MULTITHREADED";
        } else {
            throw new IllegalArgumentException("Invalid key");
        }

    }

    @Override
    public String getMethodCallSyntax(String obj, String m, String... args) {
        return null;
    }

    @Override
    public String getOutputStatement(String toDisplay) {
        return null;
    }

    @Override
    public String getProgram(String... statements) {
        return null;
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return new EngineImpl();
    }

    private class EngineImpl extends AbstractGroovyEngine {

        @Override
        public ScriptEngineFactory getFactory() {
            return GvyPoolEngineFactory.this;
        }

        @Override
        protected void postClass(String script, Class<?> klass) {
            try {
                Method me = klass.getMethod(CACHE_METHOD);
                checkRt(me.invoke(null), klass, script);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {/* ignore*/}
        }

        private void checkRt(Object rt, Class<?> klass, String source) {
            String name = null;
            int ver = 0;
            if (rt instanceof String) {
                String tmp = (String) rt;

                int i = tmp.lastIndexOf(':');

                if (i > 0) name = tmp.substring(0, i);
                else if (i < 0) name = tmp;

                if (i >= 0) {
                    try {
                        ver = Integer.parseInt(tmp.substring(i + 1));
                    } catch (NumberFormatException e) {/* ignore */}
                }
            } else if (rt instanceof List<?>) {
                List<?> tmp = (List<?>) rt;
                if (tmp.size() > 0) {
                    Object t0 = tmp.get(0);
                    if (t0 == null || t0 instanceof String) {
                        name = (String) t0;
                        if (tmp.size() > 1 && (t0 = tmp.get(1)) instanceof Integer)
                            ver = (int) t0;
                    }
                    else if (t0 instanceof Integer) ver = (int) t0;
                }
            }

            if (name != null && !name.isEmpty() && klass != null) {
                SharedScriptPool.INSTANCE.register(klass, name, Math.max(ver, 0), source);
            }
        }
    }
}
