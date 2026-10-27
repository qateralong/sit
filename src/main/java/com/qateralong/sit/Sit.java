package com.qateralong.sit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Sit implements ModInitializer {
    public static final String MOD_ID = "sit";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String SEAT_TAG = "sit.seat";
    private static final double SEAT_HEIGHT = 0.5;
    private static final int RESYNC_TICKS = 20;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("sit.json");
    private static final Set<Entity> SEATS = ConcurrentHashMap.newKeySet();
    private static final Map<ServerPlayer, Vec3> STAND_UP = new ConcurrentHashMap<>();
    private static final Map<ServerPlayer, List<Entity>> CARRIED = new ConcurrentHashMap<>();
    private static Config config = new Config();

    public static class Config {
        public boolean stairs = true;
        public boolean slabs = true;
        public boolean playerHeads = true;
        public boolean carrierShiftDrops = true;
        public double maxDistance = 3.0;
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(s -> loadConfig());
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            SEATS.clear();
            STAND_UP.clear();
            CARRIED.clear();
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !canStart(sp, hand)) {
                return InteractionResult.PASS;
            }
            return sitOnBlock(sp, (ServerLevel) level, hit) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
            if (level.isClientSide() || !config.playerHeads || !(player instanceof ServerPlayer sp) || !(target instanceof Player) || !canStart(sp, hand)) {
                return InteractionResult.PASS;
            }
            return sitOnHead(sp, target) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (isSeat(entity) && !SEATS.contains(entity)) {
                entity.discard();
            }
        });
        ServerPlayerEvents.LEAVE.register(player -> {
            CARRIED.remove(player);
            player.ejectPassengers();
            if (player.isPassenger()) {
                player.stopRiding();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickSeats();
            syncStandUp();
            syncCarriers(server.getPlayerList().getPlayers());
            if (server.getTickCount() % RESYNC_TICKS == 0) {
                resyncCarriers(server.getPlayerList().getPlayers());
            }
            if (config.carrierShiftDrops) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.isShiftKeyDown() && player.getFirstPassenger() instanceof Player) {
                        player.ejectPassengers();
                    }
                }
            }
        });
    }

    private static void loadConfig() {
        try {
            if (Files.exists(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE)) {
                    Config loaded = GSON.fromJson(reader, Config.class);
                    if (loaded != null) {
                        config = loaded;
                    }
                }
            }
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(config, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load sit.json", e);
        }
    }

    private static boolean canStart(ServerPlayer player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND
                && player.getMainHandItem().isEmpty()
                && !(player.getOffhandItem().getItem() instanceof BlockItem)
                && !player.isShiftKeyDown()
                && !player.isPassenger()
                && !player.isSpectator()
                && !player.isSleeping()
                && !player.isFallFlying();
    }

    private static boolean sitOnBlock(ServerPlayer player, ServerLevel level, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        Direction facing = null;
        if (state.getBlock() instanceof StairBlock) {
            if (!config.stairs || state.getValue(StairBlock.HALF) != Half.BOTTOM) {
                return false;
            }
            facing = state.getValue(StairBlock.FACING);
        } else if (state.getBlock() instanceof SlabBlock) {
            if (!config.slabs || state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                return false;
            }
        } else {
            return false;
        }
        BlockPos above = pos.above();
        if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
            return false;
        }
        Vec3 seatPos = new Vec3(pos.getX() + 0.5, pos.getY() + SEAT_HEIGHT, pos.getZ() + 0.5);
        if (player.getEyePosition().distanceTo(seatPos) > config.maxDistance + 1.0) {
            return false;
        }
        for (Entity seat : level.getEntitiesOfClass(Display.ItemDisplay.class, new AABB(pos), Sit::isSeat)) {
            if (seat.isVehicle()) {
                return false;
            }
            seat.discard();
        }
        Display.ItemDisplay seat = EntityType.ITEM_DISPLAY.create(level);
        if (seat == null) {
            return false;
        }
        float yaw = facing != null ? facing.getOpposite().toYRot() : player.getYRot();
        seat.moveTo(seatPos.x, seatPos.y, seatPos.z, yaw, 0.0F);
        seat.addTag(SEAT_TAG);
        seat.setInvulnerable(true);
        SEATS.add(seat);
        if (!level.addFreshEntity(seat)) {
            SEATS.remove(seat);
            return false;
        }
        if (facing != null) {
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setYBodyRot(yaw);
        }
        if (!player.startRiding(seat, true)) {
            SEATS.remove(seat);
            seat.discard();
            return false;
        }
        return true;
    }

    private static boolean sitOnHead(ServerPlayer player, Entity target) {
        if (player.distanceTo(target) > config.maxDistance + 1.0) {
            return false;
        }
        Entity top = target;
        while (top.getFirstPassenger() != null) {
            top = top.getFirstPassenger();
        }
        if (top == player || !(top instanceof Player) || top.isSpectator() || target.getRootVehicle() == player) {
            return false;
        }
        if (!player.startRiding(top, true)) {
            return false;
        }
        if (top instanceof ServerPlayer carrier) {
            carrier.connection.send(new ClientboundSetPassengersPacket(carrier));
        }
        return true;
    }

    private static void tickSeats() {
        List<Entity> dead = new ArrayList<>();
        for (Entity seat : SEATS) {
            if (seat.isRemoved()) {
                dead.add(seat);
                continue;
            }
            if (!seat.isVehicle() || !validSeatBlock(seat)) {
                seat.ejectPassengers();
                seat.discard();
                dead.add(seat);
            }
        }
        dead.forEach(SEATS::remove);
    }

    private static void syncCarriers(List<ServerPlayer> players) {
        for (ServerPlayer carrier : players) {
            List<Entity> passengers = carrier.getPassengers();
            List<Entity> last = CARRIED.getOrDefault(carrier, List.of());
            if (passengers.equals(last)) {
                continue;
            }
            carrier.connection.send(new ClientboundSetPassengersPacket(carrier));
            if (passengers.isEmpty()) {
                CARRIED.remove(carrier);
            } else {
                CARRIED.put(carrier, List.copyOf(passengers));
            }
        }
    }

    private static void resyncCarriers(List<ServerPlayer> players) {
        for (ServerPlayer carrier : players) {
            if (!(carrier.getFirstPassenger() instanceof Player)) {
                continue;
            }
            ClientboundSetPassengersPacket packet = new ClientboundSetPassengersPacket(carrier);
            carrier.serverLevel().getChunkSource().broadcastAndSend(carrier, packet);
            for (Entity passenger : carrier.getPassengers()) {
                if (passenger instanceof ServerPlayer rider) {
                    rider.connection.send(packet);
                }
            }
        }
    }

    private static void syncStandUp() {
        if (STAND_UP.isEmpty()) {
            return;
        }
        STAND_UP.forEach((player, pos) -> {
            if (!player.isRemoved() && !player.isPassenger()) {
                player.connection.teleport(pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
            }
        });
        STAND_UP.clear();
    }

    private static Vec3 remember(LivingEntity passenger, Vec3 pos) {
        if (passenger instanceof ServerPlayer player) {
            STAND_UP.put(player, pos);
        }
        return pos;
    }

    private static boolean validSeatBlock(Entity seat) {
        BlockState state = seat.level().getBlockState(seat.blockPosition());
        if (state.getBlock() instanceof StairBlock) {
            return state.getValue(StairBlock.HALF) == Half.BOTTOM;
        }
        return state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    public static boolean isSeat(Entity entity) {
        return entity instanceof Display.ItemDisplay && entity.getTags().contains(SEAT_TAG);
    }

    public static Vec3 standUpPosition(Entity seat, LivingEntity passenger) {
        BlockPos pos = seat.blockPosition();
        for (BlockPos spot : new BlockPos[]{pos, pos.above()}) {
            Vec3 onTop = DismountHelper.findSafeDismountLocation(passenger.getType(), seat.level(), spot, true);
            if (onTop != null) {
                return remember(passenger, onTop);
            }
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            Vec3 side = DismountHelper.findSafeDismountLocation(passenger.getType(), seat.level(), pos.relative(dir), true);
            if (side != null) {
                return remember(passenger, side);
            }
        }
        return remember(passenger, new Vec3(seat.getX(), pos.getY() + 1.0, seat.getZ()));
    }

    public static Vec3 besidePosition(Entity carrier, LivingEntity passenger) {
        BlockPos pos = carrier.blockPosition();
        Direction first = Direction.fromYRot(passenger.getYRot());
        Direction[] order = {first, first.getClockWise(), first.getCounterClockWise(), first.getOpposite()};
        for (Direction dir : order) {
            Vec3 side = DismountHelper.findSafeDismountLocation(passenger.getType(), carrier.level(), pos.relative(dir), true);
            if (side != null) {
                return remember(passenger, side);
            }
        }
        return new Vec3(carrier.getX(), carrier.getBoundingBox().maxY, carrier.getZ());
    }
}
