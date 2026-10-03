package dev.mccb.core.backup;

import dev.mccb.core.snapshot.SnapshotIndex;
import dev.mccb.core.snapshot.SnapshotMeta;

import java.util.ArrayList;
import java.util.List;

public final class RetentionPolicy {

	private RetentionPolicy() {
	}

	/**
	 * 挑出应该被删除的快照。
	 *
	 * <p>假设 {@code index.snapshots} 已按时间从新到旧排列，保留前 maxSnapshots 个。
	 * 返回的是"要删掉的那些"，调用方负责删完再更新清单。
	 */
	public static List<SnapshotMeta> selectForDeletion(SnapshotIndex index, int maxSnapshots) {
		List<SnapshotMeta> doomed = new ArrayList<>();

		if (index == null || maxSnapshots <= 0) {
			return doomed;
		}

		for (int i = maxSnapshots; i < index.snapshots.size(); i++) {
			doomed.add(index.snapshots.get(i));
		}

		return doomed;
	}
}
