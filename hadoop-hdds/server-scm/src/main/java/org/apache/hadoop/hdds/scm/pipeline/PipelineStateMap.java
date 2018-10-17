/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdds.scm.pipeline;

import com.google.common.base.Preconditions;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationType;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline.PipelineState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Holds the data structures which maintain the information about pipeline and
 * its state. All the read write operations in this class are protected by a
 * lock.
 * Invariant: If a pipeline exists in PipelineStateMap, both pipelineMap and
 * pipeline2container would have a non-null mapping for it.
 */
class PipelineStateMap {

  private static final Logger LOG = LoggerFactory.getLogger(
      PipelineStateMap.class);

  private final Map<PipelineID, Pipeline> pipelineMap;
  private final Map<PipelineID, Set<ContainerID>> pipeline2container;

  PipelineStateMap() {

    // TODO: Use TreeMap for range operations?
    this.pipelineMap = new HashMap<>();
    this.pipeline2container = new HashMap<>();

  }

  /**
   * Adds provided pipeline in the data structures.
   *
   * @param pipeline - Pipeline to add
   * @throws IOException if pipeline with provided pipelineID already exists
   */
  void addPipeline(Pipeline pipeline) throws IOException {
    Preconditions.checkNotNull(pipeline, "Pipeline cannot be null");
    Preconditions.checkArgument(
        pipeline.getNodes().size() == pipeline.getFactor().getNumber(),
        String.format("Nodes size=%d, replication factor=%d do not match ",
                pipeline.getNodes().size(), pipeline.getFactor().getNumber()));

    if (pipelineMap.putIfAbsent(pipeline.getID(), pipeline) != null) {
      LOG.warn("Duplicate pipeline ID detected. {}", pipeline.getID());
      throw new IOException(String
          .format("Duplicate pipeline ID %s detected.", pipeline.getID()));
    }
    pipeline2container.put(pipeline.getID(), new TreeSet<>());
  }

  /**
   * Add container to an existing pipeline.
   *
   * @param pipelineID - PipelineID of the pipeline to which container is added
   * @param containerID - ContainerID of the container to add
   * @throws IOException if pipeline is not in open state or does not exist
   */
  void addContainerToPipeline(PipelineID pipelineID, ContainerID containerID)
      throws IOException {
    Preconditions.checkNotNull(pipelineID,
        "Pipeline Id cannot be null");
    Preconditions.checkNotNull(containerID,
        "container Id cannot be null");

    Pipeline pipeline = getPipeline(pipelineID);
    if (!pipeline.isOpen()) {
      throw new IOException(
          String.format("%s is not in open state", pipelineID));
    }
    pipeline2container.get(pipelineID).add(containerID);
  }

  /**
   * Get pipeline corresponding to specified pipelineID.
   *
   * @param pipelineID - PipelineID of the pipeline to be retrieved
   * @return Pipeline
   * @throws IOException if pipeline is not found
   */
  Pipeline getPipeline(PipelineID pipelineID) throws IOException {
    Pipeline pipeline = pipelineMap.get(pipelineID);
    if (pipeline == null) {
      throw new IOException(String.format("%s not found", pipelineID));
    }
    return pipeline;
  }

  /**
   * Get pipeline corresponding to specified replication type.
   *
   * @param type - ReplicationType
   * @return List of pipelines which have the specified replication type
   */
  List<Pipeline> getPipelinesByType(ReplicationType type) {
    Preconditions.checkNotNull(type, "Replication type cannot be null");

    return pipelineMap.values().stream().filter(p -> p.getType().equals(type))
        .collect(Collectors.toList());
  }

  /**
   * Get open pipeline corresponding to specified replication type and factor.
   *
   * @param type - ReplicationType
   * @param factor - ReplicationFactor
   * @return List of open pipelines with specified replication type and factor
   */
  List<Pipeline> getPipelinesByTypeAndFactor(ReplicationType type,
      ReplicationFactor factor) {
    return pipelineMap.values().stream()
        .filter(pipeline -> pipeline.isOpen() && pipeline.getType() == type
            && pipeline.getFactor() == factor)
        .collect(Collectors.toList());
  }

  /**
   * Get set of containerIDs corresponding to a pipeline.
   *
   * @param pipelineID - PipelineID
   * @return Set of containerIDs belonging to the pipeline
   * @throws IOException if pipeline is not found
   */
  Set<ContainerID> getContainers(PipelineID pipelineID)
      throws IOException {
    Set<ContainerID> containerIDs = pipeline2container.get(pipelineID);
    if (containerIDs == null) {
      throw new IOException(String.format("%s not found", pipelineID));
    }
    return new HashSet<>(containerIDs);
  }

  /**
   * Get number of containers corresponding to a pipeline.
   *
   * @param pipelineID - PipelineID
   * @return Number of containers belonging to the pipeline
   * @throws IOException if pipeline is not found
   */
  int getNumberOfContainers(PipelineID pipelineID) throws IOException {
    Set<ContainerID> containerIDs = pipeline2container.get(pipelineID);
    if (containerIDs == null) {
      throw new IOException(String.format("%s not found", pipelineID));
    }
    return containerIDs.size();
  }

  /**
   * Remove pipeline from the data structures.
   *
   * @param pipelineID - PipelineID of the pipeline to be removed
   * @throws IOException if the pipeline is not empty or does not exist
   */
  void removePipeline(PipelineID pipelineID) throws IOException {
    Preconditions.checkNotNull(pipelineID, "Pipeline Id cannot be null");

    Pipeline pipeline = getPipeline(pipelineID);
    if (!pipeline.isClosed()) {
      throw new IOException(
          String.format("Pipeline with %s is not yet closed", pipelineID));
    }

    Set<ContainerID> containerIDs = pipeline2container.get(pipelineID);
    if (containerIDs.size() != 0) {
      throw new IOException(
          String.format("Pipeline with %s is not empty", pipelineID));
    }

    pipelineMap.remove(pipelineID);
    pipeline2container.remove(pipelineID);
  }

  /**
   * Remove container from a pipeline.
   *
   * @param pipelineID - PipelineID of the pipeline from which container needs
   *                   to be removed
   * @param containerID - ContainerID of the container to remove
   * @throws IOException if pipeline does not exist
   */
  void removeContainerFromPipeline(PipelineID pipelineID,
      ContainerID containerID) throws IOException {
    Preconditions.checkNotNull(pipelineID,
        "Pipeline Id cannot be null");
    Preconditions.checkNotNull(containerID,
        "container Id cannot be null");

    Set<ContainerID> containerIDs = pipeline2container.get(pipelineID);
    containerIDs.remove(containerID);
  }

  /**
   * Updates the state of pipeline.
   *
   * @param pipelineID - PipelineID of the pipeline whose state needs
   *                   to be updated
   * @param state - new state of the pipeline
   * @return Pipeline with the updated state
   * @throws IOException if pipeline does not exist
   */
  Pipeline updatePipelineState(PipelineID pipelineID, PipelineState state)
      throws IOException {
    Preconditions.checkNotNull(pipelineID, "Pipeline Id cannot be null");
    Preconditions.checkNotNull(state, "Pipeline LifeCycleState cannot be null");

    final Pipeline pipeline = getPipeline(pipelineID);
    return pipelineMap.compute(pipelineID,
        (id, p) -> Pipeline.newBuilder(pipeline).setState(state).build());
  }
}
