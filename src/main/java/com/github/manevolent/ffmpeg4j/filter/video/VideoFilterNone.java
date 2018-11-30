package com.github.manevolent.ffmpeg4j.filter.video;

import com.github.manevolent.ffmpeg4j.VideoFrame;

import java.util.Collection;
import java.util.Collections;

public class VideoFilterNone extends VideoFilter {
    @Override
    public Collection<VideoFrame> apply(VideoFrame source) {
        return Collections.singleton(source);
    }

    @Override
    public void close() throws Exception {

    }
}
