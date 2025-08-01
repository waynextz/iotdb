/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.consensus.iot.logdispatcher;

import org.apache.iotdb.commons.memory.AtomicLongMemoryBlock;
import org.apache.iotdb.commons.memory.IMemoryBlock;
import org.apache.iotdb.commons.service.metric.MetricService;
import org.apache.iotdb.consensus.common.request.IndexedConsensusRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

public class IoTConsensusMemoryManager {
  private static final Logger logger = LoggerFactory.getLogger(IoTConsensusMemoryManager.class);
  private final AtomicLong queueMemorySizeInByte = new AtomicLong(0);
  private final AtomicLong syncMemorySizeInByte = new AtomicLong(0);
  private IMemoryBlock memoryBlock =
      new AtomicLongMemoryBlock("Consensus-Default", null, Runtime.getRuntime().maxMemory() / 10);
  private Double maxMemoryRatioForQueue = 0.6;

  private IoTConsensusMemoryManager() {
    MetricService.getInstance().addMetricSet(new IoTConsensusMemoryManagerMetrics(this));
  }

  public boolean reserve(IndexedConsensusRequest request, boolean fromQueue) {
    long prevRef = request.incRef();
    if (prevRef == 0) {
      return reserve(request.getMemorySize(), fromQueue);
    } else {
      return true;
    }
  }

  public boolean reserve(long size, boolean fromQueue) {
    boolean result =
        fromQueue
            ? memoryBlock.allocateIfSufficient(size, maxMemoryRatioForQueue)
            : memoryBlock.allocate(size);
    if (result) {
      if (fromQueue) {
        queueMemorySizeInByte.addAndGet(size);
      } else {
        syncMemorySizeInByte.addAndGet(size);
      }
    }
    return result;
  }

  public void free(IndexedConsensusRequest request, boolean fromQueue) {
    long prevRef = request.decRef();
    if (prevRef == 1) {
      free(request.getMemorySize(), fromQueue);
    }
  }

  public void free(long size, boolean fromQueue) {
    long currentUsedMemory = memoryBlock.release(size);
    if (fromQueue) {
      queueMemorySizeInByte.addAndGet(-size);
    } else {
      syncMemorySizeInByte.addAndGet(-size);
    }
    logger.debug(
        "{} free {} bytes, total memory size: {} bytes.",
        Thread.currentThread().getName(),
        size,
        currentUsedMemory);
  }

  public void init(IMemoryBlock memoryBlock, double maxMemoryRatioForQueue) {
    this.memoryBlock = memoryBlock;
    this.maxMemoryRatioForQueue = maxMemoryRatioForQueue;
  }

  long getMemorySizeInByte() {
    return memoryBlock.getUsedMemoryInBytes();
  }

  long getQueueMemorySizeInByte() {
    return queueMemorySizeInByte.get();
  }

  long getSyncMemorySizeInByte() {
    return syncMemorySizeInByte.get();
  }

  private static final IoTConsensusMemoryManager INSTANCE = new IoTConsensusMemoryManager();

  public static IoTConsensusMemoryManager getInstance() {
    return INSTANCE;
  }
}
