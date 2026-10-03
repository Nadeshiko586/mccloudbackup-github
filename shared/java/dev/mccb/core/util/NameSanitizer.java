package dev.mccb.core.util;

public final class NameSanitizer {

	/** 净化后最长保留多少字符。 */
	private static final int MAX_LENGTH = 64;

	private NameSanitizer() {
	}

	/**
	 * 把任意名字净化成能安全用作文件名和 URL 路径段的形式。
	 *
	 * <p>保留字母、数字、空格和 {@code - _ .}，其余（含 {@code / \ : * ? " < > |} 等）
	 * 统统换成下划线。中文字符属于字母，会被原样保留。
	 */
	public static String sanitize(String raw) {
		if (raw == null) {
			return "";
		}

		StringBuilder builder = new StringBuilder(raw.length());

		for (char c : raw.trim().toCharArray()) {
			if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ' ') {
				builder.append(c);
			} else {
				builder.append('_');
			}
		}

		// 空格统一换成连字符：URL 里的 %20 可读性太差，排查问题时很碍事。
		String result = builder.toString().trim().replaceAll("\\s+", "-");

		// "." 和 ".." 在文件系统里是特殊目录，绝不能当成名字用。
		if (result.isEmpty() || isAllDots(result)) {
			return "";
		}

		return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH) : result;
	}

	private static boolean isAllDots(String value) {
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) != '.') {
				return false;
			}
		}

		return true;
	}
}
