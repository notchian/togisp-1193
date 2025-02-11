package net.minecraft.world.level.block;

import net.minecraft.core.BlockPosition;
import net.minecraft.server.level.WorldServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.IBlockAccess;
import net.minecraft.world.level.IWorldReader;
import net.minecraft.world.level.World;
import net.minecraft.world.level.block.grower.WorldGenTreeProvider;
import net.minecraft.world.level.block.state.BlockBase;
import net.minecraft.world.level.block.state.BlockStateList;
import net.minecraft.world.level.block.state.IBlockData;
import net.minecraft.world.level.block.state.properties.BlockProperties;
import net.minecraft.world.level.block.state.properties.BlockStateInteger;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.VoxelShapeCollision;

// CraftBukkit start
import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.block.BlockState;
import org.bukkit.event.world.StructureGrowEvent;
// CraftBukkit end

public class BlockSapling extends BlockPlant implements IBlockFragilePlantElement {

    public static final BlockStateInteger STAGE = BlockProperties.STAGE;
    protected static final float AABB_OFFSET = 6.0F;
    protected static final VoxelShape SHAPE = Block.box(2.0D, 0.0D, 2.0D, 14.0D, 12.0D, 14.0D);
    private final WorldGenTreeProvider treeGrower;
    public static TreeType treeType; // CraftBukkit

    protected BlockSapling(WorldGenTreeProvider worldgentreeprovider, BlockBase.Info blockbase_info) {
        super(blockbase_info);
        this.treeGrower = worldgentreeprovider;
        this.registerDefaultState((IBlockData) ((IBlockData) this.stateDefinition.any()).setValue(BlockSapling.STAGE, 0));
    }

    @Override
    public VoxelShape getShape(IBlockData iblockdata, IBlockAccess iblockaccess, BlockPosition blockposition, VoxelShapeCollision voxelshapecollision) {
        return BlockSapling.SHAPE;
    }

    @Override
    public void randomTick(IBlockData iblockdata, WorldServer worldserver, BlockPosition blockposition, RandomSource randomsource) {
        if (worldserver.getMaxLocalRawBrightness(blockposition.above()) >= 9 && randomsource.nextFloat() < (worldserver.spigotConfig.saplingModifier / (100.0f * 7))) { // Spigot - SPIGOT-7159: Better modifier resolution
            this.advanceTree(worldserver, blockposition, iblockdata, randomsource);
        }

    }

    public void advanceTree(WorldServer worldserver, BlockPosition blockposition, IBlockData iblockdata, RandomSource randomsource) {
        if ((Integer) iblockdata.getValue(BlockSapling.STAGE) == 0) {
            worldserver.setBlock(blockposition, (IBlockData) iblockdata.cycle(BlockSapling.STAGE), 4);
        } else {
            // CraftBukkit start
            if (worldserver.captureTreeGeneration) {
                this.treeGrower.growTree(worldserver, worldserver.getChunkSource().getGenerator(), blockposition, iblockdata, randomsource);
            } else {
                worldserver.captureTreeGeneration = true;
                this.treeGrower.growTree(worldserver, worldserver.getChunkSource().getGenerator(), blockposition, iblockdata, randomsource);
                worldserver.captureTreeGeneration = false;
                if (worldserver.capturedBlockStates.size() > 0) {
                    TreeType treeType = BlockSapling.treeType;
                    BlockSapling.treeType = null;
                    Location location = new Location(worldserver.getWorld(), blockposition.getX(), blockposition.getY(), blockposition.getZ());
                    java.util.List<BlockState> blocks = new java.util.ArrayList<>(worldserver.capturedBlockStates.values());
                    worldserver.capturedBlockStates.clear();
                    StructureGrowEvent event = null;
                    if (treeType != null) {
                        event = new StructureGrowEvent(location, treeType, false, null, blocks);
                        org.bukkit.Bukkit.getPluginManager().callEvent(event);
                    }
                    if (event == null || !event.isCancelled()) {
                        for (BlockState blockstate : blocks) {
                            blockstate.update(true);
                        }
                    }
                }
            }
            // CraftBukkit end
        }

    }

    @Override
    public boolean isValidBonemealTarget(IWorldReader iworldreader, BlockPosition blockposition, IBlockData iblockdata, boolean flag) {
        return true;
    }

    @Override
    public boolean isBonemealSuccess(World world, RandomSource randomsource, BlockPosition blockposition, IBlockData iblockdata) {
        return (double) world.random.nextFloat() < 0.45D;
    }

    @Override
    public void performBonemeal(WorldServer worldserver, RandomSource randomsource, BlockPosition blockposition, IBlockData iblockdata) {
        this.advanceTree(worldserver, blockposition, iblockdata, randomsource);
    }

    @Override
    protected void createBlockStateDefinition(BlockStateList.a<Block, IBlockData> blockstatelist_a) {
        blockstatelist_a.add(BlockSapling.STAGE);
    }
}
