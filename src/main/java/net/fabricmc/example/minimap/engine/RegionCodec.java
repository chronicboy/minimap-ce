package net.fabricmc.example.minimap.engine;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Region file format and the pure pixel builders. No Minecraft or OpenGL
 * dependencies, so everything here is safe to run on the I/O thread.
 *
 * A region covers 32x32 chunks (512x512 blocks). In memory it is an
 * int[1024][] of per-chunk tile arrays (index = localZ * 32 + localX, each
 * either null or int[256] with index = z * 16 + x).
 *
 * File layout (".mmr"):
 *   int magic, int version                       (uncompressed)
 *   deflate stream:
 *     128x128 ARGB overview image (LOD)          (read alone for far zoom)
 *     128 byte chunk presence bitmap
 *     256 ints per present chunk, in index order
 */
public final class RegionCodec {
	public static final int REGION_SHIFT = 9;
	public static final int REGION_SIZE = 512;
	public static final int CHUNKS_PER_SIDE = 32;
	public static final int CHUNK_COUNT = 1024;
	public static final int LOD_SIZE = 128;
	private static final int LOD_INTS = LOD_SIZE * LOD_SIZE;

	private static final int MAGIC = 0x4D435234; // "MCR4"
	private static final int VERSION = 1;

	private RegionCodec() {
	}

	// ------------------------------------------------------------------ write

	/** Writes atomically: a crash mid-write can never corrupt an existing file. */
	public static void write(File file, int[][] chunks) throws IOException {
		File parent = file.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
			throw new IOException("Cannot create directory " + parent);
		}
		File tmp = new File(parent, file.getName() + ".tmp");
		int[] lod = buildLod(chunks);
		Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		try (FileOutputStream fos = new FileOutputStream(tmp)) {
			DataOutputStream head = new DataOutputStream(fos);
			head.writeInt(MAGIC);
			head.writeInt(VERSION);
			head.flush();

			DeflaterOutputStream out = new DeflaterOutputStream(fos, deflater, 32768);
			byte[] buf = new byte[4096];
			writeInts(out, lod, buf);

			byte[] mask = new byte[CHUNK_COUNT / 8];
			for (int i = 0; i < CHUNK_COUNT; i++) {
				if (chunks[i] != null)
					mask[i >> 3] |= (byte) (1 << (i & 7));
			}
			out.write(mask);
			for (int i = 0; i < CHUNK_COUNT; i++) {
				if (chunks[i] != null)
					writeInts(out, chunks[i], buf);
			}
			out.finish();
			fos.flush();
		} finally {
			deflater.end();
		}
		try {
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void writeInts(OutputStream out, int[] data, byte[] buf) throws IOException {
		ByteBuffer bb = ByteBuffer.wrap(buf);
		int perPass = buf.length / 4;
		for (int off = 0; off < data.length; off += perPass) {
			int n = Math.min(perPass, data.length - off);
			bb.clear();
			bb.asIntBuffer().put(data, off, n);
			out.write(buf, 0, n * 4);
		}
	}

	// ------------------------------------------------------------------- read

	public static int[][] read(File file) throws IOException {
		Inflater inflater = new Inflater();
		try (FileInputStream fis = new FileInputStream(file)) {
			DataInputStream in = openBody(fis, inflater);
			skipFully(in, LOD_INTS * 4L);
			byte[] mask = new byte[CHUNK_COUNT / 8];
			in.readFully(mask);
			int[][] chunks = new int[CHUNK_COUNT][];
			byte[] buf = new byte[1024];
			for (int i = 0; i < CHUNK_COUNT; i++) {
				if ((mask[i >> 3] & (1 << (i & 7))) == 0)
					continue;
				in.readFully(buf);
				int[] c = new int[256];
				ByteBuffer.wrap(buf).asIntBuffer().get(c);
				chunks[i] = c;
			}
			return chunks;
		} finally {
			inflater.end();
		}
	}

	/** Reads only the 128x128 overview image from the start of the file. */
	public static int[] readLod(File file) throws IOException {
		Inflater inflater = new Inflater();
		try (FileInputStream fis = new FileInputStream(file)) {
			DataInputStream in = openBody(fis, inflater);
			byte[] buf = new byte[LOD_INTS * 4];
			in.readFully(buf);
			int[] lod = new int[LOD_INTS];
			ByteBuffer.wrap(buf).asIntBuffer().get(lod);
			return lod;
		} finally {
			inflater.end();
		}
	}

	private static DataInputStream openBody(FileInputStream fis, Inflater inflater) throws IOException {
		DataInputStream head = new DataInputStream(fis);
		if (head.readInt() != MAGIC)
			throw new IOException("Not a Minimap CE region file");
		int version = head.readInt();
		if (version != VERSION)
			throw new IOException("Unsupported region version " + version);
		return new DataInputStream(new BufferedInputStream(new InflaterInputStream(fis, inflater, 32768), 65536));
	}

	private static void skipFully(InputStream in, long count) throws IOException {
		byte[] buf = new byte[8192];
		while (count > 0) {
			int n = in.read(buf, 0, (int) Math.min(buf.length, count));
			if (n < 0)
				throw new EOFException();
			count -= n;
		}
	}

	/**
	 * Reads a region file written by Minimap CE 3.x ("r.X.Z.dat", uncompressed).
	 * The per-column encoding is the same, so the data can be used as-is.
	 * A truncated file yields whatever chunks could be read.
	 */
	public static int[][] readLegacy(File file) throws IOException {
		int[][] chunks = new int[CHUNK_COUNT][];
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 65536))) {
			int count = in.readInt();
			if (count < 0 || count > CHUNK_COUNT)
				throw new IOException("Bad legacy chunk count " + count);
			byte[] buf = new byte[1024];
			for (int c = 0; c < count; c++) {
				int lx, lz;
				try {
					lx = in.readByte() & 0xFF;
					lz = in.readByte() & 0xFF;
					in.readFully(buf);
				} catch (EOFException truncated) {
					break;
				}
				if (lx >= CHUNKS_PER_SIDE || lz >= CHUNKS_PER_SIDE)
					continue;
				int[] data = new int[256];
				ByteBuffer.wrap(buf).asIntBuffer().get(data);
				chunks[lz * CHUNKS_PER_SIDE + lx] = data;
			}
		}
		return chunks;
	}

	// ---------------------------------------------------------- pixel builders

	public static boolean isEmpty(int[][] chunks) {
		for (int i = 0; i < CHUNK_COUNT; i++) {
			if (chunks[i] != null)
				return false;
		}
		return true;
	}

	/** Tile value at region-local block coordinates, 0 when outside or unexplored. */
	public static int dataAt(int[][] chunks, int x, int z) {
		if (((x | z) & ~511) != 0)
			return 0;
		int[] c = chunks[((z >> 4) << 5) | (x >> 4)];
		return c == null ? 0 : c[((z & 15) << 4) | (x & 15)];
	}

	/**
	 * Builds the full 512x512 shaded ARGB image of a region using only data from
	 * inside the region (the first row/column get their cross-region shading
	 * fixed up later on the render thread).
	 */
	public static int[] buildPixels(int[][] chunks) {
		int[] out = new int[REGION_SIZE * REGION_SIZE];
		for (int ci = 0; ci < CHUNK_COUNT; ci++) {
			int[] c = chunks[ci];
			if (c == null)
				continue;
			int bx = (ci & 31) << 4;
			int bz = (ci >> 5) << 4;
			for (int lz = 0; lz < 16; lz++) {
				int z = bz + lz;
				int row = z * REGION_SIZE + bx;
				for (int lx = 0; lx < 16; lx++) {
					int d = c[(lz << 4) | lx];
					if (d == 0)
						continue;
					int north = lz > 0 ? c[((lz - 1) << 4) | lx] : dataAt(chunks, bx + lx, z - 1);
					int west = lx > 0 ? c[(lz << 4) | (lx - 1)] : dataAt(chunks, bx + lx - 1, z);
					out[row + lx] = MapShade.shade(d, north, west);
				}
			}
		}
		return out;
	}

	/**
	 * Builds the 128x128 overview image (each pixel averages a 4x4 block area).
	 * Pixels are either fully opaque or fully transparent.
	 */
	public static int[] buildLod(int[][] chunks) {
		int[] out = new int[LOD_INTS];
		for (int ci = 0; ci < CHUNK_COUNT; ci++) {
			int[] c = chunks[ci];
			if (c == null)
				continue;
			int bx = (ci & 31) << 4;
			int bz = (ci >> 5) << 4;
			for (int qz = 0; qz < 4; qz++) {
				for (int qx = 0; qx < 4; qx++) {
					int r = 0, g = 0, b = 0, n = 0;
					for (int dz = 0; dz < 4; dz++) {
						int lz = (qz << 2) + dz;
						for (int dx = 0; dx < 4; dx++) {
							int lx = (qx << 2) + dx;
							int d = c[(lz << 4) | lx];
							if (d == 0)
								continue;
							int north = lz > 0 ? c[((lz - 1) << 4) | lx] : dataAt(chunks, bx + lx, bz + lz - 1);
							int west = lx > 0 ? c[(lz << 4) | (lx - 1)] : dataAt(chunks, bx + lx - 1, bz + lz);
							int p = MapShade.shade(d, north, west);
							r += (p >> 16) & 0xFF;
							g += (p >> 8) & 0xFF;
							b += p & 0xFF;
							n++;
						}
					}
					if (n >= 6) {
						int px = (bx >> 2) + qx;
						int pz = (bz >> 2) + qz;
						out[pz * LOD_SIZE + px] = 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
					}
				}
			}
		}
		return out;
	}
}
