package net.fabricmc.example.minimap.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

/**
 * "Sharp texel" sampling for map textures drawn at a scale that is not a
 * whole number, or rotated. Plain nearest sampling makes blocks uneven
 * (some 1 pixel wide, some 2) and rotation jagged; plain linear sampling makes
 * everything blurry. This shader keeps every block a solid square of colour
 * and only blends across the one screen pixel that straddles a block edge.
 *
 * Needs OpenGL 2.0. Anything going wrong (old driver, compile error) simply
 * reports the shader as unavailable and the caller falls back to plain filters.
 */
public final class SharpShader {
	private static final String VERTEX = "#version 110\n"
			+ "void main() {\n"
			+ "    gl_Position = ftransform();\n"
			+ "    gl_TexCoord[0] = gl_MultiTexCoord0;\n"
			+ "    gl_FrontColor = gl_Color;\n"
			+ "}\n";

	private static final String FRAGMENT = "#version 110\n"
			+ "uniform sampler2D tex;\n"
			+ "uniform vec2 size;\n"
			+ "void main() {\n"
			+ "    vec2 t = gl_TexCoord[0].xy * size;\n"
			// Nearest texel edge, and how many texels one screen pixel covers.
			+ "    vec2 edge = floor(t + 0.5);\n"
			+ "    vec2 d = max(fwidth(t), vec2(0.00001));\n"
			// Further than half a pixel from an edge: the texel centre. Closer: a linear blend.
			+ "    t = edge + clamp((t - edge) / d, -0.5, 0.5);\n"
			+ "    gl_FragColor = texture2D(tex, t / size) * gl_Color;\n"
			+ "}\n";

	/** -1 not tried yet, 0 unavailable, otherwise the program id. */
	private static int program = -1;
	private static int sizeLocation = -1;
	private static float lastSize = -1;

	private SharpShader() {
	}

	public static boolean available() {
		if (program == -1)
			init();
		return program > 0;
	}

	private static void init() {
		program = 0;
		try {
			if (!GLContext.getCapabilities().OpenGL20)
				return;
			int vs = compile(GL20.GL_VERTEX_SHADER, VERTEX);
			int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
			if (vs == 0 || fs == 0)
				return;
			int p = GL20.glCreateProgram();
			GL20.glAttachShader(p, vs);
			GL20.glAttachShader(p, fs);
			GL20.glLinkProgram(p);
			GL20.glDetachShader(p, vs);
			GL20.glDetachShader(p, fs);
			GL20.glDeleteShader(vs);
			GL20.glDeleteShader(fs);
			if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
				System.err.println("[Minimap CE] Sharp map shader did not link, using plain filtering: "
						+ GL20.glGetProgramInfoLog(p, 1024));
				GL20.glDeleteProgram(p);
				return;
			}
			sizeLocation = GL20.glGetUniformLocation(p, "size");
			int texLocation = GL20.glGetUniformLocation(p, "tex");
			int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
			GL20.glUseProgram(p);
			GL20.glUniform1i(texLocation, 0);
			GL20.glUseProgram(previous);
			program = p;
		} catch (Throwable t) {
			System.err.println("[Minimap CE] Sharp map shader unavailable, using plain filtering: " + t);
			program = 0;
		}
		// A failed attempt can leave an error flag behind; never let it reach the game's own checks.
		while (GL11.glGetError() != GL11.GL_NO_ERROR) {
		}
	}

	private static int compile(int type, String source) {
		int shader = GL20.glCreateShader(type);
		GL20.glShaderSource(shader, source);
		GL20.glCompileShader(shader);
		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			System.err.println("[Minimap CE] Sharp map shader did not compile, using plain filtering: "
					+ GL20.glGetShaderInfoLog(shader, 1024));
			GL20.glDeleteShader(shader);
			return 0;
		}
		return shader;
	}

	/** Switches the shader on; returns the program that was active, for {@link #end}. */
	static int begin() {
		int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		GL20.glUseProgram(program);
		lastSize = -1;
		return previous;
	}

	/** Size in texels of the (square) texture about to be drawn. */
	static void setTextureSize(float size) {
		if (size != lastSize) {
			GL20.glUniform2f(sizeLocation, size, size);
			lastSize = size;
		}
	}

	static void use(boolean on, int previous) {
		GL20.glUseProgram(on ? program : previous);
		lastSize = -1;
	}

	static void end(int previous) {
		GL20.glUseProgram(previous);
	}
}
