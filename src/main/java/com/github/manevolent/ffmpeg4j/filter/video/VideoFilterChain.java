package com.github.manevolent.ffmpeg4j.filter.video;

import com.github.manevolent.ffmpeg4j.VideoFrame;
import com.github.manevolent.ffmpeg4j.filter.MediaFilter;
import com.github.manevolent.ffmpeg4j.filter.MediaFilterChain;

import java.util.Collection;

public class VideoFilterChain extends MediaFilterChain<VideoFrame> {
    public VideoFilterChain(Collection<MediaFilter<VideoFrame>> mediaFilters) {
        super(mediaFilters);
    }
}
