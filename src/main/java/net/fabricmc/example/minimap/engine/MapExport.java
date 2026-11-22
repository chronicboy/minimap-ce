package net.fabricmc.example.minimap.engine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Saves the explored map as a PNG image, one pixel per block (or fewer for very large maps).
 *
 * Regions are read on the map's own I/O thread, one task per region, so saving and loading
 * carry on in between and nothing ever reads a file while it is being written. Shading is
 * worked out across region borders, so the picture has no seams. The PNG is encoded on a
 * separate thread at the end. Unexplored ground is transparent.
 */
public final class MapExport {
	/** Largest image written, in pixels (4096 x 4096): bigger maps are exported at 1/2, 1/4, ... */
	static long maxPixels = 4096L * 4096L;
	private static final int SIZE = RegionCodec.REGION_SIZE;
	private static final long STATUS_SHOWN_MS = 8000L;

	private static volatile boolean running;
	private static volatile String status;
	private static volatile long statusUntil;
	private static final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<String>();

	private MapExport() {
	}

	/** Snapshot of a map to export (see {@link MapStorage#exportSource()}). */
	static final class Source {
		final ArrayList<Long> keys;
		final HashMap<Long, int[][]> memory;
		final File dir, legacyDir;

		Source(ArrayList<Long> keys, HashMap<Long, int[][]> memory, File dir, File legacyDir) {
			this.keys = keys;
			this.memory = memory;
			this.dir = dir;
			this.legacyDir = legacyDir;
		}

		int[][] read(long key) {
			int[][] chunks = memory.get(key);
			if (chunks != null)
				return chunks;
			int rx = (int) (key >> 32), rz = (int) key;
			try {
				if (dir != null) {
					File f = new File(dir, "r." + rx + "." + rz + ".mmr");
					if (f.isFile())
						return RegionCodec.read(f);
				}
				if (legacyDir != null) {
					File f = new File(legacyDir, "r." + rx + "." + rz + ".dat");
					if (f.isFile())
						return RegionCodec.readLegacy(f);
				}
			} catch (Throwable e) {
				System.err.println("[Minimap CE] Export skipped region " + rx + "," + rz + ": " + e);
			}
			return null;
		}
	}

	public static boolean isRunning() {
		return running;
	}

	/** A line for the map screen: progress while exporting, then the result for a few seconds; or null. */
	public static String status() {
		String s = status;
		if (s == null || (!running && System.currentTimeMillis() > statusUntil))
			return null;
		return s;
	}

	/** Next message for the chat, or null. */
	public static String pollMessage() {
		return messages.poll();
	}

	/**
	 * Starts exporting a map. Call on the main thread.
	 *
	 * @param out the PNG file to write (its folder is created)
	 * @return false if an export is already running or there is nothing to export
	 */
	public static boolean start(MapStorage storage, File out) {
		if (running || storage == null || storage.isClosed())
			return false;
		Source source = storage.exportSource();
		if (source.keys.isEmpty()) {
			finish("Nothing explored to export yet.", false);
			return false;
		}
		running = true;
		status = "Exporting map...";
		final Job job = new Job(source, out);
		MapIO.submit(new Runnable() {
			@Override
			public void run() {
				job.step();
			}
		});
		return true;
	}

	private static void finish(String message, boolean chat) {
		status = message;
		statusUntil = System.currentTimeMillis() + STATUS_SHOWN_MS;
		running = false;
		if (chat)
			messages.add(message);
	}

	/** The export of one map, advanced one region per I/O task. */
	private static final class Job implements Runnable {
		private final Source source;
		private final File out;
		private final long[] keys;
		private final int minRx, minRz, step, outW, outH;
		private final BufferedImage image;
		private final int[] pixels;
		private final int[] region = new int[SIZE * SIZE];
		private int next;

		// Shading across region borders: the last row of each region in the row above, and the
		// right-hand column of the region just to the west.
		private HashMap<Integer, int[]> rowAbove = new HashMap<Integer, int[]>();
		private HashMap<Integer, int[]> thisRow = new HashMap<Integer, int[]>();
		private int currentRz = Integer.MIN_VALUE;
		private int westRx = Integer.MIN_VALUE;
		private int[] westColumn;

		// Explored extent in output pixels, to crop the picture to.
		private int cropX0 = Integer.MAX_VALUE, cropY0 = Integer.MAX_VALUE, cropX1 = -1, cropY1 = -1;

		Job(Source source, File out) {
			this.source = source;
			this.out = out;
			ArrayList<Long> sorted = new ArrayList<Long>(source.keys);
			// Rows north to south, each row west to east.
			Collections.sort(sorted, new Comparator<Long>() {
				@Override
				public int compare(Long a, Long b) {
					int za = (int) (long) a, zb = (int) (long) b;
					if (za != zb)
						return za < zb ? -1 : 1;
					int xa = (int) (a >> 32), xb = (int) (b >> 32);
					return xa < xb ? -1 : (xa == xb ? 0 : 1);
				}
			});
			keys = new long[sorted.size()];
			int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
			for (int i = 0; i < keys.length; i++) {
				long k = sorted.get(i);
				keys[i] = k;
				int rx = (int) (k >> 32), rz = (int) k;
				x0 = Math.min(x0, rx);
				x1 = Math.max(x1, rx);
				z0 = Math.min(z0, rz);
				z1 = Math.max(z1, rz);
			}
			minRx = x0;
			minRz = z0;
			long blocksW = (long) (x1 - x0 + 1) * SIZE, blocksH = (long) (z1 - z0 + 1) * SIZE;
			// Halve the resolution until the picture fits the pixel limit and comfortably in memory.
			Runtime rt = Runtime.getRuntime();
			long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
			int s = 1;
			while (s < SIZE && ((blocksW / s) * (blocksH / s) > maxPixels || (blocksW / s) * (blocksH / s) * 4L * 3L > free))
				s *= 2;
			step = s;
			outW = (int) (blocksW / s);
			outH = (int) (blocksH / s);
			image = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
			pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		}

		/** Runs on the I/O thread: one region, then queues itself again (so saves and loads interleave). */
		void step() {
			try {
				if (next < keys.length) {
					long k = keys[next++];
					status = "Exporting map... " + (next * 100 / keys.length) + "%";
					addRegion((int) (k >> 32), (int) k, source.read(k));
					MapIO.submit(new Runnable() {
						@Override
						public void run() {
							step();
						}
					});
					return;
				}
				if (cropX1 < 0) {
					finish("Nothing explored to export yet.", false);
					return;
				}
				status = "Saving picture...";
				Thread writer = new Thread(this, "Minimap CE export");
				writer.setDaemon(true);
				writer.start();
			} catch (Throwable e) {
				fail(e);
			}
		}

		/** Encodes and writes the PNG (its own thread: this can take a few seconds for big maps). */
		@Override
		public void run() {
			try {
				File parent = out.getAbsoluteFile().getParentFile();
				if (parent != null && !parent.isDirectory() && !parent.mkdirs())
					throw new java.io.IOException("cannot create " + parent);
				BufferedImage cropped = image.getSubimage(cropX0, cropY0, cropX1 - cropX0 + 1, cropY1 - cropY0 + 1);
				ImageIO.write(cropped, "png", out);
				String scale = step == 1 ? "1 pixel per block" : "1 pixel per " + step + "x" + step + " blocks";
				finish("Map saved to " + out.getPath() + " (" + cropped.getWidth() + "x" + cropped.getHeight() + ", "
						+ scale + ")", true);
			} catch (Throwable e) {
				fail(e);
			}
		}

		private void fail(Throwable e) {
			System.err.println("[Minimap CE] Map export failed:");
			e.printStackTrace();
			finish("Map export failed: " + e.getMessage(), true);
		}

		private void addRegion(int rx, int rz, int[][] chunks) {
			if (rz != currentRz) {
				// New row of regions: the finished row becomes the row above (if it is directly above).
				rowAbove = rz == currentRz + 1 ? thisRow : new HashMap<Integer, int[]>();
				thisRow = new HashMap<Integer, int[]>();
				currentRz = rz;
				westRx = Integer.MIN_VALUE;
				westColumn = null;
			}
			int[] north = rowAbove.get(rx);
			int[] west = westRx == rx - 1 ? westColumn : null;
			if (chunks == null) {
				westRx = Integer.MIN_VALUE;
				westColumn = null;
				return;
			}
			int[] bottom = new int[SIZE], right = new int[SIZE];
			for (int i = 0; i < SIZE; i++) {
				bottom[i] = RegionCodec.dataAt(chunks, i, SIZE - 1);
				right[i] = RegionCodec.dataAt(chunks, SIZE - 1, i);
			}
			thisRow.put(rx, bottom);
			westRx = rx;
			westColumn = right;

			// Shade the region, looking across its north and west edges.
			int[] px = region;
			java.util.Arrays.fill(px, 0);
			boolean any = false;
			for (int ci = 0; ci < RegionCodec.CHUNK_COUNT; ci++) {
				int[] c = chunks[ci];
				if (c == null)
					continue;
				int bx = (ci & 31) << 4, bz = (ci >> 5) << 4;
				for (int lz = 0; lz < 16; lz++) {
					int z = bz + lz;
					for (int lx = 0; lx < 16; lx++) {
						int d = c[(lz << 4) | lx];
						if (d == 0)
							continue;
						int x = bx + lx;
						int n = lz > 0 ? c[((lz - 1) << 4) | lx]
								: (z > 0 ? RegionCodec.dataAt(chunks, x, z - 1) : (north != null ? north[x] : 0));
						int w = lx > 0 ? c[(lz << 4) | (lx - 1)]
								: (x > 0 ? RegionCodec.dataAt(chunks, x - 1, z) : (west != null ? west[z] : 0));
						px[z * SIZE + x] = MapShade.shade(d, n, w);
						any = true;
					}
				}
			}
			if (!any)
				return;

			// Into the picture, averaging step x step blocks per pixel; a pixel needs at least half
			// of its blocks explored, so the coastline of the explored area stays clean.
			int s = step, per = SIZE / s, need = (s * s + 1) / 2;
			int ox = (rx - minRx) * per, oy = (rz - minRz) * per;
			for (int y = 0; y < per; y++) {
				for (int x = 0; x < per; x++) {
					int argb;
					if (s == 1) {
						argb = px[y * SIZE + x];
					} else {
						int r = 0, g = 0, b = 0, count = 0;
						for (int dz = 0; dz < s; dz++) {
							int row = (y * s + dz) * SIZE + x * s;
							for (int dx = 0; dx < s; dx++) {
								int p = px[row + dx];
								if (p == 0)
									continue;
								r += (p >> 16) & 0xFF;
								g += (p >> 8) & 0xFF;
								b += p & 0xFF;
								count++;
							}
						}
						argb = count >= need ? 0xFF000000 | ((r / count) << 16) | ((g / count) << 8) | (b / count) : 0;
					}
					if (argb == 0)
						continue;
					int X = ox + x, Y = oy + y;
					pixels[Y * outW + X] = 0xFF000000 | argb;
					if (X < cropX0)
						cropX0 = X;
					if (X > cropX1)
						cropX1 = X;
					if (Y < cropY0)
						cropY0 = Y;
					if (Y > cropY1)
						cropY1 = Y;
				}
			}
		}
	}
}
