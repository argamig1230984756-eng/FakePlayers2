package dev.fakeplayers.paper;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.concurrent.ThreadLocalRandom;

/** A REAL ServerPlayer (so every plugin/command treats it as a player) with a built-in movement brain. */
public class FakeServerPlayer extends ServerPlayer {
    public final FakeData data;
    public FakeConnection fakeConnection;
    private double tx, tz;
    private boolean hasTarget;
    private int wait, stuck, patrolIdx, ticks;

    private FakeServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, FakeData data) {
        super(server, level, profile, ClientInformation.createDefault());
        this.data = data;
    }

    // ---------- spawn / remove ----------
    public static GameProfile profile(FakeData d) {
        Multimap<String, Property> props = HashMultimap.create();
        if (d.skinValue != null && !d.skinValue.isEmpty())
            props.put("textures", new Property("textures", d.skinValue, d.skinSignature.isEmpty() ? null : d.skinSignature));
        return new GameProfile(d.uuid, d.name, new PropertyMap(props));
    }

    public static Player spawn(FakeData d, Location loc) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) loc.getWorld()).getHandle();
        GameProfile profile = profile(d);
        FakeServerPlayer sp = new FakeServerPlayer(server, level, profile, d);
        sp.fakeConnection = new FakeConnection();
        sp.snapTo(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        server.getPlayerList().placeNewPlayer(sp.fakeConnection, sp, CommonListenerCookie.createInitial(profile, false));
        return sp.getBukkitEntity();
    }

    public static void remove(Player p) {
        ServerPlayer sp = ((CraftPlayer) p).getHandle();
        sp.connection.onDisconnect(new DisconnectionDetails(net.minecraft.network.chat.Component.literal("Fake player removed")));
    }

    public static boolean isFake(Player p) {
        return ((CraftPlayer) p).getHandle() instanceof FakeServerPlayer;
    }

    public static void setPing(Player p, int ms) {
        try {
            Object listener = ((CraftPlayer) p).getHandle().connection;
            for (Class<?> c = listener.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("latency");
                    f.setAccessible(true);
                    f.setInt(listener, ms);
                    return;
                } catch (NoSuchFieldException ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    // ---------- tick ----------
    @Override
    public void tick() {
        int t = ++ticks;
        if (t % 10 == 0) {
            this.connection.resetPosition();
            ((ServerLevel) this.level()).getChunkSource().move(this);
        }
        if (t % 100 == 0 && fakeConnection != null) fakeConnection.embedded().outboundMessages().clear();
        think();
        try { super.tick(); this.doTick(); } catch (NullPointerException ignored) {}
    }

    private void think() {
        this.zza = 0; this.xxa = 0; this.setJumping(false);
        boolean moving = false;
        switch (data.movement.toUpperCase()) {
            case "WANDER" -> moving = wander();
            case "FOLLOW" -> moving = follow();
            case "PATROL" -> moving = patrol();
            case "LOOK" -> { float y = getYRot() + (float) (3 * data.speed); setYRot(y); setYHeadRot(y); }
            default -> { }
        }
        if (moving) {
            this.zza = (float) Math.min(1.0, data.speed);
            if (this.horizontalCollision && this.onGround()) this.setJumping(true);
        }
        this.setSprinting(moving && data.sprint);
    }

    private double face(double x, double z) {
        double dx = x - getX(), dz = z - getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        setYRot(yaw); setYHeadRot(yaw);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean wander() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (!hasTarget) {
            if (wait-- > 0) return false;
            double a = r.nextDouble() * Math.PI * 2, rad = r.nextDouble() * data.radius;
            tx = data.x + Math.cos(a) * rad; tz = data.z + Math.sin(a) * rad;
            hasTarget = true; stuck = 0;
        }
        double d = face(tx, tz);
        if (d < 0.6 || ++stuck > 200) { hasTarget = false; wait = 20 + r.nextInt(80); return false; }
        return true;
    }

    private boolean follow() {
        Player target = Bukkit.getPlayerExact(data.followTarget);
        if (target == null || !target.getWorld().getName().equals(getBukkitEntity().getWorld().getName())) return false;
        Location l = target.getLocation();
        return face(l.getX(), l.getZ()) > 2.5;
    }

    private boolean patrol() {
        if (data.patrol.isEmpty()) return false;
        if (wait > 0) { wait--; return false; }
        if (patrolIdx >= data.patrol.size()) patrolIdx = 0;
        double[] p = data.patrol.get(patrolIdx);
        double d = face(p[0], p[1]);
        if (d < 0.8 || ++stuck > 400) { patrolIdx++; stuck = 0; wait = 20; return false; }
        return true;
    }
}
