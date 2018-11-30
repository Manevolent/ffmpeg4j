package com.github.manevolent.ffmpeg4j;

public abstract class MediaFrame {
    private final double timestamp;
    private final double position;
    private final double time;

    protected MediaFrame(double position, double time, double timestamp) {
        this.position = position;
        this.timestamp = timestamp;
        this.time = time;
    }

    /**
     * Gets the representation of how where this frame is positioned in the stream.
     */
    public double getPosition() {
        return position;
    }

    /**
     * Gets the representation of how long this frame is.
     */
    public double getTime() {
        return time;
    }

    /**
     * Gets an encoding timestamp for this frame.
     * @return Timestamp.
     */
    public double getTimestamp() {
        return timestamp;
    }
}
