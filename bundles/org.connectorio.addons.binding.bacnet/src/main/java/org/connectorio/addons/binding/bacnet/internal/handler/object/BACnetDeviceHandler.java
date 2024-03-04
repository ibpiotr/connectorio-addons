/*
 * Copyright (C) 2019-2021 ConnectorIO sp. z o.o.
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
package org.connectorio.addons.binding.bacnet.internal.handler.object;

import static com.serotonin.bacnet4j.type.enumerated.ErrorClass.object;

import com.serotonin.bacnet4j.obj.DeviceObject;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.Null;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.Device;
import org.code_house.bacnet4j.wrapper.api.JavaToBacNetConverter;
import org.code_house.bacnet4j.wrapper.api.Priorities;
import org.code_house.bacnet4j.wrapper.api.Priority;
import org.code_house.bacnet4j.wrapper.api.Type;
import org.connectorio.addons.binding.bacnet.internal.BACnetBindingConstants;
import org.connectorio.addons.binding.bacnet.internal.command.PrioritizedCommand;
import org.connectorio.addons.binding.bacnet.internal.command.ResetCommand;
import org.connectorio.addons.binding.bacnet.internal.config.DeviceChannelConfig;
import org.connectorio.addons.binding.bacnet.internal.config.DeviceConfig;
import org.connectorio.addons.binding.bacnet.internal.discovery.BACnetPropertyDiscoveryService;
import org.connectorio.addons.binding.bacnet.internal.handler.BACnetObjectBridgeHandler;
import org.connectorio.addons.binding.bacnet.internal.handler.channel.converter.CompositeConverter;
import org.connectorio.addons.binding.bacnet.internal.handler.network.BACnetNetworkBridgeHandler;
import org.connectorio.addons.binding.bacnet.internal.handler.source.BACnetObjectsSampler;
import org.connectorio.addons.binding.bacnet.internal.handler.source.BACnetPropertySampler;
import org.connectorio.addons.binding.bacnet.internal.handler.source.BACnetSamplerComposer;
import org.connectorio.addons.binding.bacnet.internal.handler.source.ChannelCallback;
import org.connectorio.addons.binding.bacnet.internal.handler.source.SamplerCallback;
import org.connectorio.addons.binding.source.SourceFactory;
import org.connectorio.addons.binding.source.sampling.SamplingSource;
import org.connectorio.addons.communication.watchdog.Watchdog;
import org.connectorio.addons.communication.watchdog.WatchdogManager;
import org.connectorio.addons.link.LinkListener;
import org.connectorio.addons.link.LinkManager;
import org.connectorio.addons.temporal.item.TemporalItemFactory;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.CoreItemFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BACnetDeviceHandler<C extends DeviceConfig> extends BACnetObjectBridgeHandler<DeviceObject, BACnetNetworkBridgeHandler<?>, C>
    implements BACnetDeviceBridgeHandler<BACnetNetworkBridgeHandler<?>, C>, LinkListener {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final LinkManager linkManager;
  private final SourceFactory sourceFactory;
  private WatchdogManager watchdogManager;

  private Device device;
  private CompletableFuture<BacNetClient> clientFuture = new CompletableFuture<>();
  private boolean discoverObjects;
  private Watchdog watchdog;
  private SamplingSource<BACnetPropertySampler> source;

  /**
   * Creates a new instance of this class for the {@link Thing}.
   *
   * @param bridge the thing that should be handled, not null
   */
  public BACnetDeviceHandler(Bridge bridge, LinkManager linkManager, SourceFactory sourceFactory, WatchdogManager watchdogManager) {
    super(bridge);
    this.linkManager = linkManager;
    this.sourceFactory = sourceFactory;
    this.watchdogManager = watchdogManager;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Optional<BACnetNetworkBridgeHandler<?>> getBridgeHandler() {
    return Optional.ofNullable(getBridge())
      .map(Bridge::getHandler)
      .filter(BACnetNetworkBridgeHandler.class::isInstance)
      .map(BACnetNetworkBridgeHandler.class::cast);
  }

  @Override
  public void initialize() {
    device = getBridgeConfig()
      .map(cfg -> {
        Integer networkNumber =  Optional.ofNullable(cfg.network)
          .orElseGet(() -> getBridgeHandler().flatMap(BACnetNetworkBridgeHandler::getNetworkNumber).orElse(0));
        return createDevice(cfg, networkNumber);
      }).orElse(null);

    if (device != null) {
      getBridgeHandler().get().getClient().thenAccept(this::initializeChannels).whenComplete((client, error) -> {
        if (error != null) {
          logger.warn("Initialization of BACnet device handler failed, could not establish client connection", error);
        }
      });
    } else {
      updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Missing device configuration");
    }
  }

  protected void initializeChannels(BacNetClient client) {
    //WatchdogBuilder watchdogBuilder = watchdogManager.builder(getThing());

    DeviceConfig deviceConfig = getConfigAs(DeviceConfig.class);
    discoverObjects = deviceConfig.discoverObjects;
    if (deviceConfig.discoverChannels && thing.getChannels().isEmpty()) {
      updateChannels(client);
    }

    this.source = sourceFactory.sampling(scheduler, new BACnetSamplerComposer(client));
    configureSource(client);

    //this.watchdog = watchdogBuilder.build(getCallback(), new ThingStatusWatchdogListener(getThing(), getCallback()));
    source.start();
    linkManager.registerListener(thing, this);
    updateStatus(ThingStatus.ONLINE);
    clientFuture.complete(client);
  }

  @Override
  public void dispose() {
    linkManager.deregisterListener(thing, this);

    if (watchdog != null) {
      watchdog.close();
    }

    if (source != null) {
      source.stop();
    }
    super.dispose();
  }

  private void updateChannels(BacNetClient client) {
    BridgeBuilder builder = editThing();
    builder.withChannels(new ArrayList<>()); // reset channel list
    for (BacNetObject object : client.getDeviceObjects(device)) {
      createChannel(builder, object, PropertyIdentifier.presentValue);
      if (Type.SCHEDULE.equals(object.getType())) {
        createChannel(builder, object, PropertyIdentifier.weeklySchedule);
        createChannel(builder, object, PropertyIdentifier.exceptionSchedule);
        createChannel(builder, object, PropertyIdentifier.effectivePeriod);
        createChannel(builder, object, PropertyIdentifier.scheduleDefault);
      }
    }
    updateThing(builder.build());
  }

  private void createChannel(BridgeBuilder builder, BacNetObject object, PropertyIdentifier propertyIdentifier) {
    String channelId = object.getType().name().toLowerCase() + "-" + object.getId() + "-" + propertyIdentifier.toString();
    ChannelUID uid = new ChannelUID(thing.getUID(), channelId);
    String itemType = mapItemType(object, propertyIdentifier);
    ChannelTypeUID channelType = mapChannelType(object, propertyIdentifier);
    if (itemType == null || channelType == null) {
      return;
    }

    Channel channel = ChannelBuilder.create(uid)
      .withType(channelType)
      .withConfiguration(channelConfiguration(object, propertyIdentifier))
      .withLabel(object.getName())
      .withDescription(object.getDescription())
      .withAcceptedItemType(itemType)
      .build();
    builder.withChannel(channel);
  }

  private String mapItemType(BacNetObject object, PropertyIdentifier propertyIdentifier) {
    switch (object.getType()) {
      case ANALOG_INPUT:
      case ANALOG_OUTPUT:
      case ANALOG_VALUE:
        return CoreItemFactory.NUMBER;
      case BINARY_INPUT:
        return CoreItemFactory.CONTACT;
      case BINARY_OUTPUT:
      case BINARY_VALUE:
        return CoreItemFactory.SWITCH;
      case MULTISTATE_INPUT:
      case MULTISTATE_OUTPUT:
      case MULTISTATE_VALUE:
        return CoreItemFactory.NUMBER;
      case CHARACTER_STRING:
      case OCTET_STRING:
        return CoreItemFactory.STRING;
      case LARGE_ANALOG:
        return CoreItemFactory.NUMBER;
      case DATE_TIME:
      case TIME:
      case DATE_VALUE:
        return CoreItemFactory.DATETIME;
      case INTEGER:
        return CoreItemFactory.NUMBER;
      case POSITIVE_INTEGER:
        return CoreItemFactory.NUMBER;
      case DATE_TIME_PATTERN:
      case DATE_PATTERN:
      case TIME_PATTERN:
        return CoreItemFactory.STRING;
      case SCHEDULE:
        if (PropertyIdentifier.presentValue.equals(propertyIdentifier) || PropertyIdentifier.scheduleDefault.equals(propertyIdentifier)) {
          return CoreItemFactory.NUMBER;
        }
        if (PropertyIdentifier.weeklySchedule.equals(propertyIdentifier)) {
          return TemporalItemFactory.WEEK_SCHEDULE;
        }
        if (PropertyIdentifier.exceptionSchedule.equals(propertyIdentifier)) {
          return TemporalItemFactory.CALENDAR;
        }
    }
    return null;
  }

  private Configuration channelConfiguration(BacNetObject object) {
    return channelConfiguration(object, PropertyIdentifier.presentValue);
  }

  private Configuration channelConfiguration(BacNetObject object, PropertyIdentifier propertyIdentifier) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("instance", object.getId());
    properties.put("type", object.getType().name());
    properties.put("readOnly", false);
    properties.put("propertyIdentifier", propertyIdentifier.toString());
    properties.put("refreshInterval", 0); // stick to device refresh interval
    return new Configuration(properties);
  }

  private ChannelTypeUID mapChannelType(BacNetObject object, PropertyIdentifier propertyIdentifier) {
    String type = "";
    switch (object.getType()) {
      case ANALOG_INPUT:
      case ANALOG_OUTPUT:
      case ANALOG_VALUE:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableNumber");
      case BINARY_INPUT:
      case BINARY_OUTPUT:
      case BINARY_VALUE:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableBinary");
      case MULTISTATE_INPUT:
      case MULTISTATE_OUTPUT:
      case MULTISTATE_VALUE:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableNumber");
      case CHARACTER_STRING:
      case OCTET_STRING:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableText");
      case LARGE_ANALOG:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableNumber");
      case DATE_TIME:
      case TIME:
      case DATE_VALUE:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableDateTime");
      case INTEGER:
      case POSITIVE_INTEGER:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableNumber");
      case DATE_TIME_PATTERN:
      case DATE_PATTERN:
      case TIME_PATTERN:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableText");
      case CALENDAR:
        return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableCalendar");
      case SCHEDULE:
        if (PropertyIdentifier.presentValue.equals(propertyIdentifier)) {
          return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceReadableNumber");
        }
        if (PropertyIdentifier.weeklySchedule.equals(propertyIdentifier)) {
          return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableWeekSchedule");
        }
        if (PropertyIdentifier.exceptionSchedule.equals(propertyIdentifier)) {
          return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableCalendar");
        }
//        if (PropertyIdentifier.effectivePeriod.equals(propertyIdentifier)) {
//          return new ChannelTypeUID(BACnetBindingConstants.BINDING_ID, "deviceWriteableWeekSchedule");
//        }
    }
    return null;
  }

  protected abstract Device createDevice(C config, Integer networkNumber);

  @Override
  public void handleCommand(ChannelUID channelUID, Command command) {
    logger.debug("Handle command {} for channel {} and property {}", command, channelUID, object);

    if (!getBridgeHandler().isPresent()) {
      logger.error("Handler is not attached to an bridge or bridge initialization failed!");
      return;
    }

    final CompletableFuture<BacNetClient> clientFuture = getBridgeHandler().get().getClient();
    Channel channel = getThing().getChannel(channelUID);
    DeviceChannelConfig config = channel.getConfiguration().as(DeviceChannelConfig.class);
    BacNetObject object = new BacNetObject(device, config.instance, config.type);
    String attribute = config.propertyIdentifier;
    //Integer writePriority = config.writePriority;

    if (command == RefreshType.REFRESH) {
      if (source != null) {
        clientFuture.thenAccept(client -> {
          source.request(new BACnetObjectsSampler(client, object, attribute, new SamplerCallback(
            CompositeConverter.INSTANCE, new ChannelCallback(getCallback(), channel))));
        });
      }
    } else if (command instanceof ResetCommand) {
      ResetCommand reset = (ResetCommand) command;
      JavaToBacNetConverter<Object> converter = (value) -> {
        logger.trace("Issuing NULL command to BACnet value to channel {}/property {}", channelUID, object);
        return Null.instance;
      };
      if (reset.getPriority() == null) {
        if (config.writePriority == null) {
          logger.debug("Submitting NULL value for channel {} to {}", channelUID, object);
          clientFuture.join().setObjectPropertyValue(object, attribute, null, converter);
        } else {
          logger.debug("Submitting NULL value for channel {} to {} with priority {}", channelUID,
            object, config.writePriority);
          clientFuture.join().setObjectPropertyValue(object, attribute, null, converter, config.writePriority);
        }
      } else {
        logger.debug("Submitting NULL value for channel {} to {} with custom reset priority {}", channelUID,
          object, config.writePriority);
        clientFuture.join().setObjectPropertyValue(object, attribute, null, converter, reset.getPriority());
      }
    } else {
      Priority priority = config.writePriority == null ? null : Priorities.get(config.writePriority)
        .orElseThrow(() -> new IllegalArgumentException("Unknown priority " + config.writePriority));
      if (command instanceof PrioritizedCommand) {
        PrioritizedCommand prioritizedCmd = (PrioritizedCommand) command;
        priority = prioritizedCmd.getPriority();
        command = prioritizedCmd.getCommand();
      }
      JavaToBacNetConverter<Command> converter = (value) -> {
        Encodable encodable = BACnetValueConverter.openHabTypeToBacNetValue(object.getType().getBacNetType(), value);
        logger.trace("Command have been converter to BACnet value {} of type {}", encodable, encodable.getClass());
        return encodable;
      };
      if (priority == null) {
        logger.debug("Submitting write {} from channel {} to {}", channelUID, command, object);
        clientFuture.join().setObjectPropertyValue(object, attribute, command, converter);
      } else {
        logger.debug("Submitting write {} from channel {} to {} with priority {}", channelUID, command, object, priority);
        clientFuture.join().setObjectPropertyValue(object, attribute, command, converter, priority);
      }
      logger.debug("Command {} for property {} executed successfully", command, object);
    }
  }

  @Override
  public Collection<Class<? extends ThingHandlerService>> getServices() {
    if (discoverObjects) {
      return Collections.singleton(BACnetPropertyDiscoveryService.class);
    }
    return Collections.emptySet();
  }

  @Override
  public CompletableFuture<BacNetClient> getClient() {
    return clientFuture;
  }

  @Override
  public Device getDevice() {
    return device;
  }

  @Override
  public void linked(ChannelUID channelUID) {
    reconfigureSource();
  }

  @Override
  public void unlinked(ChannelUID channelUID) {
    reconfigureSource();
  }


  private void reconfigureSource() {
    if (this.source != null) {
      // if source is non-null then it was started before
      getClient().thenAccept(client -> {
        this.source.stop();
        configureSource(client);
        this.source.start();
      });
    }
  }

  private void configureSource(BacNetClient client) {
    for (Channel channel : thing.getChannels()) {
      if (!linkManager.isLinked(channel.getUID())) {
        // do not poll unlinked channels
        continue;
      }

      DeviceChannelConfig deviceChannelConfig = channel.getConfiguration().as(DeviceChannelConfig.class);
      Long refreshInterval = Optional.ofNullable(deviceChannelConfig.refreshInterval)
        .filter(value -> value != 0)
        .orElse(getRefreshInterval());
      BacNetObject object = new BacNetObject(device, deviceChannelConfig.instance, deviceChannelConfig.type);
      Consumer<Encodable> consumer = new SamplerCallback(CompositeConverter.INSTANCE, new ChannelCallback(getCallback(), channel));
      source.add(refreshInterval, channel.getUID().getAsString(), new BACnetObjectsSampler(client, object, deviceChannelConfig.propertyIdentifier, consumer));
    }
  }


}
