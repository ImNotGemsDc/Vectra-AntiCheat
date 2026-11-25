package me.vectra.data;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.entity.Player;
import me.vectra.checks.Check;
import me.vectra.checks.impl.BadPacketsA;
import me.vectra.checks.impl.FlyA;
import me.vectra.checks.impl.KillAuraA;
import me.vectra.checks.impl.VelocityA;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerData {

    private final UUID uuid;
    private final User user;
    private Player bukkitPlayer;
    private final List<Check> checks = new ArrayList<>();

    public double x, y, z, lastX, lastY, lastZ;
    public double deltaX, deltaY, deltaZ, lastDeltaY;
    public double deltaXZ, lastDeltaXZ;
    public float yaw, pitch, lastYaw, lastPitch;
    public boolean onGround, lastOnGround;
    public int airTicks = 0;

    public double velocityX, velocityY, velocityZ;
    public int ticksSinceVelocity = 100;

    public PlayerData(UUID uuid, User user) {
        this.uuid = uuid;
        this.user = user;
        
        this.checks.add(new BadPacketsA(this));
        this.checks.add(new FlyA(this));
        this.checks.add(new VelocityA(this));
        this.checks.add(new KillAuraA(this));
    }

    public ClientVersion getClientVersion() { return user.getClientVersion(); }
    public List<Check> getChecks() { return checks; }
    public UUID getUuid() { return uuid; }
    public User getUser() { return user; }
    public Player getBukkitPlayer() { return bukkitPlayer; }
    public void setBukkitPlayer(Player bukkitPlayer) { this.bukkitPlayer = bukkitPlayer; }
}
