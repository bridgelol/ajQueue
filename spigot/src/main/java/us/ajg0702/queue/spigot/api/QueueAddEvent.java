package us.ajg0702.queue.spigot.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

public class QueueAddEvent extends PlayerEvent {

    private static final HandlerList handlers = new HandlerList();
    private final String server;


    public QueueAddEvent(@NotNull Player who, String server) {
        super(who);
        this.server = server;
    }

    public String getServer() {
        return server;
    }

    @NotNull
    public HandlerList getHandlers() {
        return handlers;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
