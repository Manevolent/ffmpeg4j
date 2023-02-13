
import com.github.manevolent.ffmpeg4j.*;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.junit.*;

import java.util.logging.*;

import static org.junit.Assert.assertEquals;

public class FFmpegTest {

    @BeforeClass
    public static void setupLogLevel() {
        Logging.DEBUG_LOG_LEVEL = Level.INFO;
    }

    @Test(expected = FFmpegException.class)
    public void testGetInputFormat_Invalid() throws FFmpegException {
        FFmpeg.getInputFormatByName("does_not_exist");
    }

    @Test
    public void testGetInputFormat() throws FFmpegException {
        AVInputFormat mp3Format = FFmpeg.getInputFormatByName("mp3");
        assertEquals("mp3", mp3Format.name().getString());

        AVInputFormat mp4Format = FFmpeg.getInputFormatByName("mp4");
        assertEquals("QuickTime / MOV", mp4Format.long_name().getString());

        AVInputFormat movFormat = FFmpeg.getInputFormatByName("mov");
        assertEquals(mp4Format, movFormat);

        AVInputFormat webmFormat = FFmpeg.getInputFormatByName("webm");
        assertEquals("Matroska / WebM", webmFormat.long_name().getString());
    }


    @Test(expected = FFmpegException.class)
    public void testGetOutputFormat_Invalid() throws FFmpegException {
        AVOutputFormat mp3Format = FFmpeg.getOutputFormatByName("does_not_exist");
    }

    @Test
    public void testGetOutputFormat() throws FFmpegException {
        AVOutputFormat mp3Format = FFmpeg.getOutputFormatByName("mp3");
        assertEquals("mp3", mp3Format.name().getString());

        AVOutputFormat mp4Format = FFmpeg.getOutputFormatByName("mp4");
        assertEquals("MP4 (MPEG-4 Part 14)", mp4Format.long_name().getString());

        AVOutputFormat webmFormat = FFmpeg.getOutputFormatByName("webm");
        assertEquals("WebM", webmFormat.long_name().getString());
    }

    @Test
    public void testGetCodecByName() throws FFmpegException {
        AVCodec mp3Codec = FFmpeg.getCodecByName("mp3");
        assertEquals("mp3", mp3Codec.name().getString());

        AVCodec h264Codec = FFmpeg.getCodecByName("h264");
        assertEquals("h264", h264Codec.name().getString());
    }

    @Test
    public void testGetOutputFormatByMime() throws FFmpegException {
        AVOutputFormat mp4Format = FFmpeg.getOutputFormatByMime("audio/mpeg");
        assertEquals("mp2", mp4Format.name().getString());
    }

    @Test(expected = FFmpegException.class)
    public void testGetOutputFormatByMime_Invalid() throws FFmpegException {
        FFmpeg.getOutputFormatByMime("bad/mime_type");
    }

}
