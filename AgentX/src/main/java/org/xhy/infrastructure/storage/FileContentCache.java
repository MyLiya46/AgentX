package org.xhy.infrastructure.storage;

/**
 * 文件内容缓存接口
 *
 * 定义文件内容缓存的抽象，支持多种实现（Redis、NoOp 等）。
 * 所有实现必须保证异常不抛出——缓存失败不应阻塞主流程。
 */
public interface FileContentCache {

    /**
     * 获取缓存的文件内容
     *
     * @param fileUrl 文件 URL 作为缓存 Key
     * @return 缓存内容，未命中返回 null
     */
    String get(String fileUrl);

    /**
     * 缓存文件内容
     *
     * @param fileUrl 文件 URL 作为缓存 Key
     * @param content 文件文本内容
     */
    void put(String fileUrl, String content);
}
