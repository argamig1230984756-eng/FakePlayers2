package dev.fakeplayers.velocity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Plugin(id = "fakeplayers", name = "FakePlayers", version = "1.0.0", authors = {"you"})
public final class FakePlayersVelocity {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("fakeplayers", "main");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dir;
    private final Gson gson = new Gson();
    private final Map<UUID, FakeEntry> registry = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> shown = new ConcurrentHashMap<>(); // real player -> fake uuids in their tablist
    private String secret = "change-me";

    @Inject
    public FakePlayersVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dir) {
        this.proxy = proxy; this.logger = logger; this.dir = dir;
    }

    /** Public API for other proxy plugins. */
    public Collection<FakeEntry> getFakePlayers() { return Collections.unmodifiableCollection(registry.values()); }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        try {
            Files.createDirectories(dir);
            Path cfg = dir.resolve("config.properties");
            Properties p = new Properties();
            if (Files.exists(cfg)) try (var in = Files.newInputStream(cfg)) { p.load(in); }
            else { p.setProperty("secret", "change-me"); try (var out = Files.newOutputStream(cfg)) { p.store(out, "Must match 'secret' in the Paper plugin config.yml"); } }
            secret = p.getProperty("secret", "change-me");
            Path reg = dir.resolve("registry.json");
            if (Files.exists(reg)) {
                List<FakeEntry> list = gson.fromJson(Files.readString(reg), new TypeToken<List<FakeEntry>>() {}.getType());
                if (list != null) list.forEach(f -> registry.put(f.uuid, f));
            }
        } catch (Exception ex) { logger.error("Config load failed", ex); }

        proxy.getChannelRegistrar().register(CHANNEL);
        var meta = proxy.getCommandManager().metaBuilder("vfp").aliases("fglist").plugin(this).build();
        proxy.getCommandManager().register(meta, new Cmd());
    }

    // ---------- backend -> proxy ----------
    @Subscribe
    public void onMessage(PluginMessageEvent e) {
        if (!e.getIdentifier().equals(CHANNEL)) return;
        e.setResult(PluginMessageEvent.ForwardResult.handled()); // never forward either way
        if (!(e.getSource() instanceof ServerConnection sc)) return; // ignore anything a CLIENT tries to send
        String server = sc.getServerInfo().getName();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(e.getData()))) {
            switch (in.readUTF()) {
                case "UPSERT" -> {
                    FakeEntry f = new FakeEntry();
                    f.server = server;
                    f.epoch = in.readLong();
                    f.uuid = UUID.fromString(in.readUTF());
                    f.name = in.readUTF(); f.display = in.readUTF();
                    f.skinValue = in.readUTF(); f.skinSignature = in.readUTF();
                    f.ping = in.readInt(); f.gamemode = in.readInt(); f.listed = in.readBoolean();
                    registry.put(f.uuid, f);
                }
                case "COMMIT" -> {
                    long epoch = in.readLong();
                    registry.values().removeIf(x -> x.server.equals(server) && x.epoch != epoch);
                    saveRegistry();
                    refreshAll();
                }
                default -> { }
            }
        } catch (Exception ex) { logger.warn("Bad message from {}: {}", server, ex.toString()); }
    }

    private void saveRegistry() {
        try { Files.writeString(dir.resolve("registry.json"), gson.toJson(new ArrayList<>(registry.values()))); }
        catch (IOException ex) { logger.warn("Could not save registry", ex); }
    }

    // ---------- tablist ----------
    private static Component name(FakeEntry f) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(f.display.isEmpty() ? f.name : f.display);
    }

    public void refreshAll() { proxy.getAllPlayers().forEach(this::refreshTab); }

    private void refreshTab(Player p) {
        String cur = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
        TabList tl = p.getTabList();
        Set<UUID> mine = shown.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        for (UUID id : new ArrayList<>(mine)) {
            FakeEntry f = registry.get(id);
            if (f == null || !f.listed || f.server.equals(cur)) { tl.removeEntry(id); mine.remove(id); }
        }
        for (FakeEntry f : registry.values()) {
            if (!f.listed || f.server.equals(cur)) continue; // players on that server already get it from the backend
            try {
                tl.removeEntry(f.uuid);
                var props = f.skinValue.isEmpty() ? List.<GameProfile.Property>of()
                        : List.of(new GameProfile.Property("textures", f.skinValue, f.skinSignature));
                tl.addEntry(TabListEntry.builder().tabList(tl)
                        .profile(new GameProfile(f.uuid, f.name, props))
                        .displayName(name(f)).latency(f.ping).gameMode(f.gamemode).listed(true).build());
                mine.add(f.uuid);
            } catch (Exception ex) { logger.debug("tab add failed for {}: {}", f.name, ex.toString()); }
        }
    }

    @Subscribe
    public void onSwitch(ServerPostConnectEvent e) {
        shown.remove(e.getPlayer().getUniqueId()); // Velocity wipes the tablist on server change
        proxy.getScheduler().buildTask(this, () -> refreshTab(e.getPlayer())).delay(Duration.ofMillis(600)).schedule();
    }

    @Subscribe
    public void onQuit(DisconnectEvent e) { shown.remove(e.getPlayer().getUniqueId()); }

    // ---------- server list ----------
    @Subscribe
    public void onPing(ProxyPingEvent e) {
        ServerPing ping = e.getPing();
        List<FakeEntry> fakes = registry.values().stream().filter(f -> f.listed).collect(Collectors.toList());
        if (fakes.isEmpty() || ping.getPlayers().isEmpty()) return;
        ServerPing.Players pl = ping.getPlayers().get();
        List<ServerPing.SamplePlayer> sample = new ArrayList<>(pl.getSample());
        for (FakeEntry f : fakes) if (sample.size() < 12) sample.add(new ServerPing.SamplePlayer(f.name, f.uuid));
        e.setPing(ping.asBuilder().onlinePlayers(pl.getOnline() + fakes.size())
                .samplePlayers(sample.toArray(new ServerPing.SamplePlayer[0])).build());
    }

    // ---------- proxy -> backend ----------
    private boolean run(String server, String command) {
        Optional<RegisteredServer> rs = proxy.getServer(server);
        if (rs.isEmpty()) return false;
        for (Player p : rs.get().getPlayersConnected()) {
            Optional<ServerConnection> sc = p.getCurrentServer();
            if (sc.isEmpty()) continue;
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                DataOutputStream o = new DataOutputStream(bo);
                o.writeUTF("RUN"); o.writeUTF(secret); o.writeUTF(command);
                return sc.get().sendPluginMessage(CHANNEL, bo.toByteArray());
            } catch (IOException ignored) {}
        }
        return false;
    }

    private final class Cmd implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation inv) { return inv.source().hasPermission("fakeplayers.admin"); }

        @Override
        public void execute(Invocation inv) {
            var src = inv.source();
            String[] a = inv.arguments();
            if (inv.alias().equalsIgnoreCase("fglist") || a.length == 0 || a[0].equalsIgnoreCase("list")) {
                int real = proxy.getPlayerCount();
                src.sendMessage(Component.text("Online: " + real + " real + " + registry.size() + " fake", NamedTextColor.AQUA));
                Map<String, List<FakeEntry>> by = registry.values().stream().collect(Collectors.groupingBy(f -> f.server));
                by.forEach((srv, list) -> src.sendMessage(Component.text(" " + srv + ": "
                        + list.stream().map(f -> f.name).collect(Collectors.joining(", ")), NamedTextColor.GRAY)));
                return;
            }
            if (a[0].equalsIgnoreCase("run") && a.length >= 3) {
                String server = a[1];
                String command = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                src.sendMessage(Component.text(run(server, command) ? "Sent to " + server
                        : "Failed: server unknown or no real player online there to carry the message.", NamedTextColor.YELLOW));
                return;
            }
            src.sendMessage(Component.text("/vfp list | /vfp run <server> <fp command...>   e.g. /vfp run lobby set Bob ping 25", NamedTextColor.GRAY));
        }

        @Override
        public List<String> suggest(Invocation inv) {
            String[] a = inv.arguments();
            if (a.length <= 1) return List.of("list", "run");
            if (a.length == 2 && a[0].equalsIgnoreCase("run"))
                return proxy.getAllServers().stream().map(s -> s.getServerInfo().getName()).collect(Collectors.toList());
            return List.of();
        }
    }
}
