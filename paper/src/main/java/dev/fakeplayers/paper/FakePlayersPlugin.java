package dev.fakeplayers.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class FakePlayersPlugin extends JavaPlugin implements Listener {
    private FakeManager manager;
    private ProxyBridge bridge;

    public FakeManager manager() { return manager; }
    public ProxyBridge bridge() { return bridge; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new FakeManager(this);
        manager.load();
        bridge = new ProxyBridge(this);
        FakeCommand cmd = new FakeCommand(this);
        getCommand("fp").setExecutor(cmd);
        getCommand("fp").setTabCompleter(cmd);
        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskLater(this, manager::spawnAuto, 40L); // after worlds are loaded
        long save = getConfig().getLong("autosave-seconds", 60) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, manager::save, save, save);
        long sync = getConfig().getLong("sync-interval-seconds", 30) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, bridge::syncNow, sync, sync);
    }

    @Override
    public void onDisable() { if (manager != null) manager.shutdown(); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (FakeServerPlayer.isFake(e.getPlayer())) {
            if (getConfig().getBoolean("suppress-join-quit-messages")) e.joinMessage(null);
        } else {
            Bukkit.getScheduler().runTaskLater(this, bridge::syncNow, 20L); // a real player can now carry messages
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (FakeServerPlayer.isFake(e.getPlayer()) && getConfig().getBoolean("suppress-join-quit-messages"))
            e.quitMessage(null);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (FakeServerPlayer.isFake(p) && getConfig().getBoolean("auto-respawn", true))
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isDead()) p.spigot().respawn(); }, 5L);
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Player hp && FakeServerPlayer.isFake(hp)) {
            FakeData d = manager.get(hp.getName());
            if (d != null) manager.capture(d);
        }
    }

    /** When a TPA-style command targets a fake player, make the fake accept it automatically. */
    @EventHandler
    public void onTpaCommand(PlayerCommandPreprocessEvent e) {
        if (!getConfig().getBoolean("auto-accept-tpa", true)) return;
        String[] parts = e.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2) return;
        String label = parts[0].toLowerCase();
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        List<String> tpaCmds = getConfig().getStringList("tpa-commands");
        if (tpaCmds.isEmpty()) tpaCmds = List.of("tpa", "tpahere", "tpask", "tpaskhere", "call");
        if (!tpaCmds.contains(label)) return;
        Player fake = Bukkit.getPlayerExact(parts[1]);
        if (fake == null || !FakeServerPlayer.isFake(fake)) return;
        String sender = e.getPlayer().getName();
        List<String> accept = getConfig().getStringList("accept-commands");
        if (accept.isEmpty()) accept = List.of("tpaccept");
        final List<String> acceptCmds = accept;
        long delay = getConfig().getLong("auto-accept-delay-ticks", 10);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!fake.isOnline()) return;
            for (String c : acceptCmds) fake.performCommand(c.replace("{player}", sender));
        }, delay);
    }

    private static String str(java.util.Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    /** When a menu opens for a fake player, click the first item matching a rule from config.yml. */
    @EventHandler
    public void onGuiOpen(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !FakeServerPlayer.isFake(p)) return;
        var cfg = getConfig();
        if (!cfg.getBoolean("auto-click-gui.enabled", true)) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            org.bukkit.inventory.InventoryView view = p.getOpenInventory();
            org.bukkit.inventory.Inventory top = view.getTopInventory();
            if (top.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING
                    || top.getType() == org.bukkit.event.inventory.InventoryType.PLAYER) return;
            String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(view.title()).toLowerCase();
            List<java.util.Map<?, ?>> rules = cfg.getMapList("auto-click-gui.rules");
            if (rules.isEmpty()) rules = List.of(
                    java.util.Map.of("material", "LIME_STAINED_GLASS_PANE"),
                    java.util.Map.of("material", "LIME_STAINED_GLASS"),
                    java.util.Map.of("material", "GREEN_STAINED_GLASS_PANE"),
                    java.util.Map.of("material", "GREEN_STAINED_GLASS"));
            org.bukkit.event.inventory.ClickType click;
            try { click = org.bukkit.event.inventory.ClickType.valueOf(cfg.getString("auto-click-gui.click-type", "LEFT").toUpperCase()); }
            catch (IllegalArgumentException ex) { click = org.bukkit.event.inventory.ClickType.LEFT; }
            org.bukkit.event.inventory.InventoryAction action = switch (click) {
                case RIGHT -> org.bukkit.event.inventory.InventoryAction.PICKUP_HALF;
                case SHIFT_LEFT, SHIFT_RIGHT -> org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY;
                default -> org.bukkit.event.inventory.InventoryAction.PICKUP_ALL;
            };
            for (java.util.Map<?, ?> rule : rules) {
                String wantTitle = str(rule, "title").toLowerCase();
                String wantMat = str(rule, "material");
                String wantName = str(rule, "name").toLowerCase();
                String slotStr = str(rule, "slot");
                int wantSlot = slotStr.isEmpty() ? -1 : Integer.parseInt(slotStr);
                if (!wantTitle.isEmpty() && !title.contains(wantTitle)) continue;
                for (int i = 0; i < top.getSize(); i++) {
                    if (wantSlot >= 0 && i != wantSlot) continue;
                    org.bukkit.inventory.ItemStack it = top.getItem(i);
                    if (it == null || it.getType().isAir()) continue;
                    if (!wantMat.isEmpty() && !it.getType().name().equalsIgnoreCase(wantMat)) continue;
                    if (!wantName.isEmpty()) {
                        String n = (it.hasItemMeta() && it.getItemMeta().hasDisplayName())
                                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                        .serialize(it.getItemMeta().displayName()).toLowerCase()
                                : it.getType().name().toLowerCase();
                        if (!n.contains(wantName)) continue;
                    }
                    Bukkit.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryClickEvent(
                            view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, i, click, action));
                    return;
                }
            }
        }, Math.max(1, cfg.getLong("auto-click-gui.delay-ticks", 3)));
    }
}
