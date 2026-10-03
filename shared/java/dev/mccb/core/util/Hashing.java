package dev.mccb.core.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Hashing {

	private Hashing() {
	}

	public static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 是 JDK 必须提供的算法，走到这里说明运行环境不正常。
			throw new IllegalStateException("当前 JDK 缺少 SHA-256 实现", e);
		}
	}

	public static String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);

		for (byte b : bytes) {
			builder.append(Character.forDigit((b >> 4) & 0xF, 16));
			builder.append(Character.forDigit(b & 0xF, 16));
		}

		return builder.toString();
	}
}
