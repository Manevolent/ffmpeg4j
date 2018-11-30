package com.github.manevolent.ffmpeg4j.filter;

import com.github.manevolent.ffmpeg4j.MediaFrame;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public abstract class MediaFilterChain<T extends MediaFrame> extends MediaFilter<T> {
    private final List<MediaFilter<T>> filters;

    protected MediaFilterChain(Collection<MediaFilter<T>> filters) {
        this.filters = new LinkedList<>(filters);
    }

    @Override
    public Collection<T> apply(T source) {
        Collection<T> frames = Collections.singleton(source);
        for (MediaFilter<T> filter : filters) {
            Collection<T> newCollection = new LinkedList<>();
            for (T frame : frames) newCollection.addAll(filter.apply(frame));
            frames = newCollection;
        }
        return frames;
    }

    @Override
    public Collection<T> flush() {
        Collection<T> frames = Collections.emptyList();
        for (MediaFilter<T> filter : filters) {
            Collection<T> newCollection = new LinkedList<>();
            for (T frame : frames) newCollection.addAll(filter.apply(frame));
            newCollection.addAll(filter.flush());
            frames = newCollection;
        }
        return frames;
    }

    @Override
    public void close() throws Exception {
        // Do nothing
    }
}
