package com.insidious.agent.rsocket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.core.RSocketConnector;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.metadata.*;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.DefaultPayload;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

public class RsocketTest {

    private Logger log = LoggerFactory.getLogger(RsocketTest.class);


    @Test
    public void testRsocket() {


        RSocketConnector connector = RSocketConnector.create();
        connector.metadataMimeType(WellKnownMimeType.MESSAGE_RSOCKET_COMPOSITE_METADATA.getString());
        connector.dataMimeType(WellKnownMimeType.MESSAGE_RSOCKET_COMPOSITE_METADATA.getString());
        connector.payloadDecoder(PayloadDecoder.DEFAULT);


        ByteBuf byteBuf = AuthMetadataCodec.encodeSimpleMetadata(ByteBufAllocator.DEFAULT, "user".toCharArray(), "pass".toCharArray());

        CompositeByteBuf metadata = ByteBufAllocator.DEFAULT.compositeBuffer();

        CompositeMetadataCodec.encodeAndAddMetadata(metadata,
                ByteBufAllocator.DEFAULT,
                WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION,
                byteBuf
        );

        connector.setupPayload(DefaultPayload.create(DefaultPayload.EMPTY_BUFFER, metadata.nioBuffer()));

        RSocket rSocket = connector.connect(() -> TcpClientTransport.create("localhost", 9921)).block();


        CompositeByteBuf messageMetadata = ByteBufAllocator.DEFAULT.compositeBuffer();
        RoutingMetadata routingMetadata = TaggingMetadataCodec.createRoutingMetadata(
                ByteBufAllocator.DEFAULT, Collections.singletonList("request-response")
        );


        CompositeMetadataCodec.encodeAndAddMetadata(messageMetadata,
                ByteBufAllocator.DEFAULT,
                WellKnownMimeType.MESSAGE_RSOCKET_ROUTING,
                routingMetadata.getContent()
        );

        CompositeMetadataCodec.encodeAndAddMetadata(messageMetadata,
                ByteBufAllocator.DEFAULT,
                WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION,
                byteBuf
        );

        ByteBuf out = ByteBufAllocator.DEFAULT.buffer();
        out.writeBytes("app-1".getBytes());
        out.writeInt(1);
        out.writeInt(3);
        out.writeLong(Thread.currentThread().getId());
        out.writeLong(System.nanoTime());
        out.writeLong(4);

        Payload response = rSocket.requestResponse(DefaultPayload.create(out, messageMetadata)).block();
        System.out.printf("Response: %s", response.getData().toString());


    }

    @Test
    public void test2() {
        RSocketConnector connector = RSocketConnector.create();
        connector.metadataMimeType(WellKnownMimeType.MESSAGE_RSOCKET_COMPOSITE_METADATA.getString());
        connector.dataMimeType(WellKnownMimeType.MESSAGE_RSOCKET_COMPOSITE_METADATA.getString());
        connector.payloadDecoder(PayloadDecoder.DEFAULT);


        ByteBuf byteBuf = AuthMetadataCodec.encodeSimpleMetadata(ByteBufAllocator.DEFAULT, "user".toCharArray(), "pass".toCharArray());

        CompositeByteBuf metadata = ByteBufAllocator.DEFAULT.compositeBuffer();

        CompositeMetadataCodec.encodeAndAddMetadata(metadata,
                ByteBufAllocator.DEFAULT,
                WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION,
                byteBuf
        );

        connector.setupPayload(DefaultPayload.create(DefaultPayload.EMPTY_BUFFER, metadata.nioBuffer()));

        RSocket rSocket = connector.connect(() -> TcpClientTransport.create("localhost", 8888)).block();


        CompositeByteBuf messageMetadata = ByteBufAllocator.DEFAULT.compositeBuffer();
        RoutingMetadata routingMetadata = TaggingMetadataCodec.createRoutingMetadata(
                ByteBufAllocator.DEFAULT, Collections.singletonList("request-response")
        );


        CompositeMetadataCodec.encodeAndAddMetadata(messageMetadata,
                ByteBufAllocator.DEFAULT,
                WellKnownMimeType.MESSAGE_RSOCKET_ROUTING,
                routingMetadata.getContent()
        );

        ByteBuf out = ByteBufAllocator.DEFAULT.buffer();
        out.writeBytes("app-1".getBytes());
        out.writeInt(1);
        out.writeInt(3);
        out.writeLong(Thread.currentThread().getId());
        out.writeLong(System.nanoTime());
        out.writeLong(4);

        String response = rSocket.requestResponse(DefaultPayload.create(out, messageMetadata)).map(Payload::getDataUtf8).block();
        log.info("Response: {}", response);
        rSocket.dispose();


    }

}
