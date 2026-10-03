package dev.mccb.core.platform;

import java.nio.file.Path;

/**
 * 一个可以被备份的世界。
 *
 * @param id        世界的目录名，同时用作云端文件夹名。
 *                  刻意使用目录名而非玩家看到的显示名：目录名保证是文件系统安全的，
 *                  而显示名可能含有斜杠、冒号等在 URL 里有特殊含义的字符。
 * @param directory 世界存档目录
 */
public record WorldHandle(String id, Path directory) {
}
