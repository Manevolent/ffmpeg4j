package com.github.manevolent.ffmpeg4j.output;

import com.github.manevolent.ffmpeg4j.MediaType;
import com.github.manevolent.ffmpeg4j.VideoFrame;

public abstract class VideoTargetSubstream extends MediaTargetSubstream<VideoFrame> {
    @Override
    public MediaType getType() {
        return MediaType.VIDEO;
    }
}
