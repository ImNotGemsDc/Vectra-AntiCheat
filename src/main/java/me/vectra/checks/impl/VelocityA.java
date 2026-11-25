package me.vectra.checks.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.bukkit.entity.Player;
import me.vectra.checks.Check;
import me.vectra.data.PlayerData;

public class VelocityA extends Check {

    public VelocityA(PlayerData data) {
        super(data, "Velocity", "A", 5);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) return;

        Player player = data.getBukkitPlayer();
        if (player == null) return;

        if (data.ticksSinceVelocity >= 1 && data.ticksSinceVelocity <= 3 && data.velocityY > 0.1) {
            if (player.getLocation().add(0, 2, 0).getBlock().getType().isSolid()) {
                data.velocityY = 0;
                return;
            }

            if (data.deltaY > 0.0) {
                data.velocityY = 0;
                reward();
                return;
            }

            if (data.ticksSinceVelocity == 3 && data.deltaY == 0.0 && data.onGround) {
                flag("KB=0%");
                data.velocityY = 0;
            }
        }
    }
}
