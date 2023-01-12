package com.github.mrmks.mc.gropoadler;

import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.security.PrivilegedAction;
import java.util.concurrent.ConcurrentHashMap;

public enum SharedScriptPool {
    INSTANCE;

    private GroovyClassLoader loader;
    private final ConcurrentHashMap<String, Class<?>> classesCache = new ConcurrentHashMap<>();
    private volatile DataStorage dataStorage;

    SharedScriptPool() {}

    public void register(Class<?> klass, String replace, int ver, String sourceText) {

        if (dataStorage == null) return;

        String full = generateFullName(klass, replace);
        VersionSource vs = dataStorage.get(full);

        if (vs != null && ver < vs.version())
            throw new IllegalStateException("Re-registering script with older version: " + full + "(" + klass.getName() + ") {" + ver + "} -> {" + vs.version() + "}");

        dataStorage.put(full, sourceText, ver);
        classesCache.put(full, klass);
    }

    public Class<?> loadCache(String fullName) {

        if (dataStorage == null) return null;

        Class<?> klass = classesCache.get(fullName);
        if (klass == null) {
            VersionSource source = dataStorage.get(fullName);
            if (source == null) return null;
            else {
                klass = loader.parseClass(source.text());
                if (klass != null) {
                    classesCache.put(fullName, klass);
                }
            }
        }
        return klass;
    }

    private static String generateFullName(Class<?> klass, String replace) {
        String kn = klass.getName();
        if (replace == null || replace.isEmpty()) {
            return kn;
        } else {
            if (replace.contains(".") && replace.charAt(0) != '.') return replace;
            else if (replace.charAt(0) == '.') {
                throw new IllegalArgumentException("ScriptName can not start with a dot");
            } else {
                int i = kn.lastIndexOf('.');
                return i < 0 ? replace : kn.substring(0, i + 1).concat(replace);
            }
        }
    }

    // copy from groovy-jsr223
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

    public void warmup() {
        loader = java.security.AccessController.doPrivileged(
                (PrivilegedAction<GroovyClassLoader>) () ->
                        new GroovyClassLoader(getParentLoader(), CompilerConfiguration.DEFAULT)
        );
        loader.parseClass("1 + 2 + 3 + 4");
        loader.clearCache();
    }

    public void attach(DataStorage storage) {
        this.dataStorage = storage;
    }

    public void clear() {
        this.classesCache.clear();
        this.dataStorage = null;
        this.loader.clearCache();
    }

    public interface VersionSource {
        String text();
        int version();
    }

    public interface DataStorage {
        void put(String name, String text, int ver);
        VersionSource get(String name);
    }

}
