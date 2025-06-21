package cn.nekopixel.mapbackup;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class Main extends JavaPlugin {
    private MapBackup mapBackup;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        List<String> worlds = getConfig().getStringList("worlds");
        if (worlds.isEmpty()) {
            getLogger().warning("配置文件中没有指定要备份的世界");
            return;
        }
        
        mapBackup = new MapBackup(this, worlds);
        if (!mapBackup.backupWorlds()) {
            getLogger().severe("世界备份失败，插件将被禁用！");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("加载完成！");
    }

    @Override
    public void onDisable() {
        if (mapBackup != null) {
            mapBackup.restoreWorlds();
        }
    }
}