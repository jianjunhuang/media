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
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.BinarySearchSeeker;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;

/**
 * A seeker that supports seeking within TS streams using binary search.
 *
 * <p>This seeker uses PCR or PTS values within the stream to find a packet whose timestamp is close
 * to the requested seek time.
 */
/* package */ final class TsBinarySearchSeeker extends BinarySearchSeeker {

  private static final long SEEK_TOLERANCE_US = 100_000;
  private int packetSize;
  //private static final int MINIMUM_SEARCH_RANGE_BYTES = 5 * TsExtractor.TS_PACKET_SIZE;

  public TsBinarySearchSeeker(
      TimestampAdjuster pcrTimestampAdjuster,
      long streamDurationUs,
      long inputLength,
      int pcrPid,
      int timestampSearchBytes,
      int packetSize) {
    this(
        new TsPcrSeeker(pcrPid, pcrTimestampAdjuster, timestampSearchBytes, packetSize),
        streamDurationUs,
        inputLength,
        packetSize);
  }

  public static TsBinarySearchSeeker createForPts(
      TimestampAdjuster ptsTimestampAdjuster,
      long streamDurationUs,
      long inputLength,
      int ptsPid,
      int timestampSearchBytes,
      int packetSize) {
    return new TsBinarySearchSeeker(
        new TsPtsSeeker(ptsPid, ptsTimestampAdjuster, timestampSearchBytes, packetSize),
        streamDurationUs,
        inputLength,
        packetSize);
  }

  private TsBinarySearchSeeker(
      TimestampSeeker timestampSeeker,
      long streamDurationUs,
      long inputLength,
      int packetSize) {
    super(
        new DefaultSeekTimestampConverter(),
        timestampSeeker,
        streamDurationUs,
        /* floorTimePosition= */ 0,
        /* ceilingTimePosition= */ streamDurationUs + 1,
        /* floorBytePosition= */ 0,
        /* ceilingBytePosition= */ inputLength,
        /* approxBytesPerFrame= */ packetSize,
        5 * packetSize);
    this.packetSize = packetSize;
  }

  /**
   * A {@link TimestampSeeker} implementation that looks for a given PCR timestamp at a given
   * position in a TS stream.
   *
   * <p>Given a PCR timestamp, and a position within a TS stream, this seeker will peek up to {@link
   * #timestampSearchBytes} from that stream position, look for all packets with PID equal to
   * PCR_PID, and then compare the PCR timestamps (if available) of these packets to the target
   * timestamp.
   */
  private static final class TsPcrSeeker implements TimestampSeeker {

    private final TimestampAdjuster pcrTimestampAdjuster;
    private final ParsableByteArray packetBuffer;
    private final int pcrPid;
    private final int timestampSearchBytes;

    private final int packetSize;

    public TsPcrSeeker(
        int pcrPid, TimestampAdjuster pcrTimestampAdjuster, int timestampSearchBytes, int packetSize) {
      this.pcrPid = pcrPid;
      this.packetSize = packetSize;
      this.pcrTimestampAdjuster = pcrTimestampAdjuster;
      this.timestampSearchBytes = timestampSearchBytes;
      packetBuffer = new ParsableByteArray();
    }

    @Override
    public TimestampSearchResult searchForTimestamp(ExtractorInput input, long targetTimestamp)
        throws IOException {
      long inputPosition = input.getPosition();
      int bytesToSearch = (int) min(timestampSearchBytes, input.getLength() - inputPosition);

      packetBuffer.reset(bytesToSearch);
      input.peekFully(packetBuffer.getData(), /* offset= */ 0, bytesToSearch);

      return searchForPcrValueInBuffer(packetBuffer, targetTimestamp, inputPosition);
    }

    private TimestampSearchResult searchForPcrValueInBuffer(
        ParsableByteArray packetBuffer, long targetPcrTimeUs, long bufferStartOffset) {
      int limit = packetBuffer.limit();

      long startOfLastPacketPosition = C.INDEX_UNSET;
      long endOfLastPacketPosition = C.INDEX_UNSET;
      long lastPcrTimeUsInRange = C.TIME_UNSET;

      while (packetBuffer.bytesLeft() >= packetSize) {
        int startOfPacket =
            TsUtil.findSyncBytePosition(packetBuffer.getData(), packetBuffer.getPosition(), limit);
        int endOfPacket = startOfPacket + packetSize;
        if (endOfPacket > limit) {
          break;
        }
        long pcrValue = TsUtil.readPcrFromPacket(packetBuffer, startOfPacket, pcrPid);
        if (pcrValue != C.TIME_UNSET) {
          long pcrTimeUs = pcrTimestampAdjuster.adjustTsTimestamp(pcrValue);
          if (pcrTimeUs > targetPcrTimeUs) {
            if (lastPcrTimeUsInRange == C.TIME_UNSET) {
              // First PCR timestamp is already over target.
              return TimestampSearchResult.overestimatedResult(pcrTimeUs, bufferStartOffset);
            } else {
              // Last PCR timestamp < target timestamp < this timestamp.
              return TimestampSearchResult.targetFoundResult(
                  bufferStartOffset + startOfLastPacketPosition);
            }
          } else if (pcrTimeUs + SEEK_TOLERANCE_US > targetPcrTimeUs) {
            long startOfPacketInStream = bufferStartOffset + startOfPacket;
            return TimestampSearchResult.targetFoundResult(startOfPacketInStream);
          }

          lastPcrTimeUsInRange = pcrTimeUs;
          startOfLastPacketPosition = startOfPacket;
        }
        packetBuffer.setPosition(endOfPacket);
        endOfLastPacketPosition = endOfPacket;
      }

      if (lastPcrTimeUsInRange != C.TIME_UNSET) {
        long endOfLastPacketPositionInStream = bufferStartOffset + endOfLastPacketPosition;
        return TimestampSearchResult.underestimatedResult(
            lastPcrTimeUsInRange, endOfLastPacketPositionInStream);
      } else {
        return TimestampSearchResult.NO_TIMESTAMP_IN_RANGE_RESULT;
      }
    }

    @Override
    public void onSeekFinished() {
      packetBuffer.reset(Util.EMPTY_BYTE_ARRAY);
    }
  }

  private static final class TsPtsSeeker implements TimestampSeeker {

    private final int ptsPid;
    private final TimestampAdjuster ptsTimestampAdjuster;
    private final int timestampSearchBytes;
    private final int packetSize;
    private final ParsableByteArray packetBuffer;

    public TsPtsSeeker(
        int ptsPid,
        TimestampAdjuster ptsTimestampAdjuster,
        int timestampSearchBytes,
        int packetSize) {
      this.ptsPid = ptsPid;
      this.ptsTimestampAdjuster = ptsTimestampAdjuster;
      this.timestampSearchBytes = timestampSearchBytes;
      this.packetSize = packetSize;
      packetBuffer = new ParsableByteArray();
    }

    @Override
    public TimestampSearchResult searchForTimestamp(ExtractorInput input, long targetTimestamp)
        throws IOException {
      long inputPosition = input.getPosition();
      int bytesToSearch = (int) min(timestampSearchBytes, input.getLength() - inputPosition);
      packetBuffer.reset(bytesToSearch);
      input.peekFully(packetBuffer.getData(), /* offset= */ 0, bytesToSearch);
      return searchForPtsValueInBuffer(packetBuffer, targetTimestamp, inputPosition);
    }

    private TimestampSearchResult searchForPtsValueInBuffer(
        ParsableByteArray packetBuffer, long targetTimeUs, long bufferStartOffset) {
      int limit = packetBuffer.limit();
      long closestTimeUsBelowTarget = C.TIME_UNSET;
      long closestPositionBelowTarget = C.INDEX_UNSET;
      long closestEndPositionBelowTarget = C.INDEX_UNSET;
      long closestTimeUsAboveTarget = C.TIME_UNSET;
      long closestPositionAboveTarget = C.INDEX_UNSET;

      while (packetBuffer.bytesLeft() >= packetSize) {
        int startOfPacket =
            TsUtil.findSyncBytePosition(packetBuffer.getData(), packetBuffer.getPosition(), limit);
        int endOfPacket = startOfPacket + packetSize;
        if (endOfPacket > limit) {
          break;
        }
        long ptsValue = TsUtil.readPtsFromPacket(packetBuffer, startOfPacket, ptsPid);
        if (ptsValue != C.TIME_UNSET) {
          long ptsTimeUs = ptsTimestampAdjuster.adjustTsTimestamp(ptsValue);
          if (Math.abs(ptsTimeUs - targetTimeUs) <= SEEK_TOLERANCE_US) {
            return TimestampSearchResult.targetFoundResult(bufferStartOffset + startOfPacket);
          }
          if (ptsTimeUs < targetTimeUs
              && (closestTimeUsBelowTarget == C.TIME_UNSET
                  || ptsTimeUs > closestTimeUsBelowTarget)) {
            closestTimeUsBelowTarget = ptsTimeUs;
            closestPositionBelowTarget = startOfPacket;
            closestEndPositionBelowTarget = endOfPacket;
          } else if (ptsTimeUs > targetTimeUs
              && (closestTimeUsAboveTarget == C.TIME_UNSET
                  || ptsTimeUs < closestTimeUsAboveTarget)) {
            closestTimeUsAboveTarget = ptsTimeUs;
            closestPositionAboveTarget = startOfPacket;
          }
        }
        packetBuffer.setPosition(endOfPacket);
      }

      if (closestTimeUsBelowTarget != C.TIME_UNSET
          && closestTimeUsAboveTarget != C.TIME_UNSET) {
        return TimestampSearchResult.targetFoundResult(
            bufferStartOffset + closestPositionBelowTarget);
      }
      if (closestTimeUsBelowTarget != C.TIME_UNSET) {
        return TimestampSearchResult.underestimatedResult(
            closestTimeUsBelowTarget, bufferStartOffset + closestEndPositionBelowTarget);
      }
      if (closestTimeUsAboveTarget != C.TIME_UNSET) {
        return TimestampSearchResult.overestimatedResult(
            closestTimeUsAboveTarget, bufferStartOffset + closestPositionAboveTarget);
      }
      return TimestampSearchResult.NO_TIMESTAMP_IN_RANGE_RESULT;
    }

    @Override
    public void onSeekFinished() {
      packetBuffer.reset(Util.EMPTY_BYTE_ARRAY);
    }
  }
}
