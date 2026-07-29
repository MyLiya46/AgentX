package org.xhy.infrastructure.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xhy.infrastructure.config.OssProperties;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OSS 文件下载服务
 *
 * 使用 OSS SDK + AccessKey 认证下载文件内容，替代原有的
 * HttpURLConnection 匿名访问方式。
 *
 * 设计原则：
 * - 认证访问：不依赖 Bucket 级别公共读权限，通过 AccessKey 签名访问
 * - 批量下载：一次下载多个文件，返回 URL → 内容 映射
 * - 缓存可插拔：通过 FileContentCache 接口接入缓存层（NoOp 或 Redis）
 * - 失败隔离：单个文件下载失败不影响其他文件
 */
@Service
public class OssDownloadService {

    private static final Logger logger = LoggerFactory.getLogger(OssDownloadService.class);

    /** 文本文件扩展名集合 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "csv", "log", "json", "xml", "yml", "yaml",
            "html", "htm", "js", "ts", "java", "py", "css", "sql",
            "sh", "bat", "ini", "cfg", "conf");

    /** 图片文件扩展名集合（直接传 URL，由 langchain4j ImageContent 处理） */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico");

    /** 单文件最大下载大小：2MB */
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024;

    /** 文本内容最大长度：50000 字符 */
    private static final int MAX_CONTENT_LENGTH = 50000;

    private final OssProperties ossProperties;
    private final FileContentCache fileContentCache;

    public OssDownloadService(OssProperties ossProperties, FileContentCache fileContentCache) {
        this.ossProperties = ossProperties;
        this.fileContentCache = fileContentCache;
    }

    /**
     * 批量下载文本文件内容
     *
     * 优先从缓存获取，缓存未命中时通过 OSS SDK 下载并回写缓存。
     *
     * @param fileUrls OSS 文件完整 URL 列表
     * @return URL → 文件内容 映射（下载失败的文件不在 Map 中）
     */
    public Map<String, String> downloadTextFiles(List<String> fileUrls) {
        if (fileUrls == null || fileUrls.isEmpty()) {
            return Collections.emptyMap();
        }

        // 筛选出文本文件
        List<String> textUrls = fileUrls.stream()
                .filter(url -> TEXT_EXTENSIONS.contains(getExtension(url)))
                .collect(Collectors.toList());

        if (textUrls.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>();
        OSS ossClient = null;

        try {
            ossClient = createOssClient();

            for (String fileUrl : textUrls) {
                String content = downloadSingleFile(ossClient, fileUrl);
                if (content != null) {
                    result.put(fileUrl, content);
                }
            }
        } catch (Exception e) {
            logger.error("创建 OSS 客户端或批量下载文件时发生异常", e);
        } finally {
            if (ossClient != null) {
                try {
                    ossClient.shutdown();
                } catch (Exception ignored) {
                    logger.debug("关闭 OSS 客户端时忽略异常", ignored);
                }
            }
        }

        return result;
    }

    /**
     * 构建供 LLM 使用的用户消息内容
     *
     * 将原始用户文本与文件内容合并为一条完整消息。
     *
     * @param userText   用户输入的文本（可为空）
     * @param fileUrls   文件 URL 列表
     * @param downloaded 已下载的文件内容映射
     * @return 合并后的消息字符串
     */
    public String buildEnrichedMessage(String userText, List<String> fileUrls,
            Map<String, String> downloaded) {
        StringBuilder sb = new StringBuilder();

        // 用户文本
        if (userText != null && !userText.isBlank()) {
            sb.append(userText);
        }

        // 文件内容附件
        if (fileUrls != null && !fileUrls.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }

            for (String fileUrl : fileUrls) {
                String ext = getExtension(fileUrl);
                String fileName = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);

                if (downloaded.containsKey(fileUrl)) {
                    // 文本文件下载成功：附加内容
                    sb.append("【用户上传文件: ").append(fileName).append("】\n\n");
                    sb.append(downloaded.get(fileUrl));
                    sb.append("\n\n【文件内容结束】\n");
                } else if (IMAGE_EXTENSIONS.contains(ext)) {
                    // 图片：告知模型（URL 由 langchain4j ImageContent 处理）
                    sb.append("用户上传了图片: ").append(fileName).append("\n");
                } else if (TEXT_EXTENSIONS.contains(ext)) {
                    // 文本文件下载失败
                    sb.append("用户上传了文件: ").append(fileName)
                            .append("（系统无法读取该文件内容，请告知用户此问题）\n");
                    logger.error("文本文件下载失败，已通知 LLM: url={}", fileUrl);
                } else {
                    // 其他二进制文件（PDF、docx 等）
                    sb.append("用户上传了文件: ").append(fileName)
                            .append("（").append(ext).append(" 格式，访问地址：")
                            .append(fileUrl).append("）\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 下载单个文件（含缓存逻辑）
     */
    private String downloadSingleFile(OSS ossClient, String fileUrl) {
        // 1. 查缓存
        String cached = fileContentCache.get(fileUrl);
        if (cached != null) {
            logger.debug("文件缓存命中: {}", fileUrl);
            return cached;
        }

        // 2. 缓存未命中，通过 OSS SDK 下载
        logger.debug("文件缓存未命中，使用 OSS SDK 下载: {}", fileUrl);
        String objectKey = extractObjectKey(fileUrl);
        if (objectKey == null) {
            logger.error("无法从 URL 提取 OSS ObjectKey: {}", fileUrl);
            return null;
        }

        try {
            OSSObject ossObject = ossClient.getObject(ossProperties.getBucketName(), objectKey);

            long contentLength = ossObject.getObjectMetadata().getContentLength();
            if (contentLength > MAX_FILE_SIZE) {
                logger.warn("文件过大，跳过下载: {} (大小: {} bytes)", fileUrl, contentLength);
                ossObject.close();
                return null;
            }

            // 读取文本内容
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(ossObject.getObjectContent(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > MAX_CONTENT_LENGTH) {
                        sb.setLength(MAX_CONTENT_LENGTH);
                        sb.append("\n\n... (内容已截断)");
                        break;
                    }
                }
            }
            ossObject.close();

            String content = sb.toString();
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH) + "\n\n... (内容已截断)";
            }

            // 3. 回写缓存
            fileContentCache.put(fileUrl, content);

            logger.info("OSS 文件下载成功: {} ({} chars)", fileUrl, content.length());
            return content;

        } catch (Exception e) {
            logger.error("OSS 文件下载失败: URL={}, 错误={}", fileUrl, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从完整 URL 中提取 OSS ObjectKey
     *
     * URL 格式: https://{bucket}.{endpoint}/{objectKey}
     * ObjectKey 示例: agent/2026/07/29/1234567890_abc123.txt
     */
    String extractObjectKey(String fileUrl) {
        try {
            String prefix = ossProperties.getBucketName() + "."
                    + ossProperties.getEndpoint().replace("https://", "");
            int idx = fileUrl.indexOf(prefix);
            if (idx < 0) {
                return null;
            }
            String objectKey = fileUrl.substring(idx + prefix.length() + 1);
            int queryIdx = objectKey.indexOf('?');
            if (queryIdx > 0) {
                objectKey = objectKey.substring(0, queryIdx);
            }
            return objectKey;
        } catch (Exception e) {
            logger.warn("提取 ObjectKey 失败: {}", fileUrl, e);
            return null;
        }
    }

    /**
     * 获取文件扩展名（小写，不含点号）
     */
    public static String getExtension(String url) {
        String path = url.toLowerCase();
        int queryIdx = path.indexOf('?');
        if (queryIdx > 0) {
            path = path.substring(0, queryIdx);
        }
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx >= path.length() - 1) {
            return "";
        }
        return path.substring(dotIdx + 1);
    }

    private OSS createOssClient() {
        String endpoint = ossProperties.getEndpoint();
        return new OSSClientBuilder().build(
                endpoint,
                ossProperties.getAccessKey(),
                ossProperties.getSecretKey());
    }
}
