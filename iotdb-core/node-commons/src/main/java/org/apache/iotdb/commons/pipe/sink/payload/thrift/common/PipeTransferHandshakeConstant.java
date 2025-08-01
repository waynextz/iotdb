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

package org.apache.iotdb.commons.pipe.sink.payload.thrift.common;

public class PipeTransferHandshakeConstant {

  public static final String HANDSHAKE_KEY_TIME_PRECISION = "timestampPrecision";
  public static final String HANDSHAKE_KEY_CLUSTER_ID = "clusterID";
  public static final String HANDSHAKE_KEY_CONVERT_ON_TYPE_MISMATCH = "convertOnTypeMismatch";
  public static final String HANDSHAKE_KEY_LOAD_TSFILE_STRATEGY = "loadTsFileStrategy";
  public static final String HANDSHAKE_KEY_USERNAME = "username";
  public static final String HANDSHAKE_KEY_PASSWORD = "password";
  public static final String HANDSHAKE_KEY_VALIDATE_TSFILE = "validateTsFile";
  public static final String HANDSHAKE_KEY_MARK_AS_PIPE_REQUEST = "markAsPipeRequest";

  private PipeTransferHandshakeConstant() {
    // Utility class
  }
}
