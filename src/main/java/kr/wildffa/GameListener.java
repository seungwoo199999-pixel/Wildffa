package kr.wildffa;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

public final class GameListener implements Listener {

    private final GameManager game;

    public GameListener(GameManager game) {
        this.game = game;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        game.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        game.handleDeath(event);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        game.handleRespawn(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        game.handlePortal(event);
    }

    /** 자연사 패널티: 위치 이동은 막고, 시선(고개) 회전만 허용 */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!game.isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        Location fixed = from.clone();
        fixed.setYaw(to.getYaw());
        fixed.setPitch(to.getPitch());
        event.setTo(fixed);
    }

    /** 얼어있는 동안 엔더 진주, 후렴과(chorus fruit)로 도망치는 것도 방지 */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!game.isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }
        TeleportCause cause = event.getCause();
        if (cause == TeleportCause.ENDER_PEARL || cause == TeleportCause.CHORUS_FRUIT) {
            event.setCancelled(true);
        }
    }
}
