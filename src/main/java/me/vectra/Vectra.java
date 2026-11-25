package me.vectra;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class Vectra extends JavaPlugin implements Listener, CommandExecutor {

    private static Vectra instance;
    private final Set<UUID> staffAlerts = new HashSet<>();
    private final Map<UUID, Integer> vlMap = new HashMap<>();
    private final Map<UUID, Integer> airTicks = new HashMap<>();
    private final Map<UUID, Integer> vehicleAirTicks = new HashMap<>();
    private final Map<UUID, Integer> waterTicks = new HashMap<>();
    private final Map<UUID, Integer> noSlowTicks = new HashMap<>();
    private final Map<UUID, Double> lastDeltaY = new HashMap<>();
    private final Map<UUID, Double> lastDeltaXZ = new HashMap<>();
    private final Map<UUID, Double> realFallDist = new HashMap<>();
    private final Map<UUID, Vector> lastMoveDir = new HashMap<>();
    private final Map<UUID, Long> lastVelocity = new HashMap<>();
    private final Map<UUID, Long> lastTeleport = new HashMap<>();
    private final Map<UUID, Long> lastFirework = new HashMap<>();
    private final Map<UUID, Long> lastBlockPlace = new HashMap<>();
    private final Map<UUID, Long> lastBlockBreak = new HashMap<>();
    private final Map<UUID, Long> lastSwing = new HashMap<>();
    private final Map<UUID, Long> lastAttackTime = new HashMap<>();
    private final Map<UUID, UUID> lastTarget = new HashMap<>();
    private final Map<UUID, Long> itemUseStart = new HashMap<>();
    private final Map<UUID, Long> lastInvClick = new HashMap<>();
    private final Map<UUID, Integer> fastClickStreak = new HashMap<>();
    private final Map<UUID, Long> lastGlideToggle = new HashMap<>();
    private final Map<UUID, List<Long>> clicks = new HashMap<>();
    private final Map<UUID, List<Long>> movePackets = new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("vectra") != null) getCommand("vectra").setExecutor(this);
    }

    public static Vectra getInstance() { return instance; }
    public boolean hasAlerts(UUID u) { return staffAlerts.contains(u); }
    public boolean toggleAlerts(UUID u) {
        if (staffAlerts.remove(u)) return false;
        staffAlerts.add(u);
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        vlMap.remove(u); airTicks.remove(u); vehicleAirTicks.remove(u);
        waterTicks.remove(u); noSlowTicks.remove(u); lastDeltaY.remove(u);
        lastDeltaXZ.remove(u); realFallDist.remove(u); lastMoveDir.remove(u);
        lastVelocity.remove(u); lastTeleport.remove(u); lastFirework.remove(u);
        lastBlockPlace.remove(u); lastBlockBreak.remove(u); lastSwing.remove(u);
        lastAttackTime.remove(u); lastTarget.remove(u); itemUseStart.remove(u);
        lastInvClick.remove(u); fastClickStreak.remove(u); lastGlideToggle.remove(u);
        clicks.remove(u); movePackets.remove(u);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        lastTeleport.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        airTicks.put(e.getPlayer().getUniqueId(), 0);
        realFallDist.put(e.getPlayer().getUniqueId(), 0.0);
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            lastSwing.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (isExemptMode(attacker)) return;

        UUID u = attacker.getUniqueId();
        Entity target = event.getEntity();
        long now = System.currentTimeMillis();

        checkCPS(attacker);

        if (now - lastSwing.getOrDefault(u, 0L) > 550) {
            flag(attacker, "NoSwing / KillAura", "Ataque sin animacion");
            event.setCancelled(true);
            return;
        }

        UUID prevTarget = lastTarget.get(u);
        if (prevTarget != null && !prevTarget.equals(target.getUniqueId()) && now - lastAttackTime.getOrDefault(u, 0L) < 45) {
            flag(attacker, "MultiAura", "2 objetivos en " + (now - lastAttackTime.get(u)) + "ms");
            event.setCancelled(true);
            return;
        }
        lastTarget.put(u, target.getUniqueId());
        lastAttackTime.put(u, now);

        if (attacker.isBlocking() || attacker.isHandRaised() || attacker.isDead() || attacker.isSleeping()) {
            flag(attacker, "KillAura (AutoBlock)", "Estado invalido");
            event.setCancelled(true);
            return;
        }

        double trackedFall = realFallDist.getOrDefault(u, 0.0);
        if (attacker.getFallDistance() > 
