package com.github.manevolent.ffmpeg4j;

public class VideoFrame extends MediaFrame {
    //private static final int FIELDS = 3;
    private final int format;
    private final int width, height;
    private final byte[] data;

    public VideoFrame(double timestamp, double position, double time,
                      int format, int width, int height, byte[] frameData) {
        super(position, time, timestamp);

        this.format = format;

        this.width = width;
        this.height = height;
        this.data = frameData;
    }

    /**private int addr(int x, int y, int offs) {
        return (y * getWidth() * FIELDS) + (x * FIELDS) + offs;
    }

    public byte getR(int x, int y) {
        return data[addr(x, y, 0)];
    }

    public byte getG(int x, int y) {
        return data[addr(x, y, 1)];
    }

    public byte getB(int x, int y) {
        return data[addr(x, y, 2)];
    }

    public void setR(int x, int y, byte r) {
        data[addr(x, y, 0)] = r;
    }

    public void setG(int x, int y, byte g) {
        data[addr(x, y, 1)] = g;
    }

    public void setB(int x, int y, byte b) {
        data[addr(x, y, 2)] = b;
    }

    public int getRGB(int x, int y) {
        int basePosition = (y * getWidth() * FIELDS) + (x * FIELDS);
        int argb = 0xFF;
        argb = (argb << 0) | (data[basePosition] & 0xFF);
        argb = (argb << 8) | (data[basePosition+1] & 0xFF);
        argb = (argb << 16) | (data[basePosition+2] & 0xFF);

        return argb;
    }

    public int setRGB(int x, int y, int rgb) {
        int basePosition = (y * getWidth() * FIELDS) + (x * FIELDS);
        data[basePosition] = (byte) (0xFF & ( rgb >> 16));
        data[basePosition+1] = (byte) (0xFF & (rgb >> 8 ));
        data[basePosition+2] = (byte) (0xFF & (rgb >> 0 ));
        return rgb;
    }**/

    public byte[] getData() {
        return data;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Gets the FFmpeg native pixel format for this frame.
     * @return pixel format.
     */
    public int getFormat() {
        return format;
    }
}
