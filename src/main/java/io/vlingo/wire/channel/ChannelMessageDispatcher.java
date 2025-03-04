// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.wire.channel;

import io.vlingo.actors.Logger;
import io.vlingo.wire.message.RawMessage;
import io.vlingo.wire.message.RawMessageBuilder;

public interface ChannelMessageDispatcher {
  ChannelReaderConsumer consumer();
  Logger logger();
  String name();
  
  default void dispatchMessagesFor(final RawMessageBuilder builder) {
    if (!builder.hasContent()) {
      return;
    }

    builder.prepareContent().sync();

    while (builder.isCurrentMessageComplete()) {
      try {
        final RawMessage message = builder.currentRawMessage();
        consumer().consume(message);
      } catch (Exception e) {
        // TODO: deal with this
        logger().error("Cannot dispatch message for: '" + name() + "'", e);
      }

      builder.prepareForNextMessage();

      if (builder.hasContent()) {
        builder.sync();
      }
    }
  }
}
