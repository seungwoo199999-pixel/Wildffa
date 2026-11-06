package kr.wildffa;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class WildFFA extends JavaPlugin {

    private GameManager game;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        game = new GameManager(this);
        game.load();

        GameCommand command = new GameCommand(game);
        PluginCommand pc = getCommand("game");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        getServer().getPluginManager().registerEvents(new GameListener(game), this);
        getLogger().info("WildFFA 활성화 완료");
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.shutdown();
        }
    }
}
