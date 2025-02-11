package net.minecraft.server.level;

import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.PacketListenerPlayOut;
import net.minecraft.network.protocol.game.PacketPlayOutAttachEntity;
import net.minecraft.network.protocol.game.PacketPlayOutEntity;
import net.minecraft.network.protocol.game.PacketPlayOutEntityDestroy;
import net.minecraft.network.protocol.game.PacketPlayOutEntityEffect;
import net.minecraft.network.protocol.game.PacketPlayOutEntityEquipment;
import net.minecraft.network.protocol.game.PacketPlayOutEntityHeadRotation;
import net.minecraft.network.protocol.game.PacketPlayOutEntityMetadata;
import net.minecraft.network.protocol.game.PacketPlayOutEntityTeleport;
import net.minecraft.network.protocol.game.PacketPlayOutEntityVelocity;
import net.minecraft.network.protocol.game.PacketPlayOutMount;
import net.minecraft.network.protocol.game.PacketPlayOutUpdateAttributes;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.DataWatcher;
import net.minecraft.server.network.PlayerConnection;
import net.minecraft.util.MathHelper;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityInsentient;
import net.minecraft.world.entity.EntityLiving;
import net.minecraft.world.entity.EnumItemSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifiable;
import net.minecraft.world.entity.decoration.EntityItemFrame;
import net.minecraft.world.entity.projectile.EntityArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemWorldMap;
import net.minecraft.world.level.saveddata.maps.WorldMap;
import net.minecraft.world.phys.Vec3D;
import org.slf4j.Logger;

// CraftBukkit start
import net.minecraft.server.network.ServerPlayerConnection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerVelocityEvent;
// CraftBukkit end

public class EntityTrackerEntry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TOLERANCE_LEVEL_ROTATION = 1;
    private final WorldServer level;
    private final Entity entity;
    private final int updateInterval;
    private final boolean trackDelta;
    private final Consumer<Packet<?>> broadcast;
    private final VecDeltaCodec positionCodec = new VecDeltaCodec();
    private int yRotp;
    private int xRotp;
    private int yHeadRotp;
    private Vec3D ap;
    private int tickCount;
    private int teleportDelay;
    private List<Entity> lastPassengers;
    private boolean wasRiding;
    private boolean wasOnGround;
    @Nullable
    private List<DataWatcher.b<?>> trackedDataValues;
    // CraftBukkit start
    private final Set<ServerPlayerConnection> trackedPlayers;

    public EntityTrackerEntry(WorldServer worldserver, Entity entity, int i, boolean flag, Consumer<Packet<?>> consumer, Set<ServerPlayerConnection> trackedPlayers) {
        this.trackedPlayers = trackedPlayers;
        // CraftBukkit end
        this.ap = Vec3D.ZERO;
        this.lastPassengers = Collections.emptyList();
        this.level = worldserver;
        this.broadcast = consumer;
        this.entity = entity;
        this.updateInterval = i;
        this.trackDelta = flag;
        this.positionCodec.setBase(entity.trackingPosition());
        this.yRotp = MathHelper.floor(entity.getYRot() * 256.0F / 360.0F);
        this.xRotp = MathHelper.floor(entity.getXRot() * 256.0F / 360.0F);
        this.yHeadRotp = MathHelper.floor(entity.getYHeadRot() * 256.0F / 360.0F);
        this.wasOnGround = entity.isOnGround();
        this.trackedDataValues = entity.getEntityData().getNonDefaultValues();
    }

    public void sendChanges() {
        List<Entity> list = this.entity.getPassengers();

        if (!list.equals(this.lastPassengers)) {
            this.lastPassengers = list;
            this.broadcastAndSend(new PacketPlayOutMount(this.entity)); // CraftBukkit
        }

        Entity entity = this.entity;

        if (entity instanceof EntityItemFrame) {
            EntityItemFrame entityitemframe = (EntityItemFrame) entity;

            if (true || this.tickCount % 10 == 0) { // CraftBukkit - Moved below, should always enter this block
                ItemStack itemstack = entityitemframe.getItem();

                if (this.tickCount % 10 == 0 && itemstack.getItem() instanceof ItemWorldMap) { // CraftBukkit - Moved this.tickCounter % 10 logic here so item frames do not enter the other blocks
                    Integer integer = ItemWorldMap.getMapId(itemstack);
                    WorldMap worldmap = ItemWorldMap.getSavedData(integer, this.level);

                    if (worldmap != null) {
                        Iterator<ServerPlayerConnection> iterator = this.trackedPlayers.iterator(); // CraftBukkit

                        while (iterator.hasNext()) {
                            EntityPlayer entityplayer = iterator.next().getPlayer(); // CraftBukkit

                            worldmap.tickCarriedBy(entityplayer, itemstack);
                            Packet<?> packet = worldmap.getUpdatePacket(integer, entityplayer);

                            if (packet != null) {
                                entityplayer.connection.send(packet);
                            }
                        }
                    }
                }

                this.sendDirtyEntityData();
            }
        }

        if (this.tickCount % this.updateInterval == 0 || this.entity.hasImpulse || this.entity.getEntityData().isDirty()) {
            int i;
            int j;

            if (this.entity.isPassenger()) {
                i = MathHelper.floor(this.entity.getYRot() * 256.0F / 360.0F);
                j = MathHelper.floor(this.entity.getXRot() * 256.0F / 360.0F);
                boolean flag = Math.abs(i - this.yRotp) >= 1 || Math.abs(j - this.xRotp) >= 1;

                if (flag) {
                    this.broadcast.accept(new PacketPlayOutEntity.PacketPlayOutEntityLook(this.entity.getId(), (byte) i, (byte) j, this.entity.isOnGround()));
                    this.yRotp = i;
                    this.xRotp = j;
                }

                this.positionCodec.setBase(this.entity.trackingPosition());
                this.sendDirtyEntityData();
                this.wasRiding = true;
            } else {
                ++this.teleportDelay;
                i = MathHelper.floor(this.entity.getYRot() * 256.0F / 360.0F);
                j = MathHelper.floor(this.entity.getXRot() * 256.0F / 360.0F);
                Vec3D vec3d = this.entity.trackingPosition();
                boolean flag1 = this.positionCodec.delta(vec3d).lengthSqr() >= 7.62939453125E-6D;
                Packet<?> packet1 = null;
                boolean flag2 = flag1 || this.tickCount % 60 == 0;
                boolean flag3 = Math.abs(i - this.yRotp) >= 1 || Math.abs(j - this.xRotp) >= 1;

                if (this.tickCount > 0 || this.entity instanceof EntityArrow) {
                    long k = this.positionCodec.encodeX(vec3d);
                    long l = this.positionCodec.encodeY(vec3d);
                    long i1 = this.positionCodec.encodeZ(vec3d);
                    boolean flag4 = k < -32768L || k > 32767L || l < -32768L || l > 32767L || i1 < -32768L || i1 > 32767L;

                    if (!flag4 && this.teleportDelay <= 400 && !this.wasRiding && this.wasOnGround == this.entity.isOnGround()) {
                        if ((!flag2 || !flag3) && !(this.entity instanceof EntityArrow)) {
                            if (flag2) {
                                packet1 = new PacketPlayOutEntity.PacketPlayOutRelEntityMove(this.entity.getId(), (short) ((int) k), (short) ((int) l), (short) ((int) i1), this.entity.isOnGround());
                            } else if (flag3) {
                                packet1 = new PacketPlayOutEntity.PacketPlayOutEntityLook(this.entity.getId(), (byte) i, (byte) j, this.entity.isOnGround());
                            }
                        } else {
                            packet1 = new PacketPlayOutEntity.PacketPlayOutRelEntityMoveLook(this.entity.getId(), (short) ((int) k), (short) ((int) l), (short) ((int) i1), (byte) i, (byte) j, this.entity.isOnGround());
                        }
                    } else {
                        this.wasOnGround = this.entity.isOnGround();
                        this.teleportDelay = 0;
                        packet1 = new PacketPlayOutEntityTeleport(this.entity);
                    }
                }

                if ((this.trackDelta || this.entity.hasImpulse || this.entity instanceof EntityLiving && ((EntityLiving) this.entity).isFallFlying()) && this.tickCount > 0) {
                    Vec3D vec3d1 = this.entity.getDeltaMovement();
                    double d0 = vec3d1.distanceToSqr(this.ap);

                    if (d0 > 1.0E-7D || d0 > 0.0D && vec3d1.lengthSqr() == 0.0D) {
                        this.ap = vec3d1;
                        this.broadcast.accept(new PacketPlayOutEntityVelocity(this.entity.getId(), this.ap));
                    }
                }

                if (packet1 != null) {
                    this.broadcast.accept(packet1);
                }

                this.sendDirtyEntityData();
                if (flag2) {
                    this.positionCodec.setBase(vec3d);
                }

                if (flag3) {
                    this.yRotp = i;
                    this.xRotp = j;
                }

                this.wasRiding = false;
            }

            i = MathHelper.floor(this.entity.getYHeadRot() * 256.0F / 360.0F);
            if (Math.abs(i - this.yHeadRotp) >= 1) {
                this.broadcast.accept(new PacketPlayOutEntityHeadRotation(this.entity, (byte) i));
                this.yHeadRotp = i;
            }

            this.entity.hasImpulse = false;
        }

        ++this.tickCount;
        if (this.entity.hurtMarked) {
            // CraftBukkit start - Create PlayerVelocity event
            boolean cancelled = false;

            if (this.entity instanceof EntityPlayer) {
                Player player = (Player) this.entity.getBukkitEntity();
                org.bukkit.util.Vector velocity = player.getVelocity();

                PlayerVelocityEvent event = new PlayerVelocityEvent(player, velocity.clone());
                this.entity.level.getCraftServer().getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    cancelled = true;
                } else if (!velocity.equals(event.getVelocity())) {
                    player.setVelocity(event.getVelocity());
                }
            }

            if (!cancelled) {
                this.broadcastAndSend(new PacketPlayOutEntityVelocity(this.entity));
            }
            // CraftBukkit end
            this.entity.hurtMarked = false;
        }

    }

    public void removePairing(EntityPlayer entityplayer) {
        this.entity.stopSeenByPlayer(entityplayer);
        entityplayer.connection.send(new PacketPlayOutEntityDestroy(new int[]{this.entity.getId()}));
    }

    public void addPairing(EntityPlayer entityplayer) {
        PlayerConnection playerconnection = entityplayer.connection;

        Objects.requireNonNull(entityplayer.connection);
        this.sendPairingData(playerconnection::send, entityplayer); // CraftBukkit - add player
        this.entity.startSeenByPlayer(entityplayer);
    }

    public void sendPairingData(Consumer<Packet<?>> consumer, EntityPlayer entityplayer) { // CraftBukkit - add player
        if (this.entity.isRemoved()) {
            // CraftBukkit start - Remove useless error spam, just return
            // EntityTrackerEntry.LOGGER.warn("Fetching packet for removed entity {}", this.entity);
            return;
            // CraftBukkit end
        }

        Packet<PacketListenerPlayOut> packet = this.entity.getAddEntityPacket();

        this.yHeadRotp = MathHelper.floor(this.entity.getYHeadRot() * 256.0F / 360.0F);
        consumer.accept(packet);
        if (this.trackedDataValues != null) {
            consumer.accept(new PacketPlayOutEntityMetadata(this.entity.getId(), this.trackedDataValues));
        }

        boolean flag = this.trackDelta;

        if (this.entity instanceof EntityLiving) {
            Collection<AttributeModifiable> collection = ((EntityLiving) this.entity).getAttributes().getSyncableAttributes();

            // CraftBukkit start - If sending own attributes send scaled health instead of current maximum health
            if (this.entity.getId() == entityplayer.getId()) {
                ((EntityPlayer) this.entity).getBukkitEntity().injectScaledMaxHealth(collection, false);
            }
            // CraftBukkit end

            if (!collection.isEmpty()) {
                consumer.accept(new PacketPlayOutUpdateAttributes(this.entity.getId(), collection));
            }

            if (((EntityLiving) this.entity).isFallFlying()) {
                flag = true;
            }
        }

        this.ap = this.entity.getDeltaMovement();
        if (flag && !(this.entity instanceof EntityLiving)) {
            consumer.accept(new PacketPlayOutEntityVelocity(this.entity.getId(), this.ap));
        }

        if (this.entity instanceof EntityLiving) {
            List<Pair<EnumItemSlot, ItemStack>> list = Lists.newArrayList();
            EnumItemSlot[] aenumitemslot = EnumItemSlot.values();
            int i = aenumitemslot.length;

            for (int j = 0; j < i; ++j) {
                EnumItemSlot enumitemslot = aenumitemslot[j];
                ItemStack itemstack = ((EntityLiving) this.entity).getItemBySlot(enumitemslot);

                if (!itemstack.isEmpty()) {
                    list.add(Pair.of(enumitemslot, itemstack.copy()));
                }
            }

            if (!list.isEmpty()) {
                consumer.accept(new PacketPlayOutEntityEquipment(this.entity.getId(), list));
            }
            ((EntityLiving) this.entity).detectEquipmentUpdates(); // CraftBukkit - SPIGOT-3789: sync again immediately after sending
        }

        // CraftBukkit start - Fix for nonsensical head yaw
        this.yHeadRotp = MathHelper.floor(this.entity.getYHeadRot() * 256.0F / 360.0F);
        consumer.accept(new PacketPlayOutEntityHeadRotation(this.entity, (byte) yHeadRotp));
        // CraftBukkit end

        if (this.entity instanceof EntityLiving) {
            EntityLiving entityliving = (EntityLiving) this.entity;
            Iterator iterator = entityliving.getActiveEffects().iterator();

            while (iterator.hasNext()) {
                MobEffect mobeffect = (MobEffect) iterator.next();

                consumer.accept(new PacketPlayOutEntityEffect(this.entity.getId(), mobeffect));
            }
        }

        if (!this.entity.getPassengers().isEmpty()) {
            consumer.accept(new PacketPlayOutMount(this.entity));
        }

        if (this.entity.isPassenger()) {
            consumer.accept(new PacketPlayOutMount(this.entity.getVehicle()));
        }

        if (this.entity instanceof EntityInsentient) {
            EntityInsentient entityinsentient = (EntityInsentient) this.entity;

            if (entityinsentient.isLeashed()) {
                consumer.accept(new PacketPlayOutAttachEntity(entityinsentient, entityinsentient.getLeashHolder()));
            }
        }

    }

    private void sendDirtyEntityData() {
        DataWatcher datawatcher = this.entity.getEntityData();
        List<DataWatcher.b<?>> list = datawatcher.packDirty();

        if (list != null) {
            this.trackedDataValues = datawatcher.getNonDefaultValues();
            this.broadcastAndSend(new PacketPlayOutEntityMetadata(this.entity.getId(), list));
        }

        if (this.entity instanceof EntityLiving) {
            Set<AttributeModifiable> set = ((EntityLiving) this.entity).getAttributes().getDirtyAttributes();

            if (!set.isEmpty()) {
                // CraftBukkit start - Send scaled max health
                if (this.entity instanceof EntityPlayer) {
                    ((EntityPlayer) this.entity).getBukkitEntity().injectScaledMaxHealth(set, false);
                }
                // CraftBukkit end
                this.broadcastAndSend(new PacketPlayOutUpdateAttributes(this.entity.getId(), set));
            }

            set.clear();
        }

    }

    private void broadcastAndSend(Packet<?> packet) {
        this.broadcast.accept(packet);
        if (this.entity instanceof EntityPlayer) {
            ((EntityPlayer) this.entity).connection.send(packet);
        }

    }
}
