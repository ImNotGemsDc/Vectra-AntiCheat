package me.vectra;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class Vectra extends JavaPlugin implements Listener, CommandExecutor {

    private static class AirState {
        double groundY, peakY;
        long peakTime, last;
        int vio;
    }

    private final Set<UUID> staffAlerts = new HashSet<>();
    private final Map<UUID, Integer> vlMap = new HashMap<>();
    private final Map<UUID, Map<String, Double>> buffers = new HashMap<>();
    private final Map<UUID, Long> lastFlagTime = new HashMap<>();
    private final Map<UUID, Location> lastGround = new HashMap<>();
    private final Map<UUID, AirState> airStates = new HashMap<>();
    private final Map<UUID, Double> hoverY = new HashMap<>();
    private final Map<UUID, Integer> hoverCount = new HashMap<>();
    private final Map<UUID, Integer> airTicks = new HashMap<>();
    private final Map<UUID, Integer> vehicleAirTicks = new HashMap<>();
    private final Map<UUID, Integer> waterTicks = new HashMap<>();
    private final Map<UUID, Integer> noSlowTicks = new HashMap<>();
    private final Map<UUID, Double> lastDeltaY = new HashMap<>();
    private final Map<UUID, Double> lastDeltaXZ = new HashMap<>();
    private final Map<UUID, Double> realFallDist = new HashMap<>();
    private final Map<UUID, Long> lastFallDamage = new HashMap<>();
    private final Map<UUID, Long> lastVelocity = new HashMap<>();
    private final Map<UUID, Long> lastTeleport = new HashMap<>();
    private final Map<UUID, Long> lastFirework = new HashMap<>();
    private final Map<UUID, Long> lastGlideEnd = new HashMap<>();
    private final Map<UUID, Long> itemUseStart = new HashMap<>();
    private final Map<UUID, Long> lastInvClick = new HashMap<>();
    private final Map<UUID, Integer> lastInvSlot = new HashMap<>();
    private final Map<UUID, Integer> fastClickStreak = new HashMap<>();
    private final Map<UUID, List<Long>> clicks = new HashMap<>();
    private final Map<UUID, List<Long>> places = new HashMap<>();
    private final Map<UUID, List<Long>> movePackets = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("vectra") != null) getCommand("vectra").setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID u = p.getUniqueId();
                int vl = vlMap.getOrDefault(u, 0);
                if (vl > 0 && now - lastFlagTime.getOrDefault(u, 0L) >= 15000) {
                    vlMap.put(u, vl - 1);
                    lastFlagTime.put(u, now);
                }
            }
        }, 100L, 100L);

        Bukkit.getScheduler().runTaskTimer(this, this::hoverCheck, 5L, 5L);
    }

    private void hoverCheck() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (isExemptMode(p) || p.isInsideVehicle() || p.isGliding() || p.isSwimming() || p.isClimbing()
                    || p.isInWater() || p.isInLava() || isBoosted(p, u, now)) {
                hoverCount.remove(u);
                hoverY.remove(u);
                continue;
            }
            Location loc = p.getLocation();
            if (isTrulyInAir(loc)) {
                double y = loc.getY();
                Double prev = hoverY.put(u, y);
                if (prev != null && Math.abs(y - prev) < 0.002) {
                    int c = hoverCount.getOrDefault(u, 0) + 1;
                    hoverCount.put(u, c);
                    if (c >= 5) {
                        flag(p, "Fly (Hover)", String.format("Y=%.2f", y));
                        p.teleport(back(u, loc));
                        hoverCount.put(u, 0);
                    }
                } else {
                    hoverCount.put(u, 0);
                }
            } else {
                hoverCount.remove(u);
                hoverY.remove(u);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        staffAlerts.remove(u); vlMap.remove(u); buffers.remove(u);
        lastFlagTime.remove(u); lastGround.remove(u); airStates.remove(u);
        hoverY.remove(u); hoverCount.remove(u); airTicks.remove(u);
        vehicleAirTicks.remove(u); waterTicks.remove(u); noSlowTicks.remove(u);
        lastDeltaY.remove(u); lastDeltaXZ.remove(u); realFallDist.remove(u);
        lastFallDamage.remove(u); lastVelocity.remove(u); lastTeleport.remove(u);
        lastFirework.remove(u); lastGlideEnd.remove(u); itemUseStart.remove(u);
        lastInvClick.remove(u); lastInvSlot.remove(u); fastClickStreak.remove(u);
        clicks.remove(u); places.remove(u); movePackets.remove(u);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        lastTeleport.put(u, System.currentTimeMillis());
        airTicks.put(u, 0);
        realFallDist.put(u, 0.0);
        airStates.remove(u);
    }

    @EventHandler
    public void onRiptide(PlayerRiptideEvent e) {
        lastVelocity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFallDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.FALL && !e.isCancelled()) {
            lastFallDamage.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker) || isExemptMode(attacker)) return;

        UUID u = attacker.getUniqueId();
        Entity target = event.getEntity();

        checkCPS(attacker);

        if (attacker.isDead() || attacker.isSleeping()) {
            event.setCancelled(true);
            return;
        }

        if (attacker.isBlocking()) {
            if (addBuffer(u, "autoblock", 1.0, 2.0)) {
                flag(attacker, "KillAura (AutoBlock)", "Golpe con escudo activo");
                event.setCancelled(true);
                return;
            }
        } else {
            decayBuffer(u, "autoblock", 0.5);
        }

        double trackedFall = realFallDist.getOrDefault(u, 0.0);
        if (attacker.getFallDistance() > trackedFall + 3.0 && event.getDamage() > 18.0) {
            flag(attacker, "MaceSpoof", String.format("Fall=%.1f Real=%.1f", attacker.getFallDistance(), trackedFall));
            event.setCancelled(true);
            return;
        }

        double dy = lastDeltaY.getOrDefault(u, 0.0);
        if (attacker.getFallDistance() > 0.0f && !attacker.isOnGround() && trackedFall == 0.0 && Math.abs(dy) < 0.001
                && !attacker.isInWater() && !attacker.isClimbing() && !attacker.isInsideVehicle()) {
            if (addBuffer(u, "criticals", 1.0, 2.0)) {
                flag(attacker, "Criticals", "Packet Crit");
                event.setCancelled(true);
                return;
            }
        } else {
            decayBuffer(u, "criticals", 0.5);
        }

        Location eyeLoc = attacker.getEyeLocation();
        Vector eye = eyeLoc.toVector();
        Vector dir = eyeLoc.getDirection();

        int ping = Math.min(attacker.getPing(), 350);
        double targetSpeed = target.getVelocity().length();
        double comp = (ping * 0.0025) + (targetSpeed * 0.6);

        BoundingBox hitBox = target.getBoundingBox().expand(0.42 + comp);
        if (!hitBox.contains(eye)) {
            RayTraceResult ray = hitBox.rayTrace(eye, dir, 6.5);
            if (ray == null) {
                if (addBuffer(u, "hitbox", 1.0, 2.5)) {
                    flag(attacker, "Hitbox / KillAura", "Fuera de mira");
                    event.setCancelled(true);
                    return;
                }
            } else {
                decayBuffer(u, "hitbox", 0.4);
            }
        }

        double dist = getMinDistance(eye, target.getBoundingBox().expand(0.15));
        double maxReach = (attacker.isSprinting() ? 3.22 : 3.15) + comp;
        if (dist > maxReach) {
            if (addBuffer(u, "reach", 1.0, 2.0)) {
                flag(attacker, "Reach", String.format("Dist=%.2f", dist));
                event.setCancelled(true);
            }
        } else {
            decayBuffer(u, "reach", 0.3);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player p) || isExemptMode(p)) return;
        if (event.getBow() == null || event.getBow().getType() != Material.BOW) return;
        long start = itemUseStart.getOrDefault(p.getUniqueId(), 0L);
        if (start > 0) {
            long pullTime = System.currentTimeMillis() - start;
            if (event.getForce() > 0.90 && pullTime < 550) {
                flag(p, "FastBow", "Time=" + pullTime + "ms");
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlideToggle(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (!event.isGliding()) {
            lastGlideEnd.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (isExemptMode(p)) return;

        UUID u = p.getUniqueId();
        Block against = event.getBlockAgainst();
        Block placed = event.getBlockPlaced();
        long now = System.currentTimeMillis();

        if (against.isEmpty() && hasNoSolidNeighbors(placed)) {
            flag(p, "AirPlace", "Bloque en el aire");
            event.setCancelled(true);
            return;
        }

        if (p.getEyeLocation().distance(placed.getLocation().add(0.5, 0.5, 0.5)) > 5.45) {
            flag(p, "BlockReach", "Distancia excesiva");
            event.setCancelled(true);
            return;
        }

        List<Long> placeList = places.computeIfAbsent(u, k -> new ArrayList<>());
        placeList.add(now);
        placeList.removeIf(t -> now - t > 1000);
        if (placeList.size() > 16 && !placed.getType().name().contains("SCAFFOLD")) {
            flag(p, "FastPlace", "BPS=" + placeList.size());
            event.setCancelled(true);
            return;
        }

        Location pLoc = p.getLocation();
        if (placed.getY() == pLoc.getBlockY() - 1 && placed.getX() == pLoc.getBlockX() && placed.getZ() == pLoc.getBlockZ()
                && pLoc.getPitch() < 0.0f && placed.getRelative(BlockFace.DOWN).isEmpty()) {
            if (addBuffer(u, "scaffold", 1.0, 2.0)) {
                flag(p, "Scaffold", "Mirando arriba (Pitch=" + (int) pLoc.getPitch() + ")");
                event.setCancelled(true);
            }
        } else {
            decayBuffer(u, "scaffold", 0.25);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (isExemptMode(p)) return;

        Block b = event.getBlock();
        if (p.getEyeLocation().distance(b.getLocation().add(0.5, 0.5, 0.5)) > 5.55) {
            flag(p, "BlockReach", "Romper lejos");
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || isExemptMode(p)) return;

        UUID u = p.getUniqueId();

        if (Double.isNaN(to.getX()) || Double.isNaN(to.getY()) || Double.isNaN(to.getZ())
                || Double.isInfinite(to.getY()) || Math.abs(to.getX()) > 29999999 || Math.abs(to.getZ()) > 29999999) {
            flag(p, "Crasher", "Coordenadas invalidas");
            event.setCancelled(true);
            return;
        }

        if (Math.abs(to.getPitch()) > 90.01f) {
            flag(p, "BadPackets", "Pitch=" + to.getPitch());
            event.setTo(back(u, from));
            return;
        }

        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        long now = System.currentTimeMillis();

        List<Long> packets = movePackets.computeIfAbsent(u, k -> new ArrayList<>());
        if (!packets.isEmpty() && now - packets.get(packets.size() - 1) > 250) {
            packets.clear();
        }
        packets.add(now);
        packets.removeIf(t -> now - t > 1000);
        if (packets.size() > 29 && now - lastTeleport.getOrDefault(u, 0L) > 2000) {
            if (addBuffer(u, "timer", 1.0, 3.0)) {
                flag(p, "Timer", "PPS=" + packets.size());
                event.setTo(back(u, from));
                return;
            }
        } else {
            decayBuffer(u, "timer", 0.25);
        }

        double deltaY = to.getY() - from.getY();
        double deltaXZ = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        boolean boosted = isBoosted(p, u, now);

        if (p.isInsideVehicle()) {
            realFallDist.put(u, 0.0);
            airStates.remove(u);
            Entity v = p.getVehicle();
            if (v != null && !(v instanceof LivingEntity) && !v.isInWater() && isTrulyInAir(v.getLocation())) {
                if (deltaY > 0.12 && !boosted) {
                    int vt = vehicleAirTicks.getOrDefault(u, 0) + 1;
                    vehicleAirTicks.put(u, vt);
                    if (vt > 6) {
                        flag(p, "BoatFly", String.format("DY=%.2f", deltaY));
                        v.teleport(back(u, from));
                    }
                } else {
                    vehicleAirTicks.put(u, 0);
                }
            } else {
                vehicleAirTicks.put(u, 0);
            }
            return;
        }

        if (p.isGliding()) {
            realFallDist.put(u, 0.0);
            airStates.remove(u);
            boolean usedFirework = now - lastFirework.getOrDefault(u, 0L) < 4000;
            if (!usedFirework && !boosted) {
                if (deltaXZ > 1.75) {
                    if (addBuffer(u, "elytra", 1.0, 3.0)) {
                        flag(p, "ElytraBoost", String.format("XZ=%.2f", deltaXZ));
                        event.setTo(back(u, from));
                    }
                } else if (deltaY > 0.08 && to.getPitch() > -10) {
                    if (addBuffer(u, "elytra", 1.0, 3.0)) {
                        flag(p, "ElytraFly", String.format("DY=%.2f", deltaY));
                        event.setTo(back(u, from));
                    }
                } else {
                    decayBuffer(u, "elytra", 0.25);
                }
            }
            return;
        }

        if (isInSlowBlock(to) && deltaXZ > 0.18 && !boosted) {
            if (addBuffer(u, "noweb", 1.0, 3.0)) {
                flag(p, "NoWeb", String.format("XZ=%.2f", deltaXZ));
                event.setTo(back(u, from));
                return;
            }
        } else {
            decayBuffer(u, "noweb", 0.5);
        }

        if ((p.isHandRaised() || p.isBlocking()) && !boosted) {
            if (deltaXZ > 0.22) {
                int ns = noSlowTicks.getOrDefault(u, 0) + 1;
                noSlowTicks.put(u, ns);
                if (ns > 7) {
                    flag(p, "NoSlow", String.format("XZ=%.2f", deltaXZ));
                    event.setTo(back(u, from));
                }
            } else {
                noSlowTicks.put(u, 0);
            }
        } else {
            noSlowTicks.put(u, 0);
        }

        if (isOverLiquid(to) && !p.isInWater() && !p.isSwimming() && !isNearLilyOrBoat(to)) {
            if (Math.abs(deltaY) < 0.004 && deltaXZ > 0.08) {
                int wt = waterTicks.getOrDefault(u, 0) + 1;
                waterTicks.put(u, wt);
                if (wt > 6) {
                    flag(p, "Jesus", "Caminar en agua");
                    event.setTo(back(u, from));
                }
            }
        } else {
            waterTicks.put(u, 0);
        }

        if (p.isInWater() || p.isSwimming() || p.isClimbing() || p.isInLava()
                || p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            realFallDist.put(u, 0.0);
            airTicks.put(u, 0);
            airStates.remove(u);
            return;
        }

        boolean inAir = isTrulyInAir(to);
        int jumpBoost = getPotionLevel(p, PotionEffectType.JUMP_BOOST);

        // --- FLY (AirState: Altura y Gravedad) ---
        AirState st = airStates.computeIfAbsent(u, k -> new AirState());
        if (boosted) {
            st.last = 0;
        } else if (!inAir) {
            st.groundY = to.getY();
            st.peakY = to.getY();
            st.peakTime = now;
            st.last = now;
            st.vio = 0;
        } else {
            if (st.last == 0) {
                st.groundY = from.getY();
                st.peakY = Math.max(from.getY(), to.getY());
                st.peakTime = now;
                st.last = now;
                st.vio = 0;
            } else {
                long gap = now - st.last;
                if (gap > 250) {
                    st.groundY = from.getY();
                    st.peakY = to.getY();
                    st.peakTime = now;
                    st.vio = 0;
                } else if (gap >= 120) {
                    st.peakTime += gap - 50;
                }
                st.last = now;

                if (to.getY() > st.peakY) {
                    st.peakY = to.getY();
                    st.peakTime = now;
                }

                double maxGain = 1.35 + jumpBoost * 0.75;
                if (to.getY() - st.groundY > maxGain) {
                    if (addBuffer(u, "fly_height", 1.0, 2.0)) {
                        flag(p, "Fly (Altura)", String.format("Gain=%.2f", to.getY() - st.groundY));
                        event.setTo(back(u, from));
                        return;
                    }
                } else {
                    decayBuffer(u, "fly_height", 0.25);
                }

                double n = (now - st.peakTime) / 50.0;
                if (n >= 8.0) {
                    double expected = 3.92 * (n - 49.0 * (1.0 - Math.pow(0.98, n)));
                    double drop = st.peakY - to.getY();
                    if (drop < 0.35 * expected) {
                        st.vio++;
                        if (st.vio >= 3) {
                            flag(p, "Fly (Hover/Slow)", String.format("Drop=%.2f Exp=%.2f", drop, expected));
                            event.setTo(back(u, from));
                            st.vio = 0;
                            return;
                        }
                    } else {
                        st.vio = Math.max(0, st.vio - 1);
                    }
                }
            }
        }

        double maxJump = 0.62 + jumpBoost * 0.16;
        if (deltaY > maxJump && !boosted) {
            if (addBuffer(u, "step", 1.0, 2.0)) {
                flag(p, "Step / HighJump", String.format("DY=%.2f", deltaY));
                event.setTo(back(u, from));
                return;
            }
        } else {
            decayBuffer(u, "step", 0.25);
        }

        double walkRatio = Math.max(1.0, p.getWalkSpeed() / 0.2f);
        double maxSpeed = (isNearMaterial(from, "ICE", "PACKED_ICE", "BLUE_ICE") ? 1.20 : 0.70) * walkRatio;
        maxSpeed += getPotionLevel(p, PotionEffectType.SPEED) * 0.18;
        if (!from.clone().add(0, 2.1, 0).getBlock().isEmpty()) maxSpeed += 0.25;
        if (isNearMaterial(from, "SOUL_SAND", "SOUL_SOIL")) maxSpeed += 0.20;

        if (deltaXZ > maxSpeed && !boosted) {
            if (addBuffer(u, "speed", 1.0, 3.0)) {
                flag(p, "Speed", String.format("Vel=%.2f", deltaXZ));
                event.setTo(back(u, from));
                return;
            }
        } else {
            decayBuffer(u, "speed", 0.25);
        }

        if (inAir) {
            int ticks = airTicks.getOrDefault(u, 0) + 1;
            airTicks.put(u, ticks);

            if (deltaY < 0) {
                realFallDist.put(u, realFallDist.getOrDefault(u, 0.0) - deltaY);
            }

            if (p.isOnGround() && ticks > 5 && Math.abs(deltaY) > 0.02 && isHighInAir(to)) {
                if (addBuffer(u, "groundspoof", 1.0, 3.0)) {
                    flag(p, "NoFall (GroundSpoof)", "OnGround en aire");
                }
            } else {
                decayBuffer(u, "groundspoof", 0.25);
            }

            if (ticks > 5 && deltaY > 0.12 && isTouchingWall(to) && !boosted) {
                if (addBuffer(u, "spider", 1.0, 3.0)) {
                    flag(p, "Spider", "Trepar pared");
                    event.setTo(back(u, from));
                }
            } else {
                decayBuffer(u, "spider", 0.25);
            }

            lastDeltaY.put(u, deltaY);
            lastDeltaXZ.put(u, deltaXZ);
        } else {
            double rf = realFallDist.getOrDefault(u, 0.0);
            if (rf > 4.5 + jumpBoost) {
                checkLanding(p, to, rf, jumpBoost);
            }
            realFallDist.put(u, 0.0);
            airTicks.put(u, 0);
            lastDeltaY.put(u, 0.0);
            lastDeltaXZ.put(u, deltaXZ);
            lastGround.put(u, to.clone());
        }
    }

    private void checkLanding(Player p, Location landLoc, double rf, int jumpBoost) {
        if (p.isInvulnerable() || p.getNoDamageTicks() > 0
                || p.hasPotionEffect(PotionEffectType.RESISTANCE)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || p.hasPotionEffect(PotionEffectType.LEVITATION)
                || !p.getWorld().getGameRuleValue(GameRules.FALL_DAMAGE)
                || isCushioned(landLoc)) {
            return;
        }
        UUID u = p.getUniqueId();
        long landTime = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || p.isDead() || isExemptMode(p)) return;
            if (lastFallDamage.getOrDefault(u, 0L) < landTime - 100) {
                flag(p, "NoFall", String.format("Fall=%.1f", rf));
                double dmg = Math.ceil(rf - 3.0 - jumpBoost);
                if (dmg > 0) p.damage(dmg);
            }
        }, 10L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_AIR) checkCPS(p);

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = event.getItem();
            if (item != null) {
                Material m = item.getType();
                if (m == Material.FIREWORK_ROCKET) {
                    lastFirework.put(p.getUniqueId(), System.currentTimeMillis());
                } else if (m.isEdible() || m == Material.POTION || m == Material.BOW || m == Material.TRIDENT) {
                    itemUseStart.put(p.getUniqueId(), System.currentTimeMillis());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player p = event.getPlayer();
        if (isExemptMode(p)) return;
        long start = itemUseStart.getOrDefault(p.getUniqueId(), 0L);
        if (start > 0) {
            long time = System.currentTimeMillis() - start;
            if (time < 1100 && event.getItem().getType() != Material.DRIED_KELP) {
                flag(p, "FastEat", "Time=" + time + "ms");
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p) || isExemptMode(p)) return;
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR || event.getAction() == InventoryAction.NOTHING) return;

        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();

        if (p.isSprinting() && lastDeltaXZ.getOrDefault(u, 0.0) > 0.25 && event.getView().getTopInventory().getHolder() != null) {
            if (addBuffer(u, "invmove", 1.0, 2.0)) {
                flag(p, "InventoryMove", "Click corriendo");
                event.setCancelled(true);
                return;
            }
        } else {
            decayBuffer(u, "invmove", 0.5);
        }

        int slot = event.getRawSlot();
        int prevSlot = lastInvSlot.getOrDefault(u, -999);
        lastInvSlot.put(u, slot);

        if (slot != prevSlot) {
            long diff = now - lastInvClick.getOrDefault(u, 0L);
            lastInvClick.put(u, now);

            if (diff > 2 && diff < 35) {
                int streak = fastClickStreak.getOrDefault(u, 0) + 1;
                fastClickStreak.put(u, streak);
                if (streak >= 5) {
                    flag(p, "ChestStealer", "Delay=" + diff + "ms");
                    event.setCancelled(true);
                }
            } else if (diff > 100) {
                fastClickStreak.put(u, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Player p = event.getPlayer();
        UUID u = p.getUniqueId();
        lastVelocity.put(u, System.currentTimeMillis());

        if (isExemptMode(p) || p.isInWater() || p.isInLava() || p.isInsideVehicle()) return;
        if (event.getVelocity().getY() < 0.20) return;

        Location hitLoc = p.getLocation().clone();
        long delayTicks = Math.min(12L, Math.max(5L, (p.getPing() / 50L) + 4L));

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || p.isDead()) return;
            if (hitLoc.clone().add(0, 2, 0).getBlock().isEmpty() && !isCushioned(hitLoc) && !isTouchingWall(hitLoc)) {
                if (p.getLocation().distance(hitLoc) < 0.005) {
                    if (addBuffer(u, "velocity", 1.0, 3.0)) {
                        flag(p, "Velocity (Anti-KB)", "0% Knockback");
                    }
                } else {
                    decayBuffer(u, "velocity", 0.5);
                }
            }
        }, delayTicks);
    }

    private boolean addBuffer(UUID u, String key, double add, double limit) {
        Map<String, Double> map = buffers.computeIfAbsent(u, k -> new HashMap<>());
        double val = map.getOrDefault(key, 0.0) + add;
        if (val >= limit) {
            map.put(key, limit - 1.0);
            return true;
        }
        map.put(key, val);
        return false;
    }

    private void decayBuffer(UUID u, String key, double sub) {
        Map<String, Double> map = buffers.get(u);
        if (map != null) {
            double val = map.getOrDefault(key, 0.0) - sub;
            if (val <= 0) map.remove(key);
            else map.put(key, val);
        }
    }

    private void checkCPS(Player p) {
        long now = System.currentTimeMillis();
        List<Long> list = clicks.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        list.add(now);
        list.removeIf(t -> now - t > 1000);
        if (list.size() > 22) {
            flag(p, "AutoClicker", "CPS=" + list.size());
        }
    }

    private void flag(Player cheater, String check, String info) {
        UUID u = cheater.getUniqueId();
        int vl = vlMap.getOrDefault(u, 0) + 1;
        vlMap.put(u, vl);
        lastFlagTime.put(u, System.currentTimeMillis());

        String raw = String.format("§8[§c§lVectra§8] §f%s §7falló §c%s §8[§7%s§8] §c(x%d)",
                cheater.getName(), check, info, vl);
        Component comp = LegacyComponentSerializer.legacySection().deserialize(raw);
        getLogger().info(PlainTextComponentSerializer.plainText().serialize(comp));

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.isOp() || staff.hasPermission("vectra.alerts") || staffAlerts.contains(staff.getUniqueId())) {
                staff.sendMessage(comp);
            }
        }

        if (vl >= 15) {
            vlMap.put(u, 0);
            Component kickMsg = LegacyComponentSerializer.legacySection().deserialize(
                    "§c§lVectra AntiCheat\n\n§7Modificación ilegal detectada: §f" + check);
            cheater.kick(kickMsg);
        }
    }

    private Location back(UUID u, Location fallback) {
        Location g = lastGround.get(u);
        if (g != null && g.getWorld() != null && g.getWorld().equals(fallback.getWorld()) && g.distanceSquared(fallback) < 100.0) {
            Location dest = g.clone();
            dest.setYaw(fallback.getYaw());
            dest.setPitch(fallback.getPitch());
            return dest;
        }
        return fallback;
    }

    private boolean isBoosted(Player p, UUID u, long now) {
        return now - lastVelocity.getOrDefault(u, 0L) < 3000
                || now - lastTeleport.getOrDefault(u, 0L) < 1500
                || now - lastGlideEnd.getOrDefault(u, 0L) < 2500
                || isCushioned(p.getLocation());
    }

    private boolean isCushioned(Location loc) {
        return isNearMaterial(loc, "WATER", "LAVA", "SLIME", "HAY_BLOCK", "_BED", "HONEY",
                "COBWEB", "POWDER_SNOW", "SCAFFOLDING", "VINE", "LADDER", "SWEET_BERRY",
                "BUBBLE_COLUMN", "TWISTING_VINES", "WEEPING_VINES", "PISTON");
    }

    private int getPotionLevel(Player p, PotionEffectType type) {
        PotionEffect ef = p.getPotionEffect(type);
        return ef != null ? ef.getAmplifier() + 1 : 0;
    }

    private boolean isExemptMode(Player p) {
        return p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR
                || p.getAllowFlight() || p.isFlying() || p.hasPermission("vectra.bypass");
    }

    private boolean isInSlowBlock(Location loc) {
        String m = loc.getBlock().getType().name();
        return m.contains("COBWEB") || m.contains("SWEET_BERRY_BUSH");
    }

    private boolean isTrulyInAir(Location loc) {
        for (Entity e : loc.getWorld().getNearbyEntities(loc, 1.2, 1.2, 1.2)) {
            String t = e.getType().name();
            if (t.contains("BOAT") || t.contains("SHULKER") || t.contains("MINECART") || t.contains("STRIDER")) return false;
        }
        for (double x = -0.35; x <= 0.35; x += 0.35) {
            for (double z = -0.35; z <= 0.35; z += 0.35) {
                if (!loc.clone().add(x, -0.05, z).getBlock().isEmpty()
                        || !loc.clone().add(x, -0.35, z).getBlock().isEmpty()) {
                    return false;
                }
                String belowFence = loc.clone().add(x, -0.55, z).getBlock().getType().name();
                if (belowFence.contains("FENCE") || belowFence.contains("WALL") || belowFence.contains("LECTERN")) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isHighInAir(Location loc) {
        return isTrulyInAir(loc) && loc.clone().subtract(0, 1.2, 0).getBlock().isEmpty();
    }

    private boolean isOverLiquid(Location loc) {
        Block b = loc.clone().subtract(0, 0.15, 0).getBlock();
        return b.isLiquid() && loc.getBlock().isEmpty();
    }

    private boolean isNearLilyOrBoat(Location loc) {
        for (Entity e : loc.getWorld().getNearbyEntities(loc, 1.5, 1.5, 1.5)) {
            if (e.getType().name().contains("BOAT")) return true;
        }
        return isNearMaterial(loc, "LILY_PAD", "FROSTED_ICE", "FROGSPAWN");
    }

    private boolean isNearMaterial(Location loc, String... names) {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    String m = loc.clone().add(x, y, z).getBlock().getType().name();
                    for (String name : names) {
                        if (m.contains(name)) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isTouchingWall(Location loc) {
        Block b = loc.getBlock();
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace f : faces) {
            if (b.getRelative(f).getType().isSolid()) return true;
        }
        return false;
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
        double dx = Math.max(box.getMinX() - point.getX(), Math.max(0, point.getX() - box.getMaxX()));
        double dy = Math.max(box.getMinY() - point.getY(), Math.max(0, point.getY() - box.getMaxY()));
        double dz = Math.max(box.getMinZ() - point.getZ(), Math.max(0, point.getZ() - box.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("vectra.alerts")) return true;
        if (args.length == 2 && args[0].equalsIgnoreCase("alerts")) {
            Player t = Bukkit.getPlayer(args[1]);
            if (t == null) return true;
            if (staffAlerts.remove(t.getUniqueId())) {
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cAlertas desactivadas para " + t.getName()));
            } else {
                staffAlerts.add(t.getUniqueId());
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§aAlertas activadas para " + t.getName()));
            }
            return true;
        }
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cUsa: /vectra alerts <jugador>"));
        return true;
    }
}
