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

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;

/** Utilities method for extracting MPEG-TS streams. */
@UnstableApi
public final class TsUtil {

  /**
   * Returns whether a TS packet starts at {@code searchPosition} according to the MPEG-TS
   * synchronization recommendations.
   *
   * <p>ISO/IEC 13818-1:2015 Annex G recommends that 5 sync bytes emulating the start of 5
   * consecutive TS packets should never occur as part of the TS packets' contents. So, this method
   * returns true when {@code data} contains a sync byte at {@code searchPosition}, and said sync
   * byte is also one of five consecutive sync bytes separated from each other by the size of a TS
   * packet.
   *
   * @param data The array holding the data to search in.
   * @param start The first valid position in {@code data} from which a sync byte can be read.
   * @param limit The first invalid position in {@code data}, after which no data should be read.
   * @param searchPosition The position to check for a TS packet start.
   * @return Whether a TS packet starts at {@code searchPosition}.
   */
  public static boolean isStartOfTsPacket(byte[] data, int start, int limit, int searchPosition, int packetSize) {
    int consecutiveSyncByteCount = 0;
    for (int i = -4; i <= 4; i++) {
      int currentPosition = searchPosition + i * packetSize;
      if (currentPosition < start
          || currentPosition >= limit
          || data[currentPosition] != TsExtractor.TS_SYNC_BYTE) {
        consecutiveSyncByteCount = 0;
      } else if (++consecutiveSyncByteCount == 5) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the position of the first TS_SYNC_BYTE within the range [startPosition, limitPosition)
   * from the provided data array, or returns limitPosition if sync byte could not be found.
   */
  public static int findSyncBytePosition(byte[] data, int startPosition, int limitPosition) {
    int position = startPosition;
    while (position < limitPosition && data[position] != TsExtractor.TS_SYNC_BYTE) {
      position++;
    }
    return position;
  }

  /**
   * Returns the PCR value read from a given TS packet.
   *
   * @param packetBuffer The buffer that holds the packet.
   * @param startOfPacket The starting position of the packet in the buffer.
   * @param pcrPid The PID for valid packets that contain PCR values.
   * @return The PCR value read from the packet, if its PID is equal to {@code pcrPid} and it
   *     contains a valid PCR value. Returns {@link C#TIME_UNSET} otherwise.
   */
  public static long readPcrFromPacket(
      ParsableByteArray packetBuffer, int startOfPacket, int pcrPid) {
    packetBuffer.setPosition(startOfPacket);
    if (packetBuffer.bytesLeft() < 5) {
      // Header = 4 bytes, adaptationFieldLength = 1 byte.
      return C.TIME_UNSET;
    }
    // Note: See ISO/IEC 13818-1, section 2.4.3.2 for details of the header format.
    int tsPacketHeader = packetBuffer.readInt();
    if ((tsPacketHeader & 0x800000) != 0) {
      // transport_error_indicator != 0 means there are uncorrectable errors in this packet.
      return C.TIME_UNSET;
    }
    int pid = (tsPacketHeader & 0x1FFF00) >> 8;
    if (pid != pcrPid) {
      return C.TIME_UNSET;
    }
    boolean adaptationFieldExists = (tsPacketHeader & 0x20) != 0;
    if (!adaptationFieldExists) {
      return C.TIME_UNSET;
    }

    int adaptationFieldLength = packetBuffer.readUnsignedByte();
    if (adaptationFieldLength >= 7 && packetBuffer.bytesLeft() >= 7) {
      int flags = packetBuffer.readUnsignedByte();
      boolean pcrFlagSet = (flags & 0x10) == 0x10;
      if (pcrFlagSet) {
        byte[] pcrBytes = new byte[6];
        packetBuffer.readBytes(pcrBytes, /* offset= */ 0, pcrBytes.length);
        return readPcrValueFromPcrBytes(pcrBytes);
      }
    }
    return C.TIME_UNSET;
  }

  /** Returns the PID read from a TS packet, or {@link C#INDEX_UNSET} if the header is invalid. */
  static int readPidFromPacket(ParsableByteArray packetBuffer, int startOfPacket) {
    packetBuffer.setPosition(startOfPacket);
    if (packetBuffer.bytesLeft() < 4) {
      return C.INDEX_UNSET;
    }
    int tsPacketHeader = packetBuffer.readInt();
    if ((tsPacketHeader & 0x800000) != 0) {
      return C.INDEX_UNSET;
    }
    return (tsPacketHeader & 0x1FFF00) >> 8;
  }

  /**
   * Returns the PTS read from the PES header in a TS packet.
   *
   * @param expectedPid The required packet PID, or {@link C#INDEX_UNSET} to accept any PID.
   */
  static long readPtsFromPacket(
      ParsableByteArray packetBuffer, int startOfPacket, int expectedPid) {
    packetBuffer.setPosition(startOfPacket);
    if (packetBuffer.bytesLeft() < 5) {
      return C.TIME_UNSET;
    }
    int tsPacketHeader = packetBuffer.readInt();
    if ((tsPacketHeader & 0x800000) != 0) {
      return C.TIME_UNSET;
    }
    int pid = (tsPacketHeader & 0x1FFF00) >> 8;
    if (expectedPid != C.INDEX_UNSET && pid != expectedPid) {
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

    if (!payloadUnitStartIndicator || packetBuffer.bytesLeft() < 9) {
      return C.TIME_UNSET;
    }
    int prefix =
        (packetBuffer.readUnsignedByte() << 16)
            | (packetBuffer.readUnsignedByte() << 8)
            | packetBuffer.readUnsignedByte();
    if (prefix != 0x000001) {
      return C.TIME_UNSET;
    }
    packetBuffer.skipBytes(3); // stream_id and PES_packet_length.
    if (packetBuffer.bytesLeft() < 3) {
      return C.TIME_UNSET;
    }
    packetBuffer.skipBytes(1); // PES flags.
    int ptsDtsFlags = (packetBuffer.readUnsignedByte() >> 6) & 0x03;
    packetBuffer.skipBytes(1); // PES_header_data_length.
    if (ptsDtsFlags != 0x02 && ptsDtsFlags != 0x03) {
      return C.TIME_UNSET;
    }
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

  /**
   * Returns the value of PCR base - first 33 bits in big endian order from the PCR bytes.
   *
   * <p>We ignore PCR Ext, because it's too small to have any significance.
   */
  private static long readPcrValueFromPcrBytes(byte[] pcrBytes) {
    return (pcrBytes[0] & 0xFFL) << 25
        | (pcrBytes[1] & 0xFFL) << 17
        | (pcrBytes[2] & 0xFFL) << 9
        | (pcrBytes[3] & 0xFFL) << 1
        | (pcrBytes[4] & 0xFFL) >> 7;
  }

  private TsUtil() {
    // Prevent instantiation.
  }
}
