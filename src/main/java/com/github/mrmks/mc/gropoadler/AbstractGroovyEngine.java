package com.github.mrmks.mc.gropoadler;

import groovy.lang.*;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.runtime.MethodClosure;
import org.codehaus.groovy.util.ManagedConcurrentValueMap;
import org.codehaus.groovy.util.ReferenceBundle;

import javax.script.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.PrivilegedAction;

public abstract class AbstractGroovyEngine extends AbstractScriptEngine implements Invocable {
    private static boolean debug = false;

    // script-string-to-generated Class map
    private final ManagedConcurrentValueMap<String, Class<?>> classMap = new ManagedConcurrentValueMap<>(ReferenceBundle.getSoftBundle());
    // global closures map - this is used to simulate a single
    // global functions namespace
    private final ManagedConcurrentValueMap<String, Closure<?>> globalClosures = new ManagedConcurrentValueMap<>(ReferenceBundle.getHardBundle());
    // class loader for Groovy generated classes
    private final GroovyClassLoader loader;
    // lazily initialized factory
//    private volatile GvyPoolEngineFactory factory;

    // counter used to generate unique global Script class names
    private static int counter;

    static {
        counter = 0;
    }

    @SuppressWarnings("removal") // TODO a future Groovy version should perform the operation not as a privileged action
    private static GroovyClassLoader createClassLoader() {
        return java.security.AccessController.doPrivileged((PrivilegedAction<GroovyClassLoader>) () -> new GroovyClassLoader(getParentLoader(), new CompilerConfiguration(CompilerConfiguration.DEFAULT)));
    }

    protected AbstractGroovyEngine() {
        this.loader = createClassLoader();
    }

    @Override
    public Object eval(Reader reader, ScriptContext ctx)
            throws ScriptException {
        return eval(readFully(reader), ctx);
    }

    @Override
    public Object eval(String script, ScriptContext ctx)
            throws ScriptException {
        try {
            String val = (String) ctx.getAttribute("#jsr223.groovy.engine.keep.globals", ScriptContext.ENGINE_SCOPE);
            ReferenceBundle bundle = ReferenceBundle.getHardBundle();
            if (val != null && val.length() > 0) {
                if (val.equalsIgnoreCase("soft")) {
                    bundle = ReferenceBundle.getSoftBundle();
                } else if (val.equalsIgnoreCase("weak")) {
                    bundle = ReferenceBundle.getWeakBundle();
                } else if (val.equalsIgnoreCase("phantom")) {
                    bundle = ReferenceBundle.getPhantomBundle();
                }
            }
            globalClosures.setBundle(bundle);
        } catch (ClassCastException cce) { /*ignore.*/ }

        try {
            return delegateEval(script, ctx);
        } catch (Exception e) {
            if (debug) e.printStackTrace();
            throw new ScriptException(e);
        }
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public abstract ScriptEngineFactory getFactory();

    // javax.script.Invokable methods.
    @Override
    public Object invokeFunction(String name, Object... args)
            throws ScriptException, NoSuchMethodException {
        return invokeImpl(null, name, args);
    }

    @Override
    public Object invokeMethod(Object thiz, String name, Object... args)
            throws ScriptException, NoSuchMethodException {
        if (thiz == null) {
            throw new IllegalArgumentException("script object is null");
        }
        return invokeImpl(thiz, name, args);
    }

    @Override
    public <T> T getInterface(Class<T> clazz) {
        return makeInterface(null, clazz);
    }

    @Override
    public <T> T getInterface(Object thiz, Class<T> clazz) {
        if (thiz == null) {
            throw new IllegalArgumentException("script object is null");
        }
        return makeInterface(thiz, clazz);
    }

    protected Object delegateEval(String script, ScriptContext ctx) throws ScriptException {
        Class<?> clazz = getScriptClass(script, ctx);
        if (clazz == null) throw new ScriptException("Script class is null");
        Object o = delegateEval(clazz, ctx, null);
        postClass(script, clazz);
        return o;
    }

    protected Object delegateEval(Class<?> klass, ScriptContext ctx, String scriptName) throws ScriptException {
        return eval(klass, ctx, scriptName);
    }

    // For pool
    protected void postClass(String script, Class<?> klass) {}

    protected String generateClosureName(String script, String method) {
        return method;
    }

    protected Object delegateCallGlobal(String scriptName, String name, Object[] args, ScriptContext ctx) {
        return callGlobal(scriptName, name, args, ctx);
    }

    // package-privates
    Object eval(Class<?> scriptClass, final ScriptContext ctx, String scriptName) throws ScriptException {
        /*
         * We use the following Binding instance so that global variable lookup
         * will be done in the current ScriptContext instance.
         */
        Binding binding = new Binding(ctx.getBindings(ScriptContext.ENGINE_SCOPE)) {
            @Override
            public Object getVariable(String name) {
                synchronized (ctx) {
                    int scope = ctx.getAttributesScope(name);
                    if (scope != -1) {
                        return ctx.getAttribute(name, scope);
                    }
                    // Redirect script output to context writer, if out var is not already provided
                    if ("out".equals(name)) {
                        Writer writer = ctx.getWriter();
                        if (writer != null) {
                            return (writer instanceof PrintWriter) ?
                                    (PrintWriter) writer :
                                    new PrintWriter(writer, true);
                        }
                    }
                    // Provide access to engine context, if context var is not already provided
                    if ("context".equals(name)) {
                        return ctx;
                    }
                }
                throw new MissingPropertyException(name, getClass());
            }

            @Override
            public void setVariable(String name, Object value) {
                synchronized (ctx) {
                    int scope = ctx.getAttributesScope(name);
                    if (scope == -1) {
                        scope = ScriptContext.ENGINE_SCOPE;
                    }
                    ctx.setAttribute(name, value, scope);
                }
            }
        };

        try {
            // if this class is not an instance of Script, it's a full-blown class
            // then simply return that class
            if (!Script.class.isAssignableFrom(scriptClass)) {
                return scriptClass;
            } else {
                // it's a script
                Script scriptObject = InvokerHelper.createScript(scriptClass, binding);

                // save all current closures into global closures map
                Method[] methods = scriptClass.getMethods();
                for (Method m : methods) {
                    String name = m.getName();
                    globalClosures.put(generateClosureName(scriptName, name), new MethodClosure(scriptObject, name));
                }

                MetaClass oldMetaClass = scriptObject.getMetaClass();

                /*
                 * We override the MetaClass of this script object so that we can
                 * forward calls to global closures (of previous or future "eval" calls)
                 * This gives the illusion of working on the same "global" scope.
                 */
                scriptObject.setMetaClass(new DelegatingMetaClass(oldMetaClass) {
                    @Override
                    public Object invokeMethod(Object object, String name, Object args) {
                        if (args == null) {
                            return invokeMethod(object, name, MetaClassHelper.EMPTY_ARRAY);
                        }
                        if (args instanceof Tuple) {
                            return invokeMethod(object, name, ((Tuple<?>) args).toArray());
                        }
                        if (args instanceof Object[]) {
                            return invokeMethod(object, name, (Object[]) args);
                        } else {
                            return invokeMethod(object, name, new Object[]{args});
                        }
                    }

                    @Override
                    public Object invokeMethod(Object object, String name, Object[] args) {
                        try {
                            return super.invokeMethod(object, name, args);
                        } catch (MissingMethodException mme) {
                            return delegateCallGlobal(scriptName, name, args, ctx);
                        }
                    }

                    @Override
                    public Object invokeStaticMethod(Object object, String name, Object[] args) {
                        try {
                            return super.invokeStaticMethod(object, name, args);
                        } catch (MissingMethodException mme) {
                            return delegateCallGlobal(scriptName, name, args, ctx);
                        }
                    }
                });

                return scriptObject.run();
            }
        } catch (Exception e) {
            throw new ScriptException(e);
        }
    }

    Class<?> getScriptClass(String script)
            throws CompilationFailedException {
        return getScriptClass(script, null);
    }

    Class<?> getScriptClass(String script, ScriptContext context)
            throws CompilationFailedException {
        Class<?> clazz = classMap.get(script);
        if (clazz != null) {
            return clazz;
        }

        clazz = loader.parseClass(script, generateScriptName(context));
        classMap.put(script, clazz);
        return clazz;
    }

    //-- Internals only below this point

    // invokes the specified method/function on the given object.
    private Object invokeImpl(Object thiz, String name, Object... args)
            throws ScriptException, NoSuchMethodException {
        if (name == null) {
            throw new NullPointerException("method name is null");
        }

        try {
            if (thiz != null) {
                return InvokerHelper.invokeMethod(thiz, name, args);
            } else {
                return callGlobal(name, args);
            }
        } catch (MissingMethodException mme) {
            if (mme.getType() == getClass()) {
                throw new NoSuchMethodException(mme.getMessage());
            } else {
                throw new ScriptException(mme);
            }
        } catch (Exception e) {
            throw e.getCause() instanceof ScriptException ? (ScriptException) e.getCause() : new ScriptException(e);
        }
    }

    private Object invokeImplSafe(Object thiz, String name, Object... args) {
        if (name == null) {
            throw new NullPointerException("method name is null");
        }

        try {
            if (thiz != null) {
                return InvokerHelper.invokeMethod(thiz, name, args);
            } else {
                return callGlobal(name, args);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // call the script global function of the given name
    private Object callGlobal(String name, Object[] args) throws ScriptException {
        return delegateCallGlobal(null, name, args, context);
    }

    private Object callGlobal(String scriptName, String name, Object[] args, ScriptContext ctx) {
        Closure<?> closure = globalClosures.get(generateClosureName(scriptName, name));
        if (closure != null) {
            return closure.call(args);
        } else {
            // Look for closure valued variable in the
            // given ScriptContext. If available, call it.
            Object value = ctx.getAttribute(name);
            if (value instanceof Closure) {
                return ((Closure<?>) value).call(args);
            } // else fall thru..
        }
        throw new MissingMethodException(name, getClass(), args);
    }

    // generate a unique name for top-level Script classes
    private static synchronized String generateScriptName(ScriptContext	context) {
        // If context is available, and contains FILENAME,
        // use it as script name
        if (context != null) {
            Object filename = context.getAttribute(ScriptEngine.FILENAME);
            if (filename != null) {
                return filename.toString();
            }
        }
        return "Script" + (++counter) + ".groovy";
    }

    @SuppressWarnings("unchecked")
    private <T> T makeInterface(Object obj, Class<T> clazz) {
        final Object thiz = obj;
        if (clazz == null || !clazz.isInterface()) {
            throw new IllegalArgumentException("interface Class expected");
        }
        return (T) Proxy.newProxyInstance(
                clazz.getClassLoader(),
                new Class<?>[]{clazz},
                (proxy, m, args) -> invokeImplSafe(thiz, m.getName(), args));
    }

    // determine appropriate class loader to serve as parent loader
    // for GroovyClassLoader instance
    private static ClassLoader getParentLoader() {
        // check whether thread context loader can "see" Groovy Script class
        ClassLoader ctxtLoader = Thread.currentThread().getContextClassLoader();
        try {
            Class<?> c = ctxtLoader.loadClass(Script.class.getName());
            if (c == Script.class) {
                return ctxtLoader;
            }
        } catch (ClassNotFoundException cnfe) {
            /* ignore */
        }
        // exception was thrown or we get wrong class
        return Script.class.getClassLoader();
    }

    private static String readFully(Reader reader) throws ScriptException {
        char[] arr = new char[8 * 1024]; // 8K at a time
        StringBuilder buf = new StringBuilder();
        int numChars;
        try {
            while ((numChars = reader.read(arr, 0, arr.length)) > 0) {
                buf.append(arr, 0, numChars);
            }
        } catch (IOException exp) {
            throw new ScriptException(exp);
        }
        return buf.toString();
    }
}
