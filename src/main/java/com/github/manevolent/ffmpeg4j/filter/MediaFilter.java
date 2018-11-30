package com.github.manevolent.ffmpeg4j.filter;

import com.github.manevolent.ffmpeg4j.MediaFrame;

import java.util.Collection;
import java.util.Collections;

public abstract class MediaFilter<T extends MediaFrame> implements AutoCloseable {
    public abstract Collection<T> apply(T source);

    public Collection<T> flush() {
        return Collections.emptyList();
    }
}
