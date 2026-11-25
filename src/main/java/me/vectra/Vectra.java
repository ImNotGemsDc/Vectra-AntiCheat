package me.vectra;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.plugin.java.JavaPlugin;
import me.vectra.listeners.NetworkListener;

public class Vectra extends JavaPlugin {

    private static Vectra instance;

    @Override
    public void onEnable() {
        instance = this;
        PacketEvents.getAPI().getEventManager().registerListener(new NetworkListener());
        getLogger().info("Vectra AntiCheat activado correctamente.");
    }

    public static Vectra getInstance() {
        return instance;
    }
}
