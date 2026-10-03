package dev.mccb.core.backup;

import dev.mccb.core.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 备份任务的执行器。
 *
 * <p>两个刻意的设计：
 * <ul>
 *   <li><b>单线程</b>——打包和上传同时只有一个在跑，不会几个世界一起上传把带宽打满，
 *       也不会出现两次备份同时改同一份 manifest 导致状态错乱。</li>
 *   <li><b>合并排队</b>——如果任务正在跑的时候又来了新请求，只记一个"待办"标记，
 *       跑完后再补一次，而不是排成队列。否则玩家连着退几次世界就会堆出一串上传。</li>
 * </ul>
 */
public final class BackupScheduler {

	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "mccloudbackup-worker");
		// 守护线程 + 低优先级：游戏退出时不会因为还有上传在跑而卡住，
		// 也不会和游戏主线程抢 CPU。
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private final Object lock = new Object();
	private boolean running;
	private boolean pending;

	public void submit(Runnable task) {
		synchronized (lock) {
			if (running) {
				pending = true;
				return;
			}

			running = true;
		}

		executor.execute(() -> run(task));
	}

	public boolean isBusy() {
		synchronized (lock) {
			return running;
		}
	}

	public void shutdown() {
		executor.shutdownNow();
	}

	private void run(Runnable task) {
		while (true) {
			try {
				task.run();
			} catch (Throwable t) {
				// 后台线程里任何异常都不能往外抛，否则 executor 会静默吞掉后续任务。
				Log.error("备份任务执行失败", t);
			}

			synchronized (lock) {
				if (pending) {
					pending = false;
					continue;
				}

				running = false;
				return;
			}
		}
	}
}
