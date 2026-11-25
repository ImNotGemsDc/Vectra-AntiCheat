package me.vectra.commands;

import me.vectra.Vectra;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class VectraCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§cNo tienes permiso para usar esto.");
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("alerts")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("§cJugador no encontrado o desconectado.");
                return true;
            }

            boolean enabled = Vectra.getInstance().toggleAlerts(target.getUniqueId());
            if (enabled) {
                sender.sendMessage("§8[§c§lVectra§8] §aLe activaste las alertas a §f" + target.getName());
                target.sendMessage("§8[§c§lVectra§8] §aAhora recibes alertas del AntiCheat.");
            } else {
                sender.sendMessage("§8[§c§lVectra§8] §cLe desactivaste las alertas a §f" + target.getName());
                target.sendMessage("§8[§c§lVectra§8] §cYa no recibes alertas del AntiCheat.");
            }
            return true;
        }

        sender.sendMessage("§8[§c§lVectra§8] §7Usa: §f/vectra alerts <jugador>");
        return true;
    }
}
