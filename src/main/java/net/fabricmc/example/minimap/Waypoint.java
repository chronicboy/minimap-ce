package net.fabricmc.example.minimap;

public class Waypoint {
    public String name;
    public int x;
    public int y;
    public int z;
    public int color;
    public boolean enabled;

    public Waypoint(String name, int x, int y, int z, int color, boolean enabled) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
        this.enabled = enabled;
    }

    public Waypoint(String name, int x, int y, int z, int color) {
        this(name, x, y, z, color, true);
    }

    public int getRed() {
        return (color >> 16) & 0xFF;
    }

    public int getGreen() {
        return (color >> 8) & 0xFF;
    }

    public int getBlue() {
        return color & 0xFF;
    }

    public float getRedF() {
        return getRed() / 255.0f;
    }

    public float getGreenF() {
        return getGreen() / 255.0f;
    }

    public float getBlueF() {
        return getBlue() / 255.0f;
    }
}
