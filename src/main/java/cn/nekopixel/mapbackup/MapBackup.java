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

public class MapBackup {
    private final Plugin plugin;
    private final Logger logger;
    private final Path serverRoot;
    private final Path backupDir;
    private final Path worldDir;
    private final Path backupWorldDir;
    private final Path hashFile;

    public MapBackup(Main plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.serverRoot = Bukkit.getWorldContainer().toPath();
        this.backupDir = serverRoot.resolve("backup");
        this.worldDir = serverRoot.resolve("world");
        this.backupWorldDir = backupDir.resolve("world");
        this.hashFile = backupDir.resolve("world.sha256");
    }

    public boolean backupWorld() {
        try {
            if (!Files.exists(backupDir)) {
                logger.info("正在创建备份目录...");
                Files.createDirectory(backupDir);
            }

            if (!Files.exists(backupWorldDir)) {
                if (Files.exists(worldDir)) {
                    logger.info("正在备份当前世界...");
                    copyWorld(worldDir, backupWorldDir);
                    saveWorldHash(backupWorldDir);
                    logger.info("世界备份完成");
                    return true;
                } else {
                    logger.severe("当前服务端没有 world 目录");
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            logger.severe("处理备份时发生错误：" + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean restoreWorld() {
        try {
            if (!verifyWorldIntegrity(backupWorldDir)) {
                logger.severe("备份完整性验证失败");
                return false;
            }

            logger.info("完整性验证通过，正在还原...");

            if (!Bukkit.getOnlinePlayers().isEmpty()) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.kickPlayer("§c正在还原世界，请稍后重试！");
                }
                Thread.sleep(1000);
            }

            if (Files.exists(worldDir)) {
                if (Bukkit.getWorld("world") != null) {
                    logger.info("正在卸载世界...");
                    Bukkit.unloadWorld("world", false);
                }
                deleteWorld(worldDir);
            }

            copyWorld(backupWorldDir, worldDir);
            logger.info("还原完成！");
            return true;
        } catch (Exception e) {
            logger.severe("还原世界时发生错误：" + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void saveWorldHash(Path world) throws IOException {
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

    private boolean verifyWorldIntegrity(Path world) {
        try {
            if (!Files.exists(hashFile)) {
                logger.warning("找不到哈希值文件，无法验证完整性");
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
                logger.warning("世界文件哈希值不匹配，当前备份可能已损坏");
                return false;
            }

            return true;
        } catch (Exception e) {
            logger.severe("验证时发生错误：" + e.getMessage());
            return false;
        }
    }
}