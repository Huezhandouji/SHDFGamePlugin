package com.sHDFGamePlugin.infrastructure.config;

import com.sHDFGamePlugin.domain.sector.Region;
import com.sHDFGamePlugin.domain.sector.Sector;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

public class ConfigManager {

    private static final ConfigManager INSTANCE = new ConfigManager();

    private FileConfiguration mainConfig;
    private FileConfiguration mapConfigFile;
    private final Map<String, MapConfig> mapConfigs = new HashMap<>();

    private String selectedMapId;

    private ConfigManager(){}

    public static ConfigManager getInstance() {
        return INSTANCE;
    }

    public void init(JavaPlugin plugin) {
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

        plugin.getLogger().info("Configuration loaded!");
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

    public boolean isRequireReady(){
        return mainConfig.getBoolean("waiting.require_ready", true);
    }

    public int getRespawnInvulnerability(){
        return mainConfig.getInt("playing.invulnerability", 60);
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

    private void loadMapConfigs(){
        mapConfigs.clear();
        ConfigurationSection mapsSection = mapConfigFile.getConfigurationSection("maps");
        if(mapsSection == null) return;

        for(String mapId : mapsSection.getKeys(false)){
            ConfigurationSection mapSection = mapsSection.getConfigurationSection(mapId);
            if(mapSection == null) continue;

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

            Map<String, List<String>> roles = loadRoles(mapSection);
            List<String> attackerRoles = roles.getOrDefault("attacker", new ArrayList<>());
            List<String> defenderRoles = roles.getOrDefault("defender", new ArrayList<>());

            List<Sector> sectors = loadSectors(mapSection);

            mapConfigs.put(mapId, new MapConfig(
                    mapId, name, description, icon, maxTickets, initialTickets,
                    attackerRespawnTime, defenderRespawnTime,
                    world, attackerRoles, defenderRoles, sectors
            ));

        }
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
            Region region = parseRegion(sectorSection.getConfigurationSection("region"));
            int preheatTime =  sectorSection.getInt("preheat_time", 40);
            int captureTime =  sectorSection.getInt("capture_time", 200);
            if(preheatTime < 0 || captureTime < 0){
                throw new IllegalStateException("Preheat and Capture time cannot be negative!");
            }
            int timeLimit =   sectorSection.getInt("time_limit", 2400);
            if(timeLimit < 0){
                throw new IllegalStateException("Time limit cannot be negative!");
            }
            int ticketReward = sectorSection.getInt("ticket_reward", 20);

            Region attackerSpawn = parseRegion(sectorSection.getConfigurationSection("attacker_spawn_region"));
            Region defenderSpawn = parseRegion(sectorSection.getConfigurationSection("defender_spawn_region"));
            Region attackerActive = parseRegion(sectorSection.getConfigurationSection("attacker_active_region"));
            Region defenderActive = parseRegion(sectorSection.getConfigurationSection("defender_active_region"));

            Sector sector = Sector.Builder.create()
                    .id(id)
                    .name(Component.text(name))
                    .region(region)
                    .preheatTime(preheatTime)
                    .captureTime(captureTime)
                    .timeLimit(timeLimit)
                    .ticketReward(ticketReward)
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

    private Region parseRegion(ConfigurationSection section){
        if(section == null) return null;
        Vector start = parseVector(section.getConfigurationSection("start"));
        Vector end = parseVector(section.getConfigurationSection("end"));
        if(start == null || end == null) return null;
        return Region.createFromCorners(start, end);
    }

    private Vector parseVector(ConfigurationSection section){
        if(section == null) return null;
        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        return new Vector(x, y, z);
    }
}
