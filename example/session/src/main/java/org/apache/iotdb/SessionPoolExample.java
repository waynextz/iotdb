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

package org.apache.iotdb;

import org.apache.iotdb.isession.SessionDataSet.DataIterator;
import org.apache.iotdb.isession.pool.SessionDataSetWrapper;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.pool.SessionPool;

import org.apache.tsfile.enums.TSDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings({"squid:S106", "squid:S1144"})
public class SessionPoolExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(SessionPoolExample.class);

  private static SessionPool sessionPool;
  private static ExecutorService service;

  /** Build a custom SessionPool for this example */
  private static void constructCustomSessionPool() {
    sessionPool =
        new SessionPool.Builder()
            .host("127.0.0.1")
            .port(6667)
            .user("root")
            .password("IoTDB@2011")
            .maxSize(3)
            .build();
  }

  /** Build a redirect-able SessionPool for this example */
  private static void constructRedirectSessionPool() {
    List<String> nodeUrls = new ArrayList<>();
    nodeUrls.add("127.0.0.1:6667");
    nodeUrls.add("127.0.0.1:6668");
    sessionPool =
        new SessionPool.Builder()
            .nodeUrls(nodeUrls)
            .user("root")
            .password("IoTDB@2011")
            .maxSize(3)
            .build();
  }

  public static void main(String[] args)
      throws StatementExecutionException, IoTDBConnectionException, InterruptedException {
    // Choose the SessionPool you going to use
    constructRedirectSessionPool();

    service = Executors.newFixedThreadPool(10);
    insertRecord();
    queryByRowRecord();
    Thread.sleep(1000);
    queryByIterator();
    sessionPool.close();
    service.shutdown();
  }

  // more insert example, see SessionExample.java
  private static void insertRecord() throws StatementExecutionException, IoTDBConnectionException {
    String deviceId = "root.sg1.d1";
    List<String> measurements = new ArrayList<>();
    List<TSDataType> types = new ArrayList<>();
    measurements.add("s1");
    measurements.add("s2");
    measurements.add("s3");
    types.add(TSDataType.INT64);
    types.add(TSDataType.INT64);
    types.add(TSDataType.INT64);

    for (long time = 0; time < 10; time++) {
      List<Object> values = new ArrayList<>();
      values.add(1L);
      values.add(2L);
      values.add(3L);
      sessionPool.insertRecord(deviceId, time, measurements, types, values);
    }
  }

  private static void queryByRowRecord() {
    for (int i = 0; i < 1; i++) {
      service.submit(
          () -> {
            SessionDataSetWrapper wrapper = null;
            try {
              wrapper = sessionPool.executeQueryStatement("select * from root.sg1.d1");
              System.out.println(wrapper.getColumnNames());
              System.out.println(wrapper.getColumnTypes());
              while (wrapper.hasNext()) {
                System.out.println(wrapper.next());
              }
            } catch (IoTDBConnectionException | StatementExecutionException e) {
              LOGGER.error("Query by row record error", e);
            } finally {
              // remember to close data set finally!
              sessionPool.closeResultSet(wrapper);
            }
          });
    }
  }

  private static void queryByIterator() {
    for (int i = 0; i < 1; i++) {
      service.submit(
          () -> {
            SessionDataSetWrapper wrapper = null;
            try {
              wrapper = sessionPool.executeQueryStatement("select * from root.sg1.d1");
              // get DataIterator like JDBC
              DataIterator dataIterator = wrapper.iterator();
              System.out.println(wrapper.getColumnNames());
              System.out.println(wrapper.getColumnTypes());
              while (dataIterator.next()) {
                StringBuilder builder = new StringBuilder();
                for (String columnName : wrapper.getColumnNames()) {
                  builder.append(dataIterator.getString(columnName) + " ");
                }
                System.out.println(builder);
              }
            } catch (IoTDBConnectionException | StatementExecutionException e) {
              LOGGER.error("Query by Iterator error", e);
            } finally {
              // remember to close data set finally!
              sessionPool.closeResultSet(wrapper);
            }
          });
    }
  }
}
