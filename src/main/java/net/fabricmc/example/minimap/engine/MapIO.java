package net.fabricmc.example.minimap.engine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Single background thread for all map disk access. Tasks run strictly in
 * submission order, which is what makes "save region, then load it again"
 * sequences safe without any locking.
 */
public final class MapIO {
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "Minimap CE IO");
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		}
	});

	static {
		// Second safety net: give queued saves a chance to finish on JVM exit.
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				@Override
				public void run() {
					flush(5000);
				}
			}, "Minimap CE IO flush"));
		} catch (Throwable ignored) {
		}
	}

	private MapIO() {
	}

	public static void submit(final Runnable task) {
		EXECUTOR.execute(new Runnable() {
			@Override
			public void run() {
				try {
					task.run();
				} catch (Throwable t) {
					System.err.println("[Minimap CE] I/O task failed: " + t);
				}
			}
		});
	}

	/** Blocks until everything submitted so far has been processed (or the timeout expires). */
	public static void flush(long timeoutMs) {
		try {
			Future<?> f = EXECUTOR.submit(new Runnable() {
				@Override
				public void run() {
				}
			});
			f.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (Throwable ignored) {
		}
	}
}
