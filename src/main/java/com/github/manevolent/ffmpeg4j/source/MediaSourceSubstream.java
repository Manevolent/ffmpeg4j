package com.github.manevolent.ffmpeg4j.source;

import com.github.manevolent.ffmpeg4j.MediaStream;
import com.github.manevolent.ffmpeg4j.MediaType;
import com.github.manevolent.ffmpeg4j.stream.source.SourceStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public abstract class MediaSourceSubstream<T> extends MediaStream {
    private final Queue<T> frameQueue = new LinkedBlockingQueue<>();
    private final MediaType mediaType;
    private volatile double lost;
    private final SourceStream parent;
    private boolean decoding = true;

    protected MediaSourceSubstream(SourceStream parent, MediaType mediaType) {
        this.parent = parent;
        this.mediaType = mediaType;
    }

    public final SourceStream getParent() {
        return parent;
    }

    protected boolean put(T frame) {
        return frameQueue.add(frame);
    }

    public abstract int getBitRate();

    public final MediaType getMediaType() {
        return mediaType;
    }

    /**
     * Flushes the source stream, emptying all buffered data.
     */
    public final void flush() {
        frameQueue.clear();
    }

    /**
     * Requests that the stream read a packet and put it onto the buffer.
     * @return true if the read was successful, false if the stream is otherwise broken.
     * @throws IOException
     */
    public abstract boolean read() throws IOException;

    /**
     * Gets the next available object from the source stream.
     * @return Object.  Null, if the substream is otherwise empty or ended.
     * @throws IOException
     */
    public T next() throws IOException {
        while (frameQueue.size() <= 0)
        {
            if (!isDecoding()) throw new IOException(new IllegalStateException("not decoding"));
            read();
        }

        return tryNext();
    }

    public T peek() throws IOException  {
        while (frameQueue.size() <= 0)
            read();

        return tryPeek();
    }

    public T tryNext() {
        return frameQueue.poll();
    }

    public T tryPeek() {
        return frameQueue.peek();
    }

    public Collection<T> drain() {
        List<T> newList = new ArrayList<T>(frameQueue.size());
        T o;
        while ((o = tryNext()) != null) newList.add(o);
        return newList;
    }

    public double getLost() {
        return lost;
    }

    public void setLost(double lost) {
        this.lost = lost;
    }

    public boolean isDecoding() {
        return decoding;
    }

    public void setDecoding(boolean decoding) {
        this.decoding = decoding;
    }
}
