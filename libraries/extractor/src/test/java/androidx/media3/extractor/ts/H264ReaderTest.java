/*
 * Copyright (C) 2026 The Android Open Source Project
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

import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link H264Reader}. */
@RunWith(AndroidJUnit4.class)
public final class H264ReaderTest {

  // SPS and PPS extracted from sample_h264.ts
  private static final byte[] SPS_DATA =
      TestUtil.createByteArray(
          0x00, 0x00, 0x01, 0x67, 0x64, 0x00, 0x1F, 0xAC, 0xD9, 0x40, 0xD8, 0x3D, 0xE6, 0xE1,
          0x00, 0x00, 0x03, 0x00, 0x01, 0x00, 0x00, 0x03, 0x00, 0x3C, 0x0F, 0x18, 0x31, 0x96);

  private static final byte[] PPS_DATA =
      TestUtil.createByteArray(0x00, 0x00, 0x01, 0x68, 0xEB, 0xE3, 0xCB, 0x22, 0xC0);

  // NAL unit type 15 (Subset SPS): 0x000001 followed by header byte 0x2F (nal_ref_idc=1, type=15)
  private static final byte[] SUBSET_SPS_DATA =
      TestUtil.createByteArray(
          0x00, 0x00, 0x01, 0x2F, 0x80, 0x00, 0x29, 0x01);

  // NAL unit type 20 (Slice Extension): 0x000001 followed by header byte 0x34 (nal_ref_idc=1, type=20)
  private static final byte[] SLICE_EXT_DATA =
      TestUtil.createByteArray(
          0x00, 0x00, 0x01, 0x34, 0x80, 0x00, 0x00);

  // AUD: 0x00000109F0
  private static final byte[] AUD_DATA =
      TestUtil.createByteArray(0x00, 0x00, 0x01, 0x09, 0xF0);

  private H264Reader reader;
  private FakeTrackOutput trackOutput;

  @Before
  public void setUp() {
    reader =
        new H264Reader(
            new SeiReader(Collections.emptyList(), MimeTypes.VIDEO_MP2T),
            /* allowNonIdrKeyframes= */ false,
            /* detectAccessUnits= */ false,
            MimeTypes.VIDEO_MP2T);

    FakeExtractorOutput extractorOutput =
        new FakeExtractorOutput((id, type) -> new FakeTrackOutput(/* deduplicateConsecutiveFormats= */ true));
    TrackIdGenerator idGenerator = new TrackIdGenerator(1, 1);
    reader.createTracks(extractorOutput, idGenerator);
    trackOutput = extractorOutput.trackOutputs.get(1);
  }

  @Test
  public void consume_standardH264_outputsH264MimeType() {
    byte[] stream = Bytes.concat(AUD_DATA, SPS_DATA, PPS_DATA, AUD_DATA);
    reader.packetStarted(/* pesTimeUs= */ 0, /* flags= */ 0);
    reader.consume(new ParsableByteArray(stream));
    reader.packetFinished(/* isEndOfInput= */ true);

    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
  }

  @Test
  public void consume_mvcSubsetSpsBeforePps_outputsMvcMimeType() {
    byte[] stream = Bytes.concat(AUD_DATA, SPS_DATA, SUBSET_SPS_DATA, PPS_DATA, AUD_DATA);
    reader.packetStarted(/* pesTimeUs= */ 0, /* flags= */ 0);
    reader.consume(new ParsableByteArray(stream));
    reader.packetFinished(/* isEndOfInput= */ true);

    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_MVC);
  }

  @Test
  public void consume_mvcSliceExtensionAfterPps_updatesToMvcMimeType() {
    byte[] initialStream = Bytes.concat(AUD_DATA, SPS_DATA, PPS_DATA, AUD_DATA);
    reader.packetStarted(/* pesTimeUs= */ 0, /* flags= */ 0);
    reader.consume(new ParsableByteArray(initialStream));

    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);

    // Later packet with MVC Slice Extension (NAL 20)
    byte[] mvcStream = Bytes.concat(SLICE_EXT_DATA, AUD_DATA);
    reader.packetStarted(/* pesTimeUs= */ 40000, /* flags= */ 0);
    reader.consume(new ParsableByteArray(mvcStream));
    reader.packetFinished(/* isEndOfInput= */ true);

    assertThat(trackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_MVC);
  }

  @Test
  public void consume_spsWithStereoHighProfile_outputsMvcMimeType() {
    // Modify profile_idc from 0x64 (100) to 0x80 (128 = Stereo High Profile)
    byte[] mvcSps = SPS_DATA.clone();
    mvcSps[4] = (byte) 128;

    byte[] stream = Bytes.concat(AUD_DATA, mvcSps, PPS_DATA, AUD_DATA);
    reader.packetStarted(/* pesTimeUs= */ 0, /* flags= */ 0);
    reader.consume(new ParsableByteArray(stream));
    reader.packetFinished(/* isEndOfInput= */ true);

    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_MVC);
  }
}
