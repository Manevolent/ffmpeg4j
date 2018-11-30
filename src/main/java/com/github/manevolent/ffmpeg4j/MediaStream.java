package com.github.manevolent.ffmpeg4j;

public abstract class MediaStream implements AutoCloseable {
    private volatile double position = 0D;

    /**
     * Sets the stream's position in seconds. Called by decoders.
     * @param position Position.
     */
    protected final void setPosition(double position) {
        this.position = position;
    }

    /**
     * Gets the stream's position in seconds, based on the last read packet.
     * @return Stream position.
     */
    public final double getPosition() {
        return this.position;
    }
}
