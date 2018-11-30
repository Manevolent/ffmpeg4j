package com.github.manevolent.ffmpeg4j.source;

import com.github.manevolent.ffmpeg4j.MediaType;
import com.github.manevolent.ffmpeg4j.VideoFormat;
import com.github.manevolent.ffmpeg4j.VideoFrame;
import com.github.manevolent.ffmpeg4j.stream.source.SourceStream;

public abstract class VideoSourceSubstream extends MediaSourceSubstream<VideoFrame> {
    public VideoSourceSubstream(SourceStream parent) {
        super(parent, MediaType.VIDEO);
    }

    public abstract VideoFormat getFormat();

    @Override
    public String toString() {
        return getFormat().toString() + " " + (getBitRate() / 1000) + "Kbps";
    }
}
