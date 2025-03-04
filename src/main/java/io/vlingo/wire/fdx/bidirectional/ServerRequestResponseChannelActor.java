// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.wire.fdx.bidirectional;

import io.vlingo.actors.Actor;
import io.vlingo.actors.Definition;
import io.vlingo.actors.Stoppable;
import io.vlingo.common.Cancellable;
import io.vlingo.common.Scheduled;
import io.vlingo.wire.channel.RequestChannelConsumerProvider;
import io.vlingo.wire.channel.SocketChannelSelectionProcessor;
import io.vlingo.wire.channel.SocketChannelSelectionProcessorActor;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.Iterator;

public class ServerRequestResponseChannelActor extends Actor implements ServerRequestResponseChannel, Scheduled<Object> {
  private final Cancellable cancellable;
  private final ServerSocketChannel channel;
  private final String name;
  private final SocketChannelSelectionProcessor[] processors;
  private int processorPoolIndex;
  private final Selector selector;

  @SuppressWarnings("unchecked")
  public ServerRequestResponseChannelActor(
          final RequestChannelConsumerProvider provider,
          final int port,
          final String name,
          final int processorPoolSize,
          final int maxBufferPoolSize,
          final int maxMessageSize,
          final long probeInterval) {

    this.name = name;
    this.processors = startProcessors(provider, name, processorPoolSize, maxBufferPoolSize, maxMessageSize, probeInterval);

    try {
      logger().info(getClass().getSimpleName() + ": OPENING PORT: " + port);
      this.channel = ServerSocketChannel.open();
      this.selector = Selector.open();
      channel.socket().bind(new InetSocketAddress(port));
      channel.configureBlocking(false);
      channel.register(selector, SelectionKey.OP_ACCEPT);
    } catch (Exception e) {
      final String message = "Failure opening socket because: " + e.getMessage();
      logger().error(message, e);
      throw new IllegalArgumentException(message);
    }

    this.cancellable = stage().scheduler().schedule(selfAs(Scheduled.class), null, 100, probeInterval);
  }


  //=========================================
  // ServerRequestResponseChannel
  //=========================================

  @Override
  public void close() {
    if (isStopped()) return;

    selfAs(Stoppable.class).stop();
  }

  //=========================================
  // Scheduled
  //=========================================

  @Override
  public void intervalSignal(final Scheduled<Object> scheduled, final Object data) {
    probeChannel();
  }


  //=========================================
  // Stoppable
  //=========================================

  @Override
  public void stop() {
    cancellable.cancel();

    for (final SocketChannelSelectionProcessor processor : processors) {
      processor.close();
    }

    try {
      selector.close();
    } catch (Exception e) {
      logger().error("Failed to close selctor for: '" + name + "'", e);
    }

    try {
      channel.close();
    } catch (Exception e) {
      logger().error("Failed to close channel for: '" + name + "'", e);
    }

    super.stop();
  }


  //=========================================
  // internal implementation
  //=========================================

  private void probeChannel() {
    if (isStopped()) return;

    try {
      if (selector.selectNow() > 0) {
        final Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

        while (iterator.hasNext()) {
          final SelectionKey key = iterator.next();
          iterator.remove();

          if (key.isValid()) {
            if (key.isAcceptable()) {
              accept(key);
            }
          }
        }
      }
    } catch (Exception e) {
      logger().error("Failed to accept client channel for '" + name + "' because: " + e.getMessage(), e);
    }
  }

  private void accept(final SelectionKey key) {
    pooledProcessor().process(key);
  }

  private SocketChannelSelectionProcessor pooledProcessor() {
    if (processorPoolIndex >= processors.length) {
      processorPoolIndex = 0;
    }
    return processors[processorPoolIndex++];
  }

  private SocketChannelSelectionProcessor[] startProcessors(
          final RequestChannelConsumerProvider provider,
          final String name,
          final int processorPoolSize,
          final int maxBufferPoolSize,
          final int maxMessageSize,
          final long probeInterval) {

    final SocketChannelSelectionProcessor[] processors = new SocketChannelSelectionProcessor[processorPoolSize];

    for (int idx = 0; idx < processors.length; ++idx) {
      processors[idx] = childActorFor(
              SocketChannelSelectionProcessor.class,
              Definition.has(SocketChannelSelectionProcessorActor.class,
                      Definition.parameters(provider, name + "-processor-" + idx, maxBufferPoolSize, maxMessageSize, probeInterval)));
    }

    return processors;
  }
}
