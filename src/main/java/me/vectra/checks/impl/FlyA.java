package me.vectra.checks.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import me.vectra.checks.Check;
import me.vectra.data.PlayerData;

public class FlyA extends Check {

    public FlyA(PlayerData data) {
        super(data, "Fly", "A", 10);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) return;
        
        WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
        if (!flying.hasPositionChanged()) return;

        Player player = data.getBukkitPlayer();
        if (player == null) return;

        if (isExempt(player)) {
            data.airTicks = 0;
            return;
        }

        if (data.airTicks > 6) {
            double predictedY = (data.lastDeltaY - 0.08D) * 0.9800000190734863D;
            if (Math.abs(predictedY) < 0.003D) predictedY = 0.0D;

            double difference = Math.abs(data.deltaY - predictedY);

            if (difference > 0.001D) {
                flag(String.format("Diff=%.4f", difference));
            } else {
                reward();
            }
        }
    }

    private boolean isExempt(Player player) {
        if (data.ticksSinceVelocity < 15) return true;
        return player.getAllowFlight() || player.isFlying()
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.isInsideVehicle() || player.isGliding()
                || player.isSwimming() || player.isClimbing()
                || player.isInWater() || player.isInLava()
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }
}
