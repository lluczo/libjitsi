/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.impl.neomedia;

import org.jitsi.impl.neomedia.transform.rtcp.*;

/**
 * Media stream statistics per send ssrc implementation.
 * @author Damian Minkov
 */
public class MediaStreamSentSSRCStats
    extends AbstractMediaStreamSSRCStats
{
    MediaStreamSentSSRCStats(long ssrc, StatisticsEngine statisticsEngine)
    {
        super(ssrc, statisticsEngine);
    }

    /**
     * The number of bytes sent by the stream.
     * @return number of bytes.
     */
    public long getNbBytes()
    {
        return this.statisticsEngine.getNbBytesSent(this.ssrc);
    }

    /**
     * The number of packets sent by the stream.
     * @return number of packets.
     */
    public long getNbPackets()
    {
        return this.statisticsEngine.getRtpPacketsSent(this.ssrc);
    }
}