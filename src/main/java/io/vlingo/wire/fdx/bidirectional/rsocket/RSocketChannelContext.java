// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.
package io.vlingo.wire.fdx.bidirectional.rsocket;

import io.rsocket.Payload;
import io.rsocket.util.ByteBufPayload;
import io.vlingo.actors.Logger;
import io.vlingo.wire.channel.RequestChannelConsumer;
import io.vlingo.wire.channel.RequestChannelConsumerProvider;
import io.vlingo.wire.channel.RequestResponseContext;
import io.vlingo.wire.channel.ResponseSenderChannel;
import io.vlingo.wire.message.ByteBufferPool;
import io.vlingo.wire.message.ConsumerByteBuffer;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.UnicastProcessor;

class RSocketChannelContext implements RequestResponseContext<FluxSink<ConsumerByteBuffer>> {
  private final RequestChannelConsumer consumer;
  private final Logger logger;
  private final ByteBufferPool readBufferPool;
  private final UnicastProcessor<Payload> processor;
  private Object closingData;
  private Object consumerData;

  RSocketChannelContext(final RequestChannelConsumerProvider consumerProvider, final int maxBufferPoolSize, final int maxMessageSize, final Logger logger) {
    this.consumer = consumerProvider.requestChannelConsumer();
    this.logger = logger;
    this.readBufferPool = new ByteBufferPool(maxBufferPoolSize, maxMessageSize);

    processor = UnicastProcessor.create();
  }

  UnicastProcessor<Payload> processor() {
    return processor;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T consumerData() {
    return ((T) consumerData);
  }

  @Override
  public <T> T consumerData(final T workingData) {
    this.consumerData = workingData;
    return workingData;
  }

  @Override
  public boolean hasConsumerData() {
    return this.consumerData != null;
  }

  @Override
  public String id() {
    return null;
  }

  @Override
  public ResponseSenderChannel sender() {
    return null;
  }

  @Override
  public void whenClosing(final Object data) {
    this.closingData = data;
  }

  public void close() {
    if (closingData != null) {
      try {
        this.consumer.closeWith(this, closingData);
      } catch (Exception e) {
        logger.error("Failed to close client channel because: " + e.getMessage(), e);
      }
    }
  }

  public void consume(Payload request) {
    final ByteBufferPool.PooledByteBuffer pooledBuffer = readBufferPool.accessFor("client-request");
    try {
      pooledBuffer.put(request.getData());
      this.consumer.consume(this, pooledBuffer.flip());
    } finally {
      if (pooledBuffer.isInUse()) {
        pooledBuffer.release();
      }
    }
  }

  @Override
  public void abandon() {
    close();
    processor.dispose();
  }

  @Override
  public void respondWith(final ConsumerByteBuffer buffer) {
    processor.onNext(ByteBufPayload.create(buffer.asByteBuffer()));
  }
}
