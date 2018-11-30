package com.github.manevolent.ffmpeg4j;

public class AudioFrame extends MediaFrame {
    private final float[] samples;
    private final int length;

    private final AudioFormat format;

    public AudioFrame(double timestamp, double position, double time,
                         float[] samples, AudioFormat format) {
        this(timestamp, position, time, samples, samples.length, format);
    }

    public AudioFrame(double timestamp, double position, double time,
                      float[] samples, int length, AudioFormat format) {
        super(position, time, timestamp);
        this.samples = samples;
        this.length = length;
        this.format = format;
    }

    /**
     * Gets the samples in this frame, in PCM interleaved format.
     */
    public float[] getSamples() {
        return samples;
    }

    public AudioFormat getFormat() {
        return format;
    }

    public int getLength() {
        return length;
    }
}
