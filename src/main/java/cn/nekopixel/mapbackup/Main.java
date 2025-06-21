package cn.nekopixel.mapbackup;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
    private MapBackup mapBackup;

    @Override
    public void onEnable() {
        mapBackup = new MapBackup(this);
        if (!mapBackup.backupWorld()) {
            getLogger().severe("世界备份失败，插件将被禁用！");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("加载完成！");
    }

    @Override
    public void onDisable() {
        if (mapBackup != null) {
            mapBackup.restoreWorld();
        }
    }
}