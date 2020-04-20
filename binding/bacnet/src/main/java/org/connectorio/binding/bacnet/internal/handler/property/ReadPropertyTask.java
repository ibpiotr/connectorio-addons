/*
 * Copyright (C) 2019-2020 ConnectorIO sp. z o.o.
 *
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 *     https://www.gnu.org/licenses/gpl-3.0.txt
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Foobar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.connectorio.binding.bacnet.internal.handler.property;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.BinaryPV;
import com.serotonin.bacnet4j.type.primitive.Boolean;
import com.serotonin.bacnet4j.type.primitive.Null;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.SignedInteger;
import com.serotonin.bacnet4j.type.primitive.Time;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetToJavaConverter;
import org.code_house.bacnet4j.wrapper.api.Property;
import org.eclipse.smarthome.core.library.types.DateTimeType;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerCallback;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReadPropertyTask implements Runnable, BacNetToJavaConverter<State> {

  private final Logger logger = LoggerFactory.getLogger(ReadPropertyTask.class);
  private final Supplier<CompletableFuture<BacNetClient>> client;
  private final ThingHandlerCallback callback;
  private final Property property;
  private final ChannelUID channelUID;

  public ReadPropertyTask(Supplier<CompletableFuture<BacNetClient>> client, ThingHandlerCallback callback, Property property, ChannelUID channelUID) {
    this.client = client;
    this.callback = callback;
    this.property = property;
    this.channelUID = channelUID;
  }

  @Override
  public void run() {
    CompletableFuture<BacNetClient> clientFuture = client.get();
    if (clientFuture.isDone() && !clientFuture.isCancelled() && !clientFuture.isCompletedExceptionally()) {
      try {
        State result = clientFuture.get().getPropertyValue(property, this);
        callback.stateUpdated(channelUID, result);
      } catch (InterruptedException | ExecutionException e) {
        logger.debug("Could not complete operation", e);
      }
    }
  }

  @Override
  public State fromBacNet(Encodable encodable) {
    logger.info("Mapping value {} for channel {}", encodable, channelUID);
    if (encodable instanceof Null) {
      return UnDefType.NULL;
    } else if (encodable instanceof Real) {
      return new DecimalType(((Real) encodable).floatValue());
    } else if (encodable instanceof BinaryPV) {
      return encodable == BinaryPV.active ? OnOffType.ON : OnOffType.OFF;
    } else if (encodable instanceof UnsignedInteger) {
      return new DecimalType(((UnsignedInteger) encodable).intValue());
    } else if (encodable instanceof SignedInteger) {
      return new DecimalType(((SignedInteger) encodable).intValue());
    } else if (encodable instanceof Boolean) {
      return Boolean.TRUE == encodable ? OnOffType.ON : OnOffType.OFF;
    } else if (encodable instanceof Time) {
      Time time = (Time) encodable;
      // HH:mm:ss.SSSZ
      String millis = time.getHundredth() != 0 ? "." + time.getHundredth() : "";
      return new DateTimeType(time.getHour() + ":" + time.getMinute() + ":" + time.getSecond() + millis);
    }
    logger.info("Received value {}, {}", encodable, encodable.getClass().getName());
    return null;
  }
}
