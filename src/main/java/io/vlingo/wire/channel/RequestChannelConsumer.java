// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.wire.channel;

import io.vlingo.wire.message.ConsumerByteBuffer;

public interface RequestChannelConsumer {
  void closeWith(final RequestResponseContext<?> requestResponseContext, final Object data);
  void consume(final RequestResponseContext<?> context, final ConsumerByteBuffer buffer);
}
