package kr.wildffa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 게임 진행 전체를 관리합니다.
 * - 1일차 시작: 자기장(1000x1000) 생성
 * - 15일차: 엔드 개방
 * - 20일차: 목숨 3개 제한 시작
 * - 30일차: 데스매치 (자기장이 0 0까지 축소)
 * - 시작 시 0 0에 엔드 포탈 생성, 참가자는 자기장 안 무작위 위치로 텔레포트
 * - 자연사: 인벤토리 보존 + 화염 저항 + 일정 시간 이동 불가
 */
public final class GameManager {

    private static final long TICKS_PER_DAY = 24000L;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final WildFFA plugin;
    private final File dataFile;

    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> eliminated = new HashSet<>();
    private final Map<UUID, Integer> lives = new HashMap<>();
    private final Map<UUID, Long> frozenUntil = new HashMap<>();
    private final Set<UUID> pendingNaturalDeath = new HashSet<>();

    private boolean running;
    private boolean endOpen;
    private boolean livesLimited;
    private boolean deathmatch;
    private BukkitTask task;

    public GameManager(WildFFA plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    public static Component c(String legacy) {
        return LEGACY.deserialize(legacy);
    }

    // ------------------------------------------------------------------
    // 설정값
    // ------------------------------------------------------------------

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private int endOpenDay() {
        return cfg().getInt("days.end-open", 15);
    }

    private int livesLimitDay() {
        return cfg().getInt("days.lives-limit", 20);
    }

    private int deathmatchDay() {
        return cfg().getInt("days.deathmatch", 30);
    }

    private int maxLives() {
        return cfg().getInt("lives", 3);
    }

    private double borderSize() {
        return cfg().getDouble("border.size", 1000.0);
    }

    private int freezeSeconds() {
        return cfg().getInt("natural-death.freeze-seconds", 100);
    }

    private int naturalDeathFromDay() {
        return cfg().getInt("natural-death.from-day", 1);
    }

    private boolean keepInventoryOnNaturalDeath() {
        return cfg().getBoolean("natural-death.keep-inventory", true);
    }

    private int fireResistanceSeconds() {
        return cfg().getInt("natural-death.fire-resistance-seconds", 120);
    }

    private int shrinkSeconds() {
        return cfg().getInt("deathmatch.shrink-seconds", 600);
    }

    private double deathmatchDamage() {
        return cfg().getDouble("deathmatch.border-damage", 2.0);
    }

    // ------------------------------------------------------------------
    // 기본 유틸
    // ------------------------------------------------------------------

    private World overworld() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) {
                return w;
            }
        }
        return Bukkit.getWorlds().get(0);
    }

    /** 현재 일차 (게임 시작 시 시간을 0으로 맞추므로 1일차부터 시작) */
    public int currentDay() {
        return (int) (overworld().getFullTime() / TICKS_PER_DAY) + 1;
    }

    private void broadcast(String legacy) {
        Bukkit.getServer().broadcast(c(legacy));
    }

    private String nameOf(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        String name = op.getName();
        return name != null ? name : id.toString().substring(0, 8);
    }

    private List<UUID> alive() {
        List<UUID> list = new ArrayList<>();
        for (UUID id : participants) {
            if (!eliminated.contains(id)) {
                list.add(id);
            }
        }
        return list;
    }

    private String phaseText() {
        if (deathmatch) {
            return "&4데스매치";
        }
        return endOpen ? "&5엔드 열림" : "&7엔드 닫힘";
    }

    public boolean isFrozen(UUID id) {
        Long until = frozenUntil.get(id);
        return running && until != null && until > System.currentTimeMillis();
    }

    /**
     * 안전한 지상 위치를 찾습니다. (물/용암 위는 피함)
     * jitter가 0보다 크면 실패 시 주변을 무작위로 다시 시도합니다.
     */
    private Location safeLocation(World world, double cx, double cz, double jitter) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 30; i++) {
            double x = cx;
            double z = cz;
            if (i > 0 && jitter > 0) {
                x += rnd.nextDouble(-jitter, jitter);
                z += rnd.nextDouble(-jitter, jitter);
            }
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            int y = world.getHighestBlockYAt(bx, bz);
            if (!world.getBlockAt(bx, y, bz).isLiquid()) {
                return new Location(world, bx + 0.5, y + 1, bz + 0.5);
            }
        }
        int bx = (int) Math.floor(cx);
        int bz = (int) Math.floor(cz);
        return new Location(world, bx + 0.5, world.getHighestBlockYAt(bx, bz) + 1, bz + 0.5);
    }

    /** 현재 자기장 안쪽의 무작위 위치 */
    private Location randomInsideBorder() {
        World ow = overworld();
        double r = Math.max(0.0, ow.getWorldBorder().getSize() / 2.0 - 3.0);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double x = r <= 0 ? 0 : rnd.nextDouble(-r, r);
        double z = r <= 0 ? 0 : rnd.nextDouble(-r, r);
        return safeLocation(ow, x, z, Math.min(20.0, r));
    }

    /** 물/용암이 아닌 지상 위치 (액체 위면 null) */
    private Location groundLocation(World world, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int y = world.getHighestBlockYAt(bx, bz);
        if (world.getBlockAt(bx, y, bz).isLiquid()) {
            return null;
        }
        return new Location(world, bx + 0.5, y + 1, bz + 0.5);
    }

    /**
     * 자기장(1000x1000) 안쪽의 무작위 시작 위치.
     * 가장자리와 중앙(엔드 포탈) 근처는 피하고, 다른 플레이어와 최소 거리를 두려고 시도합니다.
     */
    private Location randomStartLocation(World world, List<Location> taken) {
        double half = borderSize() / 2.0 - 30.0;
        if (half <= 0) {
            return world.getSpawnLocation();
        }
        double minDistance = cfg().getDouble("border.min-player-distance", 200.0);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int i = 0; i < 100; i++) {
            double x = rnd.nextDouble(-half, half);
            double z = rnd.nextDouble(-half, half);
            if (Math.hypot(x, z) < 50.0) {
                continue;
            }
            if (i < 60) {
                boolean tooClose = false;
                for (Location other : taken) {
                    if (Math.hypot(other.getX() - x, other.getZ() - z) < minDistance) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) {
                    continue;
                }
            }
            Location loc = groundLocation(world, x, z);
            if (loc != null) {
                return loc;
            }
        }
        return safeLocation(world, half / 2.0, half / 2.0, 40);
    }

    /** 0 0 위치에 엔드 포탈(프레임 5x5, 포탈 3x3)을 만들고 주변 7x7을 정리합니다. */
    private void buildEndPortal(World world) {
        int baseY = world.getHighestBlockYAt(0, 0);

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.getBlockAt(dx, baseY, dz).setType(Material.STONE_BRICKS, false);
                for (int dy = 1; dy <= 5; dy++) {
                    world.getBlockAt(dx, baseY + dy, dz).setType(Material.AIR, false);
                }
            }
        }

        int y = baseY + 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(dx, y, dz).setType(Material.END_PORTAL, false);
            }
        }
        for (int i = -1; i <= 1; i++) {
            setPortalFrame(world, i, y, -2, BlockFace.SOUTH);
            setPortalFrame(world, i, y, 2, BlockFace.NORTH);
            setPortalFrame(world, -2, y, i, BlockFace.EAST);
            setPortalFrame(world, 2, y, i, BlockFace.WEST);
        }
    }

    private void setPortalFrame(World world, int x, int y, int z, BlockFace facing) {
        BlockData data = Bukkit.createBlockData(Material.END_PORTAL_FRAME);
        if (data instanceof EndPortalFrame frame) {
            frame.setEye(true);
            frame.setFacing(facing);
        }
        world.getBlockAt(x, y, z).setBlockData(data, false);
    }

    // ------------------------------------------------------------------
    // 게임 시작 / 중지
    // ------------------------------------------------------------------

    /** @return 실패 사유 (성공하면 null) */
    public String start() {
        if (running) {
            return "이미 게임이 진행 중입니다. (/게임 중지 로 종료)";
        }

        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR) {
                players.add(p);
            }
        }
        if (players.isEmpty()) {
            return "참가할 플레이어가 없습니다. (관전 모드 플레이어는 제외됩니다)";
        }

        participants.clear();
        eliminated.clear();
        lives.clear();
        frozenUntil.clear();
        pendingNaturalDeath.clear();
        endOpen = false;
        livesLimited = false;
        deathmatch = false;

        World ow = overworld();
        ow.setFullTime(0L);
        applyBorders();

        if (cfg().getBoolean("end-portal.generate", true)) {
            buildEndPortal(ow);
        }

        boolean randomTeleport = cfg().getBoolean("border.random-teleport-on-start", true);
        List<Location> taken = new ArrayList<>();

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            participants.add(p.getUniqueId());
            p.setGameMode(GameMode.SURVIVAL);
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(p.getName());

            if (randomTeleport) {
                Location loc = randomStartLocation(ow, taken);
                taken.add(loc);
                p.teleportAsync(loc);
            }
        }

        running = true;

        int size = (int) borderSize();
        broadcast("&6&l===== 야생 1ㄷ1ㄷ1ㄷ1 시작! =====");
        broadcast("&e참가자: &f" + names);
        broadcast("&7자기장 &f" + size + "x" + size
                + " &7| 엔드 &f" + endOpenDay() + "일차"
                + " &7| 목숨 제한 &f" + livesLimitDay() + "일차(" + maxLives() + "개)"
                + " &7| 데스매치 &f" + deathmatchDay() + "일차");

        save();
        startTask();
        return null;
    }

    public boolean stop() {
        if (!running) {
            return false;
        }
        endGame("&7관리자가 게임을 중지했습니다.");
        return true;
    }

    private void applyBorders() {
        double size = borderSize();
        for (World w : Bukkit.getWorlds()) {
            WorldBorder b = w.getWorldBorder();
            b.reset();
            b.setCenter(0, 0);
            // 네더는 좌표가 1/8 비율이라 자기장도 1/8 크기로 맞춤
            b.setSize(w.getEnvironment() == World.Environment.NETHER ? Math.max(1.0, size / 8.0) : size);
        }
    }

    private void endGame(String message) {
        running = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        broadcast(message);

        for (World w : Bukkit.getWorlds()) {
            w.getWorldBorder().reset();
        }

        // 탈락해서 관전 중이던 플레이어는 생존 모드로 복귀 (오프라인이면 접속할 때 복귀)
        for (UUID id : new ArrayList<>(eliminated)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                restorePlayer(p);
                eliminated.remove(id);
            }
        }

        participants.clear();
        lives.clear();
        frozenUntil.clear();
        pendingNaturalDeath.clear();
        save();
    }

    private void restorePlayer(Player p) {
        p.setGameMode(GameMode.SURVIVAL);
        if (!p.isDead()) {
            p.teleportAsync(overworld().getSpawnLocation());
        }
    }

    // ------------------------------------------------------------------
    // 매초 진행 (일차 체크 / 액션바)
    // ------------------------------------------------------------------

    private void startTask() {
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        if (!running) {
            return;
        }
        int day = currentDay();

        if (!endOpen && day >= endOpenDay()) {
            endOpen = true;
            broadcast("&5&l엔드가 열렸습니다! &d(" + endOpenDay() + "일차) 이제 엔드로 갈 수 있습니다.");
            save();
        }
        if (!livesLimited && day >= livesLimitDay()) {
            limitLives();
        }
        if (!deathmatch && day >= deathmatchDay()) {
            startDeathmatch();
            if (!running) {
                return;
            }
        }
        updateActionBars(day);
    }

    private void limitLives() {
        livesLimited = true;
        int n = maxLives();
        for (UUID id : participants) {
            if (!eliminated.contains(id)) {
                lives.put(id, n);
            }
        }
        broadcast("&c&l" + livesLimitDay() + "일차! &c이제부터 목숨이 &f" + n + "개&c로 제한됩니다. 모두 0개가 되면 탈락합니다.");
        save();
    }

    private void startDeathmatch() {
        deathmatch = true;

        // 다른 차원에 있는 생존자는 오버월드로 이동
        for (UUID id : alive()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getWorld().getEnvironment() != World.Environment.NORMAL) {
                p.teleportAsync(randomInsideBorder());
            }
        }

        WorldBorder b = overworld().getWorldBorder();
        b.setCenter(0, 0);
        b.setDamageBuffer(0);
        b.setDamageAmount(deathmatchDamage());
        b.setSize(1.0, shrinkSeconds());

        broadcast("&4&l데스매치 시작! &c자기장이 &f" + shrinkSeconds() + "초&c 동안 중앙(0, 0)으로 좁혀집니다.");
        Title title = Title.title(c("&4&l데스매치 시작!"), c("&c자기장이 중앙으로 좁혀집니다"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
        }
        save();
    }

    private void updateActionBars(int day) {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!participants.contains(id)) {
                continue;
            }
            if (eliminated.contains(id)) {
                p.sendActionBar(c("&7탈락 · 관전 중"));
                continue;
            }

            Long until = frozenUntil.get(id);
            if (until != null) {
                if (until > now) {
                    long sec = (until - now + 999) / 1000;
                    p.sendActionBar(c("&c움직일 수 없음 &f" + sec + "초"));
                    continue;
                }
                frozenUntil.remove(id);
                p.sendMessage(c("&a이제 움직일 수 있습니다!"));
                save();
            }

            String livesText = livesLimited
                    ? "&c목숨 &f" + lives.getOrDefault(id, maxLives())
                    : "&a목숨 무제한";
            p.sendActionBar(c("&e" + day + "일차 &8| " + phaseText() + " &8| " + livesText));
        }
    }

    // ------------------------------------------------------------------
    // 이벤트 처리
    // ------------------------------------------------------------------

    public void handleDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        UUID id = p.getUniqueId();
        if (!running || !participants.contains(id) || eliminated.contains(id)) {
            return;
        }

        // 다른 플레이어에게 죽은 게 아니면 "자연사" (낙사, 용암, 몹 등)
        boolean natural = p.getKiller() == null && currentDay() >= naturalDeathFromDay();

        if (livesLimited) {
            int left = lives.getOrDefault(id, maxLives()) - 1;
            lives.put(id, left);
            if (left <= 0) {
                // 마지막 목숨: 탈락 (아이템은 그대로 드롭)
                eliminated.add(id);
                broadcast("&c&l" + p.getName() + " 님이 모든 목숨을 잃고 탈락했습니다!");
                save();
                checkWinner();
                return;
            }
            broadcast("&c" + p.getName() + " 님 사망! 남은 목숨: &f" + left);
        }

        if (natural) {
            if (keepInventoryOnNaturalDeath()) {
                event.setKeepInventory(true);
                event.setKeepLevel(true);
                event.getDrops().clear();
                event.setDroppedExp(0);
            }
            pendingNaturalDeath.add(id);
        }
        save();
    }

    public void handleRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        if (!running || !participants.contains(id)) {
            return;
        }

        if (eliminated.contains(id)) {
            Bukkit.getScheduler().runTask(plugin, () -> p.setGameMode(GameMode.SPECTATOR));
            return;
        }

        if (deathmatch) {
            event.setRespawnLocation(randomInsideBorder());
        }

        if (pendingNaturalDeath.remove(id)) {
            if (keepInventoryOnNaturalDeath()) {
                p.sendMessage(c("&a자연사로 인벤토리가 보존되었습니다."));
            }

            int freeze = freezeSeconds();
            if (freeze > 0) {
                frozenUntil.put(id, System.currentTimeMillis() + freeze * 1000L);
                p.sendMessage(c("&c자연사 패널티! &f" + freeze + "초&c 동안 움직일 수 없습니다."));
            }

            int fire = fireResistanceSeconds();
            if (fire > 0) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, fire * 20, 0)));
                p.sendMessage(c("&6화염 저항 &f" + fire + "초&6가 적용됩니다."));
            }
            save();
        }
    }

    public void handleJoin(Player p) {
        UUID id = p.getUniqueId();

        if (!running) {
            // 지난 게임에서 탈락/관전 상태였다면 복귀
            if (eliminated.remove(id)) {
                restorePlayer(p);
                save();
            }
            return;
        }

        if (!participants.contains(id)) {
            eliminated.add(id);
            p.setGameMode(GameMode.SPECTATOR);
            p.sendMessage(c("&7이미 진행 중인 게임이라 관전 모드로 전환되었습니다."));
            save();
            return;
        }
        if (eliminated.contains(id)) {
            p.setGameMode(GameMode.SPECTATOR);
            return;
        }
        if (deathmatch && p.getWorld().getEnvironment() != World.Environment.NORMAL) {
            p.teleportAsync(randomInsideBorder());
        }
    }

    public void handlePortal(PlayerPortalEvent event) {
        if (!running) {
            return;
        }
        Player p = event.getPlayer();
        if (!participants.contains(p.getUniqueId())) {
            return;
        }

        TeleportCause cause = event.getCause();
        if (cause != TeleportCause.NETHER_PORTAL && cause != TeleportCause.END_PORTAL) {
            return;
        }

        if (deathmatch) {
            event.setCancelled(true);
            p.sendMessage(c("&c데스매치 중에는 다른 차원으로 이동할 수 없습니다."));
            return;
        }

        World from = event.getFrom().getWorld();
        if (cause == TeleportCause.END_PORTAL
                && !endOpen
                && from != null
                && from.getEnvironment() == World.Environment.NORMAL) {
            event.setCancelled(true);
            p.sendMessage(c("&c엔드는 &f" + endOpenDay() + "일차&c부터 입장할 수 있습니다. (현재 " + currentDay() + "일차)"));
        }
    }

    private void checkWinner() {
        List<UUID> alive = alive();
        if (alive.size() > 1) {
            return;
        }
        if (alive.size() == 1) {
            endGame("&6&l최후의 생존자: &e&l" + nameOf(alive.get(0)) + " &6&l님 우승!");
        } else {
            endGame("&7생존자가 없어 게임이 종료되었습니다.");
        }
    }

    // ------------------------------------------------------------------
    // 상태 출력
    // ------------------------------------------------------------------

    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        if (!running) {
            out.add("&7진행 중인 게임이 없습니다.");
            return out;
        }
        out.add("&6현재 &e" + currentDay() + "일차 &7(" + phaseText() + "&7)");
        for (UUID id : participants) {
            String name = nameOf(id);
            if (eliminated.contains(id)) {
                out.add("&8- " + name + " (탈락)");
            } else {
                String lifeText = livesLimited ? String.valueOf(lives.getOrDefault(id, maxLives())) : "무제한";
                out.add("&f- " + name + " &7목숨: &f" + lifeText);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 저장 / 불러오기 (서버를 껐다 켜도 게임이 이어지도록)
    // ------------------------------------------------------------------

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("running", running);
        y.set("endOpen", endOpen);
        y.set("livesLimited", livesLimited);
        y.set("deathmatch", deathmatch);

        List<String> parts = new ArrayList<>();
        for (UUID id : participants) {
            parts.add(id.toString());
        }
        y.set("participants", parts);

        List<String> dead = new ArrayList<>();
        for (UUID id : eliminated) {
            dead.add(id.toString());
        }
        y.set("eliminated", dead);

        for (Map.Entry<UUID, Integer> e : lives.entrySet()) {
            y.set("lives." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, Long> e : frozenUntil.entrySet()) {
            y.set("frozen." + e.getKey(), e.getValue());
        }

        try {
            y.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("data.yml 저장 실패: " + ex.getMessage());
        }
    }

    public void load() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        running = y.getBoolean("running", false);
        endOpen = y.getBoolean("endOpen", false);
        livesLimited = y.getBoolean("livesLimited", false);
        deathmatch = y.getBoolean("deathmatch", false);

        for (String s : y.getStringList("participants")) {
            try {
                participants.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String s : y.getStringList("eliminated")) {
            try {
                eliminated.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }

        ConfigurationSection livesSec = y.getConfigurationSection("lives");
        if (livesSec != null) {
            for (String key : livesSec.getKeys(false)) {
                try {
                    lives.put(UUID.fromString(key), livesSec.getInt(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection frozenSec = y.getConfigurationSection("frozen");
        if (frozenSec != null) {
            for (String key : frozenSec.getKeys(false)) {
                try {
                    frozenUntil.put(UUID.fromString(key), frozenSec.getLong(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        if (running) {
            startTask();
            plugin.getLogger().info("진행 중이던 게임을 이어서 진행합니다.");
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        save();
    }
}
