package com.github.manevolent.ffmpeg4j;

import com.github.manevolent.ffmpeg4j.stream.output.FFmpegTargetStream;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.global.*;
import org.bytedeco.javacpp.*;

import java.io.*;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;

// http://www.codeproject.com/Tips/489450/Creating-Custom-FFmpeg-IO-Context
public final class FFmpegIO implements AutoCloseable {
    public static final int MAXIMUM_STATES = 128;

    /**
     * Default FFmpeg buffer size (used for buffering input from the stream pipes)
     */
    public static final int DEFAULT_BUFFER_SIZE = 32768;

    /**
     * Holds a list of IOStates for the global system.
     */
    private static int states_in_use = 0;

    public static int getStatesInUse() {
        return states_in_use;
    }

    private static final IOState[] IO_STATE_REGISTRY = new IOState[MAXIMUM_STATES];

    private static class IOState implements AutoCloseable {
        public final AVIOContext context;

        // Handlers for Java-based I/O
        public final InputStream inputStream;
        public final OutputStream outputStream;

        private final Pointer internalBufferPointer;

        private final int id;

        /**
         * While true, this IOState is considered in-use.
         */
        public boolean open = true;
        public Function<Long, Long> seek;

        public int num_ops = 0, total = 0;

        public byte[] buffer = null;

        private final Object closeLock = new Object();

        private IOState(int id,
                        AVIOContext context,
                        InputStream inputStream,
                        OutputStream outputStream,
                        Pointer internalBufferPointer,
                        Function<Long, Long> seek) {
            this.id = id;
            this.context = context;
            this.inputStream = inputStream;
            this.outputStream = outputStream;
            this.internalBufferPointer = internalBufferPointer;
            this.seek = seek;
        }

        @Override
        public void close() throws Exception {
            synchronized (closeLock) {
                if (open) {
                    Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL,
                            "closing I/O stream id=" + id
                                    + " num_ops=" + num_ops + " total=" + total
                    );

                    buffer = null;

                    if (outputStream != null)
                        outputStream.close();

                    if (inputStream != null)
                        inputStream.close();

                    states_in_use--;
                    Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "states in use=" + states_in_use);

                    open = false;
                }
            }
        }

        public int getId() {
            return id;
        }

        public long seek(long position) {
            return seek.apply(position);
        }
    }

    private static final Object ioLock = new Object();

    /**
     * AVIOContext holding callbacks
     */
    private final AVIOContext avioContext;

    /**
     * Used to prevent the JVM from destroying the callback objects for this AVIO contxet
     */
    private final AutoCloseable[] closeables;

    /**
     * This native method should never be de-allocated. It finds what IOState the pointer (pointer) means,
     * and uses it to read some data. Keep in mind that you can only have one of these native methods per
     * callback, so it's up to us to hold a list of states that the native method will use. This method
     * cannot have variables outside of it besides static variables.
     */
    private static final Read_packet_Pointer_BytePointer_int read =
            new Read_packet_Pointer_BytePointer_int() {
                @Override
                public int call(Pointer pointer, BytePointer buffer, int len) {
                    IntPointer ioStatePtr = new IntPointer(pointer);
                    int stateId = ioStatePtr.get();

                    try {
                        IOState state = IO_STATE_REGISTRY[stateId];
                        if (state == null || !state.open) throw new NullPointerException();

                        int target = Math.min(len, state.context.buffer_size());

                        int pos = 0, read;

                        while (pos < target) {
                            try {
                                if (state.buffer == null || state.buffer.length < target - pos)
                                    state.buffer = new byte[target - pos];

                                read = state.inputStream.read(
                                        state.buffer,
                                        pos,
                                        target - pos
                                );

                                state.num_ops++;
                            } catch (IOException e) {
                                Logging.LOGGER.log(Level.WARNING, "Problem in FFmpeg IO read id=" + stateId, e);
                                read = -1;
                            }

                            if (read < 0) {
                                if (pos <= 0) {
                                    Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL,
                                                    "EOF in I/O stream id=" +
                                                    stateId + ": read=" + read + " pos=" + pos + " target=" + target
                                    );

                                    return avutil.AVERROR_EOF; // AVERROR_EOF
                                }
                                else break; // Still have some data to read
                            } else {
                                pos += read;
                                state.total += read;
                            }
                        }

                        if (pos > 0)
                            buffer.position(0).put(state.buffer, 0, pos);

                        return pos;
                    } catch (Throwable e) {
                        Logging.LOGGER.log(Level.WARNING, "Problem in FFmpeg IO read stream id=" + stateId, e);
                        return -1;
                    }
                }
            };

    private static final Write_packet_Pointer_BytePointer_int write =
            new Write_packet_Pointer_BytePointer_int() {
                @Override
                public int call(org.bytedeco.javacpp.Pointer pointer,
                                BytePointer buffer,
                                int len) {
                    try {
                        IntPointer ioStatePtr = new IntPointer(pointer);
                        IOState state = IO_STATE_REGISTRY[ioStatePtr.get()];
                        if (state == null || !state.open) throw new NullPointerException();

                        int to_write = Math.min(len, state.context.buffer_size());

                        if (to_write <= 0)
                            throw new IllegalArgumentException("to_write: " + to_write);

                        // Allocate buffer (this was a huge pain in the ass for me, by the way. allocate it...)
                        // otherwise we'll cause a SIGSEV crash in buffer.get below
                        if (state.buffer == null || state.buffer.length < to_write)
                            state.buffer = new byte[to_write];

                        buffer.get(state.buffer, 0, to_write);

                        state.outputStream.write(state.buffer, 0, to_write);

                        state.num_ops ++;
                        state.total += to_write;

                        return to_write;
                    } catch (Throwable e) {
                        Logging.LOGGER.log(Level.WARNING, "problem in FFmpeg IO write", e);
                        return -1;
                    }
                }
            };


    private static final Seek_Pointer_long_int seek =
            new Seek_Pointer_long_int() {
                @Override
                public long call(org.bytedeco.javacpp.Pointer pointer,
                                long position,
                                int whence) {
                    switch (whence) {
                        case 0:
                            break;
                        case avformat.AVSEEK_SIZE:
                            /**
                             * Passing this as the "whence" parameter to a seek function causes it to
                             * return the filesize without seeking anywhere. Supporting this is optional.
                             * If it is not supported then the seek function will return <0.
                             */
                            // Not supported
                            return -1;
                        case avformat.AVSEEK_FORCE:
                            /**
                              * Oring this flag as into the "whence" parameter to a seek function causes it to
                              * seek by any means (like reopening and linear reading) or other normally unreasonable
                              * means that can be extremely slow.
                              * This may be ignored by the seek code.
                              */
                            // Ignore
                            break;
                    }

                    try {
                        IntPointer ioStatePtr = new IntPointer(pointer);
                        IOState state = IO_STATE_REGISTRY[ioStatePtr.get()];
                        if (state == null || !state.open) throw new NullPointerException();

                        if (state.seek == null) {
                            return -1;
                        }

                        return state.seek(position);
                    } catch (Throwable e) {
                        Logging.LOGGER.log(Level.WARNING, "problem in FFmpeg IO write", e);
                        return -1;
                    }
                }
            };


    public FFmpegIO(AVIOContext avioContext, AutoCloseable... autoCloseables) {
        this.avioContext = avioContext;
        this.closeables = autoCloseables;
    }

    private static void setIOState(int id, IOState state) {
        if (state != null && state.open) {
            states_in_use++;
            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "states in use=" + states_in_use);
        }

        IO_STATE_REGISTRY[id] = state;
    }

    /**
     * Finds the next free IOState.
     * @return
     * @throws FFmpegException
     */
    private static int allocateIOStateId() throws FFmpegException {
        int ioStateId = -1; // -1 if no iostate is available

        if (states_in_use >= MAXIMUM_STATES)
            throw new FFmpegException("no I/O states are available " +
                    "(current=" + states_in_use + " max=" + MAXIMUM_STATES + ").");

        IOState state;
        for (int newIOStateId = 0; newIOStateId < IO_STATE_REGISTRY.length; newIOStateId ++) {
            state = IO_STATE_REGISTRY[newIOStateId];

            if (state == null) {
                ioStateId = newIOStateId;
                break;
            } else if (!state.open) {
                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "re-claiming IO state id=" + newIOStateId);
                ioStateId = newIOStateId;
                break;
            }
        }

        if (ioStateId < 0)
           throw new FFmpegException("failed to allocate I/O state; are none available? " +
                   "(current=" + states_in_use + " max=" + MAXIMUM_STATES + ").");

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "opened I/O state id=" + ioStateId);
        return ioStateId;
    }

    public static FFmpegInput openInput(File file, int bufferSize) throws IOException, FFmpegException {
        return openInputStream(Files.newInputStream(file.toPath()), bufferSize);
    }

    /**
     * Opens a custom AVIOContext based around the managed InputStream proved.
     * @param _inputStream InputStream instance to have FFmpeg read from.
     * @param bufferSize buffer size of the input.
     * @return FFmpegSource instance which points to the input stream provided.
     */
    public static FFmpegInput openInputStream(final InputStream _inputStream, final int bufferSize)
            throws FFmpegException {
        Objects.requireNonNull(_inputStream, "Input stream cannot be null");

        synchronized (ioLock) {
            // Lock an IOSTATE
            int ioStateId = allocateIOStateId();

            // Open the underlying AVIOContext.
            Pointer internalBufferPointer = avutil.av_malloc(bufferSize);

            final AVIOContext context = avformat.avio_alloc_context(
                    new BytePointer(internalBufferPointer).capacity(bufferSize), bufferSize, // internal Buffer and its size
                    0,
                    null,
                    read,
                    null,
                    seek
            );

            //Returns Allocated AVIOContext or NULL on failure.
            if (context == null) throw new NullPointerException();

            context.seekable(0);

            IntPointer intPointer = new IntPointer(avutil.av_malloc(4));
            intPointer.put(ioStateId);

            context.opaque(intPointer);
            context.write_flag(0);

            IOState state = new IOState(ioStateId, context, _inputStream, null, internalBufferPointer, null);
            setIOState(ioStateId, state);

            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "opened input state id=" + ioStateId);
            return new FFmpegInput(new FFmpegIO(context, state));
        }
    }

    public static FFmpegOutput openOutput(File file, int bufferSize) throws IOException, FFmpegException {
        return openOutputStream(Files.newOutputStream(file.toPath()), bufferSize);
    }

    /**
     * Opens a custom AVIOContext based around the managed OutputStream provided.
     * @param _outputStream OutputStream instance to have FFmpeg read from.
     * @param bufferSize buffer size of the input.
     * @return FFmpegSource instance which points to the input stream provided.
     */
    public static FFmpegOutput openOutputStream(final OutputStream _outputStream, final int bufferSize)
            throws FFmpegException {
        synchronized (ioLock) {
            // Lock an IOSTATE
            int ioStateId = allocateIOStateId();

            // Open the underlying AVIOContext.
            Pointer internalBufferPointer = avutil.av_malloc(bufferSize); // sizeof() == 1 here

            final AVIOContext context = avformat.avio_alloc_context(
                    new BytePointer(internalBufferPointer), bufferSize, // internal Buffer and its size
                    1, // write_flag
                    null,
                    null,
                    write,
                    seek
            );

            //Returns Allocated AVIOContext or NULL on failure.
            if (context == null) throw new NullPointerException();

            context.seekable(0);

            IntPointer intPointer = new IntPointer(avutil.av_malloc(4));
            intPointer.put(ioStateId);

            context.opaque(intPointer);
            context.write_flag(1);

            IOState state = new IOState(ioStateId, context, null, _outputStream, internalBufferPointer, null);
            setIOState(ioStateId, state);

            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "opened output state id=" + ioStateId);
            return new FFmpegOutput(new FFmpegIO(context, state));
        }
    }

    /**
     * Opens a custom AVIOContext based around the managed channel provided. This context will be seekable.
     * @param channel Channel instance to have FFmpeg read from.
     * @param bufferSize buffer size of the input.
     * @return FFmpegSource instance which points to the input stream provided.
     */
    public static FFmpegIO openChannel(final SeekableByteChannel channel, final int bufferSize)
            throws FFmpegException {
        synchronized (ioLock) {
            // Lock an IOSTATE
            int ioStateId = allocateIOStateId();

            // Open the underlying AVIOContext.
            Pointer internalBufferPointer = avutil.av_malloc(bufferSize); // sizeof() == 1 here

            final AVIOContext context = avformat.avio_alloc_context(
                    new BytePointer(internalBufferPointer), bufferSize, // internal Buffer and its size
                    1, // write_flag
                    null,
                    read,
                    write,
                    seek
            );

            //Returns Allocated AVIOContext or NULL on failure.
            if (context == null) throw new NullPointerException();

            context.seekable(1);

            IntPointer intPointer = new IntPointer(avutil.av_malloc(4));
            intPointer.put(ioStateId);

            context.opaque(intPointer);
            context.write_flag(1);

            IOState state = new IOState(ioStateId, context,
                    Channels.newInputStream(channel),
                    Channels.newOutputStream(channel),
                    internalBufferPointer,
                    (pos) -> {
                        try {
                            channel.position(pos);
                            return channel.position();
                        } catch (IOException e) {
                            return -1L;
                        }
                    }
            );

            setIOState(ioStateId, state);

            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "opened output state id=" + ioStateId);
            return new FFmpegIO(context, state);
        }
    }

    public static FFmpegOutput openNativeUrlOutput(String path) {
        AVIOContext context = new AVIOContext();
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "open native output stream: " + path + "...");
        avformat.avio_open(context, path, avformat.AVIO_FLAG_WRITE);
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "opened native output stream: " + path + ".");
        return new FFmpegOutput(new FFmpegIO(context, (Closeable) () -> avformat.avio_close(context)));
    }

    public static FFmpegInput openNativeUrlInput(String path) {
        AVIOContext context = new AVIOContext();
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "open native input stream: " + path + "...");
        avformat.avio_open(context, path, avformat.AVIO_FLAG_READ);
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "opened native input stream: " + path + ".");
        return new FFmpegInput(new FFmpegIO(context));
    }

    public FFmpegInput asInput() {
        return new FFmpegInput(this);
    }

    public FFmpegOutput asOutput() {
        return new FFmpegOutput(this);
    }


    public AVIOContext getContext() {
        return avioContext;
    }

    @Override
    public void close() throws Exception {
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegIO.close() called");

        for (AutoCloseable closeable : closeables)
            if (closeable != null) {
                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "closeable.close()... (" + closeable.toString() + ")");
                closeable.close();
            }

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegIO.close() completed");
    }

    public static FFmpegIO createNullIO() {
        AVIOContext context = new AVIOContext();
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "creating null I/O...");
        return new FFmpegIO(context, null, null);
    }
}
