/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.extractor.ts;

import static java.lang.Math.min;

import androidx.media3.common.C;
import androidx.media3.common.util.JLog;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import java.io.IOException;

/**
 * A reader that can extract the approximate duration from a given MPEG transport stream (TS).
 *
 * <p>This reader extracts the duration by reading PCR values of the PCR PID packets at the start
 * and at the end of the stream, calculating the difference, and converting that into stream
 * duration. This reader also handles the case when a single PCR wraparound takes place within the
 * stream, which can make PCR values at the beginning of the stream larger than PCR values at the
 * end. This class can only be used once to read duration from a given stream, and the usage of the
 * class is not thread-safe, so all calls should be made from the same thread.
 */
/* package */ final class TsDurationReader {

  private static final String TAG = "TsDurationReader";

  private final int timestampSearchBytes;
  private final TimestampAdjuster pcrTimestampAdjuster;
  private final ParsableByteArray packetBuffer;

  private boolean isDurationRead;
  private boolean isFirstPcrValueRead;
  private boolean isLastPcrValueRead;

  private long firstPcrValue;
  private long lastPcrValue;
  private long lastPtsValue;
  private long durationUs;

  private int packetSize;
  private boolean usePtsForDuration;

  // Progressive search state
  private int progressiveSearchIndex;
  // Default TS packet size is 188 bytes, DEFAULT_TIMESTAMP_SEARCH_BYTES = 600 * 188 = 112800
  private static final int DEFAULT_TIMESTAMP_SEARCH_BYTES = 600 * 188;
  private static final int[] PROGRESSIVE_SEARCH_SIZES = {
      DEFAULT_TIMESTAMP_SEARCH_BYTES,  // ~110KB (original default)
      2 * 1024 * 1024,                  // 2MB
      5 * 1024 * 1024,                  // 5MB
      10 * 1024 * 1024,                 // 10MB
      20 * 1024 * 1024                  // 20MB
  };

  /* package */ TsDurationReader(int timestampSearchBytes) {
    this.timestampSearchBytes = timestampSearchBytes;
    pcrTimestampAdjuster = new TimestampAdjuster(/* firstSampleTimestampUs= */ 0);
    firstPcrValue = C.TIME_UNSET;
    lastPcrValue = C.TIME_UNSET;
    lastPtsValue = C.TIME_UNSET;
    durationUs = C.TIME_UNSET;
    packetBuffer = new ParsableByteArray();
    progressiveSearchIndex = 0;
    JLog.d("TsDurationReader --- TsDurationReader: timestampSearchBytes=" + timestampSearchBytes);
  }

  /**
   * Returns the search size for the current progressive search attempt.
   * @param inputLength The total input length in bytes
   * @return The number of bytes to search in this attempt
   */
  private int getProgressiveSearchSize(long inputLength) {
    if (progressiveSearchIndex >= PROGRESSIVE_SEARCH_SIZES.length) {
      // Fallback to configured maximum
      return (int) min(timestampSearchBytes, inputLength);
    }

    int progressiveSize = PROGRESSIVE_SEARCH_SIZES[progressiveSearchIndex];
    // Don't exceed configured maximum
    return (int) min(progressiveSize, min(timestampSearchBytes, inputLength));
  }

  public void setPacketSize(int packetSize) {
    this.packetSize = packetSize;
  }

  public void setUsePtsForDuration(boolean usePtsForDuration) {
    this.usePtsForDuration = usePtsForDuration;
  }

  /** Returns true if a TS duration has been read. */
  public boolean isDurationReadFinished() {
    return isDurationRead;
  }

  /**
   * Reads a TS duration from the input, using the given PCR PID.
   *
   * <p>This reader reads the duration by reading PCR values of the PCR PID packets at the start and
   * at the end of the stream, calculating the difference, and converting that into stream duration.
   *
   * @param input The {@link ExtractorInput} from which data should be read.
   * @param seekPositionHolder If {@link Extractor#RESULT_SEEK} is returned, this holder is updated
   *     to hold the position of the required seek.
   * @param pcrPid The PID of the packet stream within this TS stream that contains PCR values.
   * @return One of the {@code RESULT_} values defined in {@link Extractor}.
   * @throws IOException If an error occurred reading from the input.
   */
  public @Extractor.ReadResult int readDuration(
      ExtractorInput input, PositionHolder seekPositionHolder, int pcrPid) throws IOException {
    // Reset progressive search index when starting a new duration read
    if (!isLastPcrValueRead && progressiveSearchIndex == 0) {
      JLog.d("TsDurationReader --- readDuration: starting progressive search, maxSearchBytes=" + timestampSearchBytes);
    }

    JLog.d("TsDurationReader --- pcrPid=" + pcrPid);
    JLog.d("TsDurationReader --- readDuration: isDurationRead=" + isDurationRead
        + ", isFirstPcrValueRead=" + isFirstPcrValueRead
        + ", isLastPcrValueRead=" + isLastPcrValueRead
        + ", firstPcrValue=" + firstPcrValue
        + ", lastPcrValue=" + lastPcrValue
        + ", lastPtsValue=" + lastPtsValue
        + ", usePtsForDuration=" + usePtsForDuration);
    if (pcrPid <= 0) {
      return finishReadDuration(input);
    }
    if (!isLastPcrValueRead) {
      return readLastPcrValue(input, seekPositionHolder, pcrPid);
    }
    if (lastPcrValue == C.TIME_UNSET
        && (!usePtsForDuration || lastPtsValue == C.TIME_UNSET)) {
      return finishReadDuration(input);
    }
    if (!isFirstPcrValueRead) {
      return readFirstPcrValue(input, seekPositionHolder, pcrPid);
    }
    if (firstPcrValue == C.TIME_UNSET) {
      return finishReadDuration(input);
    }

    long minPcrPositionUs = pcrTimestampAdjuster.adjustTsTimestamp(firstPcrValue);
    long maxTimestamp = lastPcrValue;
    if (usePtsForDuration) {
      if (maxTimestamp == C.TIME_UNSET
          || (lastPtsValue != C.TIME_UNSET && lastPtsValue > maxTimestamp)) {
        maxTimestamp = lastPtsValue;
      }
    }
    if (maxTimestamp == C.TIME_UNSET) {
      return finishReadDuration(input);
    }
    long maxPcrPositionUs =
        pcrTimestampAdjuster.adjustTsTimestampGreaterThanPreviousTimestamp(maxTimestamp);
    durationUs = maxPcrPositionUs - minPcrPositionUs;
    JLog.d(
        "TsDurationReader --- readDuration: "
            + maxPcrPositionUs
            + " - "
            + minPcrPositionUs
            + " --> durationUs="
            + durationUs);
    return finishReadDuration(input);
  }

  /**
   * Returns the duration last read from {@link #readDuration(ExtractorInput, PositionHolder, int)}.
   */
  public long getDurationUs() {
    return durationUs;
  }

  /**
   * Returns the {@link TimestampAdjuster} that this class uses to adjust timestamps read from the
   * input TS stream.
   */
  public TimestampAdjuster getPcrTimestampAdjuster() {
    return pcrTimestampAdjuster;
  }

  public long getLastPtsValue() {
    return lastPtsValue;
  }

  private int finishReadDuration(ExtractorInput input) {
    packetBuffer.reset(Util.EMPTY_BYTE_ARRAY);
    isDurationRead = true;
    input.resetPeekPosition();
    return Extractor.RESULT_CONTINUE;
  }

  private int readFirstPcrValue(ExtractorInput input, PositionHolder seekPositionHolder, int pcrPid)
      throws IOException {
    int bytesToSearch = (int) min(timestampSearchBytes, input.getLength());
    JLog.d("TsDurationReader --- readFirstPcrValue: bytesToSearch=" + bytesToSearch);
    int searchStartPosition = 0;
    if (input.getPosition() != searchStartPosition) {
      seekPositionHolder.position = searchStartPosition;
      return Extractor.RESULT_SEEK;
    }

    packetBuffer.reset(bytesToSearch);
    input.resetPeekPosition();
    input.peekFully(packetBuffer.getData(), /* offset= */ 0, bytesToSearch);

    firstPcrValue = readFirstPcrValueFromBuffer(packetBuffer, pcrPid);
    JLog.d("TsDurationReader --- readFirstPcrValue: firstPcrValue=" + firstPcrValue);
    isFirstPcrValueRead = true;
    return Extractor.RESULT_CONTINUE;
  }

  private long readFirstPcrValueFromBuffer(ParsableByteArray packetBuffer, int pcrPid) {
    int searchStartPosition = packetBuffer.getPosition();
    int searchEndPosition = packetBuffer.limit();
    JLog.d("TsDurationReader --- readFirstPcrValueFromBuffer: searching from " + searchStartPosition + " to " + searchEndPosition + ", pcrPid=" + pcrPid + ", packetSize=" + packetSize);
    for (int searchPosition = searchStartPosition;
        searchPosition < searchEndPosition;
        searchPosition++) {
      if (packetBuffer.getData()[searchPosition] != TsExtractor.TS_SYNC_BYTE) {
        continue;
      }
      long pcrValue = TsUtil.readPcrFromPacket(packetBuffer, searchPosition, pcrPid);
      if (pcrValue != C.TIME_UNSET) {
          JLog.d("TsDurationReader --- readFirstPcrValueFromBuffer: at position " + searchPosition + ", pcrValue=" + pcrValue);
        return pcrValue;
      }
    }
    return C.TIME_UNSET;
  }

  private int readLastPcrValue(ExtractorInput input, PositionHolder seekPositionHolder, int pcrPid)
      throws IOException {
    long inputLength = input.getLength();
    int bytesToSearch = getProgressiveSearchSize(inputLength);
    long searchStartPosition = inputLength - bytesToSearch;

    JLog.d("TsDurationReader --- readLastPcrValue: round=" + (progressiveSearchIndex + 1)
        + ", bytesToSearch=" + bytesToSearch + " (" + (bytesToSearch / 1024 / 1024) + "MB)"
        + ", searchStartPosition=" + searchStartPosition + ", inputLength=" + inputLength);

    if (input.getPosition() != searchStartPosition) {
      seekPositionHolder.position = searchStartPosition;
      return Extractor.RESULT_SEEK;
    }

    packetBuffer.reset(bytesToSearch);
    input.resetPeekPosition();
    input.peekFully(packetBuffer.getData(), /* offset= */ 0, bytesToSearch);

    lastPcrValue = readLastPcrValueFromBuffer(packetBuffer, pcrPid);

    // Check if we found PCR or PTS
    boolean foundTimestamp = (lastPcrValue != C.TIME_UNSET)
        || (usePtsForDuration && lastPtsValue != C.TIME_UNSET);

    if (foundTimestamp) {
      // Found timestamp in this round, mark as complete
      JLog.d("TsDurationReader --- readLastPcrValue: FOUND in round " + (progressiveSearchIndex + 1)
          + " with " + bytesToSearch + " bytes");
      isLastPcrValueRead = true;
    } else {
      // Not found, try next round
      progressiveSearchIndex++;
      if (progressiveSearchIndex < PROGRESSIVE_SEARCH_SIZES.length
          && PROGRESSIVE_SEARCH_SIZES[progressiveSearchIndex] <= timestampSearchBytes) {
        // More rounds available, seek to next round
        JLog.d("TsDurationReader --- readLastPcrValue: NOT found, trying next round");
        long nextBytesToSearch = getProgressiveSearchSize(inputLength);
        seekPositionHolder.position = inputLength - nextBytesToSearch;
        return Extractor.RESULT_SEEK;
      } else {
        // No more rounds, mark as complete (not found)
        JLog.d("TsDurationReader --- readLastPcrValue: exhausted all rounds, marking complete");
        isLastPcrValueRead = true;
      }
    }

    JLog.d("TsDurationReader --- readLastPcrValue: lastPcrValue=" + lastPcrValue);
    JLog.d("TsDurationReader --- readLastPcrValue: lastPtsValue=" + lastPtsValue);
    return Extractor.RESULT_CONTINUE;
  }

  private long readLastPcrValueFromBuffer(ParsableByteArray packetBuffer, int pcrPid) {
    int searchStartPosition = packetBuffer.getPosition();
    int searchEndPosition = packetBuffer.limit();
    JLog.d("TsDurationReader --- readLastPcrValueFromBuffer: searching from " + searchStartPosition + " to " + searchEndPosition + ", pcrPid=" + pcrPid + ", packetSize=" + packetSize);

    // Step 1: Scan PCR first (priority)
    JLog.d("TsDurationReader --- readLastPcrValueFromBuffer: Scanning PCR first");
    // We start searching 'TsExtractor.TS_PACKET_SIZE' bytes from the end to prevent trying to read
    // from an incomplete TS packet.
    for (int searchPosition = searchEndPosition - packetSize;
        searchPosition >= searchStartPosition;
        searchPosition--) {
      if (!TsUtil.isStartOfTsPacket(
          packetBuffer.getData(), searchStartPosition, searchEndPosition, searchPosition, packetSize)) {
        continue;
      }
      long pcrValue = TsUtil.readPcrFromPacket(packetBuffer, searchPosition, pcrPid);
      if (pcrValue != C.TIME_UNSET) {
        JLog.d("TsDurationReader --- readLastPcrValueFromBuffer: PCR found at position " + searchPosition + ", pcrValue=" + pcrValue);
        return pcrValue;  // Return PCR immediately (priority)
      }
    }

    // Step 2: PCR not found, scan PTS as fallback (if enabled)
    if (usePtsForDuration) {
      JLog.d("TsDurationReader --- readLastPcrValueFromBuffer: PCR not found, scanning PTS as fallback");
      int tsPacketCount = 0;
      int ptsFoundCount = 0;
      long firstPtsPosition = -1;
      long lastPtsPosition = -1;

      for (int searchPosition = searchStartPosition;
          searchPosition <= searchEndPosition - packetSize;
          searchPosition++) {
        if (!TsUtil.isStartOfTsPacket(
            packetBuffer.getData(), searchStartPosition, searchEndPosition, searchPosition, packetSize)) {
          continue;
        }
        tsPacketCount++;
        long ptsValue = readPtsFromPacket(packetBuffer, searchPosition);
        if (ptsValue != C.TIME_UNSET) {
          lastPtsValue = ptsValue;
          ptsFoundCount++;
          long offsetFromStart = searchPosition - searchStartPosition;

          if (firstPtsPosition < 0) {
            firstPtsPosition = offsetFromStart;
            JLog.d("TsDurationReader --- readLastPcrValueFromBuffer: FIRST PTS found at offset " + offsetFromStart + " bytes (" + tsPacketCount + " packets), pts=" + ptsValue);
          }
          lastPtsPosition = offsetFromStart;
        }
      }

      JLog.d("TsDurationReader --- readLastPcrValueFromBuffer: PTS scan complete, packets=" + tsPacketCount + ", ptsFound=" + ptsFoundCount + ", firstAtOffset=" + firstPtsPosition + ", lastAtOffset=" + lastPtsPosition);
    }

    // Return TIME_UNSET to indicate neither PCR nor PTS found
    JLog.d("TsDurationReader --- readLastPcrValueFromBuffer: Neither PCR nor PTS found");
    return C.TIME_UNSET;
  }

  // Parses a PTS from a TS packet payload when present.
  // TS packet layout (188 bytes typical):
  //   [0x47][header:4 bytes]
  //   header bits: TEI (bit23) | PUSI (bit22) | PID (13 bits) | AFC (2 bits)
  //   AFC: 01 payload only, 10 adaptation only, 11 adaptation + payload
  //   [adapt_len?][adaptation_field?] [payload -> PES]
  //
  // PES payload when PUSI=1 and prefix is 0x000001:
  //   [00 00 01][stream_id][pes_len]
  //   [flags1][flags2: PTS_DTS][hdr_len][PTS(5)]...
  // PTS(5) layout (33 bits total):
  //   b0: 0010 | PTS[32..30] | marker
  //   b1: PTS[29..22]
  //   b2: PTS[21..15] | marker
  //   b3: PTS[14..7]
  //   b4: PTS[6..0] | marker
  private long readPtsFromPacket(ParsableByteArray packetBuffer, int startOfPacket) {
    packetBuffer.setPosition(startOfPacket);
    if (packetBuffer.bytesLeft() < 5) {
      return C.TIME_UNSET;
    }
    int tsPacketHeader = packetBuffer.readInt();
    if ((tsPacketHeader & 0x800000) != 0) {
      return C.TIME_UNSET;
    }
    boolean payloadUnitStartIndicator = (tsPacketHeader & 0x400000) != 0;
    int adaptationFieldControl = (tsPacketHeader & 0x30) >> 4;
    boolean hasAdaptation = adaptationFieldControl == 2 || adaptationFieldControl == 3;
    boolean hasPayload = adaptationFieldControl == 1 || adaptationFieldControl == 3;
    if (!hasPayload) {
      return C.TIME_UNSET;
    }

    if (hasAdaptation) {
      if (packetBuffer.bytesLeft() < 1) {
        return C.TIME_UNSET;
      }
      int adaptationFieldLength = packetBuffer.readUnsignedByte();
      if (packetBuffer.bytesLeft() < adaptationFieldLength) {
        return C.TIME_UNSET;
      }
      packetBuffer.skipBytes(adaptationFieldLength);
    }

    if (!payloadUnitStartIndicator) {
      return C.TIME_UNSET;
    }
    if (packetBuffer.bytesLeft() < 9) {
      return C.TIME_UNSET;
    }
    int prefix =
        (packetBuffer.readUnsignedByte() << 16)
            | (packetBuffer.readUnsignedByte() << 8)
            | packetBuffer.readUnsignedByte();
    if (prefix != 0x000001) {
      return C.TIME_UNSET;
    }
    packetBuffer.skipBytes(1); // stream_id
    packetBuffer.skipBytes(2); // PES_packet_length
    if (packetBuffer.bytesLeft() < 3) {
      return C.TIME_UNSET;
    }
    packetBuffer.skipBytes(1); // '10' + flags
    int ptsDtsFlags = (packetBuffer.readUnsignedByte() >> 6) & 0x03;
    int pesHeaderDataLength = packetBuffer.readUnsignedByte();
    if (ptsDtsFlags == 0x02 || ptsDtsFlags == 0x03) {
      if (packetBuffer.bytesLeft() < 5) {
        return C.TIME_UNSET;
      }
      long b0 = packetBuffer.readUnsignedByte();
      long b1 = packetBuffer.readUnsignedByte();
      long b2 = packetBuffer.readUnsignedByte();
      long b3 = packetBuffer.readUnsignedByte();
      long b4 = packetBuffer.readUnsignedByte();
      return ((b0 & 0x0EL) << 29)
          | ((b1 & 0xFFL) << 22)
          | ((b2 & 0xFEL) << 14)
          | ((b3 & 0xFFL) << 7)
          | ((b4 & 0xFEL) >> 1);
    }
    return C.TIME_UNSET;
  }
}
