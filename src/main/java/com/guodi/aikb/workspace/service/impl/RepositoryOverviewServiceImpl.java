package com.guodi.aikb.workspace.service.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.aikb.workspace.service.RepositoryOverviewService;

@Service
public class RepositoryOverviewServiceImpl implements RepositoryOverviewService {

    private static final String REPO_MAP_TOOL = "repo_map";
    private static final int REPO_MAP_MAX_DEPTH = 5;
    private static final int REPO_MAP_MAX_ENTRIES = 300;

    private final SyncMcpToolCallbackProvider mcpProvider;
    private final ObjectMapper objectMapper;
    private final String repositoryRoot;
    private final Path cacheFile;

    public RepositoryOverviewServiceImpl(
            ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider,
            ObjectMapper objectMapper,
            @Value("${repository-agent.repository.root:${user.dir}}") String repositoryRoot,
            @Value("${repository-agent.overview.cache-directory:${user.dir}/.repository-agent-cache}")
                    String cacheDirectory) {
        this.mcpProvider = mcpProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.repositoryRoot = Path.of(repositoryRoot).toAbsolutePath().normalize().toString();
        this.cacheFile = Path.of(cacheDirectory).toAbsolutePath().normalize()
                .resolve("overview-" + repositoryKey(this.repositoryRoot) + ".json");
    }

    @Override
    public synchronized String getOverview() {
        OverviewCache cached = readCache();
        if (cached != null
                && repositoryRoot.equals(cached.repositoryRoot())
                && cached.overviewContent() != null
                && !cached.overviewContent().isBlank()) {
            return cached.overviewContent();
        }
        return generateAndStore(null);
    }

    @Override
    public synchronized String refreshOverview() {
        OverviewCache existing = readCache();
        return generateAndStore(existing == null ? null : existing.createdAt());
    }

    private String generateAndStore(Instant originalCreatedAt) {
        String content = generateOverview();
        Instant now = Instant.now();
        writeCache(new OverviewCache(
                repositoryRoot,
                content,
                originalCreatedAt == null ? now : originalCreatedAt,
                now
        ));
        return content;
    }

    private OverviewCache readCache() {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        try {
            return objectMapper.readValue(cacheFile.toFile(), OverviewCache.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Repository Overview缓存读取失败: " + cacheFile, exception);
        }
    }

    private void writeCache(OverviewCache overview) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Path temporary = Files.createTempFile(cacheFile.getParent(), "overview-", ".tmp");
            Files.writeString(
                    temporary,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(overview),
                    StandardCharsets.UTF_8
            );
            try {
                Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Repository Overview缓存写入失败: " + cacheFile, exception);
        }
    }

    private String generateOverview() {
        if (mcpProvider == null) {
            throw new IllegalStateException("Repository MCP client不可用");
        }
        ToolCallback callback = Arrays.stream(mcpProvider.getToolCallbacks())
                .filter(item -> REPO_MAP_TOOL.equals(item.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Repository MCP未提供repo_map"));
        String result = callback.call(toJson(Map.of(
                "max_depth", REPO_MAP_MAX_DEPTH,
                "max_entries", REPO_MAP_MAX_ENTRIES
        )));
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("repo_map返回内容为空");
        }
        return result;
    }

    private String toJson(Map<String, Integer> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("repo_map参数序列化失败", exception);
        }
    }

    private static String repositoryKey(String repositoryRoot) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(repositoryRoot.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JVM不支持SHA-256", exception);
        }
    }

    private record OverviewCache(
            String repositoryRoot,
            String overviewContent,
            Instant createdAt,
            Instant updatedAt) {
    }
}
