package me.vectra.listeners;

import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import org.bukkit.entity.Player;
import me.vectra.checks.Check;
import me.vectra.data.PlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkListener extends PacketListenerAbstract {

    private final Map<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();

    public NetworkListener() {
        super(PacketListenerPriority.HIGH);
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        if (event.getUser().getUUID() != null) {
            playerDataMap.remove(event.getUser().getUUID());
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPlayer() == null || event.getUser().getUUID() == null) return;

        UUID uuid = event.getUser().getUUID();
        PlayerData data = playerDataMap.computeIfAbsent(uuid, k -> new PlayerData(uuid, event.getUser()));

        if (data.getBukkitPlayer() == null) {
            data.setBukkitPlayer((Player) event.getPlayer());
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);

            data.lastOnGround = data.onGround;
            data.onGround = flying.isOnGround();
            data.ticksSinceVelocity++;

            if (flying.hasPositionChanged()) {
                data.lastX = data.x;
                data.lastY = data.y;
                data.lastZ = data.z;
                
                data.x = flying.getLocation().getX();
                data.y = flying.getLocation().getY();
                data.z = flying.getLocation().getZ();

                data.lastDeltaY = data.deltaY;
                data.lastDeltaXZ = data.deltaXZ;

                data.deltaX = data.x - data.lastX;
                data.deltaY = data.y - data.lastY;
                data.deltaZ = data.z - data.lastZ;
                data.deltaXZ = Math.hypot(data.deltaX, data.deltaZ);

                if (!data.onGround) data.airTicks++;
                else data.airTicks = 0;
            }

            if (flying.hasRotationChanged()) {
                data.lastYaw = data.yaw;
                data.lastPitch = data.pitch;
                data.yaw = flying.getLocation().getYaw();
                data.pitch = flying.getLocation().getPitch();
            }
        }

        for (Check check : data.getChecks()) {
            check.onPacketReceive(event);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPlayer() == null || event.getUser().getUUID() == null) return;

        PlayerData data = playerDataMap.get(event.getUser().getUUID());
        if (data == null || data.getBukkitPlayer() == null) return;

        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity vel = new WrapperPlayServerEntityVelocity(event);
            if (vel.getEntityId() == data.getBukkitPlayer().getEntityId()) {
                data.velocityX = vel.getVelocity().getX();
                data.velocityY = vel.getVelocity().getY();
                data.velocityZ = vel.getVelocity().getZ();
                data.ticksSinceVelocity = 0;
            }
        }

        for (Check check : data.getChecks()) {
            check.onPacketSend(event);
        }
    }
}
