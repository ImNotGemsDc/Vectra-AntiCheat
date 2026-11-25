package me.vectra.checks;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import me.vectra.Vectra;
import me.vectra.data.PlayerData;

public abstract class Check {

    protected final PlayerData data;
    private final String name;
    private final String type;
    private int vl = 0;
    private final int maxVl;

    public Check(PlayerData data, String name, String type, int maxVl) {
        this.data = data;
        this.name = name;
        this.type = type;
        this.maxVl = maxVl;
    }

    public void onPacketReceive(PacketReceiveEvent event) {}
    public void onPacketSend(PacketSendEvent event) {}

    protected void flag(String debugInfo) {
        vl++;
        Player player = data.getBukkitPlayer();
        if (player == null) return;

        String version = data.getClientVersion().getReleaseName();
        String alert = String.format("§8[§c§lVectra§8] §f%s §7(§e%s§7) §ffalló §c%s (%s) §8[§7%s§8] §c(x%d)", 
                player.getName(), version, name, type, debugInfo, vl);

        Bukkit.getOnlinePlayers().stream()
                .filter(p -> Vectra.getInstance().hasAlerts(p.getUniqueId()))
                .forEach(p -> p.sendMessage(alert));

        if (vl >= maxVl) {
            Bukkit.getScheduler().runTask(Vectra.getInstance(), () -> {
                player.kickPlayer("§c§lVectra AntiCheat\n\n§7Modificación detectada: §f" + name + " " + type);
            });
            vl = 0;
        }
    }

    protected void reward() {
        if (vl > 0) vl--;
    }
}
