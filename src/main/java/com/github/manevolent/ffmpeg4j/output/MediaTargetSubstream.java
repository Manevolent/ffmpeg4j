package com.github.manevolent.ffmpeg4j.output;

import com.github.manevolent.ffmpeg4j.MediaStream;
import com.github.manevolent.ffmpeg4j.MediaType;

import java.io.IOException;

public abstract class MediaTargetSubstream<T> extends MediaStream {
    public abstract void write(T o) throws IOException;
    public abstract void flush() throws IOException;
    public abstract MediaType getType();
}
