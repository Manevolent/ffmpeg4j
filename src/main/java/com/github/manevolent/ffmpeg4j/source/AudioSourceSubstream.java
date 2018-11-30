package com.github.manevolent.ffmpeg4j.source;

import com.github.manevolent.ffmpeg4j.AudioFormat;
import com.github.manevolent.ffmpeg4j.AudioFrame;
import com.github.manevolent.ffmpeg4j.MediaType;
import com.github.manevolent.ffmpeg4j.stream.source.SourceStream;

public abstract class AudioSourceSubstream extends MediaSourceSubstream<AudioFrame> {
    public AudioSourceSubstream(SourceStream parent) {
        super(parent, MediaType.AUDIO);
    }

    public abstract AudioFormat getFormat();

    @Override
    public String toString() {
        return getFormat().toString() + " " + (getBitRate() / 1000) + "Kbps";
    }
}
