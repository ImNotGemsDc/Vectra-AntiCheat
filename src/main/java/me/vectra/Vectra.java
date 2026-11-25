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
                if (prev != null && Math.abs(y - prev) < 0.005) {
                    int c = hoverCount.getOrDefault(u, 0) + 1;
                    hoverCount.put(u, c);
                    if (c >= 4) {
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
        staffAlerts.remove(u); vlMap.remove(u); lastFlagTime.remove(u);
        lastGround.remove(u); airStates.remove(u); hoverY.remove(u);
        hoverCount.remove(u); airTicks.remove(u); vehicleAirTicks.remove(u);
        waterTicks.remove(u); noSlowTicks.remove(u); lastDeltaY.remove(u);
        lastDeltaXZ.remove(u); realFallDist.remove(u); lastFallDamage.remove(u);
        lastMoveDir.remove(u); lastVelocity.remove(u); lastTeleport.remove(u);
        lastFirework.remove(u); lastBlockPlace.remove(u); lastBlockBreak.remove(u);
        lastSwing.remove(u); lastAttackTime.remove(u); lastTarget.remove(u);
        itemUseStart.remove(u); lastInvClick.remove(u); fastClickStreak.remove(u);
        lastGlideToggle.remove(u); clicks.remove(u); movePackets.remove(u);
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

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            lastSwing.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker) || isExemptMode(attacker)) return;

        UUID u = attacker.getUniqueId();
        Entity target = event.getEntity();
        long now = System.currentTimeMillis();

        checkCPS(attacker);

        if (now - lastSwing.getOrDefault(u, 0L) > 550) {
            flag(attacker, "NoSwing / KillAura", "Sin animacion");
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
        if (attacker.getFallDistance() > trackedFall + 1.5 && event.getDamage() > 15.0) {
            flag(attacker, "MaceSpoof / CritSpoof", String.format("Fall=%.1f Real=%.1f", attacker.getFallDistance(), trackedFall));
            event.setCancelled(true);
            return;
        }

        if (attacker.getFallDistance() > 0.0f && !attacker.isOnGround() && trackedFall < 0.08 && lastDeltaY.getOrDefault(u, 0.0) >= 0.0) {
            flag(attacker, "Criticals", "Packet Crit");
            event.setCancelled(true);
            return;
        }

        Location eyeLoc = attacker.getEyeLocation();
        Vector eye = eyeLoc.toVector();
        Vector dir = eyeLoc.getDirection();

        int ping = Math.min(attacker.getPing(), 300);
        double pingComp = ping * 0.002;

        double expand = (target instanceof EnderCrystal ? 0.35 : 0.22) + pingComp;
        BoundingBox hitBox = target.getBoundingBox().expand(expand);
        RayTraceResult ray = hitBox.rayTrace(eye, dir, 6.5);

        if (ray == null) {
            flag(attacker, target instanceof EnderCrystal ? "CrystalAura" : "Hitbox / KillAura", "Fuera de mira");
            event.setCancelled(true);
            return;
        }

        double dist = getMinDistance(eye, target.getBoundingBox().expand(0.1));
        if (dist > 3.08 + pingComp) {
            flag(attacker, "Reach", String.format("Dist=%.2f", dist));
            event.setCancelled(true);
            return;
        }

        RayTraceResult wallRay = attacker.getWorld().rayTraceBlocks(eyeLoc, dir, dist, FluidCollisionMode.NEVER, true);
        if (wallRay != null && wallRay.getHitBlock() != null && wallRay.getHitBlock().getType().isOccluding()) {
            flag(attacker, "WallHit", "Golpe tras pared");
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player p) || isExemptMode(p)) return;
        if (event.getBow() == null || event.getBow().getType() != Material.BOW) return;
        long pullTime = System.currentTimeMillis() - itemUseStart.getOrDefault(p.getUniqueId(), 0L);
        if (event.getForce() > 0.85 && pullTime < 650) {
            flag(p, "FastBow", "Time=" + pullTime + "ms");
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player p) || isExemptMode(p)) return;
        if (System.currentTimeMillis() - lastInvClick.getOrDefault(p.getUniqueId(), 0L) < 50 && (p.isSprinting() || p.isSneaking())) {
            flag(p, "AutoTotem", "Swap instantaneo");
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlideToggle(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player p) || isExemptMode(p)) return;
        long now = System.currentTimeMillis();
        if (now - lastGlideToggle.getOrDefault(p.getUniqueId(), 0L) < 120) {
            flag(p, "PacketElytra", "Spam de Elytra");
            event.setCancelled(true);
        }
        lastGlideToggle.put(p.getUniqueId(), now);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (isExemptMode(p)) return;

        UUID u = p.getUniqueId();
        Block against = event.getBlockAgainst();
        Block placed = event.getBlockPlaced();
        long now = System.currentTimeMillis();

        if (against.isEmpty() || (against.isLiquid() && !placed.getType().name().contains("LILY") && !placed.getType().name().contains("FROG")) || hasNoSolidNeighbors(placed)) {
            flag(p, "AirPlace", "Sin apoyo");
            event.setCancelled(true);
            return;
        }

        if (now - lastSwing.getOrDefault(u, 0L) > 550) {
            flag(p, "Scaffold (NoSwing)", "Sin animacion");
            event.setCancelled(true);
            return;
        }

        if (p.getEyeLocation().distance(placed.getLocation().add(0.5, 0.5, 0.5)) > 5.15) {
            flag(p, "BlockReach", "Distancia excesiva");
            event.setCancelled(true);
            return;
        }

        long diff = now - lastBlockPlace.getOrDefault(u, 0L);
        lastBlockPlace.put(u, now);
        if (diff < 38) {
            flag(p, "FastPlace", "Delay=" + diff + "ms");
            event.setCancelled(true);
            return;
        }

        Vector toBlock = placed.getLocation().add(0.5, 0.5, 0.5).toVector().subtract(p.getEyeLocation().toVector());
        if (toBlock.lengthSquared() > 1e-4 && p.getEyeLocation().getDirection().dot(toBlock.normalize()) < -0.15) {
            flag(p, "Scaffold (Rotation)", "Angulo imposible");
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (isExemptMode(p)) return;

        UUID u = p.getUniqueId();
        Block b = event.getBlock();
        long now = System.currentTimeMillis();

        if (now - lastBlockBreak.getOrDefault(u, 0L) < 25 && b.getType().getHardness() > 0.5f) {
            flag(p, "Nuker / FastBreak", "Multi-break");
            event.setCancelled(true);
            return;
        }
        lastBlockBreak.put(u, now);

        if (p.getEyeLocation().distance(b.getLocation().add(0.5, 0.5, 0.5)) > 5.25) {
            flag(p, "BlockReach", "Romper lejos");
            event.setCancelled(true);
            return;
        }

        RayTraceResult ray = b.getBoundingBox().expand(0.25).rayTrace(p.getEyeLocation().toVector(), p.getEyeLocation().getDirection(), 6.0);
        if (ray == null) {
            flag(p, "Nuker / CivBreak", "Sin mirar al bloque");
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

        if (Double.isNaN(to.getX()) || Double.isNaN(to.getY()) || Double.isNaN(to.getZ()) || Double.isInfinite(to.getY()) || Math.abs(to.getX()) > 29999999 || Math.abs(to.getZ()) > 29999999) {
            flag(p, "Crasher", "Coordenadas invalidas");
            event.setCancelled(true);
            return;
        }

        if (Math.abs(to.getPitch()) > 90.0f) {
            flag(p, "BadPackets", "Pitch=" + to.getPitch());
            event.setTo(back(u, from));
            return;
        }

        long now = System.currentTimeMillis();

        float yawDiff = Math.abs(to.getYaw() - from.getYaw()) % 360.0f;
        if (yawDiff > 180.0f) yawDiff = 360.0f - yawDiff;
        if (yawDiff > 140.0f && now - lastAttackTime.getOrDefault(u, 0L) < 100 && now - lastTeleport.getOrDefault(u, 0L) > 1000) {
            flag(p, "Aimbot (Snap)", String.format("YawDelta=%.1f", yawDiff));
        }

        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        List<Long> packets = movePackets.computeIfAbsent(u, k -> new ArrayList<>());
        if (!packets.isEmpty() && now - packets.get(packets.size() - 1) > 300) {
            packets.clear();
        }
        packets.add(now);
        packets.removeIf(t -> now - t > 1000);
        if (packets.size() > 28 && now - lastTeleport.getOrDefault(u, 0L) > 1500) {
            flag(p, "Timer / Blink", "PPS=" + packets.size());
            event.setTo(back(u, from));
            return;
        }

        double deltaY = to.getY() - from.getY();
        double deltaXZ = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        boolean recentlyHit = now - lastVelocity.getOrDefault(u, 0L) < 1300;
        boolean recentlyTp = now - lastTeleport.getOrDefault(u, 0L) < 1500;
        boolean nearBounce = isNearMaterial(from, "SLIME", "_BED", "HONEY", "PISTON");

        if (p.isInsideVehicle()) {
            realFallDist.put(u, 0.0);
            Entity v = p.getVehicle();
            if (v != null && !(v instanceof LivingEntity && v.isOnGround()) && !v.isInWater()) {
                if (deltaXZ > 1.4 || (deltaY > 0.0 && !v.isOnGround())) {
                    int vt = vehicleAirTicks.getOrDefault(u, 0) + 1;
                    vehicleAirTicks.put(u, vt);
                    if (vt > 7) {
                        flag(p, "BoatFly / EntitySpeed", String.format("XZ=%.2f DY=%.2f", deltaXZ, deltaY));
                        v.teleport(back(u, from));
                    }
                } else {
                    vehicleAirTicks.put(u, 0);
                }
            }
            return;
        }

        if (!recentlyTp && (Math.abs(deltaY) > 1.5 || deltaXZ > 1.2) && to.getBlock().getType().isOccluding() && to.clone().add(0, 1, 0).getBlock().getType().isOccluding()) {
            flag(p, "Phase / VClip", "Atravesar bloques");
            event.setTo(back(u, from));
            return;
        }

        if (p.isSprinting()) {
            Vector moveVec = new Vector(to.getX() - from.getX(), 0, to.getZ() - from.getZ());
            Vector lookVec = new Vector(to.getDirection().getX(), 0, to.getDirection().getZ());
            if (deltaXZ > 0.22 && moveVec.lengthSquared() > 1e-4 && lookVec.lengthSquared() > 1e-4
                    && moveVec.normalize().dot(lookVec.normalize()) < -0.25 && !recentlyHit) {
                flag(p, "OmniSprint", "Correr hacia atras");
                event.setTo(back(u, from));
            }
            if (p.getFoodLevel() <= 6 || p.hasPotionEffect(PotionEffectType.BLINDNESS)) {
                flag(p, "BadPackets (Sprint)", "Sprint ilegal");
                p.setSprinting(false);
            }
        }

        if (p.isGliding()) {
            realFallDist.put(u, 0.0);
            boolean usedFirework = now - lastFirework.getOrDefault(u, 0L) < 3500;
            if (!usedFirework && !recentlyHit) {
                if (deltaXZ > 1.65) {
                    flag(p, "ElytraBoost", String.format("XZ=%.2f", deltaXZ));
                    event.setTo(back(u, from));
                } else if (deltaY > 0.05 && to.getPitch() > -15) {
                    flag(p, "ElytraFly", String.format("DY=%.2f", deltaY));
                    event.setTo(back(u, from));
                }
            }
            return;
        }

        if (isInSlowBlock(to) && deltaXZ > 0.16 && !recentlyHit) {
            flag(p, "NoWeb / NoSlow", "Rapido en bloque lento");
            event.setTo(back(u, from));
            return;
        }

        if ((p.isHandRaised() || p.isBlocking()) && !recentlyHit) {
            if (deltaXZ > 0.19) {
                int ns = noSlowTicks.getOrDefault(u, 0) + 1;
                noSlowTicks.put(u, ns);
                if (ns > 5) {
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
            if (Math.abs(deltaY) < 0.005) {
                int wt = waterTicks.getOrDefault(u, 0) + 1;
                waterTicks.put(u, wt);
                if (wt > 5) {
                    flag(p, "Jesus", "Caminar en liquido");
                    event.setTo(back(u, from));
                }
            }
        } else {
            waterTicks.put(u, 0);
        }

        if (p.isInWater() || p.isSwimming()) {
            realFallDist.put(u, 0.0);
            airStates.remove(u);
            double maxWater = 0.38;
            if (p.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) maxWater += 0.35;
            if (deltaXZ > maxWater && !recentlyHit) {
                flag(p, "WaterSpeed", String.format("XZ=%.2f", deltaXZ));
                event.setTo(back(u, from));
            }
            airTicks.put(u, 0);
            return;
        }

        if (p.isClimbing() || p.isInLava() || p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            realFallDist.put(u, 0.0);
            airTicks.put(u, 0);
            airStates.remove(u);
            return;
        }

        boolean inAir = isTrulyInAir(to);
        int jumpBoost = getPotionLevel(p, PotionEffectType.JUMP_BOOST);

        // --- FLY (AirState: Altura y Gravedad) ---
        AirState st = airStates.computeIfAbsent(u, k -> new AirState());
        if (isBoosted(p, u, now)) {
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

                double maxGain = 1.32 + jumpBoost * 0.75;
                if (to.getY() - st.groundY > maxGain) {
                    flag(p, "Fly (Altura)", String.format("Gain=%.2f", to.getY() - st.groundY));
                    event.setTo(back(u, from));
                    return;
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

        double maxJump = 0.60 + jumpBoost * 0.15;
        if (deltaY > maxJump && !recentlyHit && !nearBounce && !recentlyTp) {
            flag(p, "Step / HighJump", String.format("DY=%.2f", deltaY));
            event.setTo(back(u, from));
            return;
        }

        if (from.getY() % 0.015625 == 0.0 && deltaY < -0.95 && !recentlyTp && !recentlyHit) {
            flag(p, "ReverseStep / FastFall", String.format("DY=%.2f", deltaY));
            event.setTo(back(u, from));
            return;
        }

        double maxSpeed = isNearMaterial(from, "ICE", "PACKED_ICE", "BLUE_ICE") ? 1.15 : 0.67;
        maxSpeed += getPotionLevel(p, PotionEffectType.SPEED) * 0.16;
        if (deltaXZ > maxSpeed && !recentlyHit && !recentlyTp) {
            flag(p, "Speed", String.format("Vel=%.2f", deltaXZ));
            event.setTo(back(u, from));
            return;
        }

        if (inAir) {
            int ticks = airTicks.getOrDefault(u, 0) + 1;
            airTicks.put(u, ticks);
            double prevXZ = lastDeltaXZ.getOrDefault(u, 0.0);

            if (deltaY < 0) {
                realFallDist.put(u, realFallDist.getOrDefault(u, 0.0) - deltaY);
            }

            if (p.isOnGround() && ticks > 4 && Math.abs(deltaY) > 0.01) {
                flag(p, "NoFall (GroundSpoof)", "OnGround en aire");
            }

            Vector curDir = new Vector(to.getX() - from.getX(), 0, to.getZ() - from.getZ());
            Vector prevDir = lastMoveDir.get(u);
            if (ticks > 3 && deltaXZ > 0.26 && prevDir != null && curDir.lengthSquared() > 1e-4 && prevDir.lengthSquared() > 1e-4) {
                if (curDir.clone().normalize().dot(prevDir.clone().normalize()) < 0.15 && deltaXZ >= prevXZ * 0.95 && !recentlyHit) {
                    flag(p, "Strafe", "Cambio brusco en aire");
                    event.setTo(back(u, from));
                }
            }
            lastMoveDir.put(u, curDir);

            if (ticks > 3 && deltaY > 0.1 && isTouchingWall(to) && !recentlyHit && !nearBounce) {
                flag(p, "Spider", "Trepar pared");
                event.setTo(back(u, from));
            }

            lastDeltaY.put(u, deltaY);
            lastDeltaXZ.put(u, deltaXZ);
        } else {
            double rf = realFallDist.getOrDefault(u, 0.0);
            if (rf > 4.0 + jumpBoost) {
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

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null && !isExemptMode(p)) {
            Block b = event.getClickedBlock();
            if (b.getType().name().contains("CHEST") || b.getType().name().contains("SHULKER") || b.getType().name().contains("BARREL") || b.getType() == Material.ENDER_CHEST) {
                RayTraceResult ray = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), 5.5, FluidCollisionMode.NEVER, true);
                if (ray != null && ray.getHitBlock() != null && !ray.getHitBlock().equals(b) && ray.getHitBlock().getType().isOccluding()) {
                    flag(p, "GhostHand", "Interactuar tras pared");
                    event.setCancelled(true);
                    return;
                }
            }
        }

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
            if (time < 1200 && event.getItem().getType() != Material.DRIED_KELP) {
                flag(p, "FastEat", "Time=" + time + "ms");
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p) || isExemptMode(p)) return;

        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();

        if (p.isSprinting() && lastDeltaXZ.getOrDefault(u, 0.0) > 0.22 && event.getView().getTopInventory().getHolder() != null) {
            flag(p, "InventoryMove", "Click corriendo");
            event.setCancelled(true);
            return;
        }

        long diff = now - lastInvClick.getOrDefault(u, 0L);
        lastInvClick.put(u, now);

        if (diff > 1 && diff < 38) {
            int streak = fastClickStreak.getOrDefault(u, 0) + 1;
            fastClickStreak.put(u, streak);
            if (streak >= 4) {
                flag(p, "ChestStealer / AutoArmor", "Delay=" + diff + "ms");
                event.setCancelled(true);
            }
        } else if (diff > 120) {
            fastClickStreak.put(u, 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Player p = event.getPlayer();
        lastVelocity.put(p.getUniqueId(), System.currentTimeMillis());

        if (isExemptMode(p) || p.isInWater() || p.isInLava() || p.isInsideVehicle()) return;
        if (event.getVelocity().getY() < 0.15) return;

        Location hitLoc = p.getLocation().clone();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || p.isDead()) return;
            if (hitLoc.clone().add(0, 2, 0).getBlock().isEmpty() && !isNearMaterial(hitLoc, "COBWEB", "HONEY")) {
                if (p.getLocation().distance(hitLoc) < 0.008) {
                    flag(p, "Velocity (Anti-KB)", "0% Knockback");
                }
            }
        }, 4L);
    }

    private void checkCPS(Player p) {
        long now = System.currentTimeMillis();
        List<Long> list = clicks.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        list.add(now);
        list.removeIf(t -> now - t > 1000);
        if (list.size() > 20) {
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
                || now - lastGlideToggle.getOrDefault(u, 0L) < 1500
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
        for (double x = -0.35; x <= 0.35; x += 0.35) {
            for (double z = -0.35; z <= 0.35; z += 0.35) {
                if (!loc.clone().add(x, -0.2, z).getBlock().isEmpty() || !loc.clone().add(x, -0.6, z).getBlock().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
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
