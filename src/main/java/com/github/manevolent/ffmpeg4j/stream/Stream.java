package com.github.manevolent.ffmpeg4j.stream;

import com.github.manevolent.ffmpeg4j.MediaStream;

import java.util.List;
import java.util.stream.*;

public abstract class Stream<T extends MediaStream> implements AutoCloseable {

    public abstract List<T> getSubstreams();

    public List<T> getSubstreams(Class<? extends T> type) {
        return getSubstreams().stream().filter(ss -> type.isAssignableFrom(ss.getClass()) ||
                        ss.getClass().equals(type)).collect(Collectors.toList());
    }

    public double getPosition() {
        return getSubstreams().stream().mapToDouble(MediaStream::getPosition)
                        .min().orElse(0D);
    }
}
