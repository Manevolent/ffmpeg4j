package com.github.manevolent.ffmpeg4j.stream;

import com.github.manevolent.ffmpeg4j.MediaStream;

import java.util.List;

public abstract class Stream<T extends MediaStream> implements AutoCloseable {

    public abstract List<T> getSubstreams();

    public double getPosition() {
        return getSubstreams().stream().mapToDouble(x -> x.getPosition()).min().orElse(0D);
    }
}
