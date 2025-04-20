package us.ajg0702.queue.spigot;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;
import us.ajg0702.queue.api.spigot.AjQueueSpigotAPI;
import us.ajg0702.queue.spigot.api.QueueAddEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class InitialServerSelector implements Listener, CommandExecutor {

    private final SpigotMain plugin;

    public InitialServerSelector(SpigotMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (plugin.getMongoDatabase() == null || !plugin.getAConfig().getBoolean("initial-server-selector.enabled") || !(commandSender instanceof Player player)) {
            return false;
        }

        getLastPlayerServer(player.getUniqueId())
                .whenCompleteAsync(((lastPlayerServer, throwable) -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Failed to get last player server: " + throwable.getMessage());
                        player.sendMessage("Failed to get last server, please contact an admin.");
                        return;
                    }

                    if (lastPlayerServer == null) {
                        return;
                    }

                    Bukkit.getScheduler().runTask(
                            plugin,
                            () -> AjQueueSpigotAPI.getInstance().addToQueue(player.getUniqueId(), lastPlayerServer.serverName)
                    );
        }));

        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQueueAdd(QueueAddEvent event) {
        if (plugin.getMongoDatabase() == null) {
            return;
        }

        System.out.println("Updating last player server for " + event.getPlayer().getName() + " to " + event.getServer());
        UUID uniqueId = event.getPlayer().getUniqueId();
        CompletableFuture.runAsync(() -> updateLastPlayerServer(uniqueId, event.getServer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSelectServer(PlayerJoinEvent event) {
        if (plugin.getMongoDatabase() == null || !plugin.getAConfig().getBoolean("initial-server-selector.enabled")) {
            return;
        }

        getLastPlayerServer(event.getPlayer().getUniqueId())
                .whenCompleteAsync((lastPlayerServer, throwable) -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Failed to get last player server: " + throwable.getMessage());
                        return;
                    }

                    if (lastPlayerServer != null) {
                        if (plugin.getAConfig().getBoolean("initial-server-selector.send-to-last-server")) {
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                if (!event.getPlayer().isOnline()) {
                                    return;
                                }

                                AjQueueSpigotAPI.getInstance().addToQueue(event.getPlayer().getUniqueId(), lastPlayerServer.serverName);
                            }, 60L);
                        }

                        return;
                    }

                    String selectedServer = pickRandomServer();
                    plugin.getLogger().info("Selected server for " + event.getPlayer().getName() + ": " + selectedServer);
                    if (selectedServer != null) {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (!event.getPlayer().isOnline()) {
                                return;
                            }

                            AjQueueSpigotAPI.getInstance().addToQueue(event.getPlayer().getUniqueId(), selectedServer);
                        }, 60L);
                    }
        });
    }

    public void updateLastPlayerServer(UUID playerId, String serverName) {
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("This method must be called asynchronously");
        }

        if (plugin.getMongoDatabase() == null) {
            return;
        }

        MongoCollection<Document> collection = plugin.getMongoDatabase().getCollection("lastplayerserver");
        Document document = new Document("_id", playerId.toString())
                .append("serverName", serverName);

        collection.replaceOne(Filters.eq(playerId.toString()), document, new ReplaceOptions().upsert(true));
    }

    private CompletableFuture<LastPlayerServer> getLastPlayerServer(UUID playerId) {
        if (plugin.getMongoDatabase() == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            MongoCollection<Document> collection = plugin.getMongoDatabase().getCollection("lastplayerserver");
            Document document = collection.find(Filters.eq(playerId.toString())).first();

            if (document == null) {
                return null;
            }

            return new LastPlayerServer(
                    UUID.fromString(document.getString("_id")),
                    document.getString("serverName")
            );
        });
    }

    private String pickRandomServer() {
        Map<String, Double> servers = plugin.getAConfig().getStringList("initial-server-selector.servers")
                .stream().map(entry -> {
                    String[] split = entry.split(":");

                    return Map.entry(split[0], Double.parseDouble(split[1]));
                }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        double totalWeight = servers.values().stream().mapToDouble(Double::doubleValue).sum();
        double random = Math.random() * totalWeight;
        String selectedServer = null;

        for (Map.Entry<String, Double> entry : servers.entrySet()) {
            random -= entry.getValue();
            if (random <= 0) {
                selectedServer = entry.getKey();
                break;
            }
        }

        return selectedServer;
    }

    record LastPlayerServer(
            UUID playerId,
            String serverName
    ) {}
}
