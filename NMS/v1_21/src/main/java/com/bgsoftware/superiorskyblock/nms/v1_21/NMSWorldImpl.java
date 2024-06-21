package com.bgsoftware.superiorskyblock.nms.v1_21;

import com.bgsoftware.common.reflection.ReflectField;
import com.bgsoftware.common.reflection.ReflectMethod;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.Materials;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.key.Keys;
import com.bgsoftware.superiorskyblock.island.signs.IslandSigns;
import com.bgsoftware.superiorskyblock.nms.ICachedBlock;
import com.bgsoftware.superiorskyblock.nms.NMSWorld;
import com.bgsoftware.superiorskyblock.nms.bridge.PistonPushReaction;
import com.bgsoftware.superiorskyblock.nms.v1_20_3.algorithms.NMSCachedBlock;
import com.bgsoftware.superiorskyblock.nms.v1_21.generator.IslandsGeneratorImpl;
import com.bgsoftware.superiorskyblock.nms.v1_21.spawners.TickingSpawnerBlockEntityNotifier;
import com.bgsoftware.superiorskyblock.nms.v1_21.world.KeyBlocksCache;
import com.bgsoftware.superiorskyblock.nms.v1_21.world.PropertiesMapper;
import com.bgsoftware.superiorskyblock.nms.v1_21.world.WorldEditSessionImpl;
import com.bgsoftware.superiorskyblock.nms.world.WorldEditSession;
import com.bgsoftware.superiorskyblock.tag.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.BubbleColumn;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.CraftSign;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.generator.ChunkGenerator;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;

public class NMSWorldImpl implements NMSWorld {

    private static final ReflectMethod<Object> LINES_SIGN_CHANGE_EVENT = new ReflectMethod<>(SignChangeEvent.class, "lines");
    private static final ReflectField<List<TickingBlockEntity>> LEVEL_BLOCK_ENTITY_TICKERS_PROTECTED = new ReflectField<>(
            Level.class, List.class, Modifier.PROTECTED | Modifier.FINAL, 1);
    private static final ReflectField<List<TickingBlockEntity>> LEVEL_BLOCK_ENTITY_TICKERS_PUBLIC = new ReflectField<>(
            Level.class, List.class, Modifier.PUBLIC | Modifier.FINAL, 1);

    private final SuperiorSkyblockPlugin plugin;

    public NMSWorldImpl(SuperiorSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Key getBlockKey(ChunkSnapshot chunkSnapshot, int x, int y, int z) {
        Block block = ((CraftBlockData) chunkSnapshot.getBlockData(x, y, z)).getState().getBlock();
        Location location = new Location(
                Bukkit.getWorld(chunkSnapshot.getWorldName()),
                (chunkSnapshot.getX() << 4) + x,
                y,
                (chunkSnapshot.getZ() << 4) + z
        );

        return Keys.of(KeyBlocksCache.getBlockKey(block), location);
    }

    @Override
    public void listenSpawner(Location location, IntFunction<Integer> delayChangeCallback) {
        World world = location.getWorld();

        if (world == null)
            return;

        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);

        if (!(blockEntity instanceof SpawnerBlockEntity spawnerBlockEntity))
            return;

        List<TickingBlockEntity> blockEntityTickers;

        if (LEVEL_BLOCK_ENTITY_TICKERS_PROTECTED.isValid()) {
            blockEntityTickers = LEVEL_BLOCK_ENTITY_TICKERS_PROTECTED.get(serverLevel);
        } else {
            blockEntityTickers = serverLevel.blockEntityTickers;
        }

        Iterator<TickingBlockEntity> blockEntityTickersIterator = blockEntityTickers.iterator();
        List<TickingBlockEntity> tickersToAdd = new ArrayList<>();

        while (blockEntityTickersIterator.hasNext()) {
            TickingBlockEntity tickingBlockEntity = blockEntityTickersIterator.next();
            if (tickingBlockEntity.getPos().equals(blockPos) &&
                    !(tickingBlockEntity instanceof TickingSpawnerBlockEntityNotifier)) {
                blockEntityTickersIterator.remove();
                tickersToAdd.add(new TickingSpawnerBlockEntityNotifier(spawnerBlockEntity, tickingBlockEntity, delayChangeCallback));
            }
        }

        if (!tickersToAdd.isEmpty())
            blockEntityTickers.addAll(tickersToAdd);
    }

    @Override
    public void setWorldBorder(SuperiorPlayer superiorPlayer, Island island) {
        if (!plugin.getSettings().isWorldBorders())
            return;

        Player player = superiorPlayer.asPlayer();
        World world = superiorPlayer.getWorld();

        if (world == null || player == null)
            return;

        int islandSize = island == null ? 0 : island.getIslandSize();

        boolean disabled = !superiorPlayer.hasWorldBorderEnabled() || islandSize < 0;

        ServerLevel serverLevel = ((CraftWorld) world).getHandle();

        WorldBorder worldBorder;

        if (disabled || island == null || (!plugin.getSettings().getSpawn().isWorldBorder() && island.isSpawn())) {
            worldBorder = serverLevel.getWorldBorder();
        } else {
            worldBorder = new WorldBorder();
            worldBorder.world = serverLevel;

            World.Environment environment = world.getEnvironment();
            Location center = island.getCenter(environment);

            worldBorder.setWarningBlocks(0);
            worldBorder.setSize((islandSize * 2) + 1);
            worldBorder.setCenter(center.getX(), center.getZ());

            double worldBorderSize = worldBorder.getSize();
            switch (superiorPlayer.getBorderColor()) {
                case GREEN -> worldBorder.lerpSizeBetween(worldBorderSize - 0.1D, worldBorderSize, Long.MAX_VALUE);
                case RED -> worldBorder.lerpSizeBetween(worldBorderSize, worldBorderSize - 1.0D, Long.MAX_VALUE);
            }
        }

        ClientboundInitializeBorderPacket initializeBorderPacket = new ClientboundInitializeBorderPacket(worldBorder);
        ((CraftPlayer) player).getHandle().connection.send(initializeBorderPacket);
    }

    @Override
    public Object getBlockData(org.bukkit.block.Block block) {
        return block.getBlockData();
    }

    @Override
    public void setBlock(Location location, int combinedId) {
        World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        NMSUtils.setBlock(serverLevel.getChunkAt(blockPos), blockPos, combinedId, null, null);

        ClientboundBlockUpdatePacket blockUpdatePacket = new ClientboundBlockUpdatePacket(serverLevel, blockPos);
        NMSUtils.sendPacketToRelevantPlayers(serverLevel, blockPos.getX() >> 4, blockPos.getZ() >> 4, blockUpdatePacket);
    }

    @Override
    public ICachedBlock cacheBlock(org.bukkit.block.Block block) {
        return new NMSCachedBlock(block);
    }

    @Override
    public CompoundTag readBlockStates(Location location) {
        World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return null;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockState blockState = serverLevel.getBlockState(blockPos);

        if (blockState.getValues().isEmpty())
            return null;

        CompoundTag compoundTag = new CompoundTag();

        blockState.getValues().forEach((property, value) -> {
            String name = property.getName();

            if (property instanceof BooleanProperty) {
                compoundTag.setByte(name, (Boolean) value ? (byte) 1 : 0);
            } else if (property instanceof IntegerProperty integerProperty) {
                compoundTag.setIntArray(name, new int[]{(Integer) value, integerProperty.min, integerProperty.max});
            } else if (property instanceof EnumProperty<?>) {
                compoundTag.setString(PropertiesMapper.getPropertyName(property), ((Enum<?>) value).name());
            }
        });

        return compoundTag;
    }

    @Override
    public byte[] getLightLevels(Location location) {
        World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return new byte[0];

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());

        LevelLightEngine lightEngine = serverLevel.getLightEngine();
        return new byte[]{
                location.getWorld().getEnvironment() != World.Environment.NORMAL ? 0 :
                        (byte) lightEngine.getLayerListener(LightLayer.SKY).getLightValue(blockPos),
                (byte) lightEngine.getLayerListener(LightLayer.BLOCK).getLightValue(blockPos)
        };
    }

    @Override
    public CompoundTag readTileEntity(Location location) {
        World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return null;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);

        if (blockEntity == null)
            return null;

        net.minecraft.nbt.CompoundTag compoundTag = blockEntity.saveWithFullMetadata(MinecraftServer.getServer().registryAccess());

        compoundTag.remove("x");
        compoundTag.remove("y");
        compoundTag.remove("z");

        return CompoundTag.fromNBT(compoundTag);
    }

    @Override
    public boolean isWaterLogged(org.bukkit.block.Block block) {
        if (Materials.isWater(block.getType()))
            return true;

        BlockData blockData = block.getBlockData();

        return blockData instanceof BubbleColumn ||
                (blockData instanceof Waterlogged && ((Waterlogged) blockData).isWaterlogged());
    }

    @Override
    public PistonPushReaction getPistonReaction(org.bukkit.block.Block block) {
        BlockState blockState = ((CraftBlock) block).getNMS();
        return PistonPushReaction.values()[blockState.getPistonPushReaction().ordinal()];
    }

    @Override
    public int getDefaultAmount(org.bukkit.block.Block bukkitBlock) {
        BlockState blockState = ((CraftBlock) bukkitBlock).getNMS();
        Block block = blockState.getBlock();
        return NMSUtils.isDoubleBlock(block, blockState) ? 2 : 1;
    }

    @Override
    public void placeSign(Island island, Location location) {
        World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);

        if (!(blockEntity instanceof SignBlockEntity signBlockEntity))
            return;

        String[] lines = new String[4];
        Component[] messages = signBlockEntity.getFrontText().getMessages(false);
        System.arraycopy(CraftSign.revertComponents(messages), 0, lines, 0, lines.length);
        String[] strippedLines = new String[4];
        for (int i = 0; i < 4; i++)
            strippedLines[i] = Formatters.STRIP_COLOR_FORMATTER.format(lines[i]);

        Component[] newLines;

        IslandSigns.Result result = IslandSigns.handleSignPlace(island.getOwner(), location, strippedLines, false);
        if (result.isCancelEvent()) {
            newLines = CraftSign.sanitizeLines(strippedLines);
        } else {
            newLines = CraftSign.sanitizeLines(lines);
        }

        System.arraycopy(newLines, 0, messages, 0, 4);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setSignLines(SignChangeEvent signChangeEvent, String[] lines) {
        if (LINES_SIGN_CHANGE_EVENT.isValid()) {
            for (int i = 0; i < lines.length; i++)
                signChangeEvent.setLine(i, lines[i]);
        }
    }

    @Override
    public void playGeneratorSound(Location location) {
        World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        serverLevel.levelEvent(1501, blockPos, 0);
    }

    @Override
    public void playBreakAnimation(org.bukkit.block.Block block) {
        ServerLevel serverLevel = ((CraftWorld) block.getWorld()).getHandle();
        BlockPos blockPos = new BlockPos(block.getX(), block.getY(), block.getZ());
        serverLevel.levelEvent(null, 2001, blockPos, Block.getId(serverLevel.getBlockState(blockPos)));
    }

    @Override
    public void playPlaceSound(Location location) {
        World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        SoundType soundType = serverLevel.getBlockState(blockPos).getSoundType();

        serverLevel.playSound(null, blockPos, soundType.getPlaceSound(), SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
    }

    @Override
    public int getMinHeight(World world) {
        return world.getMinHeight();
    }

    @Override
    public void removeAntiXray(World bukkitWorld) {
        // Doesn't exist in this version.
    }

    @Override
    public ChunkGenerator createGenerator(SuperiorSkyblockPlugin plugin) {
        return new IslandsGeneratorImpl(plugin);
    }

    @Override
    public WorldEditSession createEditSession(World world) {
        return new WorldEditSessionImpl(((CraftWorld) world).getHandle());
    }

}
