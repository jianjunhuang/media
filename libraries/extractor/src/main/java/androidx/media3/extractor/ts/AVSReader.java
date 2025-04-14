package androidx.media3.extractor.ts;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public class AVSReader implements ElementaryStreamReader {

  private @MonotonicNonNull TrackOutput output;
  private @MonotonicNonNull String formatId;

  @Override
  public void seek() {

  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput,
      PesReader.TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {

  }

  @Override
  public void consume(ParsableByteArray data) throws ParserException {
    output.format(new Format.Builder()
            .setId(formatId)
            .setSampleMimeType(MimeTypes.VIDEO_AVS)
        .build());
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {

  }
}
