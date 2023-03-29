package com.github.manevolent.ffmpeg4j;

import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.global.avcodec;

public interface FFmpegStreamContext {

    AVCodecContext getCodecContext();

    AVStream getStream();

    default void copyParameters(FFmpegStreamContext target) throws FFmpegException {
        int ret = avcodec.avcodec_parameters_copy(getStream().codecpar(), target.getStream().codecpar());
        FFmpegError.checkError("avcodec_parameters_copy", ret);
    }

}
