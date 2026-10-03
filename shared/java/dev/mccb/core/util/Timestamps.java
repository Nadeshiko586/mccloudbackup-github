package dev.mccb.core.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class Timestamps {

	/** 用做文件名，故意不含冒号等跨平台有问题的字符。 */
	private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private Timestamps() {
	}

	public static String fileStamp() {
		return LocalDateTime.now().format(FILE_STAMP);
	}

	public static String forDisplay(long epochMillis) {
		return DISPLAY.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
	}
}
