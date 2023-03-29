import com.github.manevolent.ffmpeg4j.*;
import com.github.manevolent.ffmpeg4j.source.*;
import com.github.manevolent.ffmpeg4j.stream.source.*;
import org.junit.*;

import java.io.*;
import java.util.*;
import java.util.logging.*;

import static org.junit.Assert.*;

public class FFmpegInputTest {

    interface TestRunner<T> {
        void runTest(T object) throws Exception;
    }

    @BeforeClass
    public static void setupLogLevel() {
        Logging.DEBUG_LOG_LEVEL = Level.INFO;
    }

    private static void withSampleFile(TestRunner<FFmpegSourceStream> test) throws Exception {
        InputStream resource = FFmpegInputTest.class.getResourceAsStream("/sample-mp4-file-small.mp4");
        try (FFmpegInput input = FFmpegIO.openInputStream(resource, FFmpegIO.DEFAULT_BUFFER_SIZE)) {
            try (FFmpegSourceStream sourceStream = input.open("mp4")) {
                test.runTest(sourceStream);
            }
        }
    }

    /**
     * Double free can easily cause native crashes bringing the whole JVM down.
     * This test exists just to be sure there isn't a huge gap in the close logic.
     * Other gaps can still exist, however.
     */
    @Test(expected = IllegalStateException.class)
    public void testDoubleFree() throws Exception {
        withSampleFile(sourceStream -> {
            sourceStream.registerStreams().forEach(ss -> {
                try {
                    ss.close();
                } catch (Exception e) {
                    throw new AssertionError("Should not have failed to close", e);
                }
            });
        });
    }

    @Test
    public void testOpen() throws Exception {
       withSampleFile(sourceStream -> {
           Collection<?> substreams = sourceStream.registerStreams();

           assertEquals(2, substreams.size());

           VideoSourceSubstream vss = (VideoSourceSubstream) sourceStream.getSubstreams(VideoSourceSubstream.class).stream().findFirst()
                           .orElseThrow(() -> new AssertionError("No video substream, but was expected"));

           AudioSourceSubstream audioStream = (AudioSourceSubstream) sourceStream.getSubstreams(AudioSourceSubstream.class).stream().findFirst()
                           .orElseThrow(() -> new AssertionError("No audio substream, but was expected"));

           VideoFormat videoFormat = vss.getFormat();
           assertEquals("Unexpected video width", 320, videoFormat.getWidth());
           assertEquals("Unexpected video width", 240, videoFormat.getHeight());
           assertEquals("Unexpected video frame rate (FPS)", 15.0D, videoFormat.getFramesPerSecond(), 0d);

           AudioFormat audioFormat = audioStream.getFormat();
           assertEquals("Unexpected audio sample rate", 48_000, audioFormat.getSampleRate());
           assertEquals("Unexpected audio sample rate", 6, audioFormat.getChannels());
       });
    }

    @Test
    public void testVideoFrame() throws Exception {
        withSampleFile(sourceStream -> {
            sourceStream.registerStreams();

            VideoSourceSubstream vss = (VideoSourceSubstream) sourceStream.getSubstreams(VideoSourceSubstream.class).stream().findFirst()
                            .orElseThrow(() -> new AssertionError("No video substream, but was expected"));

            VideoFrame frame = vss.next();

            assertNotNull(frame);

            // The frame size should match the expected format
            assertEquals(vss.getFormat().getWidth(), frame.getWidth());
            assertEquals(vss.getFormat().getHeight(), frame.getHeight());

            // The first frame should be at 0:00, and should have a duration of 1/15th a second
            assertEquals(0D, frame.getPosition(), 0D);
            assertEquals(1D / vss.getFormat().getFramesPerSecond(), frame.getTime(), 0D);

            // The frame should have some data in it
            assertTrue(frame.getData().length > 0);

            // Advancing the frame should result in a realistic position on the next frame
            VideoFrame frame2 = vss.next();
            assertEquals(1D / vss.getFormat().getFramesPerSecond(), frame2.getPosition(), 0D);
        });
    }


    @Test
    public void testAudioFrame() throws Exception {
        withSampleFile(sourceStream -> {
            sourceStream.registerStreams();

            AudioSourceSubstream audioStream = (AudioSourceSubstream) sourceStream.getSubstreams(AudioSourceSubstream.class).stream().findFirst()
                            .orElseThrow(() -> new AssertionError("No audio substream, but was expected"));

            AudioFrame frame = audioStream.next();

            assertNotNull(frame);

            // The frame size should match the expected format
            assertEquals(audioStream.getFormat().getSampleRate(), frame.getFormat().getSampleRate());
            assertEquals(audioStream.getFormat().getChannels(), frame.getFormat().getChannels());

            // The first frame should be at 0:00, and should have an appropriate duration
            assertEquals(0D, frame.getPosition(), 0D);
            assertEquals((double)frame.getSamples().length
                            / (double)frame.getFormat().getSampleRate()
                            / (double)frame.getFormat().getChannels(),
                            frame.getTime(),
                            0.001D); // 1ms accuracy seems reasonable

            // The frame should have some data in it
            assertTrue(frame.getSamples().length > 0);

            // Advancing the frame should result in a realistic position on the next frame
            AudioFrame frame2 = audioStream.next();
            assertEquals(frame.getTime(), frame2.getPosition(), 0D);
        });
    }

    @Test
    public void testSeek_NearEnd() throws Exception {
        double seekPosition = 29.9D; // 29 seconds, near the end of the file
        double spf = 1/15D;
        withSampleFile(sourceStream -> {
            // Switch off decoding frame images to speed up the test
            // You should do this in your app, too, if you don't care about data while you seek.
            sourceStream.registerStreams().forEach(ss -> ss.setDecoding(false));
            double realSeek = sourceStream.seek(seekPosition);
            assertEquals("Seek was not accurate enough", seekPosition, realSeek, spf);
        });
    }

    @Test(expected = EOFException.class)
    public void testSeek_PastEnd() throws Exception {
        double seekPosition = 40D; // 40 seconds, past the end of the file
        double fps = 1/15D;
        withSampleFile(sourceStream -> {
            // Switch off decoding frame images to speed up the test
            // You should do this in your app, too, if you don't care about data while you seek.
            sourceStream.registerStreams().forEach(ss -> ss.setDecoding(false));
            double realSeek = sourceStream.seek(seekPosition);
            assertEquals("Seek was not accurate enough", seekPosition, realSeek, fps);
        });
    }

    @Test
    public void testSeek_Rewind() throws Exception {
        double seekPosition = 10D;
        withSampleFile(sourceStream -> {
            sourceStream.registerStreams().forEach(ss -> ss.setDecoding(false));
            sourceStream.seek(seekPosition);

            // Rewinding is not supported since we don't have seekable data sources;
            // we only support InputStreams right now.  It's definitely possible to
            // support seeking backwards with appropriate changes to FFmpegIO to support
            // a SeekableByteChannel or something like that.
            assertThrows(IllegalStateException.class, () -> {
                sourceStream.seek(0D);
            });
        });
    }

}
