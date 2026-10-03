package dev.mccb.core.util;

import java.util.Locale;

public final class HumanSize {

	private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

	private HumanSize() {
	}

	public static String format(long bytes) {
		if (bytes < 1024L) {
			return bytes + " B";
		}

		double value = bytes;
		int unit = 0;

		while (value >= 1024.0 && unit < UNITS.length - 1) {
			value /= 1024.0;
			unit++;
		}

		return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
	}
}
