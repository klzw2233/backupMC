import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class backupMC {

    /*

    private static final String SAVE_DIR = "E:\\Games\\minecrafts\\GregTech6\\.minecraft\\saves\\NEI"; // 示例存档目录路径
    private static final String BACKUP_DIR = "E:\\Games\\minecrafts\\GregTech6\\.minecraft\\backups"; // 示例备份目录路径

    */
    public static String SAVE_DIR;
    public static String BACKUP_DIR;
    public static void main(String[] args) {

        Config config = ConfigFactory.load();
        SAVE_DIR = config.getString("save-directory");
        BACKUP_DIR = config.getString("backup-directory");

        if (SAVE_DIR != null && BACKUP_DIR != null) {
            // 使用从配置文件中读取的路径进行备份操作
            // 例如: backupSavesFull(saveDir, backupDir);
        } else {
            System.err.println("Error: 'save-directory' and 'backup-directory' must be set in the configuration.");
        }

        // 获取当前时间并格式化为字符串
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));

        // 创建备份目录（如果不存在）
        createDirectory(BACKUP_DIR);

        // 检查是否存在之前的备份信息文件，以及至少一个全量备份文件存在
        File backupInfoFile = Paths.get(BACKUP_DIR, "backup_info.txt").toFile();
        boolean fullBackupExists = checkForFullBackup(BACKUP_DIR);

        if (backupInfoFile.exists() && fullBackupExists) {
            // 存在备份信息文件且有全量备份，执行增量备份
            backupSavesIncremental(SAVE_DIR, BACKUP_DIR, timestamp);
        } else {
            // 不存在备份信息文件或没有全量备份，首次备份，执行全量备份
            backupSavesFull(SAVE_DIR, BACKUP_DIR, timestamp); // 修改为执行全量备份的方法
        }
    }


    // 创建目录方法
    private static void createDirectory(String dirPath) {
        Path path = Paths.get(dirPath);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (Exception e) {
                System.err.println("Failed to create directory: " + e.getMessage());
            }
        }
    }

    // 备份存档方法（增加压缩及SHA-256计算功能）
    private static void backupSavesFull(String saveDir, String backupDir, String timestamp) {
        try {
            // 1. 创建Zip输出流指向新备份文件
            String backupFileName = "full_backup_" + timestamp + ".zip";
            FileOutputStream fos = new FileOutputStream(Paths.get(backupDir, backupFileName).toFile());
            ZipOutputStream zos = new ZipOutputStream(fos);

            // 2. 遍历存档目录下的所有文件和子目录，添加到zip输出流中
            File saveFolder = new File(saveDir);
            addFolderToZip(saveFolder, saveFolder.getName(), zos);

            // 3. 关闭Zip输出流
            zos.close();
            fos.close();

            // 4. 计算备份文件的SHA-256摘要
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(Paths.get(backupDir, backupFileName).toFile());
            DigestInputStream dis = new DigestInputStream(fis, md);
            byte[] buffer = new byte[8192];
            while (dis.read(buffer) > 0); // 读取直到结束
            dis.close();
            fis.close();

            // 5. 打印备份文件的SHA-256摘要
            byte[] hashBytes = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            String sha256Hash = sb.toString();
            System.out.printf("Backup %s created and SHA-256: %s%n", backupFileName, sha256Hash);

            // 在全量备份完成后保存备份信息
            saveCurrentBackupInfo(SAVE_DIR, BACKUP_DIR);

        } catch (IOException | NoSuchAlgorithmException e) {
            System.err.println("Backup failed: " + e.getMessage());
        }
    }

    // 辅助方法：递归地将文件夹内容添加到Zip输出流
    private static void addFolderToZip(File folder, String parentName, ZipOutputStream zos) throws IOException {
        for (File file : folder.listFiles()) {
            if (file.isDirectory()) {
                addFolderToZip(file, parentName + "/" + file.getName(), zos);
                continue;
            }
            FileInputStream fis = new FileInputStream(file);
            ZipEntry zipEntry = new ZipEntry(parentName + "/" + file.getName());
            zos.putNextEntry(zipEntry);
            byte[] bytes = new byte[1024];
            int length;
            while ((length = fis.read(bytes)) >= 0) {
                zos.write(bytes, 0, length);
            }
            zos.closeEntry();
            fis.close();
        }
    }

    // 增量备份逻辑引入
    private static void backupSavesIncremental(String saveDir, String backupDir, String timestamp) {
        try {
            // 加载上次备份的文件状态（这里简化处理，仅示范逻辑）
            Map<String, Long> previousBackupTimes = loadPreviousBackupInfo(backupDir);

            // 创建新的备份文件
            String backupFileName = "incremental_backup_" + timestamp + ".zip";
            try (FileOutputStream fos = new FileOutputStream(Paths.get(backupDir, backupFileName).toFile());
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                File saveFolder = new File(saveDir);
                addFolderToZipIncremental(saveFolder, "", zos, previousBackupTimes);

            } catch (IOException e) {
                System.err.println("Backup creation failed: " + e.getMessage());
            }

            // 更新备份信息记录
            saveCurrentBackupInfo(saveDir, backupDir);

        } catch (Exception e) {
            System.err.println("Backup process failed: " + e.getMessage());
        }
    }

    // 增量备份辅助方法，根据文件修改时间决定是否备份
    private static void addFolderToZipIncremental(File folder, String parentName, ZipOutputStream zos,
                                                  Map<String, Long> previousBackupTimes) throws IOException {
        for (File file : folder.listFiles()) {
            String filePath = parentName + "/" + file.getName();
            long lastModified = file.lastModified();

            // 检查文件是否比上次备份新或已修改
            if (!previousBackupTimes.containsKey(filePath) || lastModified > previousBackupTimes.get(filePath)) {
                if (file.isDirectory()) {
                    addFolderToZipIncremental(file, filePath, zos, previousBackupTimes);
                    continue;
                }

                try (FileInputStream fis = new FileInputStream(file)) {
                    ZipEntry zipEntry = new ZipEntry(filePath);
                    zos.putNextEntry(zipEntry);

                    byte[] bytes = new byte[1024];
                    int length;
                    while ((length = fis.read(bytes)) >= 0) {
                        zos.write(bytes, 0, length);
                    }

                    zos.closeEntry();
                }
            }
        }
    }

    /**
     * 从指定的备份目录中加载上一次备份的信息。
     *
     * @param backupDir 备份目录路径
     * @return 一个Map，键为文件路径，值为文件的最后修改时间戳
     */
    private static Map<String, Long> loadPreviousBackupInfo(String backupDir) {
        Map<String, Long> previousBackupInfo = new HashMap<>();
        String backupInfoFilePath = backupDir + "/backup_info.txt";

        try (BufferedReader reader = new BufferedReader(new FileReader(backupInfoFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 假设每行格式为 "文件路径,时间戳"
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    String filePath = parts[0];
                    long timestamp = Long.parseLong(parts[1]);
                    previousBackupInfo.put(filePath, timestamp);
                } else {
                    System.out.println("警告: 行格式错误，无法解析: " + line);
                }
            }
        } catch (IOException e) {
            System.err.println("读取备份信息文件时发生错误: " + e.getMessage());
        }

        return previousBackupInfo;
    }

    // 保存当前备份的文件状态到文本文件
    private static void saveCurrentBackupInfo(String saveDir, String backupDir) {
        try {
            // 确定备份信息文件的路径
            String backupInfoFilePath = Paths.get(backupDir, "backup_info.txt").toString();

            // 使用HashMap存储文件路径和最后修改时间
            Map<String, Long> fileStatusMap = new HashMap<>();

            // 遍历目录，收集文件状态信息
            collectFileStatus(new File(saveDir), "", fileStatusMap);

            // 将信息写入文件
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(backupInfoFilePath))) {
                for (Map.Entry<String, Long> entry : fileStatusMap.entrySet()) {
                    writer.write(entry.getKey() + "," + entry.getValue());
                    writer.newLine();
                }
            }

            System.out.println("Backup information saved successfully.");

        } catch (IOException e) {
            System.err.println("Failed to save backup information: " + e.getMessage());
        }
    }

    // 辅助方法：递归收集文件状态
    private static void collectFileStatus(File folder, String parentPath, Map<String, Long> fileStatusMap) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                String filePath = parentPath.isEmpty() ? file.getName() : parentPath + "/" + file.getName();
                if (file.isDirectory()) {
                    collectFileStatus(file, filePath, fileStatusMap);
                } else {
                    // 使用lastModified()获取最后修改时间戳
                    long lastModifiedTime = file.lastModified();
                    fileStatusMap.put(filePath, lastModifiedTime);
                }
            }
        }
    }

    // 新增方法：检查备份目录下是否存在至少一个全量备份文件
    private static boolean checkForFullBackup(String backupDir) {
        File backupFolder = new File(backupDir);
        File[] files = backupFolder.listFiles((dir, name) -> name.startsWith("full_backup_") && name.endsWith(".zip")); // 修改匹配规则为全量备份格式
        return files != null && files.length > 0;
    }

}