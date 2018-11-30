package com.github.manevolent.ffmpeg4j;

public class AudioFormat {
    private final int sampleRate;
    private final int channels;
    private final long channel_layout;

    public AudioFormat(int sampleRate, int channels, long channel_layout) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.channel_layout = channel_layout;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    @Override
    public String toString() {
        return Integer.toString(sampleRate) + "Hz, " + Integer.toString(channels) + "ch";
    }

    @Override
    public boolean equals(Object b) {
        return b != null && b instanceof AudioFormat && ((AudioFormat) b).channels == channels
                && ((AudioFormat) b).sampleRate == sampleRate;
    }

    public long getChannelLayout() {
        return channel_layout;
    }
}
