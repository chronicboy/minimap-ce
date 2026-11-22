package net.fabricmc.example.minimap;

import net.minecraft.src.Tessellator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A mob icon drawn from a few of the mob's model boxes, seen from a three-quarter
 * angle and slightly above, for mobs whose face is not one flat rectangle of the
 * skin (a horse's eyes are on the sides of a long head). Each box is textured
 * exactly the way the game's ModelBox maps the skin, so it follows resource packs.
 *
 * The projection is worked out once when the icon is built: drawing is a handful
 * of textured quads, far to near, plus a dark silhouette outline.
 */
final class ModelIcon {
	/** One visible face: screen corners in icon units (0..width, 0..height), texture coordinates, brightness. */
	private static final class Face {
		final double[] x = new double[4], y = new double[4];
		final double[] u = new double[4], v = new double[4];
		float light;
		double depth;
	}

	private final Face[] faces;
	/** Icon size in model units (one unit = one skin texel at the model's texture size). */
	final double width, height;

	/**
	 * @param texW,texH the model's texture size (ModelBase.textureWidth/Height)
	 * @param tilt      rotation of the whole head about X (radians, positive lowers the muzzle)
	 * @param yaw       how far the head is turned to show one side (radians)
	 * @param pitch     how far the camera looks down on the head (radians)
	 * @param boxes     {u, v, x, y, z, w, h, d, rotX, rotZ} or {..., pivotX, pivotY, pivotZ}: as
	 *                  ModelRenderer.addBox, plus the part's own rotation about the pivot (default the
	 *                  head's origin)
	 */
	ModelIcon(int texW, int texH, double tilt, double yaw, double pitch, double[]... boxes) {
		List<Face> visible = new ArrayList<Face>();
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		// Direction towards the light in view space (x right, y down, z away): from the upper left front.
		double lx = 0.25, ly = -0.7, lz = -0.65, ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
		lx /= ll;
		ly /= ll;
		lz /= ll;

		for (double[] b : boxes) {
			double u = b[0], v = b[1], x0 = b[2], y0 = b[3], z0 = b[4], w = b[5], h = b[6], d = b[7];
			double x1 = x0 + w, y1 = y0 + h, z1 = z0 + d;
			// Corners named as in ModelBox: 7 = (x0,y0,z0), 0 = (x1,y0,z0), 1 = (x1,y1,z0), 2 = (x0,y1,z0),
			// 3 = (x0,y0,z1), 4 = (x1,y0,z1), 5 = (x1,y1,z1), 6 = (x0,y1,z1).
			double[][] c = { { x1, y0, z0 }, { x1, y1, z0 }, { x0, y1, z0 }, { x0, y0, z1 }, { x1, y0, z1 },
					{ x1, y1, z1 }, { x0, y1, z1 }, { x0, y0, z0 } };
			double[][] p = new double[8][];
			double[] pivot = b.length >= 13 ? new double[] { b[10], b[11], b[12] } : new double[3];
			for (int i = 0; i < 8; i++)
				p[i] = transform(c[i], b[8], b[9], pivot, tilt, yaw, pitch);
			double[] centre = transform(new double[] { (x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2 }, b[8], b[9], pivot,
					tilt, yaw, pitch);

			// The six faces with the texture rectangle ModelBox gives each: {corners}, u1, v1, u2, v2.
			// Corner k of a face gets (u2,v1), (u1,v1), (u1,v2), (u2,v2) for k = 0..3, as in TexturedQuad.
			Object[][] sides = {
					{ new int[] { 4, 0, 1, 5 }, u + d + w, v + d, u + d + w + d, v + d + h },
					{ new int[] { 7, 3, 6, 2 }, u, v + d, u + d, v + d + h },
					{ new int[] { 4, 3, 7, 0 }, u + d, v, u + d + w, v + d },
					{ new int[] { 1, 2, 6, 5 }, u + d + w, v + d, u + d + w + w, v },
					{ new int[] { 0, 7, 2, 1 }, u + d, v + d, u + d + w, v + d + h },
					{ new int[] { 3, 4, 5, 6 }, u + d + w + d, v + d, u + d + w + d + w, v + d + h } };
			for (Object[] s : sides) {
				int[] k = (int[]) s[0];
				double[] a = p[k[0]], bb = p[k[1]], cc = p[k[2]], dd = p[k[3]];
				double fx = (a[0] + bb[0] + cc[0] + dd[0]) / 4, fy = (a[1] + bb[1] + cc[1] + dd[1]) / 4,
						fz = (a[2] + bb[2] + cc[2] + dd[2]) / 4;
				// Outward normal: from the box centre through the face centre.
				double nx = fx - centre[0], ny = fy - centre[1], nz = fz - centre[2];
				double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (nl < 1.0e-9 || nz / nl > -1.0e-3)
					continue; // faces away from the viewer (or is edge-on)
				nx /= nl;
				ny /= nl;
				nz /= nl;
				double u1 = (Double) s[1], v1 = (Double) s[2], u2 = (Double) s[3], v2 = (Double) s[4];
				Face f = new Face();
				double[][] corners = { a, bb, cc, dd };
				double[] us = { u2, u1, u1, u2 }, vs = { v1, v1, v2, v2 };
				for (int i = 0; i < 4; i++) {
					f.x[i] = corners[i][0];
					f.y[i] = corners[i][1];
					f.u[i] = us[i] / texW;
					f.v[i] = vs[i] / texH;
					minX = Math.min(minX, f.x[i]);
					maxX = Math.max(maxX, f.x[i]);
					minY = Math.min(minY, f.y[i]);
					maxY = Math.max(maxY, f.y[i]);
				}
				f.light = (float) (0.55 + 0.45 * Math.max(0.0, nx * lx + ny * ly + nz * lz));
				f.depth = fz;
				visible.add(f);
			}
		}
		// Far to near, so nearer faces cover farther ones.
		Face[] sorted = visible.toArray(new Face[0]);
		Arrays.sort(sorted, new Comparator<Face>() {
			@Override
			public int compare(Face a, Face b) {
				return Double.compare(b.depth, a.depth);
			}
		});
		for (Face f : sorted) {
			for (int i = 0; i < 4; i++) {
				f.x[i] -= minX;
				f.y[i] -= minY;
			}
		}
		faces = sorted;
		width = maxX - minX;
		height = maxY - minY;
	}

	/**
	 * Part rotation (as ModelRenderer: Z, then Y, then X, applied to the point in reverse), the head's
	 * tilt about X, then the view: turned by yaw about Y and looked at from above by pitch.
	 * Model space has y pointing down and the face towards -z; the result has x right, y down and
	 * z pointing away from the viewer.
	 */
	private static double[] transform(double[] p, double rotX, double rotZ, double[] pivot, double tilt, double yaw,
			double pitch) {
		double x = p[0] - pivot[0], y = p[1] - pivot[1], z = p[2] - pivot[2];
		double c = Math.cos(rotX), s = Math.sin(rotX);
		double ny = y * c - z * s, nz = y * s + z * c;
		y = ny;
		z = nz;
		c = Math.cos(rotZ);
		s = Math.sin(rotZ);
		double nx = x * c - y * s;
		ny = x * s + y * c;
		x = nx + pivot[0];
		y = ny + pivot[1];
		z += pivot[2];
		c = Math.cos(tilt);
		s = Math.sin(tilt);
		ny = y * c - z * s;
		nz = y * s + z * c;
		y = ny;
		z = nz;
		c = Math.cos(yaw);
		s = Math.sin(yaw);
		nx = x * c - z * s;
		nz = x * s + z * c;
		x = nx;
		z = nz;
		c = Math.cos(pitch);
		s = Math.sin(pitch);
		ny = y * c - z * s;
		nz = z * c + y * s;
		return new double[] { x, ny, nz };
	}

	/**
	 * Draws the icon with its top-left corner at (left, top), {@code scale} GUI pixels per model unit,
	 * with the skin texture already bound. A dark silhouette one physical pixel wide surrounds it.
	 *
	 * @param shade brightness multiplier (mobs far above or below are darker)
	 * @param pixel one physical pixel in GUI units
	 */
	void draw(Tessellator t, double left, double top, double scale, float shade, double pixel) {
		t.startDrawingQuads();
		t.setColorRGBA_F(0.02f, 0.02f, 0.03f, 1.0f);
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				if (dx == 0 && dy == 0)
					continue;
				double ox = left + dx * pixel, oy = top + dy * pixel;
				for (Face f : faces)
					quad(t, f, ox, oy, scale);
			}
		}
		for (Face f : faces) {
			float l = f.light * shade;
			t.setColorRGBA_F(l, l, l, 1.0f);
			quad(t, f, left, top, scale);
		}
		t.draw();
	}

	private static void quad(Tessellator t, Face f, double left, double top, double scale) {
		// Tessellator quads in 1.6.4 take corners in order; ModelBox's order is already a proper loop.
		for (int i = 0; i < 4; i++)
			t.addVertexWithUV(left + f.x[i] * scale, top + f.y[i] * scale, 0.0, f.u[i], f.v[i]);
	}

	/** For tests: number of faces drawn. */
	int faceCount() {
		return faces.length;
	}
}
