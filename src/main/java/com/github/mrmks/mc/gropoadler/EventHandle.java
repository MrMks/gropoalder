package com.github.mrmks.mc.gropoadler;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber
@Mod(modid = "grypolader", acceptableRemoteVersions = "*")
public class EventHandle {

    public static final String IDENTIFIER = "groovy.codeMap";

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        SharedScriptPool.INSTANCE.warmup();
        SharedScriptPool.INSTANCE.attach(new DataStorageImpl());
    }

    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppedEvent event) {
        SharedScriptPool.INSTANCE.clear();
    }

    public static class DataStorageImpl implements SharedScriptPool.DataStorage {

        volatile CodeMap map;

        @Override
        public void put(String name, String text, int ver) {
            checkMap();
            if (map != null) {
                VersionSourceImpl vs = new VersionSourceImpl(text, ver);
                SharedScriptPool.VersionSource old = map.map.put(name, vs);
                if (!vs.equals(old)) map.markDirty();
            }
        }

        @Override
        public SharedScriptPool.VersionSource get(String name) {
            checkMap();
            return map != null ? map.map.get(name) : null;
        }

        private void checkMap() {
            if (map == null) {
                synchronized (this) {
                    if (map == null) {
                        MapStorage storage = DimensionManager.getWorld(0).getMapStorage();
                        if (storage == null) storage = DimensionManager.getWorld(0).getPerWorldStorage();

                        WorldSavedData wsd = storage.getOrLoadData(CodeMap.class, IDENTIFIER);
                        if (wsd == null) storage.setData(IDENTIFIER, wsd = new CodeMap());

                        map = (CodeMap) wsd;
                    }
                }
            }
        }
    }

    public static class CodeMap extends WorldSavedData {

        ConcurrentHashMap<String, SharedScriptPool.VersionSource> map;

        public CodeMap(String name) {
            super(name);
        }

        public CodeMap() {
            super(IDENTIFIER);
            map = new ConcurrentHashMap<>();
        }

        @Override
        public void readFromNBT(NBTTagCompound nbt) {
            map = new ConcurrentHashMap<>();
            if (nbt != null) {
                for (String k : nbt.getKeySet()) {
                    NBTTagCompound compound = nbt.getCompoundTag(k);
                    String text = compound.getString("text");
                    int ver = compound.getInteger("ver");

                    map.put(k, new VersionSourceImpl(text, ver));
                }
            }
        }

        @Override
        public NBTTagCompound writeToNBT(NBTTagCompound compound) {
            if (compound == null) compound = new NBTTagCompound();
            if (map == null || map.isEmpty()) return compound;
            for (Map.Entry<String, SharedScriptPool.VersionSource> entry : map.entrySet()) {
                NBTTagCompound sub = new NBTTagCompound();
                SharedScriptPool.VersionSource vs = entry.getValue();
                sub.setString("text", vs.text());
                sub.setInteger("ver", vs.version());
                compound.setTag(entry.getKey(), sub);
            }
            return compound;
        }
    }

    private static class VersionSourceImpl implements SharedScriptPool.VersionSource {

        private final String text;
        private final int ver;
        VersionSourceImpl(String text, int ver) {
            this.text = text;
            this.ver = Math.max(ver, 0);
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public int version() {
            return ver;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (!(obj instanceof SharedScriptPool.VersionSource)) return false;
            SharedScriptPool.VersionSource that = (SharedScriptPool.VersionSource) obj;
            return that.text().equals(text) && that.version() == ver;
        }

        @Override
        public int hashCode() {
            return text.hashCode() ^ ver;
        }
    }
}
