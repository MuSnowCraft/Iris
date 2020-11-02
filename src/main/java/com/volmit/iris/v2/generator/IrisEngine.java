package com.volmit.iris.v2.generator;

import com.volmit.iris.Iris;
import com.volmit.iris.object.*;
import com.volmit.iris.util.*;
import com.volmit.iris.v2.scaffold.cache.Cache;
import com.volmit.iris.v2.scaffold.engine.Engine;
import com.volmit.iris.v2.scaffold.engine.EngineFramework;
import com.volmit.iris.v2.scaffold.engine.EngineTarget;
import com.volmit.iris.v2.scaffold.hunk.Hunk;
import com.volmit.iris.v2.scaffold.parallel.MultiBurst;
import io.papermc.lib.PaperLib;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.server.v1_16_R2.*;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;

public class IrisEngine extends BlockPopulator implements Engine
{
    @Getter
    private final EngineTarget target;

    @Getter
    private final EngineFramework framework;

    @Setter
    @Getter
    private volatile int parallelism;

    @Setter
    @Getter
    private volatile int minHeight;

    public IrisEngine(EngineTarget target)
    {
        Iris.info("Initializing Engine: " + target.getWorld().getName() + "/" + target.getDimension().getLoadKey() + " (" + target.getHeight() + " height)");
        this.target = target;
        this.framework = new IrisEngineFramework(this);
        minHeight = 0;
    }

    @Override
    public void generate(int x, int z, Hunk<BlockData> vblocks, Hunk<Biome> biomes) {
        Hunk<BlockData> blocks = vblocks.listen(this::catchBlockUpdates);
        getFramework().getEngineParallax().generateParallaxArea(x, z);
        getFramework().getBiomeActuator().actuate(x, z, biomes);
        getFramework().getTerrainActuator().actuate(x, z, blocks);
        getFramework().getCaveModifier().modify(x, z, blocks);
        getFramework().getRavineModifier().modify(x, z, blocks);
        getFramework().getDepositModifier().modify(x, z, blocks);
        getFramework().getDecorantActuator().actuate(x, z, blocks);
        getFramework().getEngineParallax().insertParallax(x, z, blocks);
        getFramework().recycle();
    }

    private void catchBlockUpdates(int x, int y, int z, BlockData data) {
        if(B.isUpdatable(data))
        {
            getParallax().updateBlock(x,y,z);
        }
    }

    @Override
    public void populate(@NotNull World world, @NotNull Random random, @NotNull Chunk c)
    {
        RNG rx = new RNG(Cache.key(c.getX(), c.getZ()));
        getParallax().getUpdatesR(c.getX(), c.getZ()).iterate(0, (x,y,z) -> update(x, getMinHeight() + y, z, c, rx));
    }

    private void update(int x, int y, int z, Chunk c, RNG rf)
    {
        Block block = c.getBlock(x,y,z);
        BlockData data = block.getBlockData();

        if(B.isStorage(data))
        {
            RNG rx = rf.nextParallelRNG(x).nextParallelRNG(z).nextParallelRNG(y);
            block.setType(Material.AIR, false);
            block.setBlockData(data, true);
            InventorySlotType slot = null;

            if(B.isStorageChest(data))
            {
                slot = InventorySlotType.STORAGE;
            }

            if(slot != null)
            {
                KList<IrisLootTable> tables = getLootTables(rx.nextParallelRNG(4568111), block);
                InventorySlotType slott = slot;

                try
                {
                    InventoryHolder m = (InventoryHolder) block.getState();
                    addItems(false, m.getInventory(), rx, tables, slott, x, y, z, 15);
                }

                catch(Throwable ignored)
                {

                }
            }
        }

        else if(B.isLit(data))
        {
            block.setType(Material.AIR, false);
            block.setBlockData(data, true);
        }
    }



    public void scramble(Inventory inventory, RNG rng)
    {
        org.bukkit.inventory.ItemStack[] items = inventory.getContents();
        org.bukkit.inventory.ItemStack[] nitems = new org.bukkit.inventory.ItemStack[inventory.getSize()];
        System.arraycopy(items, 0, nitems, 0, items.length);
        boolean packedFull = false;

        splitting: for(int i = 0; i < nitems.length; i++)
        {
            ItemStack is = nitems[i];

            if(is != null && is.getAmount() > 1 && !packedFull)
            {
                for(int j = 0; j < nitems.length; j++)
                {
                    if(nitems[j] == null)
                    {
                        int take = rng.nextInt(is.getAmount());
                        take = take == 0 ? 1 : take;
                        is.setAmount(is.getAmount() - take);
                        nitems[j] = is.clone();
                        nitems[j].setAmount(take);
                        continue splitting;
                    }
                }

                packedFull = true;
            }
        }

        for(int i = 0; i < 4; i++)
        {
            try
            {
                Arrays.parallelSort(nitems, (a, b) -> rng.nextInt());
                break;
            }

            catch(Throwable e)
            {

            }
        }

        inventory.setContents(nitems);
    }

    public void injectTables(KList<IrisLootTable> list, IrisLootReference r)
    {
        if(r.getMode().equals(LootMode.CLEAR) || r.getMode().equals(LootMode.REPLACE))
        {
            list.clear();
        }

        list.addAll(r.getLootTables(getFramework().getComplex()));
    }

    public KList<IrisLootTable> getLootTables(RNG rng, Block b)
    {
        int rx = b.getX();
        int rz = b.getZ();
        double he = getFramework().getComplex().getHeightStream().get(rx, rz);
        IrisRegion region = getFramework().getComplex().getRegionStream().get(rx, rz);
        IrisBiome biomeSurface = getFramework().getComplex().getTrueBiomeStream().get(rx, rz);
        IrisBiome biomeUnder = b.getY() < he ? getFramework().getComplex().getCaveBiomeStream().get(rx, rz) : biomeSurface;
        KList<IrisLootTable> tables = new KList<>();
        double multiplier = 1D * getDimension().getLoot().getMultiplier() * region.getLoot().getMultiplier() * biomeSurface.getLoot().getMultiplier() * biomeUnder.getLoot().getMultiplier();
        injectTables(tables, getDimension().getLoot());
        injectTables(tables, region.getLoot());
        injectTables(tables, biomeSurface.getLoot());
        injectTables(tables, biomeUnder.getLoot());

        if(tables.isNotEmpty())
        {
            int target = (int) Math.round(tables.size() * multiplier);

            while(tables.size() < target && tables.isNotEmpty())
            {
                tables.add(tables.get(rng.i(tables.size() - 1)));
            }

            while(tables.size() > target && tables.isNotEmpty())
            {
                tables.remove(rng.i(tables.size() - 1));
            }
        }

        return tables;
    }

    public void addItems(boolean debug, Inventory inv, RNG rng, KList<IrisLootTable> tables, InventorySlotType slot, int x, int y, int z, int mgf)
    {
        KList<ItemStack> items = new KList<>();

        int b = 4;
        for(IrisLootTable i : tables)
        {
            b++;
            items.addAll(i.getLoot(debug, items.isEmpty(), rng.nextParallelRNG(345911), slot, x, y, z, b + b, mgf + b));
        }

        for(ItemStack i : items)
        {
            inv.addItem(i);
        }

        scramble(inv, rng);
    }

}
