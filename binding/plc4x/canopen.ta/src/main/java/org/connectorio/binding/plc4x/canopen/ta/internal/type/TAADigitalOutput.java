/*
 * Copyright (C) 2019-2020 ConnectorIO Sp. z o.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectorio.binding.plc4x.canopen.ta.internal.type;

import java.util.Optional;

public class TAADigitalOutput extends AbstractTAObject implements TAOutput {

  private TAValue value;

  public TAADigitalOutput(short subIndex, int unit) {
    super((short) 0x238f, subIndex, unit);
  }

  @Override
  public Optional<TAValue> getState() {
    return Optional.ofNullable(value);
  }

  public void setValue(TAValue value) {
    this.value = value;
  }

  @Override
  public short getIndex() {
    return labelAddress.getSubindex();
  }

}
