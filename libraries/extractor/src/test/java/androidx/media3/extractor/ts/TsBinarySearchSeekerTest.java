/*
 * Copyright 2026 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link TsBinarySearchSeeker}. */
@RunWith(AndroidJUnit4.class)
public final class TsBinarySearchSeekerTest {

  private static final int PID = 256;
  private static final int PACKET_SIZE = 188;
  private static final int PACKET_COUNT = 100;

  @Test
  public void ptsSeeker_seekToTimestamp_findsPacketNearTarget() throws IOException {
    byte[] data = createPtsOnlyTransportStream();
    TimestampAdjuster timestampAdjuster = new TimestampAdjuster(/* firstSampleTimestampUs= */ 0);
    timestampAdjuster.adjustTsTimestamp(/* pts90Khz= */ 0);
    TsBinarySearchSeeker seeker =
        TsBinarySearchSeeker.createForPts(
            timestampAdjuster,
            /* streamDurationUs= */ 9_000_000,
            data.length,
            PID,
            /* timestampSearchBytes= */ 20 * PACKET_SIZE,
            PACKET_SIZE);
    SeekMap seekMap = seeker.getSeekMap();

    assertThat(seekMap.isSeekable()).isTrue();

    seeker.setSeekTargetUs(/* timeUs= */ 5_000_000);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();
    PositionHolder positionHolder = new PositionHolder();
    while (seeker.isSeeking()) {
      int result = seeker.handlePendingSeek(input, positionHolder);
      if (result == Extractor.RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      }
    }

    assertThat(input.getPosition()).isAtLeast(40L * PACKET_SIZE);
    assertThat(input.getPosition()).isAtMost(60L * PACKET_SIZE);
  }

  private static byte[] createPtsOnlyTransportStream() {
    byte[] data = new byte[PACKET_COUNT * PACKET_SIZE];
    Arrays.fill(data, (byte) 0xFF);
    for (int packetIndex = 0; packetIndex < PACKET_COUNT; packetIndex++) {
      int packetOffset = packetIndex * PACKET_SIZE;
      boolean containsPts = packetIndex % 10 == 0;
      data[packetOffset] = 0x47;
      data[packetOffset + 1] = (byte) ((containsPts ? 0x40 : 0) | (PID >> 8));
      data[packetOffset + 2] = (byte) PID;
      data[packetOffset + 3] = 0x10;
      if (containsPts) {
        writePesHeaderWithPts(data, packetOffset + 4, packetIndex / 10 * 90_000L);
      }
    }
    return data;
  }

  private static void writePesHeaderWithPts(byte[] data, int offset, long pts) {
    data[offset] = 0;
    data[offset + 1] = 0;
    data[offset + 2] = 1;
    data[offset + 3] = (byte) 0xE0;
    data[offset + 4] = 0;
    data[offset + 5] = 0;
    data[offset + 6] = (byte) 0x80;
    data[offset + 7] = (byte) 0x80;
    data[offset + 8] = 5;
    data[offset + 9] = (byte) (0x21 | (((pts >> 30) & 0x07) << 1));
    data[offset + 10] = (byte) (pts >> 22);
    data[offset + 11] = (byte) ((((pts >> 15) & 0x7F) << 1) | 1);
    data[offset + 12] = (byte) (pts >> 7);
    data[offset + 13] = (byte) (((pts & 0x7F) << 1) | 1);
  }
}
