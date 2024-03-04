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
package org.connectorio.addons.binding.bacnet.internal.handler.object.task;

import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.openhab.core.thing.ChannelUID;

@Deprecated
public class Readout {

  public final ChannelUID channel;
  public final BacNetObject object;
  public final PropertyIdentifier propertyIdentifier;

  public Readout(ChannelUID channel, BacNetObject object, PropertyIdentifier propertyIdentifier) {
    this.channel = channel;
    this.object = object;
    this.propertyIdentifier = propertyIdentifier;
  }
}