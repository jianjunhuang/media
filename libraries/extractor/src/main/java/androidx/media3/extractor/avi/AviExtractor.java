/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.avi;

import static java.lang.Math.max;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.JLog;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.NoOpExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from the AVI container format.
 *
 * <p>Spec: https://docs.microsoft.com/en-us/windows/win32/directshow/avi-riff-file-reference.
 */
@UnstableApi
public final class AviExtractor implements Extractor {

  private static final String TAG = "AviExtractor";

  public static final int FOURCC_RIFF = 0x46464952;
  public static final int FOURCC_AVI_ = 0x20495641; // AVI<space>
  public static final int FOURCC_AVIX = 0x58495641;
  public static final int FOURCC_LIST = 0x5453494c;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_avih = 0x68697661;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_hdrl = 0x6c726468;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_strl = 0x6c727473;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_movi = 0x69766f6d;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_idx1 = 0x31786469;

  public static final int FOURCC_JUNK = 0x4b4e554a;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_strf = 0x66727473;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_strn = 0x6e727473;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_strh = 0x68727473;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_auds = 0x73647561;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_txts = 0x73747874;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_vids = 0x73646976;

  /** Parser states. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    STATE_SKIPPING_TO_HDRL,
    STATE_READING_HDRL_HEADER,
    STATE_READING_HDRL_BODY,
    STATE_FINDING_MOVI_HEADER,
    STATE_FINDING_IDX1_HEADER,
    STATE_READING_IDX1_BODY,
    STATE_BUILDING_MOVI_SEEK_MAP,
    STATE_READING_SAMPLES,
  })
  private @interface State {}

  private static final int STATE_SKIPPING_TO_HDRL = 0;
  private static final int STATE_READING_HDRL_HEADER = 1;
  private static final int STATE_READING_HDRL_BODY = 2;
  private static final int STATE_FINDING_MOVI_HEADER = 3;
  private static final int STATE_FINDING_IDX1_HEADER = 4;
  private static final int STATE_READING_IDX1_BODY = 5;
  private static final int STATE_BUILDING_MOVI_SEEK_MAP = 6;
  private static final int STATE_READING_SAMPLES = 7;

  private static final int AVIIF_KEYFRAME = 16;
  private static final int FALLBACK_KEYFRAME_PEEK_BYTES = 8 + 4 * 1024;

  /**
   * Flags controlling the behavior of the extractor. Possible flag value is {@link
   * #FLAG_EMIT_RAW_SUBTITLE_DATA}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef(
      flag = true,
      value = {FLAG_EMIT_RAW_SUBTITLE_DATA})
  public @interface Flags {}

  /**
   * Flag to use the source subtitle formats without modification. If unset, subtitles will be
   * transcoded to {@link MimeTypes#APPLICATION_MEDIA3_CUES} during extraction.
   */
  public static final int FLAG_EMIT_RAW_SUBTITLE_DATA = 1;

  /**
   * Maximum size to skip using {@link ExtractorInput#skip}. Boxes larger than this size are skipped
   * using {@link #RESULT_SEEK}.
   */
  private static final long RELOAD_MINIMUM_SEEK_DISTANCE = 256 * 1024;
  private static final long MAX_MOVI_RESYNC_SEARCH_BYTES = 8 * 1024;
  private static final long MOVI_PRESCAN_LOG_INTERVAL_BYTES = 4 * 1024 * 1024;

  private final ParsableByteArray scratch;
  private final byte[] fallbackKeyFrameScratch;
  private final byte[] moviResyncScratch;
  private final ChunkHeaderHolder chunkHeaderHolder;
  private final boolean parseSubtitlesDuringExtraction;
  private final SubtitleParser.Factory subtitleParserFactory;

  private @State int state;
  private ExtractorOutput extractorOutput;
  private @MonotonicNonNull AviMainHeaderChunk aviHeader;
  private long durationUs;
  private ChunkReader[] chunkReaders;

  private long pendingReposition;
  @Nullable private ChunkReader currentChunkReader;
  private int hdrlSize;
  private long moviStart;
  private long moviEnd;
  private int idx1BodySize;
  private boolean seekMapHasBeenOutput;
  private boolean buildingIndexFromReadPath;
  private long moviResyncStartPosition;
  private long phaseStartTimeNs;
  private boolean loggedMoviFound;
  private boolean loggedFirstVideoSample;
  private boolean loggedFirstAudioSample;
  private long pendingSeekTimeUsAfterIndexRebuild;
  private long lastMoviPrescanLoggedPosition;

  /**
   * @deprecated Use {@link #AviExtractor(int, SubtitleParser.Factory)} instead.
   */
  @Deprecated
  public AviExtractor() {
    this(FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED);
  }

  /**
   * Constructs an instance.
   *
   * @param extractorFlags Flags that control the extractor's behavior.
   * @param subtitleParserFactory The {@link SubtitleParser.Factory} for parsing subtitles during
   *     extraction.
   */
  public AviExtractor(@Flags int extractorFlags, SubtitleParser.Factory subtitleParserFactory) {
    this.subtitleParserFactory = subtitleParserFactory;
    parseSubtitlesDuringExtraction = (extractorFlags & FLAG_EMIT_RAW_SUBTITLE_DATA) == 0;
    scratch = new ParsableByteArray(/* limit= */ 12);
    fallbackKeyFrameScratch = new byte[FALLBACK_KEYFRAME_PEEK_BYTES];
    moviResyncScratch = new byte[(int) MAX_MOVI_RESYNC_SEARCH_BYTES + 12];
    chunkHeaderHolder = new ChunkHeaderHolder();
    extractorOutput = new NoOpExtractorOutput();
    chunkReaders = new ChunkReader[0];
    moviStart = C.INDEX_UNSET;
    moviEnd = C.INDEX_UNSET;
    hdrlSize = C.LENGTH_UNSET;
    durationUs = C.TIME_UNSET;
    buildingIndexFromReadPath = false;
    moviResyncStartPosition = C.INDEX_UNSET;
    phaseStartTimeNs = C.TIME_UNSET;
    pendingSeekTimeUsAfterIndexRebuild = C.TIME_UNSET;
    lastMoviPrescanLoggedPosition = C.INDEX_UNSET;
  }

  // Extractor implementation.

  @Override
  public void init(ExtractorOutput output) {
    this.state = STATE_SKIPPING_TO_HDRL;
    this.extractorOutput =
        parseSubtitlesDuringExtraction
            ? new SubtitleTranscodingExtractorOutput(output, subtitleParserFactory)
            : output;
    pendingReposition = C.INDEX_UNSET;
    buildingIndexFromReadPath = false;
    clearMoviResyncState();
    phaseStartTimeNs = C.TIME_UNSET;
    loggedMoviFound = false;
    loggedFirstVideoSample = false;
    loggedFirstAudioSample = false;
    pendingSeekTimeUsAfterIndexRebuild = C.TIME_UNSET;
    lastMoviPrescanLoggedPosition = C.INDEX_UNSET;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    input.peekFully(scratch.getData(), /* offset= */ 0, /* length= */ 12);
    scratch.setPosition(0);
    if (scratch.readLittleEndianInt() != FOURCC_RIFF) {
      return false;
    }
    scratch.skipBytes(4); // Skip the RIFF chunk length.
    return scratch.readLittleEndianInt() == FOURCC_AVI_;
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    maybeStartPhaseTimer();
    if (resolvePendingReposition(input, seekPosition)) {
      return RESULT_SEEK;
    }
    switch (state) {
      case STATE_SKIPPING_TO_HDRL:
        JLog.d(TAG, "read -- STATE_SKIPPING_TO_HDRL");
        // Check for RIFF and AVI fourcc's just in case the caller did not sniff, in order to
        // provide a meaningful error if the input is not an AVI file.
        if (sniff(input)) {
          input.skipFully(/* length= */ 12);
        } else {
          throw ParserException.createForMalformedContainer(
              /* message= */ "AVI Header List not found", /* cause= */ null);
        }
        state = STATE_READING_HDRL_HEADER;
        return RESULT_CONTINUE;
      case STATE_READING_HDRL_HEADER:
        JLog.d(TAG, "read -- STATE_READING_HDRL_HEADER");
        input.readFully(scratch.getData(), /* offset= */ 0, /* length= */ 12);
        scratch.setPosition(0);
        chunkHeaderHolder.populateWithListHeaderFrom(scratch);
        if (chunkHeaderHolder.listType != FOURCC_hdrl) {
          throw ParserException.createForMalformedContainer(
              /* message= */ "hdrl expected, found: " + chunkHeaderHolder.listType,
              /* cause= */ null);
        }
        hdrlSize = chunkHeaderHolder.size;
        state = STATE_READING_HDRL_BODY;
        return RESULT_CONTINUE;
      case STATE_READING_HDRL_BODY:
        JLog.d(TAG, "read -- STATE_READING_HDRL_BODY");
        // hdrlSize includes the LIST type (hdrl), so we subtract 4 to the size.
        int bytesToRead = hdrlSize - 4;
        ParsableByteArray hdrlBody = new ParsableByteArray(bytesToRead);
        input.readFully(hdrlBody.getData(), /* offset= */ 0, bytesToRead);
        parseHdrlBody(hdrlBody);
        state = STATE_FINDING_MOVI_HEADER;
        return RESULT_CONTINUE;
      case STATE_FINDING_MOVI_HEADER:
        JLog.d(TAG, "read -- STATE_FINDING_MOVI_HEADER");
        if (moviStart != C.INDEX_UNSET && input.getPosition() != moviStart) {
          pendingReposition = moviStart;
          return RESULT_CONTINUE;
        }
        input.peekFully(scratch.getData(), /* offset= */ 0, /* length= */ 12);
        input.resetPeekPosition();
        scratch.setPosition(0);
        chunkHeaderHolder.populateFrom(scratch);
        int listType = scratch.readLittleEndianInt();
        if (chunkHeaderHolder.chunkType == FOURCC_RIFF) {
          // We are at the start of the file. The movi chunk is in the RIFF chunk, so we skip the
          // header, so as to read the RIFF chunk's body.
          input.skipFully(12);
          return RESULT_CONTINUE;
        }
        if (chunkHeaderHolder.chunkType != FOURCC_LIST || listType != FOURCC_movi) {
          // The chunk header (8 bytes) plus the whole body.
          pendingReposition = input.getPosition() + chunkHeaderHolder.size + 8;
          return RESULT_CONTINUE;
        }
        moviStart = input.getPosition();
        // Size includes the list type, but not the LIST or size fields, so we add 8.
        moviEnd = moviStart + chunkHeaderHolder.size + 8;
        if (!loggedMoviFound) {
          loggedMoviFound = true;
          logPhase("movi-found", "moviStart=" + moviStart + ", moviEnd=" + moviEnd);
        }
        if (!seekMapHasBeenOutput) {
          if (Assertions.checkNotNull(aviHeader).hasIndex()) {
            state = STATE_FINDING_IDX1_HEADER;
            pendingReposition = moviEnd;
            return RESULT_CONTINUE;
          } else {
            startReadingSamplesWithProgressiveIndex(input.getPosition() + 12);
            return RESULT_CONTINUE;
          }
        }
        // No need to parse the idx1, so we start reading the samples from the movi chunk straight
        // away. We skip 12 bytes to move to the start of the movi's body.
        pendingReposition = input.getPosition() + 12;
        state = STATE_READING_SAMPLES;
        return RESULT_CONTINUE;
      case STATE_FINDING_IDX1_HEADER:
        //JLog.d(TAG, "read -- STATE_FINDING_IDX1_HEADER");
        if (!peekFullyQuietly(input, scratch.getData(), /* length= */ 8)) {
          startReadingSamplesWithProgressiveIndex(moviStart + 12);
          return RESULT_CONTINUE;
        }
        input.readFully(scratch.getData(), /* offset= */ 0, /* length= */ 8);
        scratch.setPosition(0);
        int idx1Fourcc = scratch.readLittleEndianInt();
        int boxSize = scratch.readLittleEndianInt();
        if (idx1Fourcc == FOURCC_idx1) {
          logPhase("idx1-found", "idx1BodySize=" + boxSize);
          state = STATE_READING_IDX1_BODY;
          idx1BodySize = boxSize;
        } else if (boxSize < 0) {
          startReadingSamplesWithProgressiveIndex(moviStart + 12);
        } else {
          // This one is not idx1, skip to the next box.
          long nextPosition = input.getPosition() + boxSize;
          long inputLength = input.getLength();
          if (nextPosition < input.getPosition()
              || (inputLength != C.LENGTH_UNSET && nextPosition >= inputLength)) {
            startReadingSamplesWithProgressiveIndex(moviStart + 12);
          } else {
            pendingReposition = nextPosition;
          }
        }
        return RESULT_CONTINUE;
      case STATE_READING_IDX1_BODY:
        JLog.d(TAG, "read -- STATE_READING_IDX1_BODY");
        ParsableByteArray idx1Body = new ParsableByteArray(idx1BodySize);
        input.readFully(idx1Body.getData(), /* offset= */ 0, /* length= */ idx1BodySize);
        parseIdx1Body(idx1Body);
        state = STATE_READING_SAMPLES;
        pendingReposition = moviStart;
        return RESULT_CONTINUE;
      case STATE_BUILDING_MOVI_SEEK_MAP:
        return buildSeekMapFromMovi(input);
      case STATE_READING_SAMPLES:
        //JLog.d(TAG, "read -- STATE_READING_SAMPLES");
        return readMoviChunks(input);
      default:
        throw new AssertionError(); // Should never happen.
    }
  }

  @Override
  public void seek(long position, long timeUs) {
    maybeStartPhaseTimer();
    logPhase(
        "seek-request",
        "position=" + position + ", timeUs=" + timeUs + ", progressive=" + buildingIndexFromReadPath
            + ", state=" + state
            + ", pendingSeekTimeUsAfterIndexRebuild=" + pendingSeekTimeUsAfterIndexRebuild);
    if (state == STATE_BUILDING_MOVI_SEEK_MAP) {
      JLog.w(
          TAG,
          "seek arrived while building movi seek map (issue #1). previousPendingSeekTimeUs="
              + pendingSeekTimeUsAfterIndexRebuild
              + ", newTimeUs="
              + timeUs
              + ", currentPendingReposition="
              + pendingReposition
              + ", moviStart="
              + moviStart
              + ". Without mitigation, prescan resumes from the new position and chunkCount will be"
              + " under-counted.");
    }
    pendingReposition = C.INDEX_UNSET;
    currentChunkReader = null;
    clearMoviResyncState();
    if (shouldRebuildSeekMapForSeek(timeUs)) {
      prepareMoviSeekMapRebuildForSeek(timeUs);
      return;
    }
    for (ChunkReader chunkReader : chunkReaders) {
      chunkReader.seekToPosition(position);
    }
    if (position == 0) {
      if (chunkReaders.length == 0) {
        // Still unprepared.
        state = STATE_SKIPPING_TO_HDRL;
      } else {
        state = STATE_FINDING_MOVI_HEADER;
      }
      return;
    }
    state = STATE_READING_SAMPLES;
  }

  @Override
  public void release() {
    // Nothing to release.
  }

  // Internal methods.

  /**
   * Returns whether a {@link #RESULT_SEEK} is required for the pending reposition. A seek may not
   * be necessary when the desired position (as held by {@link #pendingReposition}) is after the
   * {@link ExtractorInput#getPosition() current position}, but not further than {@link
   * #RELOAD_MINIMUM_SEEK_DISTANCE}.
   */
  private boolean resolvePendingReposition(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    boolean needSeek = false;
    if (pendingReposition != C.INDEX_UNSET) {
      long currentPosition = input.getPosition();
      if (pendingReposition < currentPosition
          || pendingReposition > currentPosition + RELOAD_MINIMUM_SEEK_DISTANCE) {
        seekPosition.position = pendingReposition;
        needSeek = true;
      } else {
        // The distance to the target position is short enough that it makes sense to just skip the
        // bytes, instead of doing a seek which might re-create an HTTP connection.
        input.skipFully((int) (pendingReposition - currentPosition));
      }
    }
    pendingReposition = C.INDEX_UNSET;
    return needSeek;
  }

  private void parseHdrlBody(ParsableByteArray hrdlBody) throws IOException {
    ListChunk headerList = ListChunk.parseFrom(FOURCC_hdrl, hrdlBody);
    if (headerList.getType() != FOURCC_hdrl) {
      throw ParserException.createForMalformedContainer(
          /* message= */ "Unexpected header list type " + headerList.getType(), /* cause= */ null);
    }
    @Nullable AviMainHeaderChunk aviHeader = headerList.getChild(AviMainHeaderChunk.class);
    if (aviHeader == null) {
      throw ParserException.createForMalformedContainer(
          /* message= */ "AviHeader not found", /* cause= */ null);
    }
    this.aviHeader = aviHeader;
    JLog.d(TAG, "parseHdrlBody --- AviMainHeaderChunk: flags=" + aviHeader.flags + ", totalFrames=" + aviHeader.totalFrames
            + ", streams=" + aviHeader.streams + ", frameDurationUs=" + aviHeader.frameDurationUs);
    // This is usually wrong, so it will be overwritten by video if present
    durationUs = aviHeader.totalFrames * (long) aviHeader.frameDurationUs;
    ArrayList<ChunkReader> chunkReaderList = new ArrayList<>();
    int streamId = 0;
    for (AviChunk aviChunk : headerList.children) {
      if (aviChunk.getType() == FOURCC_strl) {
        ListChunk streamList = (ListChunk) aviChunk;
        // Note the streamId needs to increment even if the corresponding `strl` is discarded.
        // See
        // https://docs.microsoft.com/en-us/windows/win32/directshow/avi-riff-file-reference#avi-stream-headers.
        @Nullable ChunkReader chunkReader = processStreamList(streamList, streamId++);
        if (chunkReader != null) {
          chunkReaderList.add(chunkReader);
        }
      }
    }
    chunkReaders = chunkReaderList.toArray(new ChunkReader[0]);
    extractorOutput.endTracks();
  }

  /** Builds and outputs the {@link SeekMap} from the idx1 chunk. */
  private void parseIdx1Body(ParsableByteArray body) {
    long seekOffset = peekSeekOffset(body);
    while (body.bytesLeft() >= 16) {
      int chunkId = body.readLittleEndianInt();
      int flags = body.readLittleEndianInt();
      long offset = body.readLittleEndianInt() + seekOffset;
      body.skipBytes(4); // Ignore size.
      ChunkReader chunkReader = getChunkReader(chunkId);
      if (chunkReader == null) {
        // We ignore unknown chunk IDs.
        continue;
      }
      chunkReader.appendIndexChunk(
          offset, /* isKeyFrame= */ (flags & AVIIF_KEYFRAME) == AVIIF_KEYFRAME);
    }
    for (ChunkReader chunkReader : chunkReaders) {
      chunkReader.commitIndex();
    }
    buildingIndexFromReadPath = false;
    seekMapHasBeenOutput = true;
    if (chunkReaders.length == 0) {
      extractorOutput.seekMap(new SeekMap.Unseekable(durationUs));
      logPhase("seekmap-output", "source=idx1, seekable=false");
    } else {
      extractorOutput.seekMap(new AviSeekMap(durationUs));
      logPhase("seekmap-output", "source=idx1, seekable=true");
    }
  }

  private void startBuildingMoviSeekMap() {
    buildingIndexFromReadPath = false;
    state = STATE_BUILDING_MOVI_SEEK_MAP;
    pendingReposition = moviStart;
    clearMoviResyncState();
    lastMoviPrescanLoggedPosition = moviStart;
    logPhase("full-prescan-start", "from=" + moviStart);
  }

  private void startReadingSamplesWithProgressiveIndex(long startPosition) {
    buildingIndexFromReadPath = true;
    if (!seekMapHasBeenOutput) {
      // The seek map is published immediately so the UI can expose seek affordances. The underlying
      // chunk readers keep gaining offsets while playback advances, so actual seek precision
      // improves over time instead of blocking startup on a full movi prescan.
      extractorOutput.seekMap(new AviSeekMap(durationUs));
      seekMapHasBeenOutput = true;
      logPhase("seekmap-output", "source=progressive, seekable=true");
    }
    logPhase("progressive-index-start", "startPosition=" + startPosition);
    state = STATE_READING_SAMPLES;
    pendingReposition = startPosition;
    clearMoviResyncState();
  }

  private void prepareMoviSeekMapRebuildForSeek(long timeUs) {
    pendingSeekTimeUsAfterIndexRebuild = timeUs;
    // Exact seek uses the requested time, not the sync point time, so reusing a partial
    // progressive index can force the player to discard a long range of samples after the seek.
    // Rebuild from movi start to recover a more accurate landing position before resuming reads.
    for (ChunkReader chunkReader : chunkReaders) {
      chunkReader.resetFallbackIndex();
    }
    JLog.i(TAG, "Rebuilding movi seek map for exact seek. timeUs=" + timeUs);
    startBuildingMoviSeekMap();
  }

  private int buildSeekMapFromMovi(ExtractorInput input) throws IOException {
    long scanEndPosition = getMoviReadUpperBound(input);
    while (true) {
      maybeLogMoviPrescanProgress(input.getPosition(), scanEndPosition);
      if (input.getPosition() >= scanEndPosition) {
        finishBuildingMoviSeekMap();
        return RESULT_CONTINUE;
      }
      if ((input.getPosition() & 1) == 1) {
        if (input.getPosition() + 1 >= scanEndPosition) {
          finishBuildingMoviSeekMap();
          return RESULT_CONTINUE;
        }
        input.skipFully(1);
        continue;
      }
      long bytesRemaining = scanEndPosition - input.getPosition();
      if (bytesRemaining < 8) {
        finishBuildingMoviSeekMap();
        return RESULT_CONTINUE;
      }
      int bytesToPeek = bytesRemaining >= 12 ? 12 : 8;
      if (!peekFullyQuietly(input, scratch.getData(), bytesToPeek)) {
        finishBuildingMoviSeekMap();
        return RESULT_CONTINUE;
      }
      scratch.setPosition(0);
      long chunkStart = input.getPosition();
      int chunkType = scratch.readLittleEndianInt();
      int size = scratch.readLittleEndianInt();
      if (size < 0) {
        return maybeResyncMoviSeekMap(
            input, scanEndPosition, "Negative chunk size while scanning movi", chunkType, size);
      }
      if (chunkType == FOURCC_LIST || chunkType == FOURCC_RIFF) {
        if (bytesRemaining < 12) {
          finishBuildingMoviSeekMap();
          return RESULT_CONTINUE;
        }
        if (size < 4) {
          return maybeResyncMoviSeekMap(
              input,
              scanEndPosition,
              "Container size smaller than type field while scanning movi",
              chunkType,
              size);
        }
        int containerType = scratch.readLittleEndianInt();
        long nextPosition =
            chunkType == FOURCC_RIFF && !isAviRiffType(containerType)
                ? chunkStart + 8L + size
                : chunkStart + 12L;
        if (!isValidForwardPosition(chunkStart, nextPosition, scanEndPosition)) {
          return maybeResyncMoviSeekMap(
              input,
              scanEndPosition,
              "Container next position out of bounds while scanning movi",
              chunkType,
              size);
        } else if (nextPosition == scanEndPosition) {
          clearMoviResyncState();
          finishBuildingMoviSeekMap();
          return RESULT_CONTINUE;
        } else if (!advancePrescanInputTo(input, nextPosition)) {
          return RESULT_CONTINUE;
        } else {
          clearMoviResyncState();
          continue;
        }
      }
      ChunkReader chunkReader = chunkType == FOURCC_JUNK ? null : getChunkReader(chunkType);
      int adjustedSize =
          chunkReader != null
              ? maybeExtendChunkSize(input, chunkReader, chunkType, size, scanEndPosition)
              : size;
      long nextPosition = chunkStart + 8L + adjustedSize;
      if (!isValidForwardPosition(chunkStart, nextPosition, scanEndPosition)) {
        return maybeResyncMoviSeekMap(
            input,
            scanEndPosition,
            "Chunk next position out of bounds while scanning movi",
            chunkType,
            adjustedSize);
      } else if (nextPosition == scanEndPosition) {
        if (chunkType != FOURCC_JUNK && chunkReader != null) {
          chunkReader.appendSeekChunk(
              chunkStart,
              detectFallbackKeyFrame(input, chunkReader, chunkType, adjustedSize, bytesRemaining));
        }
        clearMoviResyncState();
        finishBuildingMoviSeekMap();
        return RESULT_CONTINUE;
      }
      if (chunkReader != null) {
        chunkReader.appendSeekChunk(
            chunkStart,
            detectFallbackKeyFrame(input, chunkReader, chunkType, adjustedSize, bytesRemaining));
      }
      clearMoviResyncState();
      if (!advancePrescanInputTo(input, nextPosition)) {
        return RESULT_CONTINUE;
      }
    }
  }

  private void finishBuildingMoviSeekMap() {
    JLog.i(
        TAG,
        "finishBuildingMoviSeekMap entry. lastScanPosition=" + lastMoviPrescanLoggedPosition
            + ", moviStart=" + moviStart
            + ", moviEnd=" + moviEnd
            + ", pendingReposition=" + pendingReposition
            + ", pendingSeekTimeUsAfterIndexRebuild=" + pendingSeekTimeUsAfterIndexRebuild);
    for (ChunkReader chunkReader : chunkReaders) {
      chunkReader.commitIndex();
    }
    boolean seekable = hasSeekableChunkReaders();
    seekMapHasBeenOutput = true;
    extractorOutput.seekMap(
        seekable ? new AviSeekMap(durationUs) : new SeekMap.Unseekable(durationUs));
    logPhase("full-prescan-finished", "seekable=" + seekable);
    buildingIndexFromReadPath = false;
    state = STATE_READING_SAMPLES;
    if (pendingSeekTimeUsAfterIndexRebuild != C.TIME_UNSET) {
      long seekPosition =
          seekable
              ? new AviSeekMap(durationUs).getSeekPoints(pendingSeekTimeUsAfterIndexRebuild).first.position
              : moviStart;
      for (ChunkReader chunkReader : chunkReaders) {
        chunkReader.seekToPosition(seekPosition);
      }
      JLog.i(
          TAG,
          "Finished movi seek-map rebuild for exact seek. timeUs="
              + pendingSeekTimeUsAfterIndexRebuild
              + ", position="
              + seekPosition
              + ", seekable="
              + seekable);
      pendingReposition = seekPosition;
      pendingSeekTimeUsAfterIndexRebuild = C.TIME_UNSET;
    } else {
      pendingReposition = moviStart;
    }
    clearMoviResyncState();
  }

  private void maybeLogMoviPrescanProgress(long position, long scanEndPosition) {
    if (lastMoviPrescanLoggedPosition == C.INDEX_UNSET
        || position - lastMoviPrescanLoggedPosition < MOVI_PRESCAN_LOG_INTERVAL_BYTES) {
      return;
    }
    long scannedBytes = position - moviStart;
    long totalBytes = scanEndPosition - moviStart;
    long percent = totalBytes > 0 ? (100 * scannedBytes) / totalBytes : 0;
    JLog.i(
        TAG,
        "moviPrescanProgress scannedBytes="
            + scannedBytes
            + ", totalBytes="
            + totalBytes
            + ", percent="
            + percent
            + ", position="
            + position);
    lastMoviPrescanLoggedPosition = position;
  }

  private boolean advancePrescanInputTo(ExtractorInput input, long nextPosition) throws IOException {
    long currentPosition = input.getPosition();
    long bytesToSkip = nextPosition - currentPosition;
    if (bytesToSkip < 0) {
      pendingReposition = nextPosition;
      return false;
    }
    if (bytesToSkip <= Integer.MAX_VALUE) {
      input.skipFully((int) bytesToSkip);
      return true;
    }
    pendingReposition = nextPosition;
    return false;
  }

  private boolean hasSeekableChunkReaders() {
    boolean hasIndexedChunks = false;
    boolean hasVideoWithoutKeyframes = false;
    for (ChunkReader chunkReader : chunkReaders) {
      hasIndexedChunks |= chunkReader.hasIndexChunks();
      hasVideoWithoutKeyframes |=
          chunkReader.isVideo() && chunkReader.hasIndexChunks() && !chunkReader.hasKeyFrameIndex();
    }
    return hasIndexedChunks && !hasVideoWithoutKeyframes;
  }

  private boolean shouldRebuildSeekMapForSeek(long timeUs) {
    if (!buildingIndexFromReadPath || timeUs <= 0) {
      return false;
    }
    for (ChunkReader chunkReader : chunkReaders) {
      if (!chunkReader.isVideo()) {
        continue;
      }
      if (!chunkReader.hasProgressiveSeekCoverageForTimeUs(timeUs)) {
        return true;
      }
    }
    return false;
  }

  private void maybeStartPhaseTimer() {
    if (phaseStartTimeNs == C.TIME_UNSET) {
      phaseStartTimeNs = System.nanoTime();
    }
  }

  private void logPhase(String phase, String details) {
    maybeStartPhaseTimer();
    JLog.i(TAG, "phase=" + phase + ", elapsedMs=" + getElapsedMs() + ", " + details);
  }

  private long getElapsedMs() {
    return phaseStartTimeNs == C.TIME_UNSET ? 0 : (System.nanoTime() - phaseStartTimeNs) / 1_000_000;
  }

  private static boolean peekFullyQuietly(ExtractorInput input, byte[] target, int length)
      throws IOException {
    int totalBytesPeeked = 0;
    while (totalBytesPeeked < length) {
      int bytesPeeked = input.peek(target, totalBytesPeeked, length - totalBytesPeeked);
      if (bytesPeeked == C.RESULT_END_OF_INPUT) {
        input.resetPeekPosition();
        return false;
      }
      totalBytesPeeked += bytesPeeked;
    }
    input.resetPeekPosition();
    return true;
  }

  private boolean detectFallbackKeyFrame(
      ExtractorInput input, ChunkReader chunkReader, int chunkType, int size, long bytesRemaining)
      throws IOException {
    int bytesToInspect = chunkReader.getFallbackKeyFrameSearchBytes(chunkType, size);
    if (bytesToInspect == 0) {
      return chunkReader.isFallbackKeyFrame(chunkType, fallbackKeyFrameScratch, 0, 0);
    }
    bytesToInspect = Math.min(bytesToInspect, (int) bytesRemaining - 8);
    return bytesToInspect > 0
        && peekFullyQuietly(input, fallbackKeyFrameScratch, /* length= */ 8 + bytesToInspect)
        && chunkReader.isFallbackKeyFrame(
            chunkType, fallbackKeyFrameScratch, /* offset= */ 8, 8 + bytesToInspect);
  }

  private long getMoviReadUpperBound(ExtractorInput input) {
    long inputLength = input.getLength();
    return inputLength == C.LENGTH_UNSET ? moviEnd : inputLength;
  }

  private static boolean isValidForwardPosition(long currentPosition, long nextPosition, long upperBound) {
    return nextPosition >= currentPosition && nextPosition <= upperBound;
  }

  private static boolean isAviRiffType(int riffType) {
    return riffType == FOURCC_AVI_ || riffType == FOURCC_AVIX;
  }

  private int maybeResyncMoviRead(
      ExtractorInput input, long readEndPosition, String reason, int chunkType, int size) {
    long position = input.getPosition();
    // Once parsing has clearly fallen off a valid chunk boundary, advance a bounded number of bytes
    // to look for the next plausible header rather than terminating playback immediately.
    if (position + 1 >= readEndPosition) {
      clearMoviResyncState();
      return logMoviReadEnd(reason, position, readEndPosition, chunkType, size);
    }
    if (moviResyncStartPosition == C.INDEX_UNSET) {
      moviResyncStartPosition = position;
      JLog.w(
          TAG,
          "Attempting movi read resync. reason="
              + reason
              + ", position="
              + position
              + ", chunkType="
              + Integer.toHexString(chunkType)
              + ", size="
              + size);
    } else if (position - moviResyncStartPosition >= MAX_MOVI_RESYNC_SEARCH_BYTES) {
      long resyncStartPosition = moviResyncStartPosition;
      clearMoviResyncState();
      return logMoviReadEnd(
          reason + " after resync search from " + resyncStartPosition,
          position,
          readEndPosition,
          chunkType,
          size);
    }
    pendingReposition = position + 1;
    return RESULT_CONTINUE;
  }

  private int maybeResyncMoviSeekMap(
      ExtractorInput input, long scanEndPosition, String reason, int chunkType, int size) {
    long position = input.getPosition();
    if (position + 1 >= scanEndPosition) {
      clearMoviResyncState();
      finishBuildingMoviSeekMap();
      return RESULT_CONTINUE;
    }
    if (moviResyncStartPosition == C.INDEX_UNSET) {
      moviResyncStartPosition = position;
      JLog.w(
          TAG,
          "Attempting movi seek-map resync. reason="
              + reason
              + ", position="
              + position
              + ", chunkType="
              + Integer.toHexString(chunkType)
              + ", size="
              + size);
    } else if (position - moviResyncStartPosition >= MAX_MOVI_RESYNC_SEARCH_BYTES) {
      JLog.w(
          TAG,
          "Stopping movi seek-map resync after "
              + (position - moviResyncStartPosition)
              + " bytes. reason="
              + reason
              + ", position="
              + position
              + ", chunkType="
              + Integer.toHexString(chunkType)
              + ", size="
              + size);
      clearMoviResyncState();
      finishBuildingMoviSeekMap();
      return RESULT_CONTINUE;
    }
    pendingReposition = position + 1;
    return RESULT_CONTINUE;
  }

  private void clearMoviResyncState() {
    moviResyncStartPosition = C.INDEX_UNSET;
  }

  private int logMoviReadEnd(
      String reason, long position, long readEndPosition, int chunkType, int size) {
    JLog.w(
        TAG,
        "Ending movi read early. reason="
            + reason
            + ", position="
            + position
            + ", readEndPosition="
            + readEndPosition
            + ", chunkType="
            + Integer.toHexString(chunkType)
            + ", size="
            + size);
    return C.RESULT_END_OF_INPUT;
  }

  private long peekSeekOffset(ParsableByteArray idx1Body) {
    // The spec states the offset is based on the start of the movi list type fourcc, but it also
    // says some files base the offset on the start of the file. We use a best effort approach to
    // figure out which is the case. See:
    // https://docs.microsoft.com/en-us/previous-versions/windows/desktop/api/Aviriff/ns-aviriff-avioldindex#dwoffset.
    if (idx1Body.bytesLeft() < 16) {
      // There are no full entries in the index, meaning we don't need to apply an offset.
      return 0;
    }
    int startingPosition = idx1Body.getPosition();
    idx1Body.skipBytes(8); // Skip chunkId (4 bytes) and flags (4 bytes).
    int offset = idx1Body.readLittleEndianInt();

    // moviStart points at the start of the LIST, while the seek offset is based at the start of the
    // movi fourCC, so we add 8 to reconcile the difference.
    long seekOffset = offset > moviStart ? 0L : moviStart + 8;
    idx1Body.setPosition(startingPosition);
    return seekOffset;
  }

  @Nullable
  private ChunkReader getChunkReader(int chunkId) {
    for (ChunkReader chunkReader : chunkReaders) {
      if (chunkReader.handlesChunkId(chunkId)) {
        return chunkReader;
      }
    }
    return null;
  }

  private int maybeExtendChunkSize(
      ExtractorInput input,
      ChunkReader chunkReader,
      int chunkType,
      int size,
      long readEndPosition)
      throws IOException {
    long chunkStart = input.getPosition();
    long payloadPosition = chunkStart + 8L;
    // Validate the declared boundary before consuming the sample. If the next bytes don't look like
    // a plausible chunk header, we try to extend the current sample to a better boundary so the
    // decoder receives the whole frame instead of a truncated one.
    long expectedNextPosition = payloadPosition + size + (size & 1);
    if (size <= 0 || expectedNextPosition + 8 > readEndPosition) {
      return size;
    }
    int bytesToPeek = (int) Math.min(readEndPosition - expectedNextPosition, moviResyncScratch.length);
    if (bytesToPeek < 8
        || !peekBytesAt(
            input,
            expectedNextPosition - chunkStart,
            moviResyncScratch,
            /* length= */ bytesToPeek)) {
      return size;
    }
    if (looksLikePlausibleChunkHeader(
        moviResyncScratch, /* offset= */ 0, bytesToPeek, readEndPosition - expectedNextPosition)) {
      return size;
    }
    for (int delta = 1; delta + 8 <= bytesToPeek; delta++) {
      long candidatePosition = expectedNextPosition + delta;
      if (looksLikePlausibleChunkHeader(
              moviResyncScratch, delta, bytesToPeek, readEndPosition - candidatePosition)
          && hasPlausibleSuccessor(
              moviResyncScratch, delta, bytesToPeek, readEndPosition - candidatePosition)) {
        int adjustedSize = (int) (candidatePosition - payloadPosition);
        if (adjustedSize > chunkReader.getMaximumChunkSize()) {
          return size;
        }
        JLog.w(
            TAG,
            "Extending chunk size to recover corrupted movi boundary. chunkType="
                + Integer.toHexString(chunkType)
                + ", position="
                + chunkStart
                + ", declaredSize="
                + size
                + ", adjustedSize="
                + adjustedSize);
        return adjustedSize;
      }
    }
    return size;
  }

  private boolean peekBytesAt(ExtractorInput input, long offset, byte[] target, int length)
      throws IOException {
    if (offset < 0 || offset > Integer.MAX_VALUE) {
      return false;
    }
    boolean success = false;
    try {
      input.advancePeekPosition((int) offset, /* allowEndOfInput= */ true);
      success = input.peekFully(target, /* offset= */ 0, length, /* allowEndOfInput= */ true);
      return success;
    } finally {
      input.resetPeekPosition();
    }
  }

  private boolean looksLikePlausibleChunkHeader(
      byte[] data, int offset, int limit, long bytesRemaining) {
    if (offset < 0 || offset + 8 > limit) {
      return false;
    }
    int chunkType = readLittleEndianInt(data, offset);
    int size = readLittleEndianInt(data, offset + 4);
    if (size < 0) {
      return false;
    }
    long nextPosition = 8L + size;
    if (nextPosition > bytesRemaining) {
      return false;
    }
    if (chunkType == FOURCC_LIST || chunkType == FOURCC_RIFF) {
      if (size < 4 || offset + 12 > limit) {
        return false;
      }
      return chunkType != FOURCC_RIFF || isAviRiffType(readLittleEndianInt(data, offset + 8));
    }
    return chunkType == FOURCC_JUNK || chunkType == FOURCC_idx1 || getChunkReader(chunkType) != null;
  }

  private boolean hasPlausibleSuccessor(byte[] data, int offset, int limit, long bytesRemaining) {
    int size = readLittleEndianInt(data, offset + 4);
    int step = 8 + size + (size & 1);
    return offset + step + 8 > limit
        || looksLikePlausibleChunkHeader(data, offset + step, limit, bytesRemaining - step);
  }

  private static int readLittleEndianInt(byte[] data, int offset) {
    return (data[offset] & 0xFF)
        | ((data[offset + 1] & 0xFF) << 8)
        | ((data[offset + 2] & 0xFF) << 16)
        | ((data[offset + 3] & 0xFF) << 24);
  }

  private int readMoviChunks(ExtractorInput input) throws IOException {
    long readEndPosition = getMoviReadUpperBound(input);
    if (input.getPosition() >= readEndPosition) {
      return C.RESULT_END_OF_INPUT;
    } else if (currentChunkReader != null) {
      clearMoviResyncState();
      if (currentChunkReader.onChunkData(input)) {
        if (currentChunkReader.isVideo() && !loggedFirstVideoSample) {
          loggedFirstVideoSample = true;
          logPhase("first-video-sample", "position=" + input.getPosition());
        } else if (currentChunkReader.isAudio() && !loggedFirstAudioSample) {
          loggedFirstAudioSample = true;
          logPhase("first-audio-sample", "position=" + input.getPosition());
        }
        currentChunkReader = null;
      }
    } else {
      if ((input.getPosition() & 1) == 1) {
        if (input.getPosition() + 1 >= readEndPosition) {
          return C.RESULT_END_OF_INPUT;
        }
        input.skipFully(1);
      }
      long bytesRemaining = readEndPosition - input.getPosition();
      if (bytesRemaining < 8) {
        return logMoviReadEnd(
            "Insufficient bytes for chunk header",
            input.getPosition(),
            readEndPosition,
            /* chunkType= */ 0,
            /* size= */ 0);
      }
      if (!peekFullyQuietly(input, scratch.getData(), /* length= */ 8)) {
        return logMoviReadEnd(
            "Failed to peek chunk header",
            input.getPosition(),
            readEndPosition,
            /* chunkType= */ 0,
            /* size= */ 0);
      }
      scratch.setPosition(0);
      int chunkType = scratch.readLittleEndianInt();
      int size = scratch.readLittleEndianInt();
      if (chunkType == FOURCC_LIST || chunkType == FOURCC_RIFF) {
        if (bytesRemaining < 12 || !peekFullyQuietly(input, scratch.getData(), /* length= */ 12)) {
          return logMoviReadEnd(
              "Failed to peek container header",
              input.getPosition(),
              readEndPosition,
              chunkType,
              size);
        }
        scratch.setPosition(8);
        if (size < 4) {
          return maybeResyncMoviRead(
              input, readEndPosition, "Container size smaller than type field", chunkType, size);
        }
        int containerType = scratch.readLittleEndianInt();
        long nextPosition =
            chunkType == FOURCC_RIFF && !isAviRiffType(containerType)
                ? input.getPosition() + 8L + size
                : input.getPosition() + 12L;
        if (!isValidForwardPosition(input.getPosition(), nextPosition, readEndPosition)
            || nextPosition == readEndPosition) {
          return maybeResyncMoviRead(
              input, readEndPosition, "Container next position out of bounds", chunkType, size);
        }
        clearMoviResyncState();
        input.skipFully((int) (nextPosition - input.getPosition()));
        input.resetPeekPosition();
        return RESULT_CONTINUE;
      }
      if (size < 0) {
        return maybeResyncMoviRead(input, readEndPosition, "Negative chunk size", chunkType, size);
      }
      if (chunkType == FOURCC_JUNK) {
        long nextPosition = input.getPosition() + size + 8L;
        if (!isValidForwardPosition(input.getPosition(), nextPosition, readEndPosition)
            || nextPosition == readEndPosition) {
          return maybeResyncMoviRead(input, readEndPosition, "JUNK chunk exceeds read bound", chunkType, size);
        }
        clearMoviResyncState();
        pendingReposition = nextPosition;
        return RESULT_CONTINUE;
      }
      long payloadPosition = input.getPosition() + 8L;
      if (!isValidForwardPosition(input.getPosition(), payloadPosition, readEndPosition)) {
        return maybeResyncMoviRead(
            input, readEndPosition, "Payload position out of bounds", chunkType, size);
      }
      ChunkReader chunkReader = getChunkReader(chunkType);
      if (chunkReader == null) {
        // No handler for this chunk. We skip it.
        long nextPosition = payloadPosition + size;
        if (!isValidForwardPosition(payloadPosition, nextPosition, readEndPosition)
            || nextPosition == readEndPosition) {
          return maybeResyncMoviRead(
              input, readEndPosition, "Unknown chunk exceeds read bound", chunkType, size);
        }
        clearMoviResyncState();
        pendingReposition = nextPosition;
        return RESULT_CONTINUE;
      } else {
        int adjustedSize =
            maybeExtendChunkSize(input, chunkReader, chunkType, size, readEndPosition);
        long nextPosition = payloadPosition + adjustedSize;
        if (!isValidForwardPosition(payloadPosition, nextPosition, readEndPosition)) {
          return maybeResyncMoviRead(
              input, readEndPosition, "Chunk payload exceeds read bound", chunkType, adjustedSize);
        }
        if (buildingIndexFromReadPath) {
          // In the progressive path we grow the fallback index from real sample reads instead of a
          // blocking full-file prescan, so startup stays fast while seek coverage improves.
          chunkReader.appendSeekChunk(
              input.getPosition(),
              detectFallbackKeyFrame(
                  input, chunkReader, chunkType, adjustedSize, bytesRemaining));
        }
        clearMoviResyncState();
        input.skipFully(8);
        input.resetPeekPosition();
        chunkReader.onChunkStart(adjustedSize);
        this.currentChunkReader = chunkReader;
      }
    }
    return RESULT_CONTINUE;
  }

  @Nullable
  private ChunkReader processStreamList(ListChunk streamList, int streamId) {
    JLog.d(TAG, "processStreamList streamId = " + streamId);
    AviStreamHeaderChunk aviStreamHeaderChunk = streamList.getChild(AviStreamHeaderChunk.class);
    StreamFormatChunk streamFormatChunk = streamList.getChild(StreamFormatChunk.class);
    if (aviStreamHeaderChunk == null) {
      Log.w(TAG, "Missing Stream Header");
      return null;
    }
    if (streamFormatChunk == null) {
      Log.w(TAG, "Missing Stream Format");
      return null;
    }
    long durationUs = aviStreamHeaderChunk.getDurationUs();
    Format streamFormat = streamFormatChunk.format;
    JLog.d(TAG, "AviStreamHeaderChunk: streamType=" + Integer.toHexString(aviStreamHeaderChunk.streamType)
            + ", initialFrames=" + aviStreamHeaderChunk.initialFrames
            + ", scale=" + aviStreamHeaderChunk.scale
            + ", rate=" + aviStreamHeaderChunk.rate
            + ", length=" + aviStreamHeaderChunk.length
            + ", suggestedBufferSize=" + aviStreamHeaderChunk.suggestedBufferSize
            + ", sampleSize=" + aviStreamHeaderChunk.sampleSize);
    JLog.d(TAG, "StreamFormatChunk: " + streamFormat);
    Format.Builder builder = streamFormat.buildUpon();
    builder.setId(streamId);
    int suggestedBufferSize = aviStreamHeaderChunk.suggestedBufferSize;
    if (suggestedBufferSize != 0) {
      builder.setMaxInputSize(suggestedBufferSize);
    }
    StreamNameChunk streamName = streamList.getChild(StreamNameChunk.class);
    if (streamName != null) {
      JLog.d(TAG, "StreamNameChunk: name=" + streamName.name);
      builder.setLabel(streamName.name);
    }
    int trackType = MimeTypes.getTrackType(streamFormat.sampleMimeType);
    if (trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_VIDEO) {
      TrackOutput trackOutput = extractorOutput.track(streamId, trackType);
      trackOutput.format(builder.build());
      trackOutput.durationUs(durationUs);
      this.durationUs = max(this.durationUs, durationUs);
      return new ChunkReader(
          streamId, aviStreamHeaderChunk, streamFormat.sampleMimeType, trackOutput);
    } else {
      // We don't currently support tracks other than video and audio.
      return null;
    }
  }

  /**
   * Skips one byte from the given {@code input} if the current position is odd.
   *
   * <p>This isn't documented anywhere, but AVI files are aligned to even bytes and fill gaps with
   * zeros.
   */
  private static void alignInputToEvenPosition(ExtractorInput input) throws IOException {
    if ((input.getPosition() & 1) == 1) {
      input.skipFully(1);
    }
  }

  // Internal classes.

  private class AviSeekMap implements SeekMap {

    private final long durationUs;

    public AviSeekMap(long durationUs) {
      this.durationUs = durationUs;
    }

    @Override
    public boolean isSeekable() {
      return true;
    }

    @Override
    public long getDurationUs() {
      return durationUs;
    }

    @Override
    public SeekPoints getSeekPoints(long timeUs) {
      long indexedDataEndPosition = getIndexedDataEndPosition();
      int firstIndexedChunkReader = C.INDEX_UNSET;
      for (int i = 0; i < chunkReaders.length; i++) {
        if (chunkReaders[i].isVideo() && chunkReaders[i].hasIndexChunks()) {
          firstIndexedChunkReader = i;
          break;
        }
      }
      if (firstIndexedChunkReader == C.INDEX_UNSET) {
        for (int i = 0; i < chunkReaders.length; i++) {
          if (chunkReaders[i].hasIndexChunks()) {
            firstIndexedChunkReader = i;
            break;
          }
        }
      }
      if (firstIndexedChunkReader == C.INDEX_UNSET) {
        return new SeekPoints(new SeekPoint(/* timeUs= */ 0, moviStart));
      }
      SeekPoints result =
          getSeekPointsForReader(chunkReaders[firstIndexedChunkReader], timeUs, indexedDataEndPosition);
      for (int i = firstIndexedChunkReader + 1; i < chunkReaders.length; i++) {
        if (!chunkReaders[i].hasIndexChunks()) {
          continue;
        }
        if (chunkReaders[firstIndexedChunkReader].isVideo() && !chunkReaders[i].isVideo()) {
          continue;
        }
        SeekPoints seekPoints = getSeekPointsForReader(chunkReaders[i], timeUs, indexedDataEndPosition);
        if (seekPoints.first.position < result.first.position) {
          result = seekPoints;
        }
      }
      JLog.i(
          TAG,
          "aviSeekMap.getSeekPoints requestedUs="
              + timeUs
              + ", firstTimeUs="
              + result.first.timeUs
              + ", firstPosition="
              + result.first.position
              + ", secondTimeUs="
              + result.second.timeUs
              + ", secondPosition="
              + result.second.position
              + ", indexedDataEndPosition="
              + indexedDataEndPosition);
      return result;
    }

    private SeekPoints getSeekPointsForReader(
        ChunkReader chunkReader, long timeUs, long indexedDataEndPosition) {
      return chunkReader.isH264Video() && chunkReader.hasFallbackSeekIndex()
          ? chunkReader.getSeekPointsByBytePosition(
              timeUs, /* dataStartPosition= */ moviStart + 12, indexedDataEndPosition)
          : chunkReader.getSeekPoints(timeUs);
    }

    private long getIndexedDataEndPosition() {
      long indexedDataEndPosition = moviEnd;
      for (ChunkReader chunkReader : chunkReaders) {
        if (chunkReader.hasIndexChunks()) {
          indexedDataEndPosition = max(indexedDataEndPosition, chunkReader.getLastIndexOffset() + 1);
        }
      }
      return indexedDataEndPosition;
    }
  }

  private static class ChunkHeaderHolder {
    public int chunkType;
    public int size;
    public int listType;

    public void populateWithListHeaderFrom(ParsableByteArray headerBytes) throws ParserException {
      populateFrom(headerBytes);
      if (chunkType != AviExtractor.FOURCC_LIST) {
        throw ParserException.createForMalformedContainer(
            /* message= */ "LIST expected, found: " + chunkType, /* cause= */ null);
      }
      listType = headerBytes.readLittleEndianInt();
    }

    public void populateFrom(ParsableByteArray headerBytes) {
      chunkType = headerBytes.readLittleEndianInt();
      size = headerBytes.readLittleEndianInt();
      listType = 0;
    }
  }
}
