@REM
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM     http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM

@echo off

title IoTDB Import

echo ````````````````````````````````````````````````
echo Starting IoTDB Client Import Schema Script
echo ````````````````````````````````````````````````

if "%OS%" == "Windows_NT" setlocal

pushd %~dp0..\..\..
if NOT DEFINED IOTDB_HOME set IOTDB_HOME=%CD%
popd

if NOT DEFINED MAIN_CLASS set MAIN_CLASS=org.apache.iotdb.tool.schema.ImportSchema
if NOT DEFINED JAVA_HOME goto :err

@REM -----------------------------------------------------------------------------
@REM JVM Opts we'll use in legacy run or installation
set JAVA_OPTS=-ea^
 -DIOTDB_HOME="%IOTDB_HOME%" -Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8

@REM ***** CLASSPATH library setting *****
if EXIST "%IOTDB_HOME%\lib" (set CLASSPATH="%IOTDB_HOME%\lib\*") else set CLASSPATH="%IOTDB_HOME%\..\lib\*"

REM -----------------------------------------------------------------------------

"%JAVA_HOME%\bin\java" -DIOTDB_HOME="%IOTDB_HOME%" %JAVA_OPTS% -cp %CLASSPATH% %MAIN_CLASS% %*
set ret_code=%ERRORLEVEL%
goto finally


:err
echo JAVA_HOME environment variable must be set!
set ret_code=1
pause


@REM -----------------------------------------------------------------------------
:finally

ENDLOCAL

EXIT /B %ret_code%
