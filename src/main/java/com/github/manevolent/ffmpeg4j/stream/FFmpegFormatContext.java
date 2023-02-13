package com.github.manevolent.ffmpeg4j.stream;

import org.bytedeco.ffmpeg.avformat.*;

import java.util.HashMap;
import java.util.Map;

public interface FFmpegFormatContext {
    AVFormatContext getFormatContext();

    default void setFlag(int flag, boolean value) {
        if (!value)
            getFormatContext().flags(getFormatContext().flags() & ~flag);
        else
            getFormatContext().flags(getFormatContext().flags() | flag);
    }


    default void setFlag(AVFormatFlag flag, boolean value) {
        setFlag(flag.flag, value);
    }

    default boolean isFlagSet(AVFormatFlag flag) {
        return isFlagSet(flag.flag);
    }

    default boolean isFlagSet(int flag) {
        return (getFormatContext().flags() & flag) == flag;
    }

    default Map<AVFormatFlag, Boolean> getFlags() {
        Map<AVFormatFlag, Boolean> flags = new HashMap<>();

        for (AVFormatFlag flag : AVFormatFlag.values())
            flags.put(flag, isFlagSet(flag));

        return flags;
    }

    enum AVFormatFlag {
        AVFMT_FLAG_GENPTS(0x0001),
        AVFMT_FLAG_IGNIDX(0x0002),
        AVFMT_FLAG_NONBLOCK(0x0004),
        AVFMT_FLAG_IGNDTS(0x0008),
        AVFMT_FLAG_NOFILLIN(0x0010),
        AVFMT_FLAG_NOPARSE (0x0020),
        AVFMT_FLAG_NOBUFFER(0x0040),
        AVFMT_FLAG_CUSTOM_IO(0x0080),
        AVFMT_FLAG_DISCARD_CORRUPT(0x0100),
        AVFMT_FLAG_FLUSH_PACKETS(0x0200),
        AVFMT_FLAG_BITEXACT(0x0400),
        AVFMT_FLAG_MP4A_LATM(0x0800),
        AVFMT_FLAG_SORT_DTS(0x1000),
        AVFMT_FLAG_PRIV_OPT(0x2000),
        AVFMT_FLAG_KEEP_SIDE_DATA(0x4000)


        ;

        private final int flag;

        AVFormatFlag(int flag) {
            this.flag = flag;
        }
    }
}
