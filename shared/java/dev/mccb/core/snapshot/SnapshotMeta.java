package dev.mccb.core.snapshot;

/**
 * 一份云端快照的描述。
 *
 * <p>刻意写成普通类而不是 record：这个类要靠 Gson 反序列化，而 record 需要
 * Gson 2.10+ 才支持。不同 Minecraft 版本捆绑的 Gson 版本不一样，用普通类比
 * 较稳，反正这里也没人会写错。
 */
public final class SnapshotMeta {

	public String fileName = "";
	public long sizeBytes;
	public String contentHash = "";
	public long createdAtEpochMillis;

	public SnapshotMeta() {
	}

	public SnapshotMeta(String fileName, long sizeBytes, String contentHash, long createdAtEpochMillis) {
		this.fileName = fileName;
		this.sizeBytes = sizeBytes;
		this.contentHash = contentHash;
		this.createdAtEpochMillis = createdAtEpochMillis;
	}
}
