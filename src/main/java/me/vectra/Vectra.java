package me.vectra;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.plugin.java.JavaPlugin;
import me.vectra.commands.VectraCommand;
import me.vectra.listeners.NetworkListener;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Vectra extends JavaPlugin {

    private static Vectra instance;
    private final Set<UUID> alertsEnabled = new HashSet<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        
        // Cargar jugadores con alertas desde la config
        for (String uuidStr : getConfig().getStringList("staff-alerts")) {
            alertsEnabled.add(UUID.fromString(uuidStr));
        }

        getCommand("vectra").setExecutor(new VectraCommand());
        PacketEvents.getAPI().getEventManager().registerListener(new NetworkListener());
    }

    public boolean hasAlerts(UUID uuid) {
        return alertsEnabled.contains(uuid);
    }

    public boolean toggleAlerts(UUID uuid) {
        List<String> list = getConfig().getStringList("staff-alerts");
        boolean added;
        if (alertsEnabled.contains(uuid)) {
            alertsEnabled.remove(uuid);
            list.remove(uuid.toString());
            added = false;
        } else {
            alertsEnabled.add(uuid);
            list.add(uuid.toString());
            added = true;
        }
        getConfig().set("staff-alerts", list);
        saveConfig();
        return added;
    }

    public static Vectra getInstance() { return instance; }
}
