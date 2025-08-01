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

import org.apache.iotdb.jdbc.IoTDBSQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

public class JDBCExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(JDBCExample.class);

  public static void main(String[] args) throws ClassNotFoundException, SQLException {
    Class.forName("org.apache.iotdb.jdbc.IoTDBDriver");
    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:iotdb://127.0.0.1:6667?version=V_1_0", "root", "IoTDB@2011");
        Statement statement = connection.createStatement()) {

      // set JDBC fetchSize
      statement.setFetchSize(10000);

      statement.execute("CREATE DATABASE root.sg1");
      statement.execute(
          "CREATE TIMESERIES root.sg1.d1.s1 WITH DATATYPE=INT64, ENCODING=RLE, COMPRESSOR=SNAPPY");
      statement.execute(
          "CREATE TIMESERIES root.sg1.d1.s2 WITH DATATYPE=INT64, ENCODING=RLE, COMPRESSOR=SNAPPY");
      statement.execute(
          "CREATE TIMESERIES root.sg1.d1.s3 WITH DATATYPE=INT64, ENCODING=RLE, COMPRESSOR=SNAPPY");
      statement.execute(
          "CREATE TIMESERIES root.sg1.d1.s4 WITH DATATYPE=DATE, ENCODING=PLAIN, COMPRESSOR=SNAPPY");
      statement.execute(
          "CREATE TIMESERIES root.sg1.d1.s5 WITH DATATYPE=TIMESTAMP, ENCODING=PLAIN, COMPRESSOR=SNAPPY");
      statement.execute(
          "CREATE TIMESERIES root.sg1.d1.s6 WITH DATATYPE=BLOB, ENCODING=PLAIN, COMPRESSOR=SNAPPY");
      statement.execute(
          "CREATE TIMESERIES root.sg1.d1.s7 WITH DATATYPE=STRING, ENCODING=PLAIN, COMPRESSOR=SNAPPY");

      for (int i = 0; i <= 100; i++) {
        statement.addBatch(prepareInsertStatement(i));
      }
      statement.executeBatch();
      statement.clearBatch();

      ResultSet resultSet = statement.executeQuery("select ** from root where time <= 10");
      outputResult(resultSet);
      resultSet = statement.executeQuery("select count(**) from root");
      outputResult(resultSet);
      resultSet =
          statement.executeQuery(
              "select count(**) from root where time >= 1 and time <= 100 group by ([0, 100), 20ms, 20ms)");
      outputResult(resultSet);
    } catch (IoTDBSQLException e) {
      LOGGER.error("IoTDB Jdbc example error", e);
    }
  }

  @SuppressWarnings({"squid:S106"})
  private static void outputResult(ResultSet resultSet) throws SQLException {
    if (resultSet != null) {
      System.out.println("--------------------------");
      final ResultSetMetaData metaData = resultSet.getMetaData();
      final int columnCount = metaData.getColumnCount();
      for (int i = 0; i < columnCount; i++) {
        System.out.print(metaData.getColumnLabel(i + 1) + " ");
      }
      System.out.println();
      while (resultSet.next()) {
        for (int i = 1; ; i++) {
          System.out.print(resultSet.getString(i));
          if (i < columnCount) {
            System.out.print(", ");
          } else {
            System.out.println();
            break;
          }
        }
      }
      System.out.println("--------------------------\n");
    }
  }

  private static String prepareInsertStatement(int time) {
    return String.format(
        "insert into root.sg1.d1(timestamp, s1, s2, s3, s4, s5, s6, s7) values(%d, %d, %d, %d, \"%s\", %d, %s, \"%s\")",
        time, 1, 1, 1, LocalDate.of(2024, 5, time % 31 + 1), time, "X'cafebabe'", time);
  }
}
