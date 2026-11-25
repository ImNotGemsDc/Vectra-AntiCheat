package me.vectra;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class Vectra extends JavaPlugin implements Listener, CommandExecutor {

    private final Set<UUID> staffAlerts = new HashSet<>();
    private final Map<UUID, Integer> vlMap = new HashMap<>();
    private final Map<UUID, Integer> airTicks = new HashMap<>();
    private final Map<UUID, Double> lastDeltaY = new HashMap<>();
    private final Map<UUID, Long> lastVelocity = new HashMap<>();
    private final Map<UUID, Long> lastFirework = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("vectra") != null) getCommand("vectra").setExecutor(this);
        getLogger().info("Vectra 2.0 (All-In-One) activado correctamente.");
    }

    // ================= 1. REACH, HITBOX Y KILLAURA =================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) return;

        Entity target = event.getEntity();

        // KILLAURA: Golpear mientras usa escudo o come
        if (attacker.isBlocking() || attacker.isHandRaised()) {
            flag(attacker, "KillAura (AutoBlock)", "Golpeando con item en uso");
            event.setCancelled(true);
            return;
        }

        Vector eye = attacker.getEyeLocation().toVector();
        Vector dir = attacker.getEyeLocation().getDirection();

        // HITBOX: Expandimos 0.25 por el ping y lanzamos RayTrace (Láser de mira)
        BoundingBox box = target.getBoundingBox().expand(0.25);
        RayTraceResult ray = box.rayTrace(eye, dir, 6.5);

        if (ray == null) {
            flag(attacker, "Hitbox / KillAura", "No mira al objetivo");
            event.setCancelled(true);
            return;
        }

        // REACH: Distancia exacta desde los ojos hasta la caja real del enemigo
        BoundingBox realBox = target.getBoundingBox().expand(0.1);
        double dist = getMinDistance(eye, realBox);
        if (dist > 3.10) {
            flag(attacker, "Reach", String.format("Dist=%.2f bloques", dist));
            event.setCancelled(true);
        }
    }

    // ================= 2. AIRPLACE (Poner bloques en el aire) =================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;

        Block against = event.getBlockAgainst();
        Block placed = event.getBlockPlaced();

        // Si el bloque de apoyo es aire/agua o todas las caras alrededor son aire
        if (against.isEmpty() || against.isLiquid() || hasNoSolidNeighbors(placed)) {
            flag(p, "AirPlace", "Bloque puesto en el aire");
            event.setCancelled(true);
        }
    }

    // ================= 3. FLY, SPEED, ELYTRA BOOST Y NOFALL =================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) return;

        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR
                || p.getAllowFlight() || p.isFlying() || p.isInsideVehicle()) return;

        UUID uuid = p.getUniqueId();
        double deltaY = to.getY() - from.getY();
        double deltaXZ = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        boolean recentlyHit = System.currentTimeMillis() - lastVelocity.getOrDefault(uuid, 0L) < 1200;

        // --- CHEQUEO ELYTRA BOOST / ELYTRA FLY ---
        if (p.isGliding()) {
            boolean usedFirework = System.currentTimeMillis() - lastFirework.getOrDefault(uuid, 0L) < 3500;
            if (!usedFirework && !recentlyHit) {
                if (deltaXZ > 1.65) {
                    flag(p, "ElytraBoost", String.format("Speed=%.2f sin cohete", deltaXZ));
                    event.setTo(from);
                } else if (deltaY > 0.05 && to.getPitch() > -15) {
                    flag(p, "ElytraFly", String.format("Subiendo DY=%.2f", deltaY));
                    event.setTo(from);
                }
            }
            return;
        }

        if (p.isSwimming() || p.isClimbing() || p.isInWater() || p.isInLava()
                || p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            airTicks.put(uuid, 0);
            return;
        }

        // --- CHEQUEO SPEED ---
        double maxSpeed = 0.68;
        if (p.hasPotionEffect(PotionEffectType.SPEED)) {
            int amp = p.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            maxSpeed += amp * 0.15;
        }
        if (deltaXZ > maxSpeed && !recentlyHit) {
            flag(p, "Speed", String.format("Vel=%.2f > %.2f", deltaXZ, maxSpeed));
            event.setTo(from);
        }

        // --- CHEQUEO FLY (ASCEND, HOVER, GLIDE) ---
        boolean inAir = isTrulyInAir(to);
        if (inAir) {
            int ticks = airTicks.getOrDefault(uuid, 0) + 1;
            airTicks.put(uuid, ticks);
            double prevDY = lastDeltaY.getOrDefault(uuid, 0.0);

            if (ticks > 9 && !recentlyHit) {
                if (deltaY >= 0.0) {
                    flag(p, "Fly (Hover/Ascend)", String.format("DY=%.3f en aire", deltaY));
                    event.setTo(from);
                } else if (deltaY > prevDY - 0.01 && deltaY > -0.1) {
                    flag(p, "Fly (Glide)", String.format("Caida lenta DY=%.3f", deltaY));
                }
            }
            lastDeltaY.put(uuid, deltaY);
        } else {
            airTicks.put(uuid, 0);
            lastDeltaY.put(uuid, 0.0);
        }
    }

    // Registrar uso de cohetes para ElytraBoost legítimo
    @EventHandler
    public void onFirework(PlayerInteractEvent event) {
        if (event.getItem() != null && event.getItem().getType() == Material.FIREWORK_ROCKET) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                lastFirework.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
            }
        }
    }

    // ================= 4. VELOCITY (ANTI-KNOCKBACK) =================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Player p = event.getPlayer();
        lastVelocity.put(p.getUniqueId(), System.currentTimeMillis());

        if (p.getGameMode() == GameMode.CREATIVE || p.isInWater() || p.isInLava() || p.isInsideVehicle()) return;
        if (event.getVelocity().getY() < 0.15) return;

        Location hitLoc = p.getLocation().clone();
        // Revisamos 4 ticks después si el jugador ignoró el empuje por completo
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || p.isDead()) return;
            // Si no tiene techo encima y no se movió ni 0.01 bloques
            if (hitLoc.clone().add(0, 2, 0).getBlock().isEmpty()) {
                double moved = p.getLocation().distance(hitLoc);
                if (moved < 0.01) {
                    flag(p, "Velocity (Anti-KB)", "0% Knockback recibido");
                }
            }
        }, 4L);
    }

    // ================= UTILIDADES Y ALERTAS =================
    private void flag(Player cheater, String check, String info) {
        int vl = vlMap.getOrDefault(cheater.getUniqueId(), 0) + 1;
        vlMap.put(cheater.getUniqueId(), vl);

        String alert = String.format("§8[§c§lVectra§8] §f%s §7falló §c%s §8[§7%s§8] §c(x%d)",
                cheater.getName(), check, info, vl);

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.isOp() || staffAlerts.contains(staff.getUniqueId())) {
                staff.sendMessage(alert);
            }
        }

        if (vl >= 15) {
            vlMap.put(cheater.getUniqueId(), 0);
            cheater.kickPlayer("§c§lVectra AntiCheat\n\n§7Modificación ilegal detectada: §f" + check);
        }
    }

    private boolean isTrulyInAir(Location loc) {
        for (double x = -0.35; x <= 0.35; x += 0.35) {
            for (double z = -0.35; z <= 0.35; z += 0.35) {
                Block b1 = loc.clone().add(x, -0.2, z).getBlock();
                Block b2 = loc.clone().add(x, -0.6, z).getBlock();
                if (!b1.isEmpty() || !b2.isEmpty()) return false;
            }
        }
        return true;
    }

    private boolean hasNoSolidNeighbors(Block block) {
        BlockFace[] faces = {BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : faces) {
            Block rel = block.getRelative(face);
            if (!rel.isEmpty() && !rel.isLiquid()) return false;
        }
        return true;
    }

    private double getMinDistance(Vector point, BoundingBox box) {
        double dx = Math.max(box.getMinX() - point.getX(), Math.max(0, point.getX() 
