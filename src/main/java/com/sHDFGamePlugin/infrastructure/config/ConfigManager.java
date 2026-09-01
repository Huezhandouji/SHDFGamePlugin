package com.sHDFGamePlugin.infrastructure.config;

import com.sHDFGamePlugin.domain.sector.Sector;
import com.sHDFGamePlugin.infrastructure.regionExpression.CubeRegion;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

/**
 * 配置管理（单例）：加载 config.yml（全局设置）与 maps.yml（地图/据点/炸弹配置）。
 * <p>
 * 单张地图配置出错时跳过该地图并记录日志，不影响其他地图加载。
 */
public class ConfigManager {

    private static final ConfigManager INSTANCE = new ConfigManager();

    private FileConfiguration mainConfig;
    private FileConfiguration mapConfigFile;
    private JavaPlugin plugin;
    private final Map<String, MapConfig> mapConfigs = new HashMap<>();

    private String selectedMapId;

    private ConfigManager(){}

    public static ConfigManager getInstance() {
        return INSTANCE;
    }

    public void init(JavaPlugin plugin) {
        this.plugin = plugin;
        //config.yml
        plugin.saveDefaultConfig();
        mainConfig = plugin.getConfig();

        //maps.yml
        File mapsFile = new File(plugin.getDataFolder(), "maps.yml");
        if(!mapsFile.exists()){
            plugin.saveResource("maps.yml", false);
        }
        mapConfigFile = YamlConfiguration.loadConfiguration(mapsFile);

        loadMapConfigs();

        List<String> mapNames = getMapNames();
        if(!mapNames.isEmpty()){
            selectedMapId = mapNames.getFirst();
        }

        //必要字段校验：缺失则抛异常，插件启动失败
        validateRequiredFields();

        plugin.getLogger().info("Configuration loaded!");
    }

    /**
     * 重新加载 config.yml 与 maps.yml（/sg config reload 调用）。
     * <p>
     * 单张地图配置出错时仍由 loadMapConfigs 跳过并记录日志；
     * 当前选中的地图若已不存在，则回退到第一张可用地图。
     */
    public void reload(){
        //config.yml
        plugin.reloadConfig();
        mainConfig = plugin.getConfig();

        //maps.yml
        File mapsFile = new File(plugin.getDataFolder(), "maps.yml");
        mapConfigFile = YamlConfiguration.loadConfiguration(mapsFile);
        loadMapConfigs();

        //保持当前选中的地图；若已被删除则回退到第一张可用地图
        if(selectedMapId == null || !mapConfigs.containsKey(selectedMapId)){
            List<String> mapNames = getMapNames();
            selectedMapId = mapNames.isEmpty() ? null : mapNames.getFirst();
        }

        //必要字段校验：缺失则抛异常（由 /sg config reload 捕获并提示）
        validateRequiredFields();

        plugin.getLogger().info("Configuration reloaded!");
    }

    /**
     * 校验必要配置字段；缺失或未填写时抛出异常，使插件启动失败（fail-fast）。
     * <p>
     * 由 init() / reload() 调用。maps.yml 中单张地图的字段错误仍按"跳过该地图"处理。
     */
    private void validateRequiredFields(){
        //waiting.mode
        String mode = mainConfig.getString("waiting.mode", null);
        if(mode == null || mode.isEmpty()){
            throw new IllegalStateException("Missing required config: waiting.mode (值为 require_ready 或 auto)");
        }
        if(!mode.equalsIgnoreCase("require_ready") && !mode.equalsIgnoreCase("auto")){
            throw new IllegalStateException("Invalid waiting.mode: '" + mode + "' (必须为 require_ready 或 auto)");
        }

        //waiting.countdown_time
        if(!mainConfig.contains("waiting.countdown_time") || mainConfig.getInt("waiting.countdown_time", 0) <= 0){
            throw new IllegalStateException("Missing or invalid required config: waiting.countdown_time (必须 > 0)");
        }

        //waiting.lobby_spawnpoint
        if(parseVector(mainConfig.getConfigurationSection("waiting.lobby_spawnpoint")) == null){
            throw new IllegalStateException("Missing required config: waiting.lobby_spawnpoint");
        }

        //role_selection.duration
        if(!mainConfig.contains("role_selection.duration") || mainConfig.getInt("role_selection.duration", -1) < 0){
            throw new IllegalStateException("Missing or invalid required config: role_selection.duration (必须 >= 0)");
        }

        //role_selection.attacker_spawnpoint / defender_spawnpoint
        if(parseVector(mainConfig.getConfigurationSection("role_selection.attacker_spawnpoint")) == null){
            throw new IllegalStateException("Missing required config: role_selection.attacker_spawnpoint");
        }
        if(parseVector(mainConfig.getConfigurationSection("role_selection.defender_spawnpoint")) == null){
            throw new IllegalStateException("Missing required config: role_selection.defender_spawnpoint");
        }

        //地图列表与已加载地图
        if(getMapNames().isEmpty()){
            throw new IllegalStateException("No maps configured: config.yml 的 maps 列表为空");
        }
        if(mapConfigs.isEmpty()){
            throw new IllegalStateException("No map loaded successfully: maps.yml 中没有任何地图加载成功");
        }
    }

    public MapConfig getSelectedMapConfig(){
        if(selectedMapId == null) return null;
        return mapConfigs.get(selectedMapId);
    }

    public void setSelectedMapId(String mapId){
        if(mapConfigs.containsKey(mapId)){
            this.selectedMapId = mapId;
        }
    }

    //全局配置
    public int getMinPopulationPerSide(){
        return mainConfig.getInt("waiting.min_population_per_side", 1);
    }

    public int getMaxSideDiff(){
        return mainConfig.getInt("waiting.max_side_diff", 3);
    }

    public int getMaxPopulation(){
        return mainConfig.getInt("waiting.max_population", 16);
    }

    public boolean isDefaultSpectatorOrUnknown(){
        return mainConfig.getBoolean("waiting.default_side_after_join_spectator_or_unknown", true);
    }

    /** 开局模式："require_ready"(除观战者外全员准备) 或 "auto"(无需准备)；未知值按 require_ready 处理 */
    public String getStartMode(){
        return mainConfig.getString("waiting.mode", "require_ready");
    }

    /** 是否为"需全员准备"模式 */
    public boolean isRequireReadyMode(){
        return !"auto".equalsIgnoreCase(getStartMode());
    }

    /** 准备阶段倒计时时长（tick） */
    public int getCountdownTime(){
        return mainConfig.getInt("waiting.countdown_time", 200);
    }

    /** 等待阶段所在世界 */
    public String getWaitingWorld(){
        return mainConfig.getString("waiting.world", "world");
    }

    /** 角色选择倒计时时长（tick），0 = 必须全部手动选完 */
    public int getRoleSelectionDuration(){
        return mainConfig.getInt("role_selection.duration", 600);
    }

    /** 角色选择阶段所在世界 */
    public String getRoleSelectionWorld(){
        return mainConfig.getString("role_selection.world", "world");
    }

    public boolean isAllowDuplicateRoles(){
        return mainConfig.getBoolean("playing.allow_duplicate_roles", false);
    }

    public List<String> getMapNames(){
        return mainConfig.getStringList("maps");
    }

    //地图配置
    public MapConfig getMapConfig(String id){
        return mapConfigs.get(id);
    }

    public Map<String , MapConfig> getAllMapConfigs(){
        return Collections.unmodifiableMap(mapConfigs);
    }

    //当前选中地图的观战者出生点
    public Vector getSpectatorSpawnpoint(){
        MapConfig selected = getSelectedMapConfig();
        return selected != null ? selected.getSpectatorSpawnpoint() : null;
    }

    //指定地图的观战者出生点
    public Vector getSpectatorSpawnpoint(String mapId){
        MapConfig config = mapConfigs.get(mapId);
        return config != null ? config.getSpectatorSpawnpoint() : null;
    }

    //大厅出生点（等待阶段）
    public Vector getLobbySpawnpoint(){
        return parseVector(mainConfig.getConfigurationSection("waiting.lobby_spawnpoint"));
    }

    //进攻方出生点（角色选择阶段）
    public Vector getRoleSelectionAttackerSpawnpoint(){
        return parseVector(mainConfig.getConfigurationSection("role_selection.attacker_spawnpoint"));
    }

    //防守方出生点（角色选择阶段）
    public Vector getRoleSelectionDefenderSpawnpoint(){
        return parseVector(mainConfig.getConfigurationSection("role_selection.defender_spawnpoint"));
    }

    private void loadMapConfigs(){
        mapConfigs.clear();
        ConfigurationSection mapsSection = mapConfigFile.getConfigurationSection("maps");
        if(mapsSection == null) return;

        for(String mapId : mapsSection.getKeys(false)){
            try{
                loadMapConfig(mapId, mapsSection.getConfigurationSection(mapId));
            }
            catch (Exception e){
                //该地图配置出错：跳过这张地图并记录日志，不影响其他地图加载
                plugin.getLogger().warning("Failed to load map config '" + mapId + "', this map will be skipped. Reason: " + e.getMessage());
            }
        }
    }

    //加载单张地图；任一步骤出错则抛出异常，由 loadMapConfigs 捕获后跳过该地图
    private void loadMapConfig(String mapId, ConfigurationSection mapSection){
        if(mapSection == null) return;

        String name = mapSection.getString("name", mapId);
        String description = mapSection.getString("description", "No description provided.");
        String icon = mapSection.getString("icon", "PAPER");
        int maxTickets = mapSection.getInt("max_tickets", 50);
        int initialTickets = mapSection.getInt("initial_tickets", 100);
        if(maxTickets < 0 || initialTickets < 0){
            throw new IllegalStateException("Max and initial number of tickets cannot be nagative!");
        }
        if(maxTickets < initialTickets) initialTickets = maxTickets;
        int attackerRespawnTime = mapSection.getInt("attacker_respawn_time", 40);
        int defenderRespawnTime = mapSection.getInt("defender_respawn_time", 80);
        if(attackerRespawnTime < 0 || defenderRespawnTime < 0){
            throw new IllegalStateException("Attacker respawn time or defender respawn time cannot be negative!");
        }
        String world =  mapSection.getString("world", "overworld");
        Vector spectatorSpawnpoint = parseVector(mapSection.getConfigurationSection("spectator_spawnpoint"));

        Map<String, List<String>> roles = loadRoles(mapSection);
        List<String> attackerRoles = roles.getOrDefault("attacker", new ArrayList<>());
        List<String> defenderRoles = roles.getOrDefault("defender", new ArrayList<>());

        List<Sector> sectors = loadSectors(mapSection);

        mapConfigs.put(mapId, new MapConfig(
                mapId, name, description, icon, maxTickets, initialTickets,
                attackerRespawnTime, defenderRespawnTime,
                world, spectatorSpawnpoint, attackerRoles, defenderRoles, sectors
        ));
    }

    private Map<String, List<String>> loadRoles(ConfigurationSection mapSection){
        Map<String, List<String>> roles = new HashMap<>();
        ConfigurationSection rolesSection = mapSection.getConfigurationSection("roles");
        if(rolesSection == null) return roles;

        if(rolesSection.getKeys(false).contains("attacker")){
            roles.put("attacker", rolesSection.getStringList("attacker"));
        }

        if(rolesSection.getKeys(false).contains("defender")){
            roles.put("defender", rolesSection.getStringList("defender"));
        }

        return roles;


    }

    private List<Sector> loadSectors(ConfigurationSection mapSection){
        List<Sector> sectors = new ArrayList<>();
        List<Map<?, ?>> objectives = mapSection.getMapList("objectives");
        for(Map<?, ?> obj : objectives){
            // 递归转换为 ConfigurationSection
            ConfigurationSection sectorSection = toConfigurationSection(obj);

            String id = sectorSection.getString("id");
            String name = sectorSection.getString("name", id);
            int timeLimit = sectorSection.getInt("time_limit", 2400);
            if(timeLimit < 0){
                throw new IllegalStateException("Time limit cannot be negative!");
            }
            int ticketReward = sectorSection.getInt("ticket_reward", 20);

            List<BombConfig> bombs = parseBombs(sectorSection);

            CubeRegion attackerSpawn = parseCubeRegion(sectorSection.getConfigurationSection("attacker_spawn_region"));
            CubeRegion defenderSpawn = parseCubeRegion(sectorSection.getConfigurationSection("defender_spawn_region"));
            CubeRegion attackerActive = parseCubeRegion(sectorSection.getConfigurationSection("attacker_active_region"));
            CubeRegion defenderActive = parseCubeRegion(sectorSection.getConfigurationSection("defender_active_region"));

            Sector sector = Sector.Builder.create()
                    .id(id)
                    .name(Component.text(name))
                    .timeLimit(timeLimit)
                    .ticketReward(ticketReward)
                    .bombs(bombs)
                    .attackerSpawnRegion(attackerSpawn)
                    .defenderSpawnRegion(defenderSpawn)
                    .attackerActiveRegion(attackerActive)
                    .defenderActiveRegion(defenderActive)
                    .build();

            sectors.add(sector);
        }
        return sectors;
    }

    /**
     * 递归将 Map 转换为 MemoryConfiguration，使嵌套的 Map 也能被正确读取为 ConfigurationSection。
     */
    private ConfigurationSection toConfigurationSection(Map<?, ?> map) {
        MemoryConfiguration section = new MemoryConfiguration();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nestedMap) {
                section.set(entry.getKey().toString(), toConfigurationSection(nestedMap));
            } else {
                section.set(entry.getKey().toString(), value);
            }
        }
        return section;
    }

    private List<BombConfig> parseBombs(ConfigurationSection objectiveSection){
        List<BombConfig> bombs = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        List<Map<?, ?>> bombList = objectiveSection.getMapList("bombs");
        for(Map<?, ?> obj : bombList){
            ConfigurationSection bombSection = toConfigurationSection(obj);

            String bombId = bombSection.getString("id");
            if(bombId == null || !seenIds.add(bombId)){
                throw new IllegalStateException("Bomb id is missing or duplicated in objective!");
            }
            String bombName = bombSection.getString("name", bombId);
            CubeRegion region = parseCubeRegion(bombSection.getConfigurationSection("region"));
            int plantTime = bombSection.getInt("plant_time", 80);
            int fuseTime = bombSection.getInt("fuse_time", 1200);
            int defuseTime = bombSection.getInt("defuse_time", 100);

            bombs.add(BombConfig.Builder.create()
                    .id(bombId)
                    .name(Component.text(bombName))
                    .region(region)
                    .plantTime(plantTime)
                    .fuseTime(fuseTime)
                    .defuseTime(defuseTime)
                    .build());
        }
        return bombs;
    }

    private CubeRegion parseCubeRegion(ConfigurationSection section){
        if(section == null) return null;
        Vector start = parseVector(section.getConfigurationSection("start"));
        Vector end = parseVector(section.getConfigurationSection("end"));
        if(start == null || end == null) return null;
        return CubeRegion.createFromCorners(start, end);
    }

    private Vector parseVector(ConfigurationSection section){
        if(section == null) return null;
        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        return new Vector(x, y, z);
    }
}
