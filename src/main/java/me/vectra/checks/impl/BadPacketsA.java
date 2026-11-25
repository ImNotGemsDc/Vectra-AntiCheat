package me.vectra.checks.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import me.vectra.checks.Check;
import me.vectra.data.PlayerData;

public class BadPacketsA extends Check {

    public BadPacketsA(PlayerData data) {
        super(data, "BadPackets", "A", 1);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            if (Math.abs(data.pitch) > 90.0f) {
                flag("Pitch=" + data.pitch);
                event.setCancelled(true);
            }
        }
    }
}
