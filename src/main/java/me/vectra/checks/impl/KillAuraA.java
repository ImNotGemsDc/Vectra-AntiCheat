package me.vectra.checks.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Player;
import me.vectra.checks.Check;
import me.vectra.data.PlayerData;

public class KillAuraA extends Check {

    public KillAuraA(PlayerData data) {
        super(data, "KillAura", "A", 3);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);

            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                Player player = data.getBukkitPlayer();
                if (player == null) return;

                if (player.isHandRaised() || player.isBlocking()) {
                    flag("AutoBlock/MultiAction");
                    event.setCancelled(true);
                }

                if (player.isDead() || player.isSleeping()) {
                    flag("InvalidState");
                    event.setCancelled(true);
                }
            }
        }
    }
}
