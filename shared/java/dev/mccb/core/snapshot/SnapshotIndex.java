package dev.mccb.core.snapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个世界在云端的快照清单，序列化后存放在该世界目录下的 manifest.json。
 */
public final class SnapshotIndex {

	/** 清单格式版本，将来改结构时用来做兼容处理。 */
	public int formatVersion = 1;

	public String world = "";

	/** 按时间从新到旧排列。 */
	public List<SnapshotMeta> snapshots = new ArrayList<>();

	public SnapshotMeta latest() {
		return snapshots.isEmpty() ? null : snapshots.get(0);
	}
}
