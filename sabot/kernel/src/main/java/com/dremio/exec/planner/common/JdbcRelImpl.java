/*
 * Copyright (C) 2017-2018 Dremio Corporation
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
package com.dremio.exec.planner.common;

import org.apache.calcite.plan.CopyWithCluster.CopyToCluster;
import org.apache.calcite.rel.RelNode;

import com.dremio.exec.catalog.StoragePluginId;

/**
 * Relational expression to push down to jdbc source
 */
public interface JdbcRelImpl extends RelNode, CopyToCluster {

  /**
   * Get the plugin ID from the JDBC node. A null plugin ID implies that the operation is agnostic to which
   * JDBC data source it is executing on. (Currently only VALUES nodes).
   *
   * @return The plugin ID, or null if the operation is data source agnostic.
   */
  StoragePluginId getPluginId();

  /**
   * Revert to logical rel node
   * @return the reverted node
   */
  RelNode revert();
}
