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

import static androidx.media3.common.C.TRACK_TYPE_AUDIO;
import static androidx.media3.common.C.TRACK_TYPE_VIDEO;
import static androidx.media3.common.util.Assertions.checkArgument;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.JLog;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.container.ParsableNalUnitBitArray;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/** Reads chunks holding sample data. */
/* package */ final class ChunkReader {

  /** Parser states. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    CHUNK_TYPE_VIDEO_COMPRESSED,
    CHUNK_TYPE_VIDEO_UNCOMPRESSED,
    CHUNK_TYPE_AUDIO,
  })
  private @interface ChunkType {}

  private static final int INITIAL_INDEX_SIZE = 512;
  private static final int FALLBACK_H264_SCAN_BYTES = 4 * 1024;
  private static final int FALLBACK_VISUAL_SCAN_BYTES = 64;
  private static final int CHUNK_TYPE_VIDEO_COMPRESSED = ('d' << 16) | ('c' << 24);
  private static final int CHUNK_TYPE_VIDEO_UNCOMPRESSED = ('d' << 16) | ('b' << 24);
  private static final int CHUNK_TYPE_AUDIO = ('w' << 16) | ('b' << 24);
  private static final int MPEG4_VISUAL_VOP_START_CODE = 0x000001B6;
  private static final int MPEG2_PICTURE_START_CODE = 0x00000100;

  private final AviStreamHeaderChunk streamHeaderChunk;
  private final TrackOutput trackOutput;
  @Nullable private final String sampleMimeType;

  /** The chunk id fourCC (example: `01wb`), as defined in the index and the movi. */
  private final int chunkId;

  /** Secondary chunk id. Bad muxers sometimes use an uncompressed video id (db) for key frames. */
  private final int alternativeChunkId;

  private final long durationUs;
  private final long videoTimestampMultiplierUs;
  private final int videoTimestampDivisor;

  private int chunkCount;
  private int currentChunkSize;
  private int bytesRemainingInCurrentChunk;
  private long currentTimestampUs;

  /** Number of chunks as calculated by the index. */
  private int currentChunkIndex;

  private int indexChunkCount;
  private int indexSize;
  private long firstIndexChunkOffset;
  private long[] keyFrameOffsets;
  private int[] keyFrameIndices;
  private int seekIndexSize;
  private long[] seekOffsets;
  private int[] seekIndices;

  // Buffer state for H264 access-unit splitting. Only used when isH264Video() is true.
  @Nullable private byte[] h264ChunkBuffer;
  private int h264ChunkBufferOffset;
  private final ParsableByteArray h264ScratchByteArray;
  private final long videoFrameDurationUs;

  public ChunkReader(
      int id,
      AviStreamHeaderChunk streamHeaderChunk,
      @Nullable String sampleMimeType,
      TrackOutput trackOutput) {
    this.streamHeaderChunk = streamHeaderChunk;
    @C.TrackType int trackType = streamHeaderChunk.getTrackType();
    checkArgument(trackType == TRACK_TYPE_AUDIO || trackType == TRACK_TYPE_VIDEO);
    @ChunkType
    int chunkType = trackType == TRACK_TYPE_VIDEO ? CHUNK_TYPE_VIDEO_COMPRESSED : CHUNK_TYPE_AUDIO;
    chunkId = getChunkIdFourCc(id, chunkType);
    durationUs = streamHeaderChunk.getDurationUs();
    this.sampleMimeType = sampleMimeType;
    this.trackOutput = trackOutput;
    alternativeChunkId =
        trackType == TRACK_TYPE_VIDEO ? getChunkIdFourCc(id, CHUNK_TYPE_VIDEO_UNCOMPRESSED) : -1;
    firstIndexChunkOffset = C.INDEX_UNSET;
    keyFrameOffsets = new long[INITIAL_INDEX_SIZE];
    keyFrameIndices = new int[INITIAL_INDEX_SIZE];
    seekOffsets = new long[INITIAL_INDEX_SIZE];
    seekIndices = new int[INITIAL_INDEX_SIZE];
    chunkCount = streamHeaderChunk.length;
    currentTimestampUs = 0;
    videoTimestampMultiplierUs =
        trackType == TRACK_TYPE_VIDEO ? C.MICROS_PER_SECOND * (long) streamHeaderChunk.scale : 0L;
    videoTimestampDivisor = trackType == TRACK_TYPE_VIDEO ? streamHeaderChunk.rate : 0;
    long fallbackFrameDurationUs;
    if (videoTimestampDivisor > 0 && videoTimestampMultiplierUs > 0) {
      fallbackFrameDurationUs = videoTimestampMultiplierUs / videoTimestampDivisor;
    } else if (streamHeaderChunk.length > 0 && durationUs > 0) {
      fallbackFrameDurationUs = durationUs / streamHeaderChunk.length;
    } else {
      fallbackFrameDurationUs = 0L;
    }
    videoFrameDurationUs = fallbackFrameDurationUs;
    h264ScratchByteArray = new ParsableByteArray();
  }

  public void appendIndexChunk(long offset, boolean isKeyFrame) {
    if (firstIndexChunkOffset == C.INDEX_UNSET) {
      firstIndexChunkOffset = offset;
    }
    if (isKeyFrame) {
      if (indexSize == keyFrameIndices.length) {
        keyFrameOffsets = Arrays.copyOf(keyFrameOffsets, keyFrameOffsets.length * 3 / 2);
        keyFrameIndices = Arrays.copyOf(keyFrameIndices, keyFrameIndices.length * 3 / 2);
      }
      keyFrameOffsets[indexSize] = offset;
      keyFrameIndices[indexSize] = indexChunkCount;
      indexSize++;
    }
    indexChunkCount++;
  }

  public void appendSeekChunk(long offset, boolean isKeyFrame) {
    // Progressive indexing may revisit already indexed regions after a seek/reload. Ignore
    // duplicate or backwards offsets so the active index remains monotonic.
    if (seekIndexSize > 0 && offset <= seekOffsets[seekIndexSize - 1]) {
      return;
    }
    if (firstIndexChunkOffset == C.INDEX_UNSET) {
      firstIndexChunkOffset = offset;
    }
    if (seekIndexSize == seekIndices.length) {
      seekOffsets = Arrays.copyOf(seekOffsets, seekOffsets.length * 3 / 2);
      seekIndices = Arrays.copyOf(seekIndices, seekIndices.length * 3 / 2);
    }
    seekOffsets[seekIndexSize] = offset;
    seekIndices[seekIndexSize] = indexChunkCount;
    seekIndexSize++;
    if (isKeyFrame) {
      if (indexSize == keyFrameIndices.length) {
        keyFrameOffsets = Arrays.copyOf(keyFrameOffsets, keyFrameOffsets.length * 3 / 2);
        keyFrameIndices = Arrays.copyOf(keyFrameIndices, keyFrameIndices.length * 3 / 2);
      }
      keyFrameOffsets[indexSize] = offset;
      keyFrameIndices[indexSize] = indexChunkCount;
      indexSize++;
    }
    indexChunkCount++;
  }

  public void advanceCurrentChunk() {
    currentChunkIndex++;
  }

  public long getCurrentChunkTimestampUs() {
    return usesDynamicAudioTimestamps() ? currentTimestampUs : getChunkTimestampUs(currentChunkIndex);
  }

  public long getFrameDurationUs() {
    return getChunkTimestampUs(/* chunkIndex= */ 1);
  }

  public void commitIndex() {
    int previousChunkCount = chunkCount;
    int headerChunkCount = streamHeaderChunk.length;
    keyFrameOffsets = Arrays.copyOf(keyFrameOffsets, indexSize);
    keyFrameIndices = Arrays.copyOf(keyFrameIndices, indexSize);
    seekOffsets = Arrays.copyOf(seekOffsets, seekIndexSize);
    seekIndices = Arrays.copyOf(seekIndices, seekIndexSize);
    boolean overwroteFromIndex = false;
    if (isVideo() && indexChunkCount > 0) {
      chunkCount = indexChunkCount;
      overwroteFromIndex = true;
    } else if (isAudio() && streamHeaderChunk.sampleSize != 0 && indexChunkCount > 0) {
      // In some files the AVI stream header chunk for audio has the number of bytes of audio in
      // dwLength instead of the number of chunks. Overwrite the chunk size to use the size of the
      // index, which should match the number of chunks because we only support formats where every
      // audio sample is a sync sample, and every sync sample should be in the index.
      chunkCount = indexChunkCount;
      overwroteFromIndex = true;
    }
    JLog.i(
        "ChunkReader",
        "commitIndex track="
            + (isVideo() ? "video" : (isAudio() ? "audio" : "other"))
            + ", indexChunkCount="
            + indexChunkCount
            + ", headerChunkCount="
            + headerChunkCount
            + ", chunkCountBefore="
            + previousChunkCount
            + ", chunkCountAfter="
            + chunkCount
            + ", overwroteFromIndex="
            + overwroteFromIndex
            + ", keyFrameCount="
            + indexSize
            + ", seekIndexCount="
            + seekIndexSize);
  }

  public void resetFallbackIndex() {
    firstIndexChunkOffset = C.INDEX_UNSET;
    indexChunkCount = 0;
    indexSize = 0;
    seekIndexSize = 0;
    keyFrameOffsets = new long[INITIAL_INDEX_SIZE];
    keyFrameIndices = new int[INITIAL_INDEX_SIZE];
    seekOffsets = new long[INITIAL_INDEX_SIZE];
    seekIndices = new int[INITIAL_INDEX_SIZE];
    currentChunkIndex = 0;
    currentTimestampUs = 0;
    h264ChunkBufferOffset = 0;
  }

  public boolean handlesChunkId(int chunkId) {
    return this.chunkId == chunkId || alternativeChunkId == chunkId;
  }

  public boolean isCurrentFrameAKeyFrame() {
    return Arrays.binarySearch(keyFrameIndices, 0, indexSize, currentChunkIndex) >= 0;
  }

  public boolean isVideo() {
    return (chunkId & CHUNK_TYPE_VIDEO_COMPRESSED) == CHUNK_TYPE_VIDEO_COMPRESSED;
  }

  public boolean isAudio() {
    return (chunkId & CHUNK_TYPE_AUDIO) == CHUNK_TYPE_AUDIO;
  }

  public boolean hasIndexChunks() {
    return firstIndexChunkOffset != C.INDEX_UNSET;
  }

  public boolean hasKeyFrameIndex() {
    return indexSize > 0;
  }

  public boolean hasProgressiveSeekCoverageForTimeUs(long timeUs) {
    if (isAudio()) {
      return true;
    }
    if (!isVideo() || seekIndexSize == 0 || indexSize == 0) {
      return false;
    }
    // The progressive path only knows precise coverage up to the last chunk it has actually read.
    // Seeks beyond that point can fall back to a much earlier key frame and stall exact seeking.
    int lastIndexedChunkIndex = seekIndices[seekIndexSize - 1];
    return timeUs <= getChunkTimestampUs(lastIndexedChunkIndex);
  }

  public boolean isH264Video() {
    return isVideo() && MimeTypes.VIDEO_H264.equals(sampleMimeType);
  }

  public boolean hasFallbackSeekIndex() {
    return seekIndexSize > 0;
  }

  public long getLastIndexOffset() {
    if (seekIndexSize > 0) {
      return seekOffsets[seekIndexSize - 1];
    }
    if (indexSize > 0) {
      return keyFrameOffsets[indexSize - 1];
    }
    return firstIndexChunkOffset;
  }

  public int getMaximumChunkSize() {
    return streamHeaderChunk.suggestedBufferSize != 0
        ? streamHeaderChunk.suggestedBufferSize
        : Integer.MAX_VALUE;
  }

  public int getFallbackKeyFrameSearchBytes(int chunkId, int chunkSize) {
    if (isAudio() || chunkId == alternativeChunkId || MimeTypes.VIDEO_MJPEG.equals(sampleMimeType)) {
      return 0;
    }
    if (MimeTypes.VIDEO_H264.equals(sampleMimeType)) {
      return Math.min(chunkSize, FALLBACK_H264_SCAN_BYTES);
    }
    if (isMpeg4VisualLike()) {
      return Math.min(chunkSize, FALLBACK_VISUAL_SCAN_BYTES);
    }
    if (MimeTypes.VIDEO_MPEG2.equals(sampleMimeType)) {
      return Math.min(chunkSize, FALLBACK_VISUAL_SCAN_BYTES);
    }
    return 0;
  }

  public boolean isFallbackKeyFrame(int chunkId, byte[] chunkData, int offset, int limit) {
    if (isAudio() || chunkId == alternativeChunkId || MimeTypes.VIDEO_MJPEG.equals(sampleMimeType)) {
      return true;
    }
    if (MimeTypes.VIDEO_H264.equals(sampleMimeType)) {
      return containsH264KeyframeNalUnit(chunkData, offset, limit);
    }
    if (isMpeg4VisualLike()) {
      return containsIntraMpeg4VisualFrame(chunkData, offset, limit);
    }
    if (MimeTypes.VIDEO_MPEG2.equals(sampleMimeType)) {
      return containsIntraMpeg2Picture(chunkData, offset, limit);
    }
    return false;
  }

  /** Prepares for parsing a chunk with the given {@code size}. */
  public void onChunkStart(int size) {
    currentChunkSize = size;
    bytesRemainingInCurrentChunk = size;
  }

  /**
   * Provides data associated to the current chunk and returns whether the full chunk has been
   * parsed.
   */
  public boolean onChunkData(ExtractorInput input) throws IOException {
    if (isH264Video()) {
      return onH264ChunkData(input);
    }
    bytesRemainingInCurrentChunk -=
        trackOutput.sampleData(input, bytesRemainingInCurrentChunk, false);
    boolean done = bytesRemainingInCurrentChunk == 0;
    if (done) {
      long timeUs = getCurrentChunkTimestampUs();
      if (currentChunkSize > 0) {
        trackOutput.sampleMetadata(
            timeUs,
            (isCurrentFrameAKeyFrame() ? C.BUFFER_FLAG_KEY_FRAME : 0),
            currentChunkSize,
            0,
            null);
      }
      if (usesDynamicAudioTimestamps()) {
        currentTimestampUs = timeUs + getAudioChunkDurationUs(currentChunkSize);
      }
      advanceCurrentChunk();
    }
    return done;
  }

  private boolean onH264ChunkData(ExtractorInput input) throws IOException {
    if (h264ChunkBuffer == null || h264ChunkBuffer.length < currentChunkSize) {
      h264ChunkBuffer = new byte[Math.max(currentChunkSize, 64 * 1024)];
    }
    if (currentChunkSize > 0) {
      input.readFully(h264ChunkBuffer, h264ChunkBufferOffset, bytesRemainingInCurrentChunk);
      h264ChunkBufferOffset += bytesRemainingInCurrentChunk;
      bytesRemainingInCurrentChunk = 0;
      emitH264AccessUnits(h264ChunkBuffer, h264ChunkBufferOffset);
    }
    h264ChunkBufferOffset = 0;
    advanceCurrentChunk();
    return true;
  }

  /**
   * Splits a buffered H264 AVI video chunk into one TrackOutput sample per access unit (picture)
   * and emits them with monotonically increasing per-frame timestamps. Some AVI files pack multiple
   * H264 frames into a single 00dc chunk; emitting them as one sample with a single timestamp
   * causes the renderer to drop all but the first frame.
   */
  private void emitH264AccessUnits(byte[] data, int length) {
    int chunkBaseChunkIndex = currentChunkIndex;
    long chunkBaseTimeUs = getChunkTimestampUs(chunkBaseChunkIndex);
    // Find every NAL unit and tag whether each one starts a new picture.
    int[] nalStartCodeOffsets = new int[16];
    int[] nalPayloadOffsets = new int[16];
    int[] nalTypes = new int[16];
    boolean[] isFirstVclOfPicture = new boolean[16];
    int nalCount = 0;
    int searchOffset = 0;
    while (searchOffset < length) {
      int startCodeOffset = findH264StartCodeOffset(data, searchOffset, length);
      if (startCodeOffset == C.INDEX_UNSET) {
        break;
      }
      int payloadOffset =
          startCodeOffset + getH264StartCodeLength(data, startCodeOffset, length);
      if (payloadOffset >= length) {
        break;
      }
      int nalType = data[payloadOffset] & 0x1F;
      boolean firstVcl = false;
      if (nalType >= 1 && nalType <= 5) {
        firstVcl = isH264FirstSliceOfPicture(data, payloadOffset + 1, length);
      }
      if (nalCount == nalStartCodeOffsets.length) {
        nalStartCodeOffsets = Arrays.copyOf(nalStartCodeOffsets, nalCount * 2);
        nalPayloadOffsets = Arrays.copyOf(nalPayloadOffsets, nalCount * 2);
        nalTypes = Arrays.copyOf(nalTypes, nalCount * 2);
        isFirstVclOfPicture = Arrays.copyOf(isFirstVclOfPicture, nalCount * 2);
      }
      nalStartCodeOffsets[nalCount] = startCodeOffset;
      nalPayloadOffsets[nalCount] = payloadOffset;
      nalTypes[nalCount] = nalType;
      isFirstVclOfPicture[nalCount] = firstVcl;
      nalCount++;
      searchOffset = payloadOffset + 1;
    }

    // Collect indices of the first-VCL-of-picture NALs.
    int[] pictureNalIndices = new int[Math.max(1, nalCount)];
    int pictureCount = 0;
    for (int i = 0; i < nalCount; i++) {
      if (isFirstVclOfPicture[i]) {
        if (pictureCount == pictureNalIndices.length) {
          pictureNalIndices = Arrays.copyOf(pictureNalIndices, pictureCount * 2);
        }
        pictureNalIndices[pictureCount++] = i;
      }
    }

    if (pictureCount == 0) {
      // No picture in this chunk (e.g. SPS/PPS/SEI only). Emit as a single non-key sample so the
      // decoder still receives parameter sets, but do not advance the picture counter.
      h264ScratchByteArray.reset(data, length);
      trackOutput.sampleData(h264ScratchByteArray, length);
      trackOutput.sampleMetadata(chunkBaseTimeUs, 0, length, 0, null);
      return;
    }

    boolean chunkMarkedKeyByIndex = isCurrentFrameAKeyFrame();
    for (int p = 0; p < pictureCount; p++) {
      int firstNalOfPicture = pictureNalIndices[p];
      int auStart = (p == 0) ? 0 : nalStartCodeOffsets[firstNalOfPicture];
      int auEnd =
          (p + 1 < pictureCount)
              ? nalStartCodeOffsets[pictureNalIndices[p + 1]]
              : length;
      int auSize = auEnd - auStart;
      if (auSize <= 0) {
        continue;
      }
      long pts;
      if (videoFrameDurationUs > 0) {
        pts = chunkBaseTimeUs + (long) p * videoFrameDurationUs;
      } else {
        pts = chunkBaseTimeUs;
      }
      boolean isIdr = nalTypes[firstNalOfPicture] == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR;
      // Mark as key frame if either the index says this chunk is a key chunk (covers idx1/fallback
      // classifications) or this picture itself starts with an IDR VCL.
      int flags = (isIdr || (p == 0 && chunkMarkedKeyByIndex)) ? C.BUFFER_FLAG_KEY_FRAME : 0;
      h264ScratchByteArray.reset(data, auEnd);
      h264ScratchByteArray.setPosition(auStart);
      trackOutput.sampleData(h264ScratchByteArray, auSize);
      trackOutput.sampleMetadata(pts, flags, auSize, 0, null);
      if (logNextH264Emit && p == 0) {
        JLog.i(
            "ChunkReader",
            "first H264 emit after seek. chunkIndex=" + chunkBaseChunkIndex
                + ", chunkBaseTimeUs=" + chunkBaseTimeUs
                + ", emittedPts=" + pts
                + ", auSize=" + auSize
                + ", flagsKey=" + ((flags & C.BUFFER_FLAG_KEY_FRAME) != 0)
                + ", isIdr=" + isIdr
                + ", chunkMarkedKeyByIndex=" + chunkMarkedKeyByIndex
                + ", pictureCountInChunk=" + pictureCount
                + ", chunkCount=" + chunkCount);
        logNextH264Emit = false;
      }
      if (h264EmitDebugRemaining > 0) {
        JLog.i(
            "ChunkReader",
            "h264-emit chunkIndex=" + chunkBaseChunkIndex
                + ", p=" + p
                + ", pts=" + pts
                + ", auSize=" + auSize
                + ", key=" + ((flags & C.BUFFER_FLAG_KEY_FRAME) != 0)
                + ", isIdr=" + isIdr);
        h264EmitDebugRemaining--;
      }
    }
  }

  private static boolean isH264FirstSliceOfPicture(byte[] data, int offset, int limit) {
    if (offset >= limit) {
      return false;
    }
    try {
      ParsableNalUnitBitArray bitArray = new ParsableNalUnitBitArray(data, offset, limit);
      // first_mb_in_slice is the very first ue(v) value in the slice header. == 0 marks the first
      // slice of a coded picture.
      return bitArray.readUnsignedExpGolombCodedInt() == 0;
    } catch (IllegalStateException | IndexOutOfBoundsException e) {
      // Truncated or malformed slice header. Treat as a new picture so we don't drop the NAL.
      return true;
    }
  }

  private boolean logNextH264Emit;
  private int h264EmitDebugRemaining;

  public void seekToPosition(long position) {
    if (indexSize > 0) {
      int index = binarySearchFloor(keyFrameOffsets, indexSize, position);
      currentChunkIndex = keyFrameIndices[index];
    } else if (seekIndexSize > 0) {
      int index = binarySearchFloor(seekOffsets, seekIndexSize, position);
      currentChunkIndex = seekIndices[index];
    } else {
      currentChunkIndex = 0;
    }
    currentTimestampUs = getChunkTimestampUs(currentChunkIndex);
    h264ChunkBufferOffset = 0;
    logNextH264Emit = isH264Video();
    h264EmitDebugRemaining = isH264Video() ? 30 : 0;
    if (logNextH264Emit) {
      JLog.i(
          "ChunkReader",
          "seekToPosition position=" + position
              + ", landedChunkIndex=" + currentChunkIndex
              + ", landedTimestampUs=" + currentTimestampUs
              + ", chunkCount=" + chunkCount
              + ", durationUs=" + durationUs
              + ", videoFrameDurationUs=" + videoFrameDurationUs
              + ", indexSize=" + indexSize
              + ", seekIndexSize=" + seekIndexSize);
    }
  }

  public SeekMap.SeekPoints getSeekPoints(long timeUs) {
    if (indexSize == 0 && seekIndexSize == 0) {
      // Return the offset of the first chunk as there are no keyframes in the index.
      return new SeekMap.SeekPoints(
          new SeekPoint(/* timeUs= */ 0, /* position= */ firstIndexChunkOffset));
    }
    int targetFrameIndex = (int) (timeUs / getFrameDurationUs());
    if (indexSize == 0) {
      int seekIndex = binarySearchFloor(seekIndices, seekIndexSize, targetFrameIndex);
      if (seekIndices[seekIndex] == targetFrameIndex) {
        return new SeekMap.SeekPoints(getSeekPointForSeekIndex(seekIndex));
      }
      SeekPoint precedingSeekPoint = getSeekPointForSeekIndex(seekIndex);
      if (seekIndex + 1 < seekIndexSize) {
        return new SeekMap.SeekPoints(precedingSeekPoint, getSeekPointForSeekIndex(seekIndex + 1));
      } else {
        return new SeekMap.SeekPoints(precedingSeekPoint);
      }
    }
    int keyFrameIndex = binarySearchFloor(keyFrameIndices, indexSize, targetFrameIndex);
    if (keyFrameIndices[keyFrameIndex] == targetFrameIndex) {
      return new SeekMap.SeekPoints(getSeekPoint(keyFrameIndex));
    }
    if (shouldSeekToFollowingKeyFrameInFallback(keyFrameIndex)) {
      return new SeekMap.SeekPoints(getSeekPoint(keyFrameIndex + 1));
    }
    // The target frame is not a key frame, we look for the two closest ones.
    SeekPoint precedingKeyFrameSeekPoint = getSeekPoint(keyFrameIndex);
    if (keyFrameIndex + 1 < indexSize) {
      return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint, getSeekPoint(keyFrameIndex + 1));
    } else {
      return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint);
    }
  }

  public SeekMap.SeekPoints getSeekPointsByBytePosition(
      long timeUs, long dataStartPosition, long dataEndPosition) {
    if (indexSize == 0 && seekIndexSize == 0) {
      return new SeekMap.SeekPoints(
          new SeekPoint(/* timeUs= */ 0, /* position= */ firstIndexChunkOffset));
    }
    long targetPosition =
        durationUs > 0 && dataEndPosition > dataStartPosition
            ? dataStartPosition
                + Util.scaleLargeTimestamp(
                    timeUs, dataEndPosition - dataStartPosition, durationUs)
            : firstIndexChunkOffset;
    if (indexSize == 0) {
      int seekIndex = binarySearchFloor(seekOffsets, seekIndexSize, targetPosition);
      SeekPoint precedingSeekPoint = getByteSeekPointForSeekIndex(seekIndex, dataStartPosition, dataEndPosition);
      if (seekOffsets[seekIndex] == targetPosition || seekIndex + 1 >= seekIndexSize) {
        return new SeekMap.SeekPoints(precedingSeekPoint);
      }
      return new SeekMap.SeekPoints(
          precedingSeekPoint,
          getByteSeekPointForSeekIndex(seekIndex + 1, dataStartPosition, dataEndPosition));
    }
    int keyFrameIndex = binarySearchFloor(keyFrameOffsets, indexSize, targetPosition);
    SeekPoint precedingKeyFrameSeekPoint =
        getByteSeekPoint(keyFrameIndex, dataStartPosition, dataEndPosition);
    if (keyFrameOffsets[keyFrameIndex] == targetPosition) {
      return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint);
    }
    if (shouldSeekToFollowingKeyFrameInFallback(keyFrameIndex)) {
      return new SeekMap.SeekPoints(
          getByteSeekPoint(keyFrameIndex + 1, dataStartPosition, dataEndPosition));
    }
    if (keyFrameIndex + 1 < indexSize) {
      return new SeekMap.SeekPoints(
          precedingKeyFrameSeekPoint,
          getByteSeekPoint(keyFrameIndex + 1, dataStartPosition, dataEndPosition));
    }
    return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint);
  }

  private long getChunkTimestampUs(int chunkIndex) {
    return durationUs * chunkIndex / chunkCount;
  }

  private long getAudioChunkDurationUs(int chunkSize) {
    long durationDivisor = (long) streamHeaderChunk.rate * streamHeaderChunk.sampleSize;
    return durationDivisor > 0
        ? Util.scaleLargeTimestamp(
            /* timestamp= */ chunkSize,
            /* multiplier= */ C.MICROS_PER_SECOND * (long) streamHeaderChunk.scale,
            /* divisor= */ durationDivisor)
        : 0L;
  }

  private boolean usesDynamicAudioTimestamps() {
    return isAudio()
        && streamHeaderChunk.sampleSize > 0
        && streamHeaderChunk.scale > 0
        && streamHeaderChunk.rate > 0;
  }

  private boolean shouldSeekToFollowingKeyFrameInFallback(int keyFrameIndex) {
    // Missing/truncated-idx1 fallback seeks run through ProgressiveMediaPeriod's exact-seek path,
    // which still uses SeekPoints.first.position as the load position. Jumping to the following H264
    // random access point avoids decoding and discarding several seconds from an earlier IDR.
    return isVideo()
        && MimeTypes.VIDEO_H264.equals(sampleMimeType)
        && seekIndexSize > 0
        && keyFrameIndex + 1 < indexSize;
  }

  private static int binarySearchFloor(long[] array, int length, long value) {
    int index = Arrays.binarySearch(array, 0, length, value);
    return index >= 0 ? index : Math.max(0, -index - 2);
  }

  private static int binarySearchFloor(int[] array, int length, int value) {
    int index = Arrays.binarySearch(array, 0, length, value);
    return index >= 0 ? index : Math.max(0, -index - 2);
  }

  private SeekPoint getSeekPoint(int keyFrameIndex) {
    return new SeekPoint(
        keyFrameIndices[keyFrameIndex] * getFrameDurationUs(), keyFrameOffsets[keyFrameIndex]);
  }

  private SeekPoint getSeekPointForSeekIndex(int seekIndex) {
    return new SeekPoint(seekIndices[seekIndex] * getFrameDurationUs(), seekOffsets[seekIndex]);
  }

  private SeekPoint getByteSeekPoint(int keyFrameIndex, long dataStartPosition, long dataEndPosition) {
    return new SeekPoint(
        getChunkTimestampUs(keyFrameIndices[keyFrameIndex]),
        keyFrameOffsets[keyFrameIndex]);
  }

  private SeekPoint getByteSeekPointForSeekIndex(
      int seekIndex, long dataStartPosition, long dataEndPosition) {
    return new SeekPoint(
        getChunkTimestampUs(seekIndices[seekIndex]),
        seekOffsets[seekIndex]);
  }

  private boolean isMpeg4VisualLike() {
    return MimeTypes.VIDEO_MP4V.equals(sampleMimeType)
        || MimeTypes.VIDEO_MP42.equals(sampleMimeType)
        || MimeTypes.VIDEO_MP43.equals(sampleMimeType)
        || MimeTypes.VIDEO_DIVX.equals(sampleMimeType)
        || MimeTypes.VIDEO_XVID.equals(sampleMimeType)
        || MimeTypes.VIDEO_DX50.equals(sampleMimeType)
        || MimeTypes.VIDEO_MSMPEG4V3.equals(sampleMimeType);
  }

  /* package */ static boolean containsH264KeyframeNalUnit(byte[] data, int offset, int limit) {
    int nalStartOffset = offset;
    while (nalStartOffset < limit) {
      int startCodeOffset = findH264StartCodeOffset(data, nalStartOffset, limit);
      if (startCodeOffset == C.INDEX_UNSET) {
        return false;
      }
      int nalHeaderOffset = startCodeOffset + getH264StartCodeLength(data, startCodeOffset, limit);
      if (nalHeaderOffset >= limit) {
        return false;
      }
      int nalUnitType = data[nalHeaderOffset] & 0x1F;
      int nextStartCodeOffset = findH264StartCodeOffset(data, nalHeaderOffset + 1, limit);
      int nalLimit = nextStartCodeOffset == C.INDEX_UNSET ? limit : nextStartCodeOffset;
      if (nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR
          || nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR) {
        // AVI seeks always land on the start of the chunk, so we only treat the chunk as seekable
        // when its first VCL access unit is an IDR. Non-IDR I-slices can still depend on earlier
        // frames, which is not safe when jumping straight to the start of the chunk.
        return nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR;
      }
      nalStartOffset = nalLimit;
    }
    return false;
  }

  private static int findH264StartCodeOffset(byte[] data, int offset, int limit) {
    for (int i = offset; i + 3 < limit; i++) {
      if (data[i] == 0 && data[i + 1] == 0) {
        if (data[i + 2] == 1) {
          return i;
        }
        if (i + 3 < limit && data[i + 2] == 0 && data[i + 3] == 1) {
          return i;
        }
      }
    }
    return C.INDEX_UNSET;
  }

  private static int getH264StartCodeLength(byte[] data, int startCodeOffset, int limit) {
    return startCodeOffset + 2 < limit && data[startCodeOffset + 2] == 1 ? 3 : 4;
  }

  private static boolean containsIntraMpeg4VisualFrame(byte[] data, int offset, int limit) {
    for (int i = offset; i + 4 < limit; i++) {
      int startCode =
          ((data[i] & 0xFF) << 24)
              | ((data[i + 1] & 0xFF) << 16)
              | ((data[i + 2] & 0xFF) << 8)
              | (data[i + 3] & 0xFF);
      if (startCode == MPEG4_VISUAL_VOP_START_CODE) {
        return ((data[i + 4] & 0xC0) >> 6) == 0;
      }
    }
    return false;
  }

  private static boolean containsIntraMpeg2Picture(byte[] data, int offset, int limit) {
    for (int i = offset; i + 5 < limit; i++) {
      int startCode =
          ((data[i] & 0xFF) << 24)
              | ((data[i + 1] & 0xFF) << 16)
              | ((data[i + 2] & 0xFF) << 8)
              | (data[i + 3] & 0xFF);
      if (startCode == MPEG2_PICTURE_START_CODE) {
        return ((data[i + 5] >> 3) & 0x07) == 1;
      }
    }
    return false;
  }

  private static int getChunkIdFourCc(int streamId, @ChunkType int chunkType) {
    int tens = streamId / 10;
    int ones = streamId % 10;
    return (('0' + ones) << 8) | ('0' + tens) | chunkType;
  }
}
