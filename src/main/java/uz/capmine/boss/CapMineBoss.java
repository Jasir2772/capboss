package uz.capmine.boss;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CapMineBoss extends JavaPlugin implements CommandExecutor, Listener {

    private LivingEntity activeBoss = null;
    private BossBar bossBar = null;
    private final Map<UUID, Double> damageMap = new HashMap<>();
    private boolean isEnraged = false;
    private int enrageTimer = 30;
    private final Set<Integer> sentWarnings = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.getCommand("cboss").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        startScheduler();
        getLogger().info("CapMine Boss tizimi yoqildi!");
    }

    private void startScheduler() {
        new BukkitRunnable() {
            @Override
            public void run() {
                List<String> times = getConfig().getStringList("spawn-times");
                LocalTime now = LocalTime.now();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");

                long minSeconds = Long.MAX_VALUE;
                for (String timeStr : times) {
                    try {
                        LocalTime target = LocalTime.parse(timeStr, formatter);
                        long seconds = now.until(target, java.time.temporal.ChronoUnit.SECONDS);
                        if (seconds < 0) seconds += 86400;
                        if (seconds < minSeconds) minSeconds = seconds;
                    } catch (Exception ignored) {}
                }

                if (minSeconds > 0 && minSeconds <= 5400 && !sentWarnings.contains(90) && minSeconds > 5100) sendWarning(90);
                else if (minSeconds > 0 && minSeconds <= 900 && !sentWarnings.contains(15) && minSeconds > 600) sendWarning(15);
                else if (minSeconds > 0 && minSeconds <= 300 && !sentWarnings.contains(5) && minSeconds > 120) sendWarning(5);
                else if (minSeconds > 0 && minSeconds <= 60 && !sentWarnings.contains(1) && minSeconds > 10) sendWarning(1);

                if (minSeconds <= 0 && minSeconds > -5 && (activeBoss == null || activeBoss.isDead())) {
                    sentWarnings.clear();
                    Location loc = getSpawnLocation();
                    if (loc != null) {
                        spawnBoss(loc);
                    } else {
                        Bukkit.getConsoleSender().sendMessage(color("&c[CapMine Boss] XATO: Boss chiqadigan joy belgilanmagan! /cboss setspawn qiling."));
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void sendWarning(int minutes) {
        String msg = getConfig().getString("warnings." + minutes);
        if (msg != null) Bukkit.broadcastMessage(color(msg));
        sentWarnings.add(minutes);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) || !sender.isOp() && !sender.hasPermission("capmine.boss.admin")) {
            sender.sendMessage(color(getConfig().getString("messages.prefix") + getConfig().getString("messages.no-permission")));
            return true;
        }

        Player player = (Player) sender;

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(color("&a[CapMine] Config yangilandi!"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("setspawn")) {
            getConfig().set("boss-location.world", player.getWorld().getName());
            getConfig().set("boss-location.x", player.getLocation().getX());
            getConfig().set("boss-location.y", player.getLocation().getY());
            getConfig().set("boss-location.z", player.getLocation().getZ());
            saveConfig();
            sender.sendMessage(color(getConfig().getString("messages.prefix") + getConfig().getString("messages.location-set")));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("spawn")) {
            if (activeBoss != null && !activeBoss.isDead()) {
                sender.sendMessage(color(getConfig().getString("messages.prefix") + getConfig().getString("messages.already-spawned")));
                return true;
            }
            Location loc = getSpawnLocation();
            if (loc != null) {
                spawnBoss(loc);
            } else {
                sender.sendMessage(color(getConfig().getString("messages.prefix") + getConfig().getString("messages.location-not-set")));
            }
            return true;
        }

        sender.sendMessage(color("&e[CapMine Boss] Buyruqlar: /cboss setspawn, /cboss spawn, /cboss reload"));
        return true;
    }

    private Location getSpawnLocation() {
        String worldName = getConfig().getString("boss-location.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        
        double x = getConfig().getDouble("boss-location.x");
        double y = getConfig().getDouble("boss-location.y");
        double z = getConfig().getDouble("boss-location.z");
        
        return new Location(world, x, y, z);
    }

    private void spawnBoss(Location loc) {
        damageMap.clear();
        isEnraged = false;
        enrageTimer = 30;

        // Eski BossBar qolib ketgan bo'lsa o'chirish
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        String entityTypeStr = getConfig().getString("boss.entity-type", "ZOMBIE_VILLAGER");
        EntityType type;
        try {
            type = EntityType.valueOf(entityTypeStr.toUpperCase());
        } catch (Exception e) {
            type = EntityType.ZOMBIE_VILLAGER; // Xato bo'lsa default
        }

        LivingEntity boss = (LivingEntity) loc.getWorld().spawnEntity(loc, type);
        activeBoss = boss;

        // 1 soniyalik himoya (darhol o'lib qolmasligi uchun)
        boss.setInvulnerable(true);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (boss.isValid()) boss.setInvulnerable(false);
        }, 20L);

        double health = getConfig().getDouble("boss.health", 1500.0);
        boss.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
        boss.setHealth(health);
        boss.setCustomName(color(getConfig().getString("boss.name", "&cBoss")));
        boss.setCustomNameVisible(true);
        boss.setRemoveWhenFarAway(false);
        boss.setPersistent(true); // Server restart bo'lsa ham o'chib qolmasin

        boss.getEquipment().setHelmet(new ItemStack(Material.valueOf(getConfig().getString("boss.helmet", "NETHERITE_HELMET"))));
        boss.getEquipment().setChestplate(new ItemStack(Material.valueOf(getConfig().getString("boss.chestplate", "NETHERITE_CHESTPLATE"))));
        boss.getEquipment().setLeggings(new ItemStack(Material.valueOf(getConfig().getString("boss.leggings", "NETHERITE_LEGGINGS"))));
        boss.getEquipment().setBoots(new ItemStack(Material.valueOf(getConfig().getString("boss.boots", "NETHERITE_BOOTS"))));
        boss.getEquipment().setItemInMainHand(new ItemStack(Material.valueOf(getConfig().getString("boss.weapon", "NETHERITE_AXE"))));
        
        boss.getEquipment().setHelmetDropChance(0f); boss.getEquipment().setChestplateDropChance(0f);
        boss.getEquipment().setLeggingsDropChance(0f); boss.getEquipment().setBootsDropChance(0f);
        boss.getEquipment().setItemInMainHandDropChance(0f);

        bossBar = Bukkit.createBossBar(getBossBarTitle(health, health), BarColor.GREEN, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);
        
        Bukkit.broadcastMessage(color(getConfig().getString("messages.prefix") + getConfig().getString("messages.spawned")));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (activeBoss == null || !event.getEntity().equals(activeBoss)) return;

        Player damager = null;
        if (event.getDamager() instanceof Player) damager = (Player) event.getDamager();
        else if (event.getDamager() instanceof org.bukkit.entity.Projectile) {
            if (((org.bukkit.entity.Projectile) event.getDamager()).getShooter() instanceof Player) 
                damager = (Player) ((org.bukkit.entity.Projectile) event.getDamager()).getShooter();
        }

        if (damager != null) {
            damageMap.put(damager.getUniqueId(), damageMap.getOrDefault(damager.getUniqueId(), 0.0) + event.getFinalDamage());
        }

        double currentHealth = activeBoss.getHealth() - event.getFinalDamage();
        if (currentHealth < 0) currentHealth = 0;
        double maxHealth = activeBoss.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        
        if (bossBar != null) {
            bossBar.setProgress(currentHealth / maxHealth);
            bossBar.setTitle(getBossBarTitle(currentHealth, maxHealth));
        }

        double enrageHealth = getConfig().getDouble("boss.enrage-health", 755.0);
        if (!isEnraged && currentHealth <= enrageHealth) {
            isEnraged = true;
            bossBar.setColor(BarColor.RED);
            startEnrageTimer();
            Bukkit.broadcastMessage(color("&#FF0000BOSS G'AZABLANDI!"));
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 1f);
        }
    }

    private void startEnrageTimer() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (activeBoss == null || activeBoss.isDead() || !isEnraged) { this.cancel(); return; }
                if (enrageTimer > 0) {
                    String enrageMsg = getConfig().getString("messages.bossbar-enrage", "&#FF0000PvP: {seconds}");
                    bossBar.setTitle(color(enrageMsg.replace("{seconds}", String.valueOf(enrageTimer))));
                    enrageTimer--;
                } else {
                    bossBar.setTitle(getBossBarTitle(activeBoss.getHealth(), activeBoss.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()) + " &#FF0000[PVP YOQILDI]");
                    this.cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (activeBoss == null || !event.getEntity().equals(activeBoss)) return;

        event.getDrops().clear();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        List<Map.Entry<UUID, Double>> sorted = damageMap.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()).limit(3).collect(Collectors.toList());

        String top1 = "Yo'q", top2 = "Yo'q", top3 = "Yo'q";
        double dmg1 = 0, dmg2 = 0, dmg3 = 0;
        int tok1 = 0, tok2 = 0, tok3 = 0;

        if (sorted.size() > 0) {
            top1 = Bukkit.getOfflinePlayer(sorted.get(0).getKey()).getName();
            dmg1 = Math.round(sorted.get(0).getValue() * 100.0) / 100.0;
            tok1 = getConfig().getInt("rewards.top-1", 5000);
            giveReward(top1, tok1);
        }
        if (sorted.size() > 1) {
            top2 = Bukkit.getOfflinePlayer(sorted.get(1).getKey()).getName();
            dmg2 = Math.round(sorted.get(1).getValue() * 100.0) / 100.0;
            tok2 = getConfig().getInt("rewards.top-2", 3000);
            giveReward(top2, tok2);
        }
        if (sorted.size() > 2) {
            top3 = Bukkit.getOfflinePlayer(sorted.get(2).getKey()).getName();
            dmg3 = Math.round(sorted.get(2).getValue() * 100.0) / 100.0;
            tok3 = getConfig().getInt("rewards.top-3", 2000);
            giveReward(top3, tok3);
        }

        List<String> messages = getConfig().getStringList("messages.death-broadcast");
        for (String msg : messages) {
            if (msg == null) continue;
            msg = msg.replace("%top1%", top1).replace("%top2%", top2).replace("%top3%", top3)
                     .replace("%damage1%", String.valueOf(dmg1)).replace("%damage2%", String.valueOf(dmg2)).replace("%damage3%", String.valueOf(dmg3))
                     .replace("%token1%", String.valueOf(tok1)).replace("%token2%", String.valueOf(tok2)).replace("%token3%", String.valueOf(tok3));
            Bukkit.broadcastMessage(color(msg));
        }

        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        activeBoss = null;
    }

    private void giveReward(String playerName, int amount) {
        if (playerName.equals("Yo'q")) return;
        String cmd = getConfig().getString("rewards.give-command", "points give %player% %amount%")
                .replace("%player%", playerName).replace("%amount%", String.valueOf(amount));
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    private String getBossBarTitle(double current, double max) {
        String title = getConfig().getString("messages.bossbar-title", "Boss [{current}/{max}]");
        return color(title.replace("{current}", String.valueOf(Math.round(current))).replace("{max}", String.valueOf(Math.round(max))));
    }

    private String color(String text) {
        if (text == null) return "";
        Pattern pattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) replacement.append("§").append(c);
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);
        return net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
            }
