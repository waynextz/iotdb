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

package org.apache.iotdb.db.pipe.sink.protocol.pipeconsensus;

import org.apache.iotdb.common.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.common.rpc.thrift.TConsensusGroupType;
import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.client.IClientManager;
import org.apache.iotdb.commons.client.container.IoTV2GlobalComponentContainer;
import org.apache.iotdb.commons.client.sync.SyncPipeConsensusServiceClient;
import org.apache.iotdb.commons.consensus.index.ProgressIndex;
import org.apache.iotdb.commons.exception.pipe.PipeRuntimeSinkRetryTimesConfigurableException;
import org.apache.iotdb.commons.pipe.config.PipeConfig;
import org.apache.iotdb.commons.pipe.sink.payload.pipeconsensus.response.PipeConsensusTransferFilePieceResp;
import org.apache.iotdb.commons.pipe.sink.protocol.IoTDBSink;
import org.apache.iotdb.consensus.pipe.thrift.TCommitId;
import org.apache.iotdb.consensus.pipe.thrift.TPipeConsensusBatchTransferResp;
import org.apache.iotdb.consensus.pipe.thrift.TPipeConsensusTransferResp;
import org.apache.iotdb.db.pipe.consensus.metric.PipeConsensusSinkMetrics;
import org.apache.iotdb.db.pipe.event.common.deletion.PipeDeleteDataNodeEvent;
import org.apache.iotdb.db.pipe.event.common.tablet.PipeInsertNodeTabletInsertionEvent;
import org.apache.iotdb.db.pipe.event.common.tsfile.PipeTsFileInsertionEvent;
import org.apache.iotdb.db.pipe.sink.protocol.pipeconsensus.payload.builder.PipeConsensusSyncBatchReqBuilder;
import org.apache.iotdb.db.pipe.sink.protocol.pipeconsensus.payload.request.PipeConsensusDeleteNodeReq;
import org.apache.iotdb.db.pipe.sink.protocol.pipeconsensus.payload.request.PipeConsensusTabletBinaryReq;
import org.apache.iotdb.db.pipe.sink.protocol.pipeconsensus.payload.request.PipeConsensusTabletInsertNodeReq;
import org.apache.iotdb.db.pipe.sink.protocol.pipeconsensus.payload.request.PipeConsensusTsFilePieceReq;
import org.apache.iotdb.db.pipe.sink.protocol.pipeconsensus.payload.request.PipeConsensusTsFilePieceWithModReq;
import org.apache.iotdb.db.pipe.sink.protocol.pipeconsensus.payload.request.PipeConsensusTsFileSealReq;
import org.apache.iotdb.db.pipe.sink.protocol.pipeconsensus.payload.request.PipeConsensusTsFileSealWithModReq;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.write.InsertNode;
import org.apache.iotdb.pipe.api.annotation.TableModel;
import org.apache.iotdb.pipe.api.annotation.TreeModel;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeConnectorRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;
import org.apache.iotdb.pipe.api.event.dml.insertion.TsFileInsertionEvent;
import org.apache.iotdb.pipe.api.exception.PipeException;
import org.apache.iotdb.rpc.TSStatusCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** This connector is used for PipeConsensus to transfer queued event. */
@TreeModel
@TableModel
public class PipeConsensusSyncSink extends IoTDBSink {
  private static final Logger LOGGER = LoggerFactory.getLogger(PipeConsensusSyncSink.class);
  private static final String PIPE_CONSENSUS_SYNC_CONNECTION_FAILED_FORMAT =
      "PipeConsensus: syncClient connection to %s:%s failed when %s, because: %s";
  private static final String TABLET_INSERTION_NODE_SCENARIO = "transfer insertionNode tablet";
  private static final String TSFILE_SCENARIO = "transfer tsfile";
  private static final String TABLET_BATCH_SCENARIO = "transfer tablet batch";
  private static final String DELETION_SCENARIO = "transfer deletion";
  private final IClientManager<TEndPoint, SyncPipeConsensusServiceClient> syncRetryClientManager;
  private final List<TEndPoint> peers;
  private final int thisDataNodeId;
  private final int consensusGroupId;
  private final PipeConsensusSinkMetrics pipeConsensusSinkMetrics;
  private PipeConsensusSyncBatchReqBuilder tabletBatchBuilder;

  public PipeConsensusSyncSink(
      final List<TEndPoint> peers,
      final int consensusGroupId,
      final int thisDataNodeId,
      final PipeConsensusSinkMetrics pipeConsensusSinkMetrics) {
    // In PipeConsensus, one pipeConsensusTask corresponds to a pipeConsensusConnector. Thus,
    // `peers` here actually is a singletonList that contains one peer's TEndPoint. But here we
    // retain the implementation of list to cope with possible future expansion
    this.peers = peers;
    this.consensusGroupId = consensusGroupId;
    this.thisDataNodeId = thisDataNodeId;
    this.syncRetryClientManager =
        IoTV2GlobalComponentContainer.getInstance().getGlobalSyncClientManager();
    this.pipeConsensusSinkMetrics = pipeConsensusSinkMetrics;
  }

  @Override
  public void customize(
      final PipeParameters parameters, final PipeConnectorRuntimeConfiguration configuration)
      throws Exception {
    super.customize(parameters, configuration);
    if (isTabletBatchModeEnabled) {
      tabletBatchBuilder =
          new PipeConsensusSyncBatchReqBuilder(
              parameters,
              new TConsensusGroupId(TConsensusGroupType.DataRegion, consensusGroupId),
              thisDataNodeId);
    }
    // Currently, tablet batch is false by default in PipeConsensus;
    isTabletBatchModeEnabled = false;
  }

  @Override
  public void handshake() throws Exception {
    // Do nothing
    // PipeConsensus doesn't need to do handshake, since nodes in same consensusGroup/cluster
    // usually have same configuration.
  }

  @Override
  public void heartbeat() throws Exception {
    // Do nothing
  }

  @Override
  public void transfer(final TabletInsertionEvent tabletInsertionEvent) throws Exception {
    // Note: here we don't need to do type judgment here, because PipeConsensus uses
    // PIPE_CONSENSUS_PROCESSOR and will not change the event type like
    // org.apache.iotdb.db.pipe.connector.protocol.thrift.sync.IoTDBDataRegionSyncConnector
    try {
      if (isTabletBatchModeEnabled) {
        if (tabletBatchBuilder.onEvent(tabletInsertionEvent)) {
          doTransfer();
        }
      } else {
        final long startTime = System.nanoTime();
        doTransferWrapper((PipeInsertNodeTabletInsertionEvent) tabletInsertionEvent);
        final long duration = System.nanoTime() - startTime;
        pipeConsensusSinkMetrics.recordRetryWALTransferTimer(duration);
      }
    } catch (final Exception e) {
      throw new PipeRuntimeSinkRetryTimesConfigurableException(
          String.format(
              "Failed to transfer tablet insertion event %s, because %s.",
              tabletInsertionEvent, e.getMessage()),
          Integer.MAX_VALUE);
    }
  }

  @Override
  public void transfer(final TsFileInsertionEvent tsFileInsertionEvent) throws Exception {
    // Note: here we don't need to do type judgment here, because PipeConsensus uses DO_NOTHING
    // processor and will not change the event type like
    // org.apache.iotdb.db.pipe.connector.protocol.thrift.sync.IoTDBDataRegionSyncConnector
    try {
      final long startTime = System.nanoTime();
      // In order to commit in order
      if (isTabletBatchModeEnabled && !tabletBatchBuilder.isEmpty()) {
        doTransfer();
      }
      doTransfer((PipeTsFileInsertionEvent) tsFileInsertionEvent);
      final long duration = System.nanoTime() - startTime;
      pipeConsensusSinkMetrics.recordRetryTsFileTransferTimer(duration);
    } catch (Exception e) {
      throw new PipeRuntimeSinkRetryTimesConfigurableException(
          String.format(
              "Failed to transfer tsfile insertion event %s, because %s.",
              tsFileInsertionEvent, e.getMessage()),
          Integer.MAX_VALUE);
    }
  }

  @Override
  public void transfer(final Event event) throws Exception {
    // in order to commit in order
    if (isTabletBatchModeEnabled && !tabletBatchBuilder.isEmpty()) {
      doTransfer();
    }

    // Only deletion event will be passed here.
    doTransferWrapper((PipeDeleteDataNodeEvent) event);
  }

  private void doTransfer() {
    try (final SyncPipeConsensusServiceClient syncPipeConsensusServiceClient =
        syncRetryClientManager.borrowClient(getFollowerUrl())) {
      final TPipeConsensusBatchTransferResp resp;
      resp =
          syncPipeConsensusServiceClient.pipeConsensusBatchTransfer(
              tabletBatchBuilder.toTPipeConsensusBatchTransferReq());

      final List<TSStatus> statusList =
          resp.getBatchResps().stream()
              .map(TPipeConsensusTransferResp::getStatus)
              .collect(Collectors.toList());

      // TODO(support batch): handle retry logic
      // Only handle the failed statuses to avoid string format performance overhead
      //      if (status.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()
      //          && status.getCode() != TSStatusCode.REDIRECTION_RECOMMEND.getStatusCode()) {
      //        receiverStatusHandler.handle(
      //            resp.getStatus(),
      //            String.format(
      //                "Transfer PipeConsensusTransferTabletBatchReq error, result status %s",
      //                resp.status),
      //            tabletBatchBuilder.deepCopyEvents().toString());
      //      }

      tabletBatchBuilder.onSuccess();
    } catch (final Exception e) {
      throw new PipeRuntimeSinkRetryTimesConfigurableException(
          String.format(
              PIPE_CONSENSUS_SYNC_CONNECTION_FAILED_FORMAT,
              getFollowerUrl().getIp(),
              getFollowerUrl().getPort(),
              TABLET_BATCH_SCENARIO,
              e.getMessage()),
          Integer.MAX_VALUE);
    }
  }

  private void doTransferWrapper(final PipeDeleteDataNodeEvent pipeDeleteDataNodeEvent)
      throws PipeException {
    // We increase the reference count for this event to determine if the event may be released.
    if (!pipeDeleteDataNodeEvent.increaseReferenceCount(PipeConsensusSyncSink.class.getName())) {
      return;
    }
    try {
      doTransfer(pipeDeleteDataNodeEvent);
    } finally {
      pipeDeleteDataNodeEvent.decreaseReferenceCount(PipeConsensusSyncSink.class.getName(), false);
    }
  }

  private void doTransfer(final PipeDeleteDataNodeEvent pipeDeleteDataNodeEvent)
      throws PipeException {
    final ProgressIndex progressIndex;
    final TPipeConsensusTransferResp resp;
    final TCommitId tCommitId =
        new TCommitId(
            pipeDeleteDataNodeEvent.getReplicateIndexForIoTV2(),
            pipeDeleteDataNodeEvent.getCommitterKey().getRestartTimes(),
            pipeDeleteDataNodeEvent.getRebootTimes());
    final TConsensusGroupId tConsensusGroupId =
        new TConsensusGroupId(TConsensusGroupType.DataRegion, consensusGroupId);

    try (final SyncPipeConsensusServiceClient syncPipeConsensusServiceClient =
        syncRetryClientManager.borrowClient(getFollowerUrl())) {
      progressIndex = pipeDeleteDataNodeEvent.getProgressIndex();
      resp =
          syncPipeConsensusServiceClient.pipeConsensusTransfer(
              PipeConsensusDeleteNodeReq.toTPipeConsensusTransferReq(
                  pipeDeleteDataNodeEvent.getDeleteDataNode(),
                  tCommitId,
                  tConsensusGroupId,
                  progressIndex,
                  thisDataNodeId));
    } catch (final Exception e) {
      throw new PipeRuntimeSinkRetryTimesConfigurableException(
          String.format(
              PIPE_CONSENSUS_SYNC_CONNECTION_FAILED_FORMAT,
              getFollowerUrl().getIp(),
              getFollowerUrl().getPort(),
              DELETION_SCENARIO,
              e.getMessage()),
          Integer.MAX_VALUE);
    }

    final TSStatus status = resp.getStatus();
    // Only handle the failed statuses to avoid string format performance overhead
    if (resp.getStatus().getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()
        && resp.getStatus().getCode() != TSStatusCode.REDIRECTION_RECOMMEND.getStatusCode()) {
      receiverStatusHandler.handle(
          status,
          String.format(
              "PipeConsensus transfer DeletionEvent %s error, result status %s.",
              pipeDeleteDataNodeEvent.getDeletionResource(), status),
          pipeDeleteDataNodeEvent.getDeleteDataNode().toString());
    }

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "Successfully transferred deletion event {}.",
          pipeDeleteDataNodeEvent.getDeletionResource());
    }
  }

  private void doTransferWrapper(
      final PipeInsertNodeTabletInsertionEvent pipeInsertNodeTabletInsertionEvent)
      throws PipeException {
    // We increase the reference count for this event to determine if the event may be released.
    if (!pipeInsertNodeTabletInsertionEvent.increaseReferenceCount(
        PipeConsensusSyncSink.class.getName())) {
      return;
    }
    try {
      doTransfer(pipeInsertNodeTabletInsertionEvent);
    } finally {
      pipeInsertNodeTabletInsertionEvent.decreaseReferenceCount(
          PipeConsensusSyncSink.class.getName(), false);
    }
  }

  private void doTransfer(PipeInsertNodeTabletInsertionEvent pipeInsertNodeTabletInsertionEvent)
      throws PipeException {
    final InsertNode insertNode;
    final ProgressIndex progressIndex;
    final TPipeConsensusTransferResp resp;
    final TCommitId tCommitId =
        new TCommitId(
            pipeInsertNodeTabletInsertionEvent.getReplicateIndexForIoTV2(),
            pipeInsertNodeTabletInsertionEvent.getCommitterKey().getRestartTimes(),
            pipeInsertNodeTabletInsertionEvent.getRebootTimes());
    final TConsensusGroupId tConsensusGroupId =
        new TConsensusGroupId(TConsensusGroupType.DataRegion, consensusGroupId);

    try (final SyncPipeConsensusServiceClient syncPipeConsensusServiceClient =
        syncRetryClientManager.borrowClient(getFollowerUrl())) {
      insertNode = pipeInsertNodeTabletInsertionEvent.getInsertNode();
      progressIndex = pipeInsertNodeTabletInsertionEvent.getProgressIndex();

      if (insertNode != null) {
        resp =
            syncPipeConsensusServiceClient.pipeConsensusTransfer(
                PipeConsensusTabletInsertNodeReq.toTPipeConsensusTransferReq(
                    insertNode, tCommitId, tConsensusGroupId, progressIndex, thisDataNodeId));
      } else {
        resp =
            syncPipeConsensusServiceClient.pipeConsensusTransfer(
                PipeConsensusTabletBinaryReq.toTPipeConsensusTransferReq(
                    pipeInsertNodeTabletInsertionEvent.getByteBuffer(),
                    tCommitId,
                    tConsensusGroupId,
                    progressIndex,
                    thisDataNodeId));
      }
    } catch (final Exception e) {
      throw new PipeRuntimeSinkRetryTimesConfigurableException(
          String.format(
              PIPE_CONSENSUS_SYNC_CONNECTION_FAILED_FORMAT,
              getFollowerUrl().getIp(),
              getFollowerUrl().getPort(),
              TABLET_INSERTION_NODE_SCENARIO,
              e.getMessage()),
          Integer.MAX_VALUE);
    }

    final TSStatus status = resp.getStatus();
    // Only handle the failed statuses to avoid string format performance overhead
    if (status.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()
        && status.getCode() != TSStatusCode.REDIRECTION_RECOMMEND.getStatusCode()) {
      receiverStatusHandler.handle(
          status,
          String.format(
              "PipeConsensus transfer PipeInsertNodeTabletInsertionEvent %s error, result status %s",
              pipeInsertNodeTabletInsertionEvent, status),
          pipeInsertNodeTabletInsertionEvent.toString());
    }
  }

  private void doTransfer(final PipeTsFileInsertionEvent pipeTsFileInsertionEvent)
      throws PipeException {
    final File tsFile = pipeTsFileInsertionEvent.getTsFile();
    final File modFile = pipeTsFileInsertionEvent.getModFile();
    final TPipeConsensusTransferResp resp;

    try (final SyncPipeConsensusServiceClient syncPipeConsensusServiceClient =
        syncRetryClientManager.borrowClient(getFollowerUrl())) {
      final TCommitId tCommitId =
          new TCommitId(
              pipeTsFileInsertionEvent.getReplicateIndexForIoTV2(),
              pipeTsFileInsertionEvent.getCommitterKey().getRestartTimes(),
              pipeTsFileInsertionEvent.getRebootTimes());
      final TConsensusGroupId tConsensusGroupId =
          new TConsensusGroupId(TConsensusGroupType.DataRegion, consensusGroupId);

      // 1. Transfer tsFile, and mod file if exists
      if (pipeTsFileInsertionEvent.isWithMod()) {
        transferFilePieces(
            modFile, syncPipeConsensusServiceClient, true, tCommitId, tConsensusGroupId);
        transferFilePieces(
            tsFile, syncPipeConsensusServiceClient, true, tCommitId, tConsensusGroupId);
        // 2. Transfer file seal signal with mod, which means the file is transferred completely
        resp =
            syncPipeConsensusServiceClient.pipeConsensusTransfer(
                PipeConsensusTsFileSealWithModReq.toTPipeConsensusTransferReq(
                    modFile.getName(),
                    modFile.length(),
                    tsFile.getName(),
                    tsFile.length(),
                    pipeTsFileInsertionEvent.getFlushPointCount(),
                    tCommitId,
                    tConsensusGroupId,
                    pipeTsFileInsertionEvent.getProgressIndex(),
                    thisDataNodeId));
      } else {
        transferFilePieces(
            tsFile, syncPipeConsensusServiceClient, false, tCommitId, tConsensusGroupId);
        // 2. Transfer file seal signal without mod, which means the file is transferred completely
        resp =
            syncPipeConsensusServiceClient.pipeConsensusTransfer(
                PipeConsensusTsFileSealReq.toTPipeConsensusTransferReq(
                    tsFile.getName(),
                    tsFile.length(),
                    pipeTsFileInsertionEvent.getFlushPointCount(),
                    tCommitId,
                    tConsensusGroupId,
                    pipeTsFileInsertionEvent.getProgressIndex(),
                    thisDataNodeId));
      }
    } catch (final Exception e) {
      throw new PipeRuntimeSinkRetryTimesConfigurableException(
          String.format(
              PIPE_CONSENSUS_SYNC_CONNECTION_FAILED_FORMAT,
              getFollowerUrl().getIp(),
              getFollowerUrl().getPort(),
              TSFILE_SCENARIO,
              e.getMessage()),
          Integer.MAX_VALUE);
    }

    final TSStatus status = resp.getStatus();
    // Only handle the failed statuses to avoid string format performance overhead
    if (status.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()
        && status.getCode() != TSStatusCode.REDIRECTION_RECOMMEND.getStatusCode()) {
      receiverStatusHandler.handle(
          resp.getStatus(),
          String.format("Seal file %s error, result status %s.", tsFile, resp.getStatus()),
          tsFile.getName());
    }

    LOGGER.info("Successfully transferred file {}.", tsFile);
  }

  protected void transferFilePieces(
      final File file,
      final SyncPipeConsensusServiceClient syncPipeConsensusServiceClient,
      final boolean isMultiFile,
      final TCommitId tCommitId,
      final TConsensusGroupId tConsensusGroupId)
      throws PipeException, IOException {
    final int readFileBufferSize = PipeConfig.getInstance().getPipeConnectorReadFileBufferSize();
    final byte[] readBuffer = new byte[readFileBufferSize];
    long position = 0;
    try (final RandomAccessFile reader = new RandomAccessFile(file, "r")) {
      while (true) {
        final int readLength = reader.read(readBuffer);
        if (readLength == -1) {
          break;
        }

        final byte[] payLoad =
            readLength == readFileBufferSize
                ? readBuffer
                : Arrays.copyOfRange(readBuffer, 0, readLength);
        final PipeConsensusTransferFilePieceResp resp;
        try {
          resp =
              PipeConsensusTransferFilePieceResp.fromTPipeConsensusTransferResp(
                  syncPipeConsensusServiceClient.pipeConsensusTransfer(
                      isMultiFile
                          ? PipeConsensusTsFilePieceWithModReq.toTPipeConsensusTransferReq(
                              file.getName(),
                              position,
                              payLoad,
                              tCommitId,
                              tConsensusGroupId,
                              thisDataNodeId)
                          : PipeConsensusTsFilePieceReq.toTPipeConsensusTransferReq(
                              file.getName(),
                              position,
                              payLoad,
                              tCommitId,
                              tConsensusGroupId,
                              thisDataNodeId)));
        } catch (Exception e) {
          throw new PipeRuntimeSinkRetryTimesConfigurableException(
              String.format(
                  "Network error when transfer file %s, because %s.", file, e.getMessage()),
              Integer.MAX_VALUE);
        }

        position += readLength;

        final TSStatus status = resp.getStatus();
        // This case only happens when the connection is broken, and the connector is reconnected
        // to the receiver, then the receiver will redirect the file position to the last position
        if (status.getCode()
            == TSStatusCode.PIPE_CONSENSUS_TRANSFER_FILE_OFFSET_RESET.getStatusCode()) {
          position = resp.getEndWritingOffset();
          reader.seek(position);
          LOGGER.info("Redirect file position to {}.", position);
          continue;
        }

        // Only handle the failed statuses to avoid string format performance overhead
        if (status.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()
            && status.getCode() != TSStatusCode.REDIRECTION_RECOMMEND.getStatusCode()) {
          receiverStatusHandler.handle(
              resp.getStatus(),
              String.format("Transfer file %s error, result status %s.", file, resp.getStatus()),
              file.getName());
        }
      }
    }
  }

  private TEndPoint getFollowerUrl() {
    // In current pipeConsensus design, one connector corresponds to one follower, so the peers is
    // actually a singleton list
    return peers.get(0);
  }

  // synchronized to avoid close connector when transfer event
  @Override
  public synchronized void close() {
    super.close();

    if (tabletBatchBuilder != null) {
      tabletBatchBuilder.close();
    }
  }
}
