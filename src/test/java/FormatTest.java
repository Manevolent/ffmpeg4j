import com.github.manevolent.ffmpeg4j.FFmpeg;
import com.github.manevolent.ffmpeg4j.FFmpegException;

public class FormatTest {
    public static void main(String[] args) throws FFmpegException {
        FFmpeg.register();
        FFmpeg.getInputFormatByName("mp3");
    }
}
