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
package androidx.media3.extractor;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.Util;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link Ac3Util}. */
@RunWith(AndroidJUnit4.class)
public final class Ac3UtilTest {

  private static final int TRUEHD_SYNCFRAME_SAMPLE_COUNT = 40;
  private static final byte[] TRUEHD_SYNCFRAME_HEADER =
      Util.getBytesFromHexString("C07504D8F8726FBA0097C00FB7520000");
  private static final byte[] TRUEHD_NON_SYNCFRAME_HEADER =
      Util.getBytesFromHexString("A025048860224E6F6DEDB6D5B6DBAFE6");

  // False-positive AC-3 syncword found in uncompressed PCM audio with frmsizecod/2 = 24 >= 19.
  private static final byte[] FALSE_POSITIVE_AC3_HEADER_OUT_OF_BOUNDS =
      Util.getBytesFromHexString("0B77123AF1490309F1EBF85A10DEFAA9");

  @Test
  public void parseTrueHdSyncframeAudioSampleCount_nonSyncframe() {
    assertThat(Ac3Util.parseTrueHdSyncframeAudioSampleCount(TRUEHD_NON_SYNCFRAME_HEADER))
        .isEqualTo(0);
  }

  @Test
  public void parseTrueHdSyncframeAudioSampleCount_syncframe() {
    assertThat(Ac3Util.parseTrueHdSyncframeAudioSampleCount(TRUEHD_SYNCFRAME_HEADER))
        .isEqualTo(TRUEHD_SYNCFRAME_SAMPLE_COUNT);
  }

  @Test
  public void parseAc3SyncframeInfo_outOfBoundsFrmsizecod_doesNotThrowAndReturnsUnset() {
    ParsableBitArray bitArray = new ParsableBitArray(FALSE_POSITIVE_AC3_HEADER_OUT_OF_BOUNDS);
    Ac3Util.SyncFrameInfo frameInfo = Ac3Util.parseAc3SyncframeInfo(bitArray);
    assertThat(frameInfo.frameSize).isEqualTo(C.LENGTH_UNSET);
    assertThat(frameInfo.mimeType).isNull();
  }
}
