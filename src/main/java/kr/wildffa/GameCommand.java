package kr.wildffa;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public final class GameCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "wildffa.admin";
    private static final List<String> SUBCOMMANDS = List.of("시작", "중지", "상태");

    private final GameManager game;

    public GameCommand(GameManager game) {
        this.game = game;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(GameManager.c("&e사용법: &f/게임 <시작|중지|상태>"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "시작", "start" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    sender.sendMessage(GameManager.c("&c권한이 없습니다."));
                    return true;
                }
                String error = game.start();
                if (error != null) {
                    sender.sendMessage(GameManager.c("&c" + error));
                }
            }
            case "중지", "stop" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    sender.sendMessage(GameManager.c("&c권한이 없습니다."));
                    return true;
                }
                if (!game.stop()) {
                    sender.sendMessage(GameManager.c("&c진행 중인 게임이 없습니다."));
                }
            }
            case "상태", "status" -> {
                for (String line : game.statusLines()) {
                    sender.sendMessage(GameManager.c(line));
                }
            }
            default -> sender.sendMessage(GameManager.c("&e사용법: &f/게임 <시작|중지|상태>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0])) {
                    result.add(sub);
                }
            }
        }
        return result;
    }
}
