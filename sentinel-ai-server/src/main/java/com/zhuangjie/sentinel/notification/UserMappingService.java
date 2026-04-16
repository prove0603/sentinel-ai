package com.zhuangjie.sentinel.notification;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Git 用户名 → 系统负责人 + 企业微信群昵称 映射服务。
 * <p>
 * 从独立的 YAML 文件加载，应用启动时缓存，运行期间不刷新。
 * 映射文件不存在时优雅降级，不影响应用启动。
 */
@Slf4j
@Service
public class UserMappingService {

    @Value("${sentinel.notification.user-mapping-file:wecom-user-mapping.yml}")
    private String mappingFilePath;

    /** owner → UserInfo 映射（用于通过 owner 查昵称） */
    private final Map<String, UserInfo> ownerIndex = new ConcurrentHashMap<>();
    /** git用户名(小写) → UserInfo 映射 */
    private final Map<String, UserInfo> gitNameIndex = new ConcurrentHashMap<>();

    private volatile String defaultOwner = "zhuangjie";

    @Data
    public static class UserInfo {
        private String gitName;
        private String owner;
        private String wecomName;
    }

    @PostConstruct
    public void loadMapping() {
        InputStream is = openMappingFile();
        if (is == null) {
            log.info("[UserMapping] 映射文件未找到(classpath:{} 和 file:{})，将使用默认负责人: {}",
                    mappingFilePath, Path.of(mappingFilePath).toAbsolutePath(), defaultOwner);
            return;
        }
        try (is) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(is);
            if (data == null) {
                log.warn("[UserMapping] 映射文件内容为空");
                return;
            }
            if (data.containsKey("default-owner")) {
                defaultOwner = String.valueOf(data.get("default-owner"));
            }
            Object usersObj = data.get("users");
            if (usersObj instanceof Map<?, ?> usersMap) {
                gitNameIndex.clear();
                ownerIndex.clear();
                for (var entry : usersMap.entrySet()) {
                    String gitName = String.valueOf(entry.getKey());
                    UserInfo info = new UserInfo();
                    info.setGitName(gitName);

                    Object val = entry.getValue();
                    if (val instanceof Map<?, ?> detail) {
                        info.setOwner(detail.containsKey("owner") ? String.valueOf(detail.get("owner")) : gitName);
                        info.setWecomName(detail.containsKey("wecom-name") ? String.valueOf(detail.get("wecom-name")) : null);
                    } else {
                        info.setOwner(String.valueOf(val));
                        info.setWecomName(null);
                    }

                    gitNameIndex.put(gitName.toLowerCase(), info);
                    ownerIndex.put(info.getOwner().toLowerCase(), info);
                }
            }
            log.info("[UserMapping] 加载完成: {} 条映射, 默认负责人={}", gitNameIndex.size(), defaultOwner);
        } catch (IOException e) {
            log.warn("[UserMapping] 加载映射文件失败: {}", e.getMessage());
        }
    }

    /**
     * 优先从 classpath 加载，找不到再从文件系统加载，都找不到返回 null。
     * 这样无论 IDE 启动（cwd=项目根）还是 mvn spring-boot:run（cwd=server模块）都能正确加载。
     */
    private InputStream openMappingFile() {
        try {
            ClassPathResource cpResource = new ClassPathResource(mappingFilePath);
            if (cpResource.exists()) {
                log.info("[UserMapping] 从 classpath 加载映射文件: {}", mappingFilePath);
                return cpResource.getInputStream();
            }
        } catch (IOException ignored) {
        }
        Path fsPath = Path.of(mappingFilePath);
        if (Files.exists(fsPath)) {
            try {
                log.info("[UserMapping] 从文件系统加载映射文件: {}", fsPath.toAbsolutePath());
                return Files.newInputStream(fsPath);
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    /**
     * 根据 Git 用户名解析 owner。
     * 找到映射则返回 owner，找不到则返回 defaultOwner。
     */
    public String resolveOwner(String gitUsername) {
        if (gitUsername == null || gitUsername.isBlank()) return defaultOwner;
        UserInfo info = gitNameIndex.get(gitUsername.toLowerCase());
        return info != null ? info.getOwner() : defaultOwner;
    }

    /**
     * 根据 owner 获取企业微信群昵称（用于消息文本中 @人）。
     */
    public String getWecomName(String owner) {
        if (owner == null || owner.isBlank()) return null;
        UserInfo info = ownerIndex.get(owner.toLowerCase());
        return info != null ? info.getWecomName() : null;
    }

    /**
     * 根据 Git 用户名获取企业微信群昵称。
     */
    public String getWecomNameByGitName(String gitUsername) {
        if (gitUsername == null || gitUsername.isBlank()) return null;
        UserInfo info = gitNameIndex.get(gitUsername.toLowerCase());
        return info != null ? info.getWecomName() : null;
    }

    public String getDefaultOwner() {
        return defaultOwner;
    }
}
