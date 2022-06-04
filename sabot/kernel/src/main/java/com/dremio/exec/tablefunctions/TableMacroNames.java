/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
 */
package com.dremio.exec.tablefunctions;

import java.util.List;

import com.google.common.collect.ImmutableList;

/**
 * Utility class for table macro names.
 */
public final class TableMacroNames {

  public static final String NAMESPACE = "__system_table_macros";

  public static final List<String> TIME_TRAVEL = ImmutableList.of(NAMESPACE, TimeTravelTableMacro.NAME);

  private TableMacroNames() {
  }
}
