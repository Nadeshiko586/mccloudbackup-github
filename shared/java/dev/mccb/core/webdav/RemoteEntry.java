package dev.mccb.core.webdav;

/**
 * WebDAV 目录里的一项。
 *
 * @param name       条目名（已从 href 中解码出来的最后一段，不含路径）
 * @param sizeBytes  文件大小；目录为 0
 * @param collection 是否为目录
 */
public record RemoteEntry(String name, long sizeBytes, boolean collection) {
}
