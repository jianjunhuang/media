/*
 * Copyright (C) 2025
 */
package androidx.media3.extractor.mp4;

import android.util.ArrayMap;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.JLog;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.Mp4Box;
import androidx.media3.extractor.ChunkIndex;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.SeekMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans MOOF boxes across the whole input to compute duration and build a seekable index.
 *
 * <p>This scanner is designed to be used transiently before normal extraction begins. It uses only
 * readFully/skipFully and restores the input position via setRetryPosition when done.
 */
@UnstableApi
final class MoofPreScanner {

  interface TrackInfoProvider {
    long getTimescale(int trackId);

    int getDefaultSampleDuration(int trackId);
  }

  static final class Result {
    final long durationUs;
    final SeekMap seekMap;

    Result(long durationUs, SeekMap seekMap) {
      this.durationUs = durationUs;
      this.seekMap = seekMap;
    }
  }

  private static final String TAG = "MoofPreScanner";

  private long resumePosition;
  private long computedDurationUs;
  private final ArrayList<Long> moofOffsets;
  private final ArrayList<Integer> moofSizes;
  private final ArrayList<Long> moofTimesUs;

  MoofPreScanner() {
    resumePosition = C.INDEX_UNSET;
    computedDurationUs = C.TIME_UNSET;
    moofOffsets = new ArrayList<>();
    moofSizes = new ArrayList<>();
    moofTimesUs = new ArrayList<>();
  }

  void start(ExtractorInput input) {
    if (resumePosition == C.INDEX_UNSET) {
      resumePosition = input.getPosition();
    }
    computedDurationUs = C.TIME_UNSET;
    moofOffsets.clear();
    moofSizes.clear();
    moofTimesUs.clear();
    JLog.d(TAG, "start --- resumePos=" + resumePosition);
  }

  Result scanToEndAndReset(ExtractorInput input, TrackInfoProvider provider) throws IOException {
    ParsableByteArray header = new ParsableByteArray(Mp4Box.LONG_HEADER_SIZE);
    while (true) {
      // Attempt to read the standard 8-byte header. Allow end-of-input.
      if (!input.readFully(header.getData(), 0, Mp4Box.HEADER_SIZE, /* allowEndOfInput= */ true)) {
        break; // EOF
      }
      header.setPosition(0);
      long atomSize = header.readUnsignedInt();
      int atomType = header.readInt();
      long headerBytesRead = Mp4Box.HEADER_SIZE;
      if (atomSize == Mp4Box.DEFINES_LARGE_SIZE) {
        int remain = Mp4Box.LONG_HEADER_SIZE - Mp4Box.HEADER_SIZE;
        input.readFully(header.getData(), Mp4Box.HEADER_SIZE, remain);
        header.setPosition(Mp4Box.HEADER_SIZE);
        atomSize = header.readUnsignedLongToLong();
        headerBytesRead = Mp4Box.LONG_HEADER_SIZE;
      } else if (atomSize == Mp4Box.EXTENDS_TO_END_SIZE) {
        long endPosition = input.getLength();
        if (endPosition != C.LENGTH_UNSET) {
          atomSize = endPosition - input.getPosition() + headerBytesRead;
        }
      }
      if (atomSize < headerBytesRead) {
        // Malformed; stop scanning to avoid infinite loops.
        break;
      }
      long payloadSize = atomSize - headerBytesRead;
      if (atomType == Mp4Box.TYPE_moof && payloadSize <= Integer.MAX_VALUE) {
        long moofOffset = input.getPosition() - headerBytesRead;
        moofOffsets.add(moofOffset);
        moofSizes.add((int) atomSize);

        ParsableByteArray moofData = new ParsableByteArray((int) atomSize);
        System.arraycopy(header.getData(), 0, moofData.getData(), 0, (int) headerBytesRead);
        if (payloadSize > 0) {
          input.readFully(moofData.getData(), (int) headerBytesRead, (int) payloadSize);
        }
        scanMoofBytes(moofData, provider);
      } else {
        if (payloadSize > 0) {
          input.skipFully((int) payloadSize);
        }
      }
    }

    long durationUs = computedDurationUs;
    SeekMap seekMap = buildSeekMapFromMoofIndex(durationUs);

    try {
      input.setRetryPosition(resumePosition, new Throwable("resume after moof pre-scan"));
    } catch (Throwable e) {
      JLog.e(TAG, "scanToEndAndReset --- setRetryPosition error: " + e.toString());
    }
    long oldResume = resumePosition;
    resumePosition = C.INDEX_UNSET;
    JLog.d(TAG, "scanToEndAndReset --- done, durationUs=" + durationUs + ", backTo=" + oldResume);
    return new Result(durationUs, seekMap);
  }

  private void scanMoofBytes(ParsableByteArray moof, TrackInfoProvider provider) {
    moof.setPosition(0);
    long moofSize = moof.readUnsignedInt();
    moof.readInt(); // type 'moof'
    long moofHeader = Mp4Box.HEADER_SIZE;
    if (moofSize == Mp4Box.DEFINES_LARGE_SIZE) {
      moofHeader = Mp4Box.LONG_HEADER_SIZE;
      moof.setPosition((int) moofHeader);
      moofSize = moof.readUnsignedLongToLong();
      moof.setPosition((int) moofHeader);
    }
    int limit = (int) moofSize;
    int pos = (int) moofHeader;

    long moofMinStartUs = C.TIME_UNSET;
    long moofMaxEndUs = C.TIME_UNSET;

    while (pos + Mp4Box.HEADER_SIZE <= limit) {
      moof.setPosition(pos);
      long childSize = moof.readUnsignedInt();
      int childType = moof.readInt();
      long childHeader = Mp4Box.HEADER_SIZE;
      if (childSize == Mp4Box.DEFINES_LARGE_SIZE) {
        childHeader = Mp4Box.LONG_HEADER_SIZE;
        moof.setPosition(pos + Mp4Box.HEADER_SIZE);
        childSize = moof.readUnsignedLongToLong();
      }
      if (childSize <= 0 || pos + childSize > limit) {
        break;
      }
      if (childType == Mp4Box.TYPE_traf) {
        int trafStart = (int) (pos + childHeader);
        int trafEnd = (int) (pos + childSize);
        TrafTimes times = scanTraf(moof, trafStart, trafEnd, provider);
        if (times != null && times.timescale > 0) {
          long startUs = Util.scaleLargeTimestamp(times.baseDecodeTime, C.MICROS_PER_SECOND, times.timescale);
          long endDecode = times.baseDecodeTime + times.totalDecodeDuration;
          long endUs = Util.scaleLargeTimestamp(endDecode, C.MICROS_PER_SECOND, times.timescale);
          if (moofMinStartUs == C.TIME_UNSET || startUs < moofMinStartUs) {
            moofMinStartUs = startUs;
          }
          if (moofMaxEndUs == C.TIME_UNSET || endUs > moofMaxEndUs) {
            moofMaxEndUs = endUs;
          }
        }
      }
      pos += (int) childSize;
    }

    if (moofMinStartUs != C.TIME_UNSET) {
      // Align moof time entry to first TRAF start time (min). One time per MOOF.
      int recorded = moofTimesUs.size();
      if (recorded < moofOffsets.size()) {
        moofTimesUs.add(moofMinStartUs);
      }
    }
    if (moofMaxEndUs != C.TIME_UNSET) {
      if (computedDurationUs == C.TIME_UNSET || moofMaxEndUs > computedDurationUs) {
        computedDurationUs = moofMaxEndUs;
      }
    }
  }

  private static final class TrafTimes {
    final long baseDecodeTime;
    final long totalDecodeDuration;
    final long timescale;

    TrafTimes(long baseDecodeTime, long totalDecodeDuration, long timescale) {
      this.baseDecodeTime = baseDecodeTime;
      this.totalDecodeDuration = totalDecodeDuration;
      this.timescale = timescale;
    }
  }

  @Nullable
  private TrafTimes scanTraf(ParsableByteArray data, int start, int end, TrackInfoProvider provider) {
    int position = start;
    long baseDecodeTime = 0L;
    int trackId = C.INDEX_UNSET;
    int defaultSampleDuration = 0;
    boolean haveTfdt = false;

    // First pass: tfhd/tfdt to establish defaults
    while (position + Mp4Box.HEADER_SIZE <= end) {
      data.setPosition(position);
      long size = data.readUnsignedInt();
      int type = data.readInt();
      long header = Mp4Box.HEADER_SIZE;
      if (size == Mp4Box.DEFINES_LARGE_SIZE) {
        header = Mp4Box.LONG_HEADER_SIZE;
        data.setPosition(position + Mp4Box.HEADER_SIZE);
        size = data.readUnsignedLongToLong();
      }
      if (size <= 0 || position + size > end) {
        break;
      }
      if (type == Mp4Box.TYPE_tfhd) {
        data.setPosition((int) (position + header));
        int fullAtom = data.readInt();
        int atomFlags = BoxParser.parseFullBoxFlags(fullAtom);
        trackId = data.readInt();
        int cursor = (int) (position + header + 8); // after fullAtom + trackId
        data.setPosition(cursor);
        if ((atomFlags & 0x01) != 0) { // base_data_offset_present
          data.skipBytes(8);
        }
        if ((atomFlags & 0x02) != 0) { // default_sample_description_index_present
          data.skipBytes(4);
        }
        if ((atomFlags & 0x08) != 0) { // default_sample_duration_present
          defaultSampleDuration = data.readInt();
        }
        if (defaultSampleDuration == 0 && trackId != C.INDEX_UNSET) {
          defaultSampleDuration = provider.getDefaultSampleDuration(trackId);
        }
      } else if (type == Mp4Box.TYPE_tfdt) {
        data.setPosition((int) (position + header));
        int fullAtom = data.readInt();
        int version = BoxParser.parseFullBoxVersion(fullAtom);
        baseDecodeTime = (version == 1) ? data.readUnsignedLongToLong() : data.readUnsignedInt();
        haveTfdt = true;
      }
      position += (int) size;
    }

    long timescale = 0L;
    if (trackId != C.INDEX_UNSET) {
      timescale = provider.getTimescale(trackId);
    }

    // Second pass: trun sum durations
    position = start;
    long cumulativeDecode = 0L;
    while (position + Mp4Box.HEADER_SIZE <= end) {
      data.setPosition(position);
      long size = data.readUnsignedInt();
      int type = data.readInt();
      long header = Mp4Box.HEADER_SIZE;
      if (size == Mp4Box.DEFINES_LARGE_SIZE) {
        header = Mp4Box.LONG_HEADER_SIZE;
        data.setPosition(position + Mp4Box.HEADER_SIZE);
        size = data.readUnsignedLongToLong();
      }
      if (size <= 0 || position + size > end) {
        break;
      }
      if (type == Mp4Box.TYPE_trun) {
        data.setPosition((int) (position + header));
        int fullAtom = data.readInt();
        int atomFlags = BoxParser.parseFullBoxFlags(fullAtom);
        int sampleCount = data.readUnsignedIntToInt();
        if ((atomFlags & 0x01) != 0) { // data_offset_present
          data.skipBytes(4);
        }
        boolean sampleDurationsPresent = (atomFlags & 0x100) != 0;
        boolean sampleSizesPresent = (atomFlags & 0x200) != 0;
        boolean sampleFlagsPresent = (atomFlags & 0x400) != 0;
        boolean sampleCompositionTimeOffsetsPresent = (atomFlags & 0x800) != 0;
        if ((atomFlags & 0x04) != 0) { // first_sample_flags_present
          data.skipBytes(4);
        }
        for (int i = 0; i < sampleCount; i++) {
          int sampleDuration = sampleDurationsPresent ? data.readInt() : defaultSampleDuration;
          if (sampleSizesPresent) {
            data.readInt();
          }
          if (sampleFlagsPresent) {
            data.readInt();
          }
          if (sampleCompositionTimeOffsetsPresent) {
            data.readInt();
          }
          if (sampleDuration > 0) {
            cumulativeDecode += sampleDuration;
          }
        }
      }
      position += (int) size;
    }

    if (timescale <= 0) {
      return null;
    }
    // If tfdt missing, we still allow baseDecodeTime=0 as a best effort.
    return new TrafTimes(baseDecodeTime, cumulativeDecode, timescale);
  }

  private SeekMap buildSeekMapFromMoofIndex(long durationUs) {
    if (moofOffsets.isEmpty()) {
      long dur = (durationUs != C.TIME_UNSET) ? durationUs : C.TIME_UNSET;
      return new SeekMap.Unseekable(dur, 0);
    }
    int count = moofOffsets.size();
    while (moofTimesUs.size() < count) {
      moofTimesUs.add(0L);
    }
    long[] offsets = new long[count];
    int[] sizes = new int[count];
    long[] timesUs = new long[count];
    long[] durationsUs = new long[count];

    for (int i = 0; i < count; i++) {
      offsets[i] = moofOffsets.get(i);
      sizes[i] = moofSizes.get(i);
      timesUs[i] = moofTimesUs.get(i);
      if (i < count - 1) {
        durationsUs[i] = moofTimesUs.get(i + 1) - timesUs[i];
      } else {
        durationsUs[i] = (durationUs != C.TIME_UNSET) ? (durationUs - timesUs[i]) : 0;
      }
      if (durationsUs[i] < 0) {
        durationsUs[i] = 0;
      }
    }
    JLog.d(TAG, "buildSeekMapFromMoofIndex --- chunks=" + count + ", durationUs=" + durationUs);
    return new ChunkIndex(sizes, offsets, durationsUs, timesUs);
  }
}


