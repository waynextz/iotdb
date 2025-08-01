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

package org.apache.iotdb.cli;

import org.apache.iotdb.cli.AbstractCli.OperationResult;
import org.apache.iotdb.cli.type.ExitType;
import org.apache.iotdb.cli.utils.CliContext;
import org.apache.iotdb.exception.ArgsErrorException;
import org.apache.iotdb.jdbc.IoTDBConnection;
import org.apache.iotdb.jdbc.IoTDBDatabaseMetadata;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

public class AbstractCliTest {
  private static Logger logger = LoggerFactory.getLogger(AbstractCliTest.class);
  @Mock private IoTDBConnection connection;

  @Mock private IoTDBDatabaseMetadata databaseMetadata;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    when(connection.getMetaData()).thenReturn(databaseMetadata);
    when(connection.getTimeZone()).thenReturn("Asia/Shanghai");
  }

  @After
  public void tearDown() {}

  @Test
  public void testInit() {
    AbstractCli.init();
    String[] keywords = {
      AbstractCli.HOST_ARGS,
      AbstractCli.HELP_ARGS,
      AbstractCli.PORT_ARGS,
      AbstractCli.PW_ARGS,
      AbstractCli.USERNAME_ARGS,
      AbstractCli.ISO8601_ARGS,
    };
    for (String keyword : keywords) {
      if (!AbstractCli.keywordSet.contains("-" + keyword)) {
        logger.error(keyword);
        fail();
      }
    }
  }

  @Test
  public void testCheckRequiredArg() throws ParseException, ArgsErrorException {
    CliContext ctx = new CliContext(System.in, System.out, System.err, ExitType.EXCEPTION);
    Options options = AbstractCli.createOptions();
    CommandLineParser parser = new DefaultParser();
    String[] args = new String[] {"-u", "user1"};
    CommandLine commandLine = parser.parse(options, args);
    String str =
        AbstractCli.checkRequiredArg(
            ctx, AbstractCli.USERNAME_ARGS, AbstractCli.USERNAME_NAME, commandLine, true, "root");
    assertEquals("user1", str);

    args =
        new String[] {
          "-u", "root",
        };
    commandLine = parser.parse(options, args);
    str =
        AbstractCli.checkRequiredArg(
            ctx, AbstractCli.HOST_ARGS, AbstractCli.HOST_NAME, commandLine, false, "127.0.0.1");
    assertEquals("127.0.0.1", str);
    try {
      str =
          AbstractCli.checkRequiredArg(
              ctx, AbstractCli.HOST_ARGS, AbstractCli.HOST_NAME, commandLine, true, "127.0.0.1");
    } catch (ArgsErrorException e) {
      assertEquals("IoTDB: Required values for option 'host' not provided", e.getMessage());
    }
    try {
      str =
          AbstractCli.checkRequiredArg(
              ctx, AbstractCli.HOST_ARGS, AbstractCli.HOST_NAME, commandLine, false, null);
    } catch (ArgsErrorException e) {
      assertEquals("IoTDB: Required values for option 'host' is null.", e.getMessage());
    }
  }

  @Test
  public void testRemovePasswordArgs() {
    AbstractCli.init();
    String[] input =
        new String[] {"-h", "127.0.0.1", "-p", "6667", "-u", "root", "-pw", "IoTDB@2011"};
    String[] res =
        new String[] {"-h", "127.0.0.1", "-p", "6667", "-u", "root", "-pw", "IoTDB@2011"};
    isTwoStringArrayEqual(res, AbstractCli.removePasswordArgs(input));

    input = new String[] {"-h", "127.0.0.1", "-p", "6667", "-pw", "IoTDB@2011", "-u", "root"};
    res = new String[] {"-h", "127.0.0.1", "-p", "6667", "-pw", "IoTDB@2011", "-u", "root"};
    isTwoStringArrayEqual(res, AbstractCli.removePasswordArgs(input));

    input = new String[] {"-h", "127.0.0.1", "-p", "6667", "root", "-u", "root", "-pw"};
    res = new String[] {"-h", "127.0.0.1", "-p", "6667", "root", "-u", "root"};
    isTwoStringArrayEqual(res, AbstractCli.removePasswordArgs(input));

    input = new String[] {"-h", "127.0.0.1", "-p", "6667", "-pw", "-u", "root"};
    res = new String[] {"-h", "127.0.0.1", "-p", "6667", "-u", "root"};
    isTwoStringArrayEqual(res, AbstractCli.removePasswordArgs(input));

    input = new String[] {"-pw", "-h", "127.0.0.1", "-p", "6667", "root", "-u", "root"};
    res = new String[] {"-h", "127.0.0.1", "-p", "6667", "root", "-u", "root"};
    isTwoStringArrayEqual(res, AbstractCli.removePasswordArgs(input));

    input = new String[] {};
    res = new String[] {};
    isTwoStringArrayEqual(res, AbstractCli.removePasswordArgs(input));
  }

  private void isTwoStringArrayEqual(String[] expected, String[] actual) {
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], actual[i]);
    }
  }

  @Test
  public void testHandleInputInputCmd() {
    CliContext ctx = new CliContext(System.in, System.out, System.err, ExitType.EXCEPTION);
    assertEquals(
        OperationResult.STOP_OPER,
        AbstractCli.handleInputCmd(ctx, AbstractCli.EXIT_COMMAND, connection));
    assertEquals(
        OperationResult.STOP_OPER,
        AbstractCli.handleInputCmd(ctx, AbstractCli.QUIT_COMMAND, connection));
    assertEquals(
        OperationResult.CONTINUE_OPER,
        AbstractCli.handleInputCmd(
            ctx, String.format("%s=", AbstractCli.SET_TIMESTAMP_DISPLAY), connection));
    assertEquals(
        OperationResult.CONTINUE_OPER,
        AbstractCli.handleInputCmd(
            ctx, String.format("%s=xxx", AbstractCli.SET_TIMESTAMP_DISPLAY), connection));
    assertEquals(
        OperationResult.CONTINUE_OPER,
        AbstractCli.handleInputCmd(
            ctx, String.format("%s=default", AbstractCli.SET_TIMESTAMP_DISPLAY), connection));

    assertEquals(
        OperationResult.CONTINUE_OPER,
        AbstractCli.handleInputCmd(ctx, AbstractCli.SHOW_TIMEZONE, connection));
    assertEquals(
        OperationResult.CONTINUE_OPER,
        AbstractCli.handleInputCmd(ctx, AbstractCli.SHOW_TIMESTAMP_DISPLAY, connection));
    assertEquals(
        OperationResult.CONTINUE_OPER,
        AbstractCli.handleInputCmd(ctx, AbstractCli.SHOW_FETCH_SIZE, connection));

    assertEquals(
        OperationResult.CONTINUE_OPER,
        AbstractCli.handleInputCmd(
            ctx, String.format("%s=%s", AbstractCli.SET_TIME_ZONE, "Asis/chongqing"), connection));
    assertEquals(
        OperationResult.CONTINUE_OPER,
        AbstractCli.handleInputCmd(
            ctx, String.format("%s=+08:00", AbstractCli.SET_TIME_ZONE), connection));

    assertEquals(
        OperationResult.CONTINUE_OPER,
        AbstractCli.handleInputCmd(
            ctx, String.format("%s=", AbstractCli.SET_FETCH_SIZE), connection));
    assertEquals(
        OperationResult.CONTINUE_OPER,
        AbstractCli.handleInputCmd(
            ctx, String.format("%s=111", AbstractCli.SET_FETCH_SIZE), connection));
  }
}
