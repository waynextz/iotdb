# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

import operator

from sqlalchemy import create_engine, inspect
from sqlalchemy.dialects import registry
from sqlalchemy.orm import Session
from sqlalchemy.sql import text
from tests.integration.iotdb_container import IoTDBContainer
from urllib.parse import quote_plus as urlquote

final_flag = True
failed_count = 0


def test_fail():
    global failed_count
    global final_flag
    final_flag = False
    failed_count += 1


def print_message(message):
    print("*********")
    print(message)
    print("*********")
    assert False


def test_dialect():

    with IoTDBContainer("iotdb:dev") as db:
        db: IoTDBContainer
        password = urlquote("IoTDB@2011")
        host = db.get_container_host_ip()
        port = db.get_exposed_port(6667)
        url = f"iotdb://root:{password}@{host}:{port}"
        registry.register("iotdb", "iotdb.sqlalchemy.IoTDBDialect", "IoTDBDialect")
        eng = create_engine(url)

        with Session(eng) as session:
            session.execute(text("create database root.cursor"))
            session.execute(text("create database root.cursor_s1"))
            session.execute(
                text(
                    "create timeseries root.cursor.device1.temperature with datatype=FLOAT,encoding=RLE"
                )
            )
            session.execute(
                text(
                    "create timeseries root.cursor.device1.status with datatype=FLOAT,encoding=RLE"
                )
            )
            session.execute(
                text(
                    "create timeseries root.cursor.device2.temperature with datatype=FLOAT,encoding=RLE"
                )
            )

        insp = inspect(eng)
        # test get_schema_names
        schema_names = insp.get_schema_names()
        if not operator.ge(
            schema_names, ["root.__system", "root.cursor", "root.cursor_s1"]
        ):
            test_fail()
            print_message("Actual result " + str(schema_names))
            print_message("test get_schema_names failed!")
        # test get_table_names
        table_names = insp.get_table_names("root.cursor")
        if not operator.eq(table_names, ["device1", "device2"]):
            test_fail()
            print_message("Actual result " + str(table_names))
            print_message("test get_table_names failed!")
        # test get_columns
        columns = insp.get_columns(table_name="device1", schema="root.cursor")
        if len(columns) != 3:
            test_fail()
            print_message("Actual result " + str(columns))
            print_message("test get_columns failed!")

        with Session(eng) as session:
            session.execute(text("delete database root.cursor"))
            session.execute(text("delete database root.cursor_s1"))

        # close engine
        eng.dispose()


if final_flag:
    print("All executions done!!")
else:
    print("Some test failed, please have a check")
    print("failed count: ", failed_count)
    exit(1)
