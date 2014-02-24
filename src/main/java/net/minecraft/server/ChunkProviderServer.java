package net.minecraft.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// CraftBukkit start
import java.util.Random;

import org.bukkit.Server;
import org.bukkit.craftbukkit.chunkio.ChunkIOExecutor;
import org.bukkit.craftbukkit.util.LongHash;
import org.bukkit.craftbukkit.util.LongHashSet;
import org.bukkit.craftbukkit.util.LongObjectHashMap;
import org.bukkit.event.world.ChunkUnloadEvent;
// CraftBukkit end

import de.minetick.MinetickMod;
import de.minetick.MinetickEmptyChunk;

public class ChunkProviderServer implements IChunkProvider {

    private static final Logger b = LogManager.getLogger();
    // CraftBukkit start - private -> public
    public LongHashSet unloadQueue = new LongHashSet(); // LongHashSet
    public Chunk emptyChunk;
    public IChunkProvider chunkProvider;
    private IChunkLoader f;
    public boolean forceChunkLoad = false; // true -> false
    public LongObjectHashMap<Chunk> chunks = new LongObjectHashMap<Chunk>();
    public WorldServer world;
    // CraftBukkit end

    // Poweruser start
    private ChunkRegionLoader checkedRegionLoader = null;
    private LongHashSet corruptRegions = new LongHashSet();
    private MinetickEmptyChunk mtmEmptyChunk;

    public boolean doesChunkExist(int x, int z) {
        if(this.checkedRegionLoader == null) {
            if (this.f instanceof ChunkRegionLoader) {
                this.checkedRegionLoader = (ChunkRegionLoader) this.f;
            }
        }
        if(this.checkedRegionLoader != null) {
            boolean doesChunkExist = false;
            long regionHash = LongHash.toLong(x >> 5, z >> 5); // region hash
            if(this.corruptRegions.contains(regionHash)) { return false; }
                try {
                    doesChunkExist = this.checkedRegionLoader.chunkExists(this.world, x, z);
                } catch (Exception e) {
                    this.corruptRegions.add(regionHash);
                    String corruptFile = "\\" + this.world.getWorld().getName() + "\\region\\r." + (x >> 5) + "." + (z >> 5) + ".mca";
                    b.error("!!! Corrupt Region File " + corruptFile + " !!!");
                    b.error("The chunks within this region will be temporarily regenerated, but not saved!");
                    b.error("You might want to restore the file from a previous backup.");
                }
                return doesChunkExist;
        }
        return false;
    }
    // Poweruser end

    public ChunkProviderServer(WorldServer worldserver, IChunkLoader ichunkloader, IChunkProvider ichunkprovider) {
        //this.emptyChunk = new EmptyChunk(worldserver, 0, 0);
        this.emptyChunk = new EmptyChunk(worldserver, Integer.MIN_VALUE, Integer.MIN_VALUE); // Poweruser
        this.mtmEmptyChunk = new MinetickEmptyChunk(worldserver, Integer.MIN_VALUE, Integer.MIN_VALUE); // Poweruser
        this.world = worldserver;
        this.f = ichunkloader;
        this.chunkProvider = ichunkprovider;
    }

    public boolean isChunkLoaded(int i, int j) {
        return this.chunks.containsKey(LongHash.toLong(i, j)); // CraftBukkit
    }

    public void queueUnload(int i, int j) {
        if (this.world.worldProvider.e()) {
            ChunkCoordinates chunkcoordinates = this.world.getSpawn();
            int k = i * 16 + 8 - chunkcoordinates.x;
            int l = j * 16 + 8 - chunkcoordinates.z;
            short short1 = 128;

            // CraftBukkit start
            if (k < -short1 || k > short1 || l < -short1 || l > short1 || !(this.world.keepSpawnInMemory)) { // Added 'this.world.keepSpawnInMemory'
                this.unloadQueue.add(i, j);

                Chunk c = this.chunks.get(LongHash.toLong(i, j));
                if (c != null) {
                    c.mustSave = true;
                }
            }
            // CraftBukkit end
        } else {
            // CraftBukkit start
            this.unloadQueue.add(i, j);

            Chunk c = this.chunks.get(LongHash.toLong(i, j));
            if (c != null) {
                c.mustSave = true;
            }
            // CraftBukkit end
        }
    }

    public void a() {
        Iterator iterator = this.chunks.values().iterator(); // CraftBukkit

        while (iterator.hasNext()) {
            Chunk chunk = (Chunk) iterator.next();

            this.queueUnload(chunk.locX, chunk.locZ);
        }
    }

    // CraftBukkit start - Add async variant, provide compatibility
    public Chunk getChunkAt(int i, int j) {
        return getChunkAt(i, j, null);
    }

    public Chunk getChunkAt(int i, int j, Runnable runnable) {
        //this.unloadQueue.remove(i, j);
        Chunk chunk = this.chunks.get(LongHash.toLong(i, j));
        ChunkRegionLoader loader = null;

        if (this.f instanceof ChunkRegionLoader) {
            loader = (ChunkRegionLoader) this.f;
        }

        // We can only use the queue for already generated chunks
        if (chunk == null && loader != null && this.doesChunkExist(i, j)) {
            if (runnable != null) {
                ChunkIOExecutor.queueChunkLoad(this.world, loader, this, i, j, runnable);
                return null;
            } else {
                chunk = ChunkIOExecutor.syncChunkLoad(this.world, loader, this, i, j);
            }
        } else if (chunk == null) {
            chunk = this.originalGetChunkAt(i, j);
        }

        // If we didn't load the chunk async and have a callback run it now
        if (runnable != null) {
            runnable.run();
        }

        return chunk;
    }

    public Chunk originalGetChunkAt(int i, int j) {
        //this.unloadQueue.remove(i, j);
        Chunk chunk = (Chunk) this.chunks.get(LongHash.toLong(i, j));
        boolean newChunk = false;

        if (chunk == null) {
            //chunk = this.loadChunk(i, j);
            // Poweruser start
            boolean isMarkedCorrupt = this.corruptRegions.contains(LongHash.toLong(i >> 5,  j >> 5));
            if(!isMarkedCorrupt) {
                chunk = this.loadChunk(i, j);
            }

            // Poweruser end
            if (chunk == null) {
                if (this.chunkProvider == null) {
                    chunk = this.emptyChunk;
                // Poweruser start
                } else if (MinetickMod.doesWorldNotGenerateChunks(this.world.getWorld().getName())) {
                    return this.mtmEmptyChunk;
                // Poweruser end
                } else {
                    try {
                        chunk = this.chunkProvider.getOrCreateChunk(i, j);
                    } catch (Throwable throwable) {
                        CrashReport crashreport = CrashReport.a(throwable, "Exception generating new chunk");
                        CrashReportSystemDetails crashreportsystemdetails = crashreport.a("Chunk to be generated");

                        crashreportsystemdetails.a("Location", String.format("%d,%d", new Object[] { Integer.valueOf(i), Integer.valueOf(j)}));
                        crashreportsystemdetails.a("Position hash", Long.valueOf(LongHash.toLong(i, j))); // CraftBukkit - Use LongHash
                        crashreportsystemdetails.a("Generator", this.chunkProvider.getName());
                        throw new ReportedException(crashreport);
                    }
                }
                newChunk = true; // CraftBukkit
                // Poweruser start
                chunk.newChunk = true;
                if(chunk != null && isMarkedCorrupt) {
                    chunk.markAsCorrupt();
                }
                // Poweruser end
            }

            this.chunks.put(LongHash.toLong(i, j), chunk); // CraftBukkit
            chunk.addEntities();

            // CraftBukkit start
            Server server = this.world.getServer();
            if (server != null) {
                /*
                 * If it's a new world, the first few chunks are generated inside
                 * the World constructor. We can't reliably alter that, so we have
                 * no way of creating a CraftWorld/CraftServer at that point.
                 */
                server.getPluginManager().callEvent(new org.bukkit.event.world.ChunkLoadEvent(chunk.bukkitChunk, newChunk));
            }
            // CraftBukkit end
            chunk.a(this, this, i, j);
        }

        return chunk;
    }

    public Chunk getOrCreateChunk(int i, int j) {
        // CraftBukkit start
        Chunk chunk = (Chunk) this.chunks.get(LongHash.toLong(i, j));

        chunk = chunk == null ? (!this.world.isLoading && !this.forceChunkLoad ? this.emptyChunk : this.getChunkAt(i, j)) : chunk;
        if (chunk == this.emptyChunk) return chunk;
        if (i != chunk.locX || j != chunk.locZ) {
            b.error("Chunk (" + chunk.locX + ", " + chunk.locZ + ") stored at  (" + i + ", " + j + ") in world '" + world.getWorld().getName() + "'");
            b.error(chunk.getClass().getName());
            Throwable ex = new Throwable();
            ex.fillInStackTrace();
            ex.printStackTrace();
        }
        return chunk;
        // CraftBukkit end
    }

    public Chunk loadChunk(int i, int j) { // CraftBukkit - private -> public
        if (this.f == null) {
            return null;
        } else {
            // Poweruser start
            if(this.corruptRegions.contains(LongHash.toLong(i >> 5, j >> 5))) {
                return this.getChunkAt(i, j);
            }
            // Poweruser end
            try {
                Chunk chunk = this.f.a(this.world, i, j);

                if (chunk != null) {
                    chunk.p = this.world.getTime();
                    if (this.chunkProvider != null) {
                        this.chunkProvider.recreateStructures(i, j);
                    }
                }

                return chunk;
            } catch (Exception exception) {
                b.error("Couldn\'t load chunk", exception);
                return null;
            }
        }
    }

    public void saveChunkNOP(Chunk chunk) { // CraftBukkit - private -> public
        //if (this.f != null) {
        if (this.f != null && !chunk.isCorrupt() && !chunk.isEmpty()) { // Poweruser
            try {
                this.f.b(this.world, chunk);
            } catch (Exception exception) {
                b.error("Couldn\'t save entities", exception);
            }
        }
    }

    public void saveChunk(Chunk chunk) { // CraftBukkit - private -> public
        //if (this.f != null) {
        if (this.f != null && !chunk.isCorrupt() && !chunk.isEmpty()) { // Poweruser
            try {
                chunk.p = this.world.getTime();
                this.f.a(this.world, chunk);
                // CraftBukkit start - IOException to Exception
            } catch (Exception ioexception) {
                b.error("Couldn\'t save chunk", ioexception);
                /* Remove extra exception
            } catch (ExceptionWorldConflict exceptionworldconflict) {
                b.error("Couldn\'t save chunk; already in use by another instance of Minecraft?", exceptionworldconflict);
                // CraftBukkit end */
            }
        }
    }

    public void getChunkAt(IChunkProvider ichunkprovider, int i, int j) {
        Chunk chunk = this.getOrCreateChunk(i, j);

        if (!chunk.done) {
            chunk.p();
            if (this.chunkProvider != null) {
                this.chunkProvider.getChunkAt(ichunkprovider, i, j);

                // CraftBukkit start
                //BlockSand.instaFall = true;
                BlockFalling.enableInstantFall(this.world); // Poweruser
                Random random = new Random();
                random.setSeed(world.getSeed());
                long xRand = random.nextLong() / 2L * 2L + 1L;
                long zRand = random.nextLong() / 2L * 2L + 1L;
                random.setSeed((long) i * xRand + (long) j * zRand ^ world.getSeed());

                org.bukkit.World world = this.world.getWorld();
                if (world != null) {
                    this.world.populating = true;
                    try {
                        for (org.bukkit.generator.BlockPopulator populator : world.getPopulators()) {
                            populator.populate(world, random, chunk.bukkitChunk);
                        }
                    } finally {
                        this.world.populating = false;
                    }
                }
                //BlockSand.instaFall = false;
                BlockFalling.disableInstantFall(this.world); // Poweruser
                this.world.getServer().getPluginManager().callEvent(new org.bukkit.event.world.ChunkPopulateEvent(chunk.bukkitChunk));
                // CraftBukkit end

                chunk.e();
            }
        }
    }

    public boolean saveChunks(boolean flag, IProgressUpdate iprogressupdate) {
        int i = 0;
        // CraftBukkit start
        Iterator iterator = this.chunks.values().iterator();

        while (iterator.hasNext()) {
            Chunk chunk = (Chunk) iterator.next();
            // CraftBukkit end

            if (flag) {
                this.saveChunkNOP(chunk);
            }

            if (chunk.a(flag)) {
                this.saveChunk(chunk);
                chunk.n = false;
                ++i;
                if (i == 24 && !flag) {
                    return false;
                }
            }
        }
        // Poweruser start
        /*
        if(flag && this.canSave()) {
            this.b();
        }
        */
        // Poweruser end

        return true;
    }

    public void b() {
        if (this.f != null) {
            this.f.b();
        }
    }

    public boolean unloadChunks() {
        if (!this.world.savingDisabled) {
            // CraftBukkit start
            Server server = this.world.getServer();
            for (int i = 0; i < 100 && !this.unloadQueue.isEmpty(); i++) {
                long chunkcoordinates = this.unloadQueue.popFirst();
                Chunk chunk = this.chunks.get(chunkcoordinates);
                if (chunk == null) continue;
                if (this.world.getWorld().isChunkInUse(LongHash.msw(chunkcoordinates), LongHash.lsw(chunkcoordinates))) { continue; } // Poweruser

                ChunkUnloadEvent event = new ChunkUnloadEvent(chunk.bukkitChunk);
                server.getPluginManager().callEvent(event);
                if (!event.isCancelled()) {
                    chunk.removeEntities();
                    this.saveChunk(chunk);
                    this.saveChunkNOP(chunk);
                    // this.unloadQueue.remove(olong);
                    // this.chunks.remove(olong.longValue());
                    this.chunks.remove(chunkcoordinates); // CraftBukkit
                }
            }
            // CraftBukkit end

            if (this.f != null) {
                this.f.a();
            }
        }

        return this.chunkProvider.unloadChunks();
    }

    public boolean canSave() {
        return !this.world.savingDisabled;
    }

    public String getName() {
        // CraftBukkit - this.chunks.count() -> .values().size()
        return "ServerChunkCache: " + this.chunks.values().size() + " Drop: " + this.unloadQueue.size();
    }

    public List getMobsFor(EnumCreatureType enumcreaturetype, int i, int j, int k) {
        return this.chunkProvider.getMobsFor(enumcreaturetype, i, j, k);
    }

    public ChunkPosition findNearestMapFeature(World world, String s, int i, int j, int k) {
        return this.chunkProvider.findNearestMapFeature(world, s, i, j, k);
    }

    public int getLoadedChunks() {
        // CraftBukkit - this.chunks.count() -> .values().size()
        return this.chunks.values().size();
    }

    public void recreateStructures(int i, int j) {}
}
