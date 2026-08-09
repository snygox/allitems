package de.futania.allitems;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AllItems – server-weite Item-Sammel-Challenge.
 *
 * ALLES in einer Datei, wie gewuenscht. Kompatibel mit so gut wie jeder
 * Bukkit/Spigot/Paper-Version (sinnvoll ab 1.13, siehe unten), weil das
 * Plugin sich zur Laufzeit selbst an die jeweilige Version anpasst:
 *
 *   1. Der Item-Pool wird NICHT hart im Code hinterlegt, sondern beim ersten
 *      Start live aus Material.values() gelesen – also genau den Items, die
 *      auf dem tatsaechlich laufenden Server existieren. Läuft das Plugin auf
 *      1.20, bekommst du den 1.20-Itempool; auf 1.21.11 den von 1.21.11; auf
 *      einer zukuenftigen Version automatisch den neuen, groesseren Pool.
 *      Kein Neukompilieren noetig, wenn der Server irgendwann upgedatet wird.
 *
 *   2. Alle Material-Namen werden ausschliesslich als String verglichen statt
 *      als harte Enum-Konstante (Material.valueOf(...) in try/catch statt
 *      z.B. "Material.LIGHT" direkt im Code). Existiert ein Item-Name auf
 *      einer bestimmten Version nicht, wird er einfach ignoriert statt das
 *      Plugin beim Start abstuerzen zu lassen.
 *
 *   3. Inventar-Zugriffe (getStorageContents/getItemInOffHand), die es nicht
 *      auf jeder API-Version gibt, sind einzeln in try/catch gegen
 *      NoSuchMethodError abgesichert und fallen automatisch auf aeltere
 *      Methoden zurueck.
 *
 * Ehrliche Grenze: Bossbars gibt es als Spielfeature erst seit Minecraft 1.9,
 * und das moderne (namensbasierte) Item-System erst seit 1.13. Fuer Server
 * die aelter sind als das, muesste man ein komplett anderes Item-Modell
 * verwenden – das deckt dieses Plugin nicht ab, weil das heute praktisch
 * niemand mehr braucht. Ab 1.13 aufwaerts (inkl. aller zukuenftigen
 * Versionen) funktioniert es automatisch, ohne Anpassung.
 */
public class AllItemsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    /**
     * Technische / nicht regulaer im Survival erreichbare Items, die aus dem
     * Pool ausgeschlossen werden. Bewusst als Strings statt als direkte
     * Material.XYZ-Referenzen, damit ein fehlender Name auf einer anderen
     * Version nicht zu einem Absturz beim Laden fuehrt.
     */
    private static final List<String> EXCLUDED_NAMES = Arrays.asList(
            "AIR", "CAVE_AIR", "VOID_AIR",
            "BARRIER", "STRUCTURE_VOID", "STRUCTURE_BLOCK", "JIGSAW",
            "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
            "DEBUG_STICK", "KNOWLEDGE_BOOK", "LIGHT"
    );

    private File dataFile;
    private List<Material> order = new ArrayList<>();
    private int index = 0;
    private int collectedCount = 0;
    private List<String> skippedNames = new ArrayList<>();
    private boolean finished = false;
    private BossBar bossBar;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "data.yml");

        String detectedVersion = detectServerVersion();
        getLogger().info("Erkannte Server-Version: " + detectedVersion
                + " - Item-Pool wird automatisch dafuer generiert.");

        loadOrGenerate();

        try {
            bossBar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SOLID);
            for (Player p : Bukkit.getOnlinePlayers()) {
                bossBar.addPlayer(p);
            }
        } catch (NoSuchMethodError | AbstractMethodError e) {
            bossBar = null;
            getLogger().warning("Bossbar-API auf diesem Server nicht verfuegbar - "
                    + "Fortschritt wird trotzdem im Chat angesagt, nur ohne Balken oben.");
        }
        updateBossBar();

        getServer().getPluginManager().registerEvents(this, this);

        var skipCommand = getCommand("skipitem");
        if (skipCommand != null) {
            skipCommand.setExecutor(this);
        } else {
            getLogger().severe("Befehl 'skipitem' konnte nicht registriert werden - plugin.yml pruefen!");
        }

        // Prueft jede Sekunde (20 Ticks) alle Online-Spieler auf das aktuelle Ziel-Item
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        getLogger().info("AllItems aktiviert: " + getStatusSummary());
    }

    @Override
    public void onDisable() {
        saveData();
        if (bossBar != null) {
            bossBar.removeAll();
        }
    }

    // ------------------------------------------------------------------
    // Command: /skipitem
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Dieser Befehl kann nur von Spielern ausgefuehrt werden.");
            return true;
        }
        skipCurrentItem((Player) sender);
        return true;
    }

    // ------------------------------------------------------------------
    // Events: Bossbar synchron mit Online-Spielern halten
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (bossBar != null) {
            bossBar.addPlayer(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (bossBar != null) {
            bossBar.removePlayer(event.getPlayer());
        }
    }

    // ------------------------------------------------------------------
    // Spielablauf
    // ------------------------------------------------------------------

    /** Wird jede Sekunde vom Scheduler aufgerufen. */
    private void tick() {
        if (finished || order.isEmpty()) return;

        Material target = order.get(index);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (hasItem(p, target)) {
                onItemCollected(p, target);
                return; // in diesem Tick reicht ein Fund, naechster Tick prueft das neue Ziel-Item
            }
        }
    }

    private void onItemCollected(Player player, Material material) {
        collectedCount++;
        index++;
        Bukkit.broadcastMessage("§a✔ §f" + player.getName() + " §ahat §f" + prettify(material)
                + " §agefunden! §7(" + index + "/" + order.size() + ")");
        advanceOrFinish();
    }

    /** Ueberspringt das aktuelle Item. Kein OP noetig, kommt danach nie wieder dran. */
    private void skipCurrentItem(Player player) {
        if (finished || order.isEmpty()) {
            player.sendMessage("§7Die AllItems-Challenge ist bereits abgeschlossen.");
            return;
        }
        Material current = order.get(index);
        skippedNames.add(current.name());
        index++;
        Bukkit.broadcastMessage("§e⏭ §f" + player.getName() + " §ehat §f" + prettify(current)
                + " §eübersprungen. §7(" + index + "/" + order.size() + ")");
        advanceOrFinish();
    }

    private void advanceOrFinish() {
        if (index >= order.size()) {
            finished = true;
            Bukkit.broadcastMessage("§6§l✦ Die AllItems-Challenge wurde von diesem Server abgeschlossen! ✦");
        }
        saveData();
        updateBossBar();
    }

    /**
     * Prueft, ob ein Spieler das Ziel-Item irgendwo im Inventar hat.
     * Ruestung/Nebenhand-Zugriffe sind einzeln gegen fehlende Methoden auf
     * aelteren API-Versionen abgesichert.
     */
    private boolean hasItem(Player p, Material target) {
        PlayerInventory inv = p.getInventory();

        ItemStack[] storage;
        try {
            storage = inv.getStorageContents();
        } catch (NoSuchMethodError | AbstractMethodError e) {
            storage = inv.getContents();
        }
        if (storage != null) {
            for (ItemStack item : storage) {
                if (item != null && item.getType() == target) return true;
            }
        }

        try {
            ItemStack[] armor = inv.getArmorContents();
            if (armor != null) {
                for (ItemStack item : armor) {
                    if (item != null && item.getType() == target) return true;
                }
            }
        } catch (NoSuchMethodError | AbstractMethodError ignored) {
            // Server ohne Ruestungs-Slot-API - ueberspringen
        }

        try {
            ItemStack offHand = inv.getItemInOffHand();
            if (offHand != null && offHand.getType() == target) return true;
        } catch (NoSuchMethodError | AbstractMethodError ignored) {
            // Server ohne Nebenhand-Slot (aelter als 1.9) - ueberspringen
        }

        return false;
    }

    // ------------------------------------------------------------------
    // Bossbar
    // ------------------------------------------------------------------

    private void updateBossBar() {
        if (bossBar == null) return;

        if (finished || order.isEmpty()) {
            bossBar.setTitle("§6§l✦ Alle Items geschafft! ✦");
            bossBar.setProgress(1.0);
            bossBar.setColor(BarColor.GREEN);
            return;
        }

        Material current = order.get(index);
        bossBar.setTitle("§b🎯 " + prettify(current) + " §7— §f" + index + "/" + order.size() + " geschafft");
        double progress = (double) index / order.size();
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
    }

    private String prettify(Material material) {
        String[] parts = material.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // Versionserkennung
    // ------------------------------------------------------------------

    /** Liest z.B. "1.21.11" aus einem Bukkit-Versionsstring wie "1.21.11-R0.1-SNAPSHOT". */
    private String detectServerVersion() {
        String raw = Bukkit.getBukkitVersion();
        Matcher matcher = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(raw);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return raw;
    }

    // ------------------------------------------------------------------
    // Persistenz
    // ------------------------------------------------------------------

    private void loadOrGenerate() {
        if (dataFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);

            order = new ArrayList<>();
            for (String name : cfg.getStringList("order")) {
                try {
                    order.add(Material.valueOf(name));
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Unbekanntes Material '" + name + "' in data.yml wird ignoriert "
                            + "(vermutlich durch einen Versionswechsel entfernt).");
                }
            }

            index = cfg.getInt("index", 0);
            collectedCount = cfg.getInt("collected", 0);
            skippedNames = new ArrayList<>(cfg.getStringList("skipped"));
            finished = index >= order.size();

            getLogger().info("AllItems-Fortschritt geladen: " + index + "/" + order.size());
        } else {
            generateNewOrder();
            saveData();
            getLogger().info("Neue AllItems-Challenge erzeugt mit " + order.size() + " Items.");
        }
    }

    /** Wird nur beim allerersten Start aufgerufen - liest den Item-Pool live vom laufenden Server. */
    private void generateNewOrder() {
        order = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isItem()) continue;
            if (m.isLegacy()) continue;
            if (EXCLUDED_NAMES.contains(m.name())) continue;
            order.add(m);
        }
        Collections.shuffle(order);
        index = 0;
        collectedCount = 0;
        skippedNames = new ArrayList<>();
        finished = false;
    }

    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        List<String> names = new ArrayList<>();
        for (Material m : order) names.add(m.name());

        cfg.set("order", names);
        cfg.set("index", index);
        cfg.set("collected", collectedCount);
        cfg.set("skipped", skippedNames);

        try {
            cfg.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Konnte data.yml nicht speichern: " + e.getMessage());
        }
    }

    private String getStatusSummary() {
        return index + "/" + order.size() + " Items erledigt (" + collectedCount + " gesammelt, "
                + skippedNames.size() + " uebersprungen)";
    }
}
