package com.github.manevolent.ffmpeg4j.filter.audio;

import com.github.manevolent.ffmpeg4j.AudioFrame;

import java.util.Collection;
import java.util.Collections;

public class AudioFilterNone extends AudioFilter {
    @Override
    public Collection<AudioFrame> apply(AudioFrame source) {
        return Collections.singletonList(source);
    }

    @Override
    public void close() throws Exception {

    }
}
