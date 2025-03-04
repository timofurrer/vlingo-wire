// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.wire.fdx.bidirectional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.vlingo.actors.Logger;
import io.vlingo.actors.World;
import io.vlingo.actors.testkit.TestUntil;
import io.vlingo.wire.message.ByteBufferAllocator;
import io.vlingo.wire.node.Address;
import io.vlingo.wire.node.AddressType;
import io.vlingo.wire.node.Host;

public class SocketRequestResponseChannelTest {
  private static final int POOL_SIZE = 100;
  private static AtomicInteger TEST_PORT = new AtomicInteger(37370);

  private ByteBuffer buffer;
  private ClientRequestResponseChannel client;
  private TestResponseChannelConsumer clientConsumer;
  private TestRequestChannelConsumerProvider provider;
  private ServerRequestResponseChannel server;
  private TestRequestChannelConsumer serverConsumer;
  private World world;

  @Test
  public void testBasicRequestResponse() throws Exception {
    final String request = "Hello, Request-Response";

    serverConsumer.currentExpectedRequestLength = request.length();
    clientConsumer.currentExpectedResponseLength = serverConsumer.currentExpectedRequestLength;
    request(request);

    serverConsumer.untilConsume = TestUntil.happenings(1);
    clientConsumer.untilConsume = TestUntil.happenings(1);

    while (serverConsumer.untilConsume.remaining() > 0) {
      ;
    }
    serverConsumer.untilConsume.completes();

    while (clientConsumer.untilConsume.remaining() > 0) {
      client.probeChannel();
    }
    clientConsumer.untilConsume.completes();

    assertFalse(serverConsumer.requests.isEmpty());
    assertEquals(1, serverConsumer.consumeCount);
    assertEquals(serverConsumer.consumeCount, serverConsumer.requests.size());

    assertFalse(clientConsumer.responses.isEmpty());
    assertEquals(1, clientConsumer.consumeCount);
    assertEquals(clientConsumer.consumeCount, clientConsumer.responses.size());

    assertEquals(clientConsumer.responses.get(0), serverConsumer.requests.get(0));
  }

  @Test
  public void testGappyRequestResponse() throws Exception {
    final String requestPart1 = "Request Part-1";
    final String requestPart2 = ":Request Part-2";
    final String requestPart3 = ":Request Part-3";

    serverConsumer.currentExpectedRequestLength = requestPart1.length() + requestPart2.length() + requestPart3.length();
    clientConsumer.currentExpectedResponseLength = serverConsumer.currentExpectedRequestLength;

    // simulate network latency for parts of single request

    request(requestPart1);
    Thread.sleep(100);
    request(requestPart2);
    Thread.sleep(200);
    request(requestPart3);
    serverConsumer.untilConsume = TestUntil.happenings(1);
    while (serverConsumer.untilConsume.remaining() > 0) {
      ;
    }
    serverConsumer.untilConsume.completes();

    clientConsumer.untilConsume = TestUntil.happenings(1);
    while (clientConsumer.untilConsume.remaining() > 0) {
      Thread.sleep(10);
      client.probeChannel();
    }
    clientConsumer.untilConsume.completes();

    assertFalse(serverConsumer.requests.isEmpty());
    assertEquals(1, serverConsumer.consumeCount);
    assertEquals(serverConsumer.consumeCount, serverConsumer.requests.size());

    assertFalse(clientConsumer.responses.isEmpty());
    assertEquals(1, clientConsumer.consumeCount);
    assertEquals(clientConsumer.consumeCount, clientConsumer.responses.size());

    assertEquals(clientConsumer.responses.get(0), serverConsumer.requests.get(0));
  }

  @Test
  public void test10RequestResponse() throws Exception {
    final String request = "Hello, Request-Response";

    serverConsumer.currentExpectedRequestLength = request.length() + 1; // digits 0 - 9
    clientConsumer.currentExpectedResponseLength = serverConsumer.currentExpectedRequestLength;

    serverConsumer.untilConsume = TestUntil.happenings(10);
    clientConsumer.untilConsume = TestUntil.happenings(10);

    for (int idx = 0; idx < 10; ++idx) {
      request(request + idx);
    }

    while (clientConsumer.untilConsume.remaining() > 0) {
      client.probeChannel();
    }

    serverConsumer.untilConsume.completes();
    clientConsumer.untilConsume.completes();

    assertFalse(serverConsumer.requests.isEmpty());
    assertEquals(10, serverConsumer.consumeCount);
    assertEquals(serverConsumer.consumeCount, serverConsumer.requests.size());

    assertFalse(clientConsumer.responses.isEmpty());
    assertEquals(10, clientConsumer.consumeCount);
    assertEquals(clientConsumer.consumeCount, clientConsumer.responses.size());

    for (int idx = 0; idx < 10; ++idx) {
      assertEquals(clientConsumer.responses.get(idx), serverConsumer.requests.get(idx));
    }
  }

  @Test
  public void testThatRequestResponsePoolLimitsNotExceeded() throws Exception {
    final int TOTAL = POOL_SIZE * 2;

    final String request = "Hello, Request-Response";

    serverConsumer.currentExpectedRequestLength = request.length() + 3; // digits 000 - 999
    clientConsumer.currentExpectedResponseLength = serverConsumer.currentExpectedRequestLength;

    serverConsumer.untilConsume = TestUntil.happenings(TOTAL);
    clientConsumer.untilConsume = TestUntil.happenings(TOTAL);

    for (int idx = 0; idx < TOTAL; ++idx) {
      request(request + String.format("%03d", idx));
    }

    while (clientConsumer.untilConsume.remaining() > 0) {
      client.probeChannel();
    }
    serverConsumer.untilConsume.completes();
    clientConsumer.untilConsume.completes();

    assertFalse(serverConsumer.requests.isEmpty());
    assertEquals(TOTAL, serverConsumer.consumeCount);
    assertEquals(serverConsumer.consumeCount, serverConsumer.requests.size());

    assertFalse(clientConsumer.responses.isEmpty());
    assertEquals(TOTAL, clientConsumer.consumeCount);
    assertEquals(clientConsumer.consumeCount, clientConsumer.responses.size());

    for (int idx = 0; idx < TOTAL; ++idx) {
      assertEquals(clientConsumer.responses.get(idx), serverConsumer.requests.get(idx));
    }
  }

  @Before
  public void setUp() throws Exception {
    world = World.startWithDefaults("test-request-response-channel");

    buffer = ByteBufferAllocator.allocate(1024);
    final Logger logger = Logger.basicLogger();
    provider = new TestRequestChannelConsumerProvider();
    serverConsumer = (TestRequestChannelConsumer) provider.consumer;

    final int testPort = TEST_PORT.incrementAndGet();

    server = ServerRequestResponseChannel.start(
                    world.stage(),
                    provider,
                    testPort,
                    "test-server",
                    1,
                    POOL_SIZE,
                    10240,
                    10L);

    clientConsumer = new TestResponseChannelConsumer();

    client = new BasicClientRequestResponseChannel(Address.from(Host.of("localhost"), testPort,  AddressType.NONE), clientConsumer, POOL_SIZE, 10240, logger);
  }

  @After
  public void tearDown() {
    server.close();
    client.close();

    try { Thread.sleep(1000); } catch (Exception e) {  }

    world.terminate();
  }

  private void request(final String request) {
    buffer.clear();
    buffer.put(request.getBytes());
    buffer.flip();
    client.requestWith(buffer);
  }
}
