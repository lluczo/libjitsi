/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.transform.dtls;

import java.io.*;
import java.security.*;

import org.bouncycastle.crypto.tls.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

/**
 * Implements {@link PacketTransformer} for DTLS-SRTP.
 *
 * @author Lyubomir Marinov
 */
public class DtlsPacketTransformer
    implements PacketTransformer
{
    /**
     * The length of the header of a DTLS record.
     */
    static final int DTLS_RECORD_HEADER_LENGTH = 13;

    /**
     * The number of milliseconds a <tt>DtlsPacketTransform</tt> is to wait on
     * its {@link #dtlsProtocol} in order to receive a packet.
     */
    private static final int DTLS_TRANSPORT_RECEIVE_WAITMILLIS = 1;

    /**
     * The <tt>Logger</tt> used by the <tt>DtlsPacketTransformer</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(DtlsPacketTransformer.class);

    /**
     * Determines whether a specific array of <tt>byte</tt>s appears to contain
     * a DTLS record.
     *
     * @param buf the array of <tt>byte</tt>s to be analyzed
     * @param off the offset within <tt>buf</tt> at which the analysis is to
     * start
     * @param len the number of bytes within <tt>buf</tt> starting at
     * <tt>off</tt> to be analyzed
     * @return <tt>true</tt> if the specified <tt>buf</tt> appears to contain a
     * DTLS record
     */
    public static boolean isDtlsRecord(byte[] buf, int off, int len)
    {
        boolean b = false;

        if (len >= DTLS_RECORD_HEADER_LENGTH)
        {
            short type = TlsUtils.readUint8(buf, off);

            switch (type)
            {
            case ContentType.alert:
            case ContentType.application_data:
            case ContentType.change_cipher_spec:
            case ContentType.handshake:
                int major = buf[off + 1] & 0xff;
                int minor = buf[off + 2] & 0xff;
                ProtocolVersion version = null;

                if ((major == ProtocolVersion.DTLSv10.getMajorVersion())
                        && (minor == ProtocolVersion.DTLSv10.getMinorVersion()))
                {
                    version = ProtocolVersion.DTLSv10;
                }
                if ((version == null)
                        && (major == ProtocolVersion.DTLSv12.getMajorVersion())
                        && (minor == ProtocolVersion.DTLSv12.getMinorVersion()))
                {
                    version = ProtocolVersion.DTLSv12;
                }
                if (version != null)
                {
                    int length = TlsUtils.readUint16(buf, off + 11);

                    if (DTLS_RECORD_HEADER_LENGTH + length <= len)
                        b = true;
                }
                break;
            default:
                /*
                 * Unless a new ContentType has been defined by the Bouncy
                 * Castle Crypto APIs, the specified buf does not represent a
                 * DTLS record.
                 */
                break;
            }
        }
        return b;
    }

    /**
     * The ID of the component which this instance works for/is associated with.
     */
    private final int componentID;

    /**
     * The background <tt>Thread</tt> which initializes {@link #dtlsTransport}.
     */
    private Thread connectThread;

    /**
     * The <tt>RTPConnector</tt> which uses this <tt>PacketTransformer</tt>.
     */
    private AbstractRTPConnector connector;

    /**
     * The <tt>DatagramTransport</tt> implementation which adapts
     * {@link #connector} and this <tt>PacketTransformer</tt> to the terms of
     * the Bouncy Castle Crypto APIs.
     */
    private DatagramTransportImpl datagramTransport;

    /**
     * The DTLS protocol according to which this <tt>DtlsPacketTransformer</tt>
     * is to act either as a DTLS server or a DTLS client.
     */
    private int dtlsProtocol;

    /**
     * The <tt>DTLSTransport</tt> through which the actual packet
     * transformations are being performed by this instance.
     */
    private DTLSTransport dtlsTransport;

    /**
     * The <tt>MediaType</tt> of the stream which this instance works for/is
     * associated with.
     */
    private MediaType mediaType;

    /**
     * The <tt>TransformEngine</tt> which has initialized this instance.
     */
    private final DtlsTransformEngine transformEngine;

    /**
     * Initializes a new <tt>DtlsPacketTransformer</tt> instance.
     *
     * @param transformEngine the <tt>TransformEngine</tt> which is initializing
     * the new instance
     * @param componentID the ID of the component for which the new instance is
     * to work
     */
    public DtlsPacketTransformer(
            DtlsTransformEngine transformEngine,
            int componentID)
    {
        this.transformEngine = transformEngine;
        this.componentID = componentID;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void close()
    {
        setConnector(null);
        setMediaType(null);
    }

    /**
     * Closes {@link #datagramTransport} if it is non-<tt>null</tt> and logs and
     * swallows any <tt>IOException</tt>.
     */
    private void closeDatagramTransport()
    {
        if (datagramTransport != null)
        {
            try
            {
                datagramTransport.close();
            }
            catch (IOException ioe)
            {
                /*
                 * DatagramTransportImpl has no reason to fail because it is
                 * merely an adapter of #connector and this PacketTransformer to
                 * the terms of the Bouncy Castle Crypto API.
                 */
                logger.error(
                        "Failed to (properly) close "
                            + datagramTransport.getClass(),
                        ioe);
            }
            datagramTransport = null;
        }
    }

    /**
     * Gets the <tt>DtlsControl</tt> implementation associated with this
     * instance.
     *
     * @return the <tt>DtlsControl</tt> implementation associated with this
     * instance
     */
    DtlsControlImpl getDtlsControl()
    {
        return getTransformEngine().getDtlsControl();
    }

    /**
     * Gets the <tt>TransformEngine</tt> which has initialized this instance.
     *
     * @return the <tt>TransformEngine</tt> which has initialized this instance
     */
    DtlsTransformEngine getTransformEngine()
    {
        return transformEngine;
    }

    /**
     * {@inheritDoc}
     */
    public RawPacket reverseTransform(RawPacket pkt)
    {
        byte[] buf = pkt.getBuffer();
        int off = pkt.getOffset();
        int len = pkt.getLength();

        if (isDtlsRecord(buf, off, len))
        {
            boolean receive;

            synchronized (this)
            {
                if (datagramTransport == null)
                {
                    receive = false;
                }
                else
                {
                    datagramTransport.queue(buf, off, len);
                    receive = true;
                }
            }
            if (receive)
            {
                DTLSTransport dtlsTransport = this.dtlsTransport;

                if (dtlsTransport == null)
                {
                    /*
                     * The specified pkt looks like a DTLS record and it has
                     * been consumed for the purposes of the secure channel
                     * represented by this PacketTransformer.
                     */
                    pkt = null;
                }
                else
                {
                    try
                    {
                        int receiveLimit = dtlsTransport.getReceiveLimit();
                        int delta = receiveLimit - len;

                        if (delta > 0)
                        {
                            pkt.grow(delta);
                            buf = pkt.getBuffer();
                            off = pkt.getOffset();
                            len = pkt.getLength();
                        }
                        else if (delta < 0)
                        {
                            pkt.shrink(-delta);
                            buf = pkt.getBuffer();
                            off = pkt.getOffset();
                            len = pkt.getLength();
                        }

                        int received
                            = dtlsTransport.receive(
                                buf, off, len,
                                DTLS_TRANSPORT_RECEIVE_WAITMILLIS);

                        if (received <= 0)
                        {
                            // No application data was decoded.
                            pkt = null;
                        }
                        else
                        {
                            delta = len - received;
                            if (delta > 0)
                                pkt.shrink(delta);
                        }
                    }
                    catch (IOException ioe)
                    {
                        pkt = null;
                        logger.error("Failed to decode a DTLS record!", ioe);
                    }
                }
            }
            else
            {
                /*
                 * The specified pkt looks like a DTLS record but it is
                 * unexpected in the current state of the secure channels
                 * represented by this PacketTransformer.
                 */
                pkt = null;
            }
        }
        else
        {
            /*
             * The specified pkt does not look like a DTLS record so it is not a
             * valid packet on the secure channel represented by this
             * PacketTransformer.
             */
            pkt = null;
        }
        return pkt;
    }

    /**
     * Runs in {@link #connectThread} to initialize {@link #dtlsTransport}.
     *
     * @param dtlsProtocol
     * @param tlsPeer
     * @param datagramTransport
     */
    private void runInConnectThread(
            DTLSProtocol dtlsProtocol,
            TlsPeer tlsPeer,
            DatagramTransport datagramTransport)
    {
        DTLSTransport dtlsTransport = null;

        if (dtlsProtocol instanceof DTLSClientProtocol)
        {
            DTLSClientProtocol dtlsClientProtocol
                = (DTLSClientProtocol) dtlsProtocol;
            TlsClient tlsClient = (TlsClient) tlsPeer;

            try
            {
                dtlsTransport
                    = dtlsClientProtocol.connect(
                            tlsClient, 
                            datagramTransport);
            }
            catch (IOException ioe)
            {
                logger.error(
                        "Failed to connect this DTLS client to a DTLS server!",
                        ioe);
            }
        }
        else if (dtlsProtocol instanceof DTLSServerProtocol)
        {
            DTLSServerProtocol dtlsServerProtocol
                = (DTLSServerProtocol) dtlsProtocol;
            TlsServer tlsServer = (TlsServer) tlsPeer;

            try
            {
                dtlsTransport
                    = dtlsServerProtocol.accept(
                            tlsServer,
                            datagramTransport);
            }
            catch (IOException ioe)
            {
                logger.error(
                        "Failed to accept a connection from a DTLS client!",
                        ioe);
            }
        }
        else
            throw new IllegalStateException("dtlsProtocol");

        synchronized (this)
        {
            if (Thread.currentThread().equals(this.connectThread)
                    && datagramTransport.equals(this.datagramTransport))
            {
                this.dtlsTransport = dtlsTransport;
                notifyAll();
            }
        }
    }

    /**
     * Sets the <tt>RTPConnector</tt> which is to use or uses this
     * <tt>PacketTransformer</tt>.
     *
     * @param connector the <tt>RTPConnector</tt> which is to use or uses this
     * <tt>PacketTransformer</tt>
     */
    void setConnector(AbstractRTPConnector connector)
    {
        if (this.connector != connector)
        {
            this.connector = connector;

            DatagramTransportImpl datagramTransport = this.datagramTransport;

            if (datagramTransport != null)
                datagramTransport.setConnector(connector);
        }
    }

    /**
     * Sets the DTLS protocol according to which this
     * <tt>DtlsPacketTransformer</tt> is to act either as a DTLS server or a
     * DTLS client.
     *
     * @param dtlsProtocol {@link DtlsControl#DTLS_CLIENT_PROTOCOL} to have this
     * <tt>DtlsPacketTransformer</tt> act as a DTLS client or
     * {@link DtlsControl#DTLS_SERVER_PROTOCOL} to have this
     * <tt>DtlsPacketTransformer</tt> act as a DTLS server
     */
    void setDtlsProtocol(int dtlsProtocol)
    {
        if (this.dtlsProtocol != dtlsProtocol)
        {
            this.dtlsProtocol = dtlsProtocol;
        }
    }

    /**
     * Sets the <tt>MediaType</tt> of the stream which this instance is to work
     * for/be associated with.
     *
     * @param mediaType the <tt>MediaType</tt> of the stream which this instance
     * is to work for/be associated with
     */
    synchronized void setMediaType(MediaType mediaType)
    {
        if (this.mediaType != mediaType)
        {
            if (this.mediaType != null)
                stop();

            this.mediaType = mediaType;

            if (this.mediaType != null)
                start();
        }
    }

    /**
     * Starts this <tt>PacketTransformer</tt>.
     */
    private synchronized void start()
    {
        if (this.datagramTransport != null)
        {
            if ((this.connectThread == null) && (dtlsTransport == null))
            {
                logger.warn(
                        getClass().getName()
                            + " has been started but has failed to establish"
                            + " the DTLS connection!");
            }
            return;
        }

        int dtlsProtocol = this.dtlsProtocol;

        if ((dtlsProtocol != DtlsControl.DTLS_CLIENT_PROTOCOL)
                && (dtlsProtocol != DtlsControl.DTLS_SERVER_PROTOCOL))
            throw new IllegalStateException("dtlsProtocol");

        AbstractRTPConnector connector = this.connector;

        if (connector == null)
            throw new NullPointerException("connector");

        SecureRandom secureRandom = new SecureRandom();
        final DTLSProtocol dtlsProtocolObj;
        final TlsPeer tlsPeer;

        if (dtlsProtocol == DtlsControl.DTLS_CLIENT_PROTOCOL)
        {
            dtlsProtocolObj = new DTLSClientProtocol(secureRandom);
            tlsPeer = new TlsClientImpl(this);
        }
        else
        {
            dtlsProtocolObj = new DTLSServerProtocol(secureRandom);
            tlsPeer = new TlsServerImpl(this);
        }

        final DatagramTransportImpl datagramTransport
            = new DatagramTransportImpl(componentID);

        datagramTransport.setConnector(connector);

        Thread connectThread
            = new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        runInConnectThread(
                                dtlsProtocolObj,
                                tlsPeer,
                                datagramTransport);
                    }
                    finally
                    {
                        if (Thread.currentThread().equals(
                                DtlsPacketTransformer.this.connectThread))
                        {
                            DtlsPacketTransformer.this.connectThread = null;
                        }
                    }
                }
            };

        connectThread.setDaemon(true);
        connectThread.setName(
                DtlsPacketTransformer.class.getName() + ".connectThread");

        this.connectThread = connectThread;
        this.datagramTransport = datagramTransport;

        boolean started = false;

        try
        {
            connectThread.start();
            started = true;
        }
        finally
        {
            if (!started)
            {
                if (connectThread.equals(this.connectThread))
                    this.connectThread = null;
                if (datagramTransport.equals(this.datagramTransport))
                    this.datagramTransport = null;
            }
        }

        notifyAll();
    }

    /**
     * Stops this <tt>PacketTransformer</tt>.
     */
    private synchronized void stop()
    {
        if (connectThread != null)
            connectThread = null;
        if (dtlsTransport != null)
        {
            try
            {
                dtlsTransport.close();
            }
            catch (IOException ioe)
            {
                logger.error(
                        "Failed to (properly) close "
                            + dtlsTransport.getClass(),
                        ioe);
            }
            dtlsTransport = null;
        }
        closeDatagramTransport();

        notifyAll();
    }

    /**
     * {@inheritDoc}
     */
    public RawPacket transform(RawPacket pkt)
    {
        byte[] buf = pkt.getBuffer();
        int off = pkt.getOffset();
        int len = pkt.getLength();

        /*
         * If the specified pkt represents a DTLS record, then it should pass
         * through this PacketTransformer (e.g. it has been sent through
         * DatagramPacketImpl).
         */
        if (!isDtlsRecord(buf, off, len))
        {
            /*
             * The specified pkt will pass through this PacketTransformer only
             * if it gets transformed into a DTLS record.
             */
            pkt = null;

            DTLSTransport dtlsTransport = this.dtlsTransport;

            if (dtlsTransport != null)
            {
                try
                {
                    dtlsTransport.send(buf, off, len);
                }
                catch (IOException ioe)
                {
                    logger.error(
                            "Failed to send application data over DTLS"
                                + " transport!",
                            ioe);
                }
            }
        }
        return pkt;
    }
}
