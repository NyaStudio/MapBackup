package cn.nekopixel.mapbackup;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.List;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.ArrayList;

public class MapBackup {
    private final Plugin plugin;
    private final Logger logger;
    private final Path serverRoot;
    private final Path backupDir;
    private final List<String> worldsToBackup;

    public MapBackup(Main plugin, List<String> worlds) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.serverRoot = Bukkit.getWorldContainer().toPath();
        this.backupDir = serverRoot.resolve("backup");
        this.worldsToBackup = worlds;
    }

    public boolean backupWorlds() {
        try {
            if (!Files.exists(backupDir)) {
                logger.info("正在创建备份目录...");
                Files.createDirectory(backupDir);
            }

            logger.info("========== 世界状态检查 ==========");
            int existingWorlds = 0;
            int missingWorlds = 0;
            int backedUpWorlds = 0;
            
            for (String worldName : worldsToBackup) {
                Path worldDir = serverRoot.resolve(worldName);
                Path backupWorldDir = backupDir.resolve(worldName);
                
                if (!Files.exists(worldDir)) {
                    logger.warning("✗ 世界 " + worldName + " 不存在");
                    missingWorlds++;
                } else if (Files.exists(backupWorldDir)) {
                    logger.info("✓ 世界 " + worldName + " 已有备份");
                    existingWorlds++;
                    backedUpWorlds++;
                } else {
                    logger.info("✓ 世界 " + worldName + " 存在，需要备份");
                    existingWorlds++;
                }
            }
            
            logger.info("配置世界总数: " + worldsToBackup.size() + 
                       " | 存在: " + existingWorlds + 
                       " | 缺失: " + missingWorlds + 
                       " | 已备份: " + backedUpWorlds);
            logger.info("==================================");
            
            if (existingWorlds == 0) {
                logger.severe("没有找到任何配置的世界！请检查配置文件");
                return false;
            }

            boolean allSuccess = true;
            int successCount = 0;
            for (String worldName : worldsToBackup) {
                if (backupWorld(worldName)) {
                    successCount++;
                } else {
                    allSuccess = false;
                }
            }
            
            logger.info("备份完成！成功备份 " + successCount + "/" + worldsToBackup.size() + " 个世界");
            return allSuccess;
        } catch (Exception e) {
            logger.severe("创建备份目录时发生错误：" + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean backupWorld(String worldName) {
        try {
            Path worldDir = serverRoot.resolve(worldName);
            Path backupWorldDir = backupDir.resolve(worldName);
            Path hashFile = backupDir.resolve(worldName + ".sha256");

            if (!Files.exists(worldDir)) {
                return true;
            }
            
            if (Files.exists(backupWorldDir)) {
                return true;
            }
            
            logger.info("正在备份世界: " + worldName + "...");
            copyWorld(worldDir, backupWorldDir);
            saveWorldHash(backupWorldDir, hashFile);
            logger.info("世界 " + worldName + " 备份完成");
            return true;
        } catch (Exception e) {
            logger.severe("备份世界 " + worldName + " 时发生错误：" + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean restoreWorlds() {
        logger.info("========== 备份状态检查 ==========");
        int availableBackups = 0;
        int missingBackups = 0;
        int corruptedBackups = 0;
        List<String> validWorlds = new ArrayList<>();
        
        for (String worldName : worldsToBackup) {
            Path backupWorldDir = backupDir.resolve(worldName);
            Path hashFile = backupDir.resolve(worldName + ".sha256");
            
            if (!Files.exists(backupWorldDir)) {
                logger.warning("✗ 世界 " + worldName + " 没有备份");
                missingBackups++;
            } else if (!verifyWorldIntegrity(backupWorldDir, hashFile)) {
                logger.severe("✗ 世界 " + worldName + " 备份已损坏");
                corruptedBackups++;
            } else {
                logger.info("✓ 世界 " + worldName + " 备份完整，可以还原");
                availableBackups++;
                validWorlds.add(worldName);
            }
        }
        
        logger.info("备份总数: " + worldsToBackup.size() + 
                   " | 可用: " + availableBackups + 
                   " | 缺失: " + missingBackups + 
                   " | 损坏: " + corruptedBackups);
        logger.info("==================================");
        
        if (availableBackups == 0) {
            logger.severe("没有可用的备份进行还原！");
            return false;
        }
        
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            logger.info("正在踢出所有玩家...");
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.kickPlayer("§c正在还原世界，请稍后重试！");
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        boolean allSuccess = true;
        int successCount = 0;
        for (String worldName : validWorlds) {
            if (restoreWorld(worldName)) {
                successCount++;
            } else {
                allSuccess = false;
            }
        }
        
        logger.info("还原完成！成功还原 " + successCount + "/" + availableBackups + " 个世界");
        return allSuccess;
    }

    private boolean restoreWorld(String worldName) {
        try {
            Path worldDir = serverRoot.resolve(worldName);
            Path backupWorldDir = backupDir.resolve(worldName);

            logger.info("正在还原世界: " + worldName + "...");

            if (Files.exists(worldDir)) {
                if (Bukkit.getWorld(worldName) != null) {
                    logger.info("正在卸载世界: " + worldName + "...");
                    Bukkit.unloadWorld(worldName, false);
                }
                deleteWorld(worldDir);
            }

            copyWorld(backupWorldDir, worldDir);
            logger.info("世界 " + worldName + " 还原完成！");
            return true;
        } catch (Exception e) {
            logger.severe("还原世界 " + worldName + " 时发生错误：" + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void saveWorldHash(Path world, Path hashFile) throws IOException {
        TreeMap<String, String> fileHashes = calculateDirectoryHash(world);
        try (BufferedWriter writer = Files.newBufferedWriter(hashFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (var entry : fileHashes.entrySet()) {
                writer.write(entry.getKey() + ":" + entry.getValue() + "\n");
            }
        }
    }

    private TreeMap<String, String> calculateDirectoryHash(Path directory) throws IOException {
        TreeMap<String, String> fileHashes = new TreeMap<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!Files.isDirectory(file) && !file.getFileName().toString().endsWith(".lock")) {
                    String relativePath = directory.relativize(file).toString();
                    String hash = calculateFileHash(file);
                    fileHashes.put(relativePath, hash);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return fileHashes;
    }

    private String calculateFileHash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            try (InputStream is = Files.newInputStream(file)) {
                while ((count = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, count);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new IOException("计算失败：" + e.getMessage());
        }
    }

    private void copyWorld(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.getFileName().toString().endsWith(".lock")) {
                    Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteWorld(Path world) throws IOException {
        Files.walkFileTree(world, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean verifyWorldIntegrity(Path world, Path hashFile) {
        try {
            if (!Files.exists(hashFile)) {
                logger.warning("找不到世界 " + world.getFileName() + " 的哈希值文件，无法验证完整性");
                return false;
            }

            TreeMap<String, String> storedHashes = new TreeMap<>();
            List<String> lines = Files.readAllLines(hashFile);
            for (String line : lines) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    storedHashes.put(parts[0], parts[1]);
                }
            }

            TreeMap<String, String> currentHashes = calculateDirectoryHash(world);

            if (!storedHashes.equals(currentHashes)) {
                logger.warning("世界 " + world.getFileName() + " 文件哈希值不匹配，当前备份可能已损坏");
                return false;
            }

            return true;
        } catch (Exception e) {
            logger.severe("验证世界 " + world.getFileName() + " 时发生错误：" + e.getMessage());
            return false;
        }
    }
}