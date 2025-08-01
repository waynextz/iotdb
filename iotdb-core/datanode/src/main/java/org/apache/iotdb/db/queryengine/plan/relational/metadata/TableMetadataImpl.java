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

package org.apache.iotdb.db.queryengine.plan.relational.metadata;

import org.apache.iotdb.commons.exception.IoTDBException;
import org.apache.iotdb.commons.exception.table.TableNotExistsException;
import org.apache.iotdb.commons.partition.DataPartition;
import org.apache.iotdb.commons.partition.DataPartitionQueryParam;
import org.apache.iotdb.commons.partition.SchemaPartition;
import org.apache.iotdb.commons.schema.table.TreeViewSchema;
import org.apache.iotdb.commons.schema.table.TsTable;
import org.apache.iotdb.commons.udf.builtin.relational.TableBuiltinAggregationFunction;
import org.apache.iotdb.commons.udf.builtin.relational.TableBuiltinScalarFunction;
import org.apache.iotdb.commons.udf.utils.UDFDataTypeTransformer;
import org.apache.iotdb.db.exception.load.LoadAnalyzeTableColumnDisorderException;
import org.apache.iotdb.db.exception.sql.SemanticException;
import org.apache.iotdb.db.queryengine.common.MPPQueryContext;
import org.apache.iotdb.db.queryengine.common.SessionInfo;
import org.apache.iotdb.db.queryengine.plan.analyze.ClusterPartitionFetcher;
import org.apache.iotdb.db.queryengine.plan.analyze.IModelFetcher;
import org.apache.iotdb.db.queryengine.plan.analyze.IPartitionFetcher;
import org.apache.iotdb.db.queryengine.plan.analyze.ModelFetcher;
import org.apache.iotdb.db.queryengine.plan.relational.function.OperatorType;
import org.apache.iotdb.db.queryengine.plan.relational.function.TableBuiltinTableFunction;
import org.apache.iotdb.db.queryengine.plan.relational.function.arithmetic.AdditionResolver;
import org.apache.iotdb.db.queryengine.plan.relational.function.arithmetic.DivisionResolver;
import org.apache.iotdb.db.queryengine.plan.relational.function.arithmetic.ModulusResolver;
import org.apache.iotdb.db.queryengine.plan.relational.function.arithmetic.MultiplicationResolver;
import org.apache.iotdb.db.queryengine.plan.relational.function.arithmetic.SubtractionResolver;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.fetcher.TableDeviceSchemaFetcher;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.fetcher.TableDeviceSchemaValidator;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.fetcher.TableHeaderSchemaValidator;
import org.apache.iotdb.db.queryengine.plan.relational.security.AccessControl;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Expression;
import org.apache.iotdb.db.queryengine.plan.relational.type.InternalTypeManager;
import org.apache.iotdb.db.queryengine.plan.relational.type.TypeManager;
import org.apache.iotdb.db.queryengine.plan.relational.type.TypeNotFoundException;
import org.apache.iotdb.db.queryengine.plan.relational.type.TypeSignature;
import org.apache.iotdb.db.queryengine.plan.udf.TableUDFUtils;
import org.apache.iotdb.db.schemaengine.table.DataNodeTableCache;
import org.apache.iotdb.db.utils.constant.SqlConstant;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.udf.api.customizer.analysis.AggregateFunctionAnalysis;
import org.apache.iotdb.udf.api.customizer.analysis.ScalarFunctionAnalysis;
import org.apache.iotdb.udf.api.customizer.parameter.FunctionArguments;
import org.apache.iotdb.udf.api.relational.AggregateFunction;
import org.apache.iotdb.udf.api.relational.ScalarFunction;
import org.apache.iotdb.udf.api.relational.TableFunction;

import org.apache.tsfile.file.metadata.IDeviceID;
import org.apache.tsfile.read.common.type.BlobType;
import org.apache.tsfile.read.common.type.StringType;
import org.apache.tsfile.read.common.type.Type;
import org.apache.tsfile.read.common.type.TypeFactory;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.apache.iotdb.db.queryengine.transformation.dag.column.FailFunctionColumnTransformer.FAIL_FUNCTION_NAME;
import static org.apache.tsfile.read.common.type.BinaryType.TEXT;
import static org.apache.tsfile.read.common.type.BooleanType.BOOLEAN;
import static org.apache.tsfile.read.common.type.DateType.DATE;
import static org.apache.tsfile.read.common.type.DoubleType.DOUBLE;
import static org.apache.tsfile.read.common.type.FloatType.FLOAT;
import static org.apache.tsfile.read.common.type.IntType.INT32;
import static org.apache.tsfile.read.common.type.LongType.INT64;
import static org.apache.tsfile.read.common.type.StringType.STRING;
import static org.apache.tsfile.read.common.type.TimestampType.TIMESTAMP;
import static org.apache.tsfile.read.common.type.UnknownType.UNKNOWN;

public class TableMetadataImpl implements Metadata {

  private final TypeManager typeManager = new InternalTypeManager();

  private final IPartitionFetcher partitionFetcher = ClusterPartitionFetcher.getInstance();

  private final DataNodeTableCache tableCache = DataNodeTableCache.getInstance();

  private final IModelFetcher modelFetcher = ModelFetcher.getInstance();

  @Override
  public boolean tableExists(final QualifiedObjectName name) {
    return tableCache.getTable(name.getDatabaseName(), name.getObjectName()) != null;
  }

  @Override
  public Optional<TableSchema> getTableSchema(
      final SessionInfo session, final QualifiedObjectName name) {
    final String databaseName = name.getDatabaseName();
    final String tableName = name.getObjectName();

    final TsTable table = tableCache.getTable(databaseName, tableName);
    if (table == null) {
      return Optional.empty();
    }
    final List<ColumnSchema> columnSchemaList =
        table.getColumnList().stream()
            .map(
                o -> {
                  final ColumnSchema schema =
                      new ColumnSchema(
                          o.getColumnName(),
                          TypeFactory.getType(o.getDataType()),
                          false,
                          o.getColumnCategory());
                  schema.setProps(o.getProps());
                  return schema;
                })
            .collect(Collectors.toList());
    return Optional.of(
        TreeViewSchema.isTreeViewTable(table)
            ? new TreeDeviceViewSchema(table.getTableName(), columnSchemaList, table.getProps())
            : new TableSchema(table.getTableName(), columnSchemaList));
  }

  @Override
  public Type getOperatorReturnType(OperatorType operatorType, List<? extends Type> argumentTypes)
      throws OperatorNotFoundException {

    switch (operatorType) {
      case ADD:
        if (!isTwoTypeCalculable(argumentTypes)
            || !AdditionResolver.checkConditions(argumentTypes).isPresent()) {
          throw new OperatorNotFoundException(
              operatorType,
              argumentTypes,
              new IllegalArgumentException("Should have two numeric operands."));
        }
        return AdditionResolver.checkConditions(argumentTypes).get();
      case SUBTRACT:
        if (!isTwoTypeCalculable(argumentTypes)
            || !SubtractionResolver.checkConditions(argumentTypes).isPresent()) {
          throw new OperatorNotFoundException(
              operatorType,
              argumentTypes,
              new IllegalArgumentException("Should have two numeric operands."));
        }
        return SubtractionResolver.checkConditions(argumentTypes).get();
      case MULTIPLY:
        if (!isTwoTypeCalculable(argumentTypes)
            || !MultiplicationResolver.checkConditions(argumentTypes).isPresent()) {
          throw new OperatorNotFoundException(
              operatorType,
              argumentTypes,
              new IllegalArgumentException("Should have two numeric operands."));
        }
        return MultiplicationResolver.checkConditions(argumentTypes).get();
      case DIVIDE:
        if (!isTwoTypeCalculable(argumentTypes)
            || !DivisionResolver.checkConditions(argumentTypes).isPresent()) {
          throw new OperatorNotFoundException(
              operatorType,
              argumentTypes,
              new IllegalArgumentException("Should have two numeric operands."));
        }
        return DivisionResolver.checkConditions(argumentTypes).get();
      case MODULUS:
        if (!isTwoTypeCalculable(argumentTypes)
            || !ModulusResolver.checkConditions(argumentTypes).isPresent()) {
          throw new OperatorNotFoundException(
              operatorType,
              argumentTypes,
              new IllegalArgumentException("Should have two numeric operands."));
        }
        return ModulusResolver.checkConditions(argumentTypes).get();
      case NEGATION:
        if (!isOneNumericType(argumentTypes) && !isTimestampType(argumentTypes.get(0))) {
          throw new OperatorNotFoundException(
              operatorType,
              argumentTypes,
              new IllegalArgumentException("Should have one numeric operands."));
        }
        return argumentTypes.get(0);
      case EQUAL:
      case LESS_THAN:
      case LESS_THAN_OR_EQUAL:
        if (!isTwoTypeComparable(argumentTypes)) {
          throw new OperatorNotFoundException(
              operatorType,
              argumentTypes,
              new IllegalArgumentException("Should have two comparable operands."));
        }
        return BOOLEAN;
      default:
        throw new OperatorNotFoundException(
            operatorType, argumentTypes, new UnsupportedOperationException());
    }
  }

  @Override
  public Type getFunctionReturnType(String functionName, List<? extends Type> argumentTypes) {
    return getFunctionType(functionName, argumentTypes);
  }

  public static Type getFunctionType(String functionName, List<? extends Type> argumentTypes) {

    // builtin scalar function
    if (TableBuiltinScalarFunction.DIFF.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!isOneNumericType(argumentTypes)
          && !(argumentTypes.size() == 2
              && isNumericType(argumentTypes.get(0))
              && BOOLEAN.equals(argumentTypes.get(1)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only supports one numeric data types [INT32, INT64, FLOAT, DOUBLE] and one boolean");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.ROUND.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!isOneSupportedMathNumericType(argumentTypes)
          && !isTwoSupportedMathNumericType(argumentTypes)) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only supports two numeric data types [INT32, INT64, FLOAT, DOUBLE]");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.REPLACE
        .getFunctionName()
        .equalsIgnoreCase(functionName)) {

      if (!isTwoCharType(argumentTypes) && !isThreeCharType(argumentTypes)) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts two or three arguments and they must be text or string data type.");
      }
      return STRING;
    } else if (TableBuiltinScalarFunction.SUBSTRING
        .getFunctionName()
        .equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 2
              && isCharType(argumentTypes.get(0))
              && isIntegerNumber(argumentTypes.get(1)))
          && !(argumentTypes.size() == 3
              && isCharType(argumentTypes.get(0))
              && isIntegerNumber(argumentTypes.get(1))
              && isIntegerNumber(argumentTypes.get(2)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts two or three arguments and first must be text or string data type, second and third must be numeric data types [INT32, INT64]");
      }
      return STRING;
    } else if (TableBuiltinScalarFunction.LENGTH.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isCharType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be text or string data type.");
      }
      return INT32;
    } else if (TableBuiltinScalarFunction.UPPER.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isCharType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be text or string data type.");
      }
      return STRING;
    } else if (TableBuiltinScalarFunction.LOWER.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isCharType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be text or string data type.");
      }
      return STRING;
    } else if (TableBuiltinScalarFunction.TRIM.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isCharType(argumentTypes.get(0)))
          && !(argumentTypes.size() == 2 && isTwoCharType(argumentTypes))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one or two arguments and they must be text or string data type.");
      }
      return STRING;
    } else if (TableBuiltinScalarFunction.LTRIM.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isCharType(argumentTypes.get(0)))
          && !(argumentTypes.size() == 2 && isTwoCharType(argumentTypes))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one or two arguments and they must be text or string data type.");
      }
      return STRING;
    } else if (TableBuiltinScalarFunction.RTRIM.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isCharType(argumentTypes.get(0)))
          && !(argumentTypes.size() == 2 && isTwoCharType(argumentTypes))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one or two arguments and they must be text or string data type.");
      }
      return STRING;
    } else if (TableBuiltinScalarFunction.REGEXP_LIKE
        .getFunctionName()
        .equalsIgnoreCase(functionName)) {
      if (!isTwoCharType(argumentTypes)) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts two arguments and they must be text or string data type.");
      }
      return BOOLEAN;
    } else if (TableBuiltinScalarFunction.STRPOS.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!isTwoCharType(argumentTypes)) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts two arguments and they must be text or string data type.");
      }
      return INT32;
    } else if (TableBuiltinScalarFunction.STARTS_WITH
        .getFunctionName()
        .equalsIgnoreCase(functionName)) {
      if (!isTwoCharType(argumentTypes)) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts two arguments and they must be text or string data type.");
      }
      return BOOLEAN;
    } else if (TableBuiltinScalarFunction.ENDS_WITH
        .getFunctionName()
        .equalsIgnoreCase(functionName)) {
      if (!isTwoCharType(argumentTypes)) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts two arguments and they must be text or string data type.");
      }
      return BOOLEAN;
    } else if (TableBuiltinScalarFunction.CONCAT.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() >= 2
          && argumentTypes.stream().allMatch(TableMetadataImpl::isCharType))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts two or more arguments and they must be text or string data type.");
      }
      return STRING;
    } else if (TableBuiltinScalarFunction.STRCMP.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!isTwoCharType(argumentTypes)) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts two arguments and they must be text or string data type.");
      }
      return INT32;
    } else if (TableBuiltinScalarFunction.SIN.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.COS.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.TAN.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.ASIN.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.ACOS.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.ATAN.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.SINH.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.COSH.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.TANH.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.DEGREES
        .getFunctionName()
        .equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.RADIANS
        .getFunctionName()
        .equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.ABS.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return argumentTypes.get(0);
    } else if (TableBuiltinScalarFunction.SIGN.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return argumentTypes.get(0);
    } else if (TableBuiltinScalarFunction.CEIL.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.FLOOR.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.EXP.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.LN.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.LOG10.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.SQRT.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0)))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts one argument and it must be Double, Float, Int32 or Int64 data type.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.PI.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.isEmpty())) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " accepts no argument.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.E.getFunctionName().equalsIgnoreCase(functionName)) {
      if (!(argumentTypes.isEmpty())) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " accepts no argument.");
      }
      return DOUBLE;
    } else if (TableBuiltinScalarFunction.DATE_BIN
        .getFunctionName()
        .equalsIgnoreCase(functionName)) {
      if (!isTimestampType(argumentTypes.get(2))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " only accepts two or three arguments and the second and third must be TimeStamp data type.");
      }
      return TIMESTAMP;
    } else if (TableBuiltinScalarFunction.FORMAT.getFunctionName().equalsIgnoreCase(functionName)) {
      if (argumentTypes.size() < 2 || !isCharType(argumentTypes.get(0))) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " must have at least two arguments, and first argument pattern must be TEXT or STRING type.");
      }
      return STRING;
    } else if (FAIL_FUNCTION_NAME.equalsIgnoreCase(functionName)) {
      return UNKNOWN;
    } else if (TableBuiltinScalarFunction.GREATEST.getFunctionName().equalsIgnoreCase(functionName)
        || TableBuiltinScalarFunction.LEAST.getFunctionName().equalsIgnoreCase(functionName)) {
      if (argumentTypes.size() < 2 || !areAllTypesSameAndComparable(argumentTypes)) {
        throw new SemanticException(
            "Scalar function "
                + functionName.toLowerCase(Locale.ENGLISH)
                + " must have at least two arguments, and all type must be the same.");
      }
      return argumentTypes.get(0);
    } else if (TableBuiltinScalarFunction.BIT_COUNT.getFunctionName().equalsIgnoreCase(functionName)
        || TableBuiltinScalarFunction.BITWISE_AND.getFunctionName().equalsIgnoreCase(functionName)
        || TableBuiltinScalarFunction.BITWISE_OR.getFunctionName().equalsIgnoreCase(functionName)
        || TableBuiltinScalarFunction.BITWISE_XOR
            .getFunctionName()
            .equalsIgnoreCase(functionName)) {
      if (argumentTypes.size() != 2
          || !(isIntegerNumber(argumentTypes.get(0)) && isIntegerNumber(argumentTypes.get(1)))) {
        throw new SemanticException(
            String.format(
                "Scalar function %s only accepts two arguments and they must be Int32 or Int64 data type.",
                functionName));
      }
      return INT64;
    } else if (TableBuiltinScalarFunction.BITWISE_NOT
        .getFunctionName()
        .equalsIgnoreCase(functionName)) {
      if (argumentTypes.size() != 1 || !isIntegerNumber(argumentTypes.get(0))) {
        throw new SemanticException(
            String.format(
                "Scalar function %s only accepts one argument and it must be Int32 or Int64 data type.",
                functionName));
      }
      return INT64;
    } else if (TableBuiltinScalarFunction.BITWISE_LEFT_SHIFT
            .getFunctionName()
            .equalsIgnoreCase(functionName)
        || TableBuiltinScalarFunction.BITWISE_RIGHT_SHIFT
            .getFunctionName()
            .equalsIgnoreCase(functionName)
        || TableBuiltinScalarFunction.BITWISE_RIGHT_SHIFT_ARITHMETIC
            .getFunctionName()
            .equalsIgnoreCase(functionName)) {
      if (argumentTypes.size() != 2
          || !(isIntegerNumber(argumentTypes.get(0)) && isIntegerNumber(argumentTypes.get(1)))) {
        throw new SemanticException(
            String.format(
                "Scalar function %s only accepts two arguments and they must be Int32 or Int64 data type.",
                functionName));
      }
      return argumentTypes.get(0);
    }

    // builtin aggregation function
    // check argument type
    switch (functionName.toLowerCase(Locale.ENGLISH)) {
      case SqlConstant.AVG:
      case SqlConstant.SUM:
      case SqlConstant.EXTREME:
      case SqlConstant.STDDEV:
      case SqlConstant.STDDEV_POP:
      case SqlConstant.STDDEV_SAMP:
      case SqlConstant.VARIANCE:
      case SqlConstant.VAR_POP:
      case SqlConstant.VAR_SAMP:
        if (argumentTypes.size() != 1) {
          throw new SemanticException(
              String.format(
                  "Aggregate functions [%s] should only have one argument", functionName));
        }

        if (!isSupportedMathNumericType(argumentTypes.get(0))) {
          throw new SemanticException(
              String.format(
                  "Aggregate functions [%s] only support numeric data types [INT32, INT64, FLOAT, DOUBLE]",
                  functionName));
        }
        break;
      case SqlConstant.MIN:
      case SqlConstant.MAX:
      case SqlConstant.MODE:
        if (argumentTypes.size() != 1) {
          throw new SemanticException(
              String.format(
                  "Aggregate functions [%s] should only have one argument", functionName));
        }
        break;
      case SqlConstant.COUNT_IF:
        if (argumentTypes.size() != 1 || !isBool(argumentTypes.get(0))) {
          throw new SemanticException(
              String.format(
                  "Aggregate functions [%s] should only have one boolean expression as argument",
                  functionName));
        }
        break;
      case SqlConstant.FIRST_AGGREGATION:
      case SqlConstant.LAST_AGGREGATION:
        if (argumentTypes.size() != 2) {
          throw new SemanticException(
              String.format(
                  "Aggregate functions [%s] should only have one or two arguments", functionName));
        } else if (!isTimestampType(argumentTypes.get(1))) {
          throw new SemanticException(
              String.format(
                  "Second argument of Aggregate functions [%s] should be orderable", functionName));
        }
        break;
      case SqlConstant.FIRST_BY_AGGREGATION:
      case SqlConstant.LAST_BY_AGGREGATION:
        if (argumentTypes.size() != 3) {
          throw new SemanticException(
              String.format(
                  "Aggregate functions [%s] should only have two or three arguments",
                  functionName));
        }
        break;
      case SqlConstant.MAX_BY:
      case SqlConstant.MIN_BY:
        if (argumentTypes.size() != 2) {
          throw new SemanticException(
              String.format(
                  "Aggregate functions [%s] should only have two arguments", functionName));
        } else if (!argumentTypes.get(1).isOrderable()) {
          throw new SemanticException(
              String.format(
                  "Second argument of Aggregate functions [%s] should be orderable", functionName));
        }

        break;
      case SqlConstant.APPROX_COUNT_DISTINCT:
        if (argumentTypes.size() != 1 && argumentTypes.size() != 2) {
          throw new SemanticException(
              String.format(
                  "Aggregate functions [%s] should only have two arguments", functionName));
        }

        if (argumentTypes.size() == 2 && !isSupportedMathNumericType(argumentTypes.get(1))) {
          throw new SemanticException(
              String.format(
                  "Second argument of Aggregate functions [%s] should be numberic type and do not use expression",
                  functionName));
        }
        break;
      case SqlConstant.APPROX_MOST_FREQUENT:
        if (argumentTypes.size() != 3) {
          throw new SemanticException(
              String.format(
                  "Aggregation functions [%s] should only have three arguments", functionName));
        }
        break;
      case SqlConstant.COUNT:
        break;
      default:
        // ignore
    }

    // get return type
    switch (functionName.toLowerCase(Locale.ENGLISH)) {
      case SqlConstant.COUNT:
      case SqlConstant.COUNT_ALL:
      case SqlConstant.COUNT_IF:
      case SqlConstant.APPROX_COUNT_DISTINCT:
        return INT64;
      case SqlConstant.FIRST_AGGREGATION:
      case SqlConstant.LAST_AGGREGATION:
      case SqlConstant.FIRST_BY_AGGREGATION:
      case SqlConstant.LAST_BY_AGGREGATION:
      case SqlConstant.EXTREME:
      case SqlConstant.MODE:
      case SqlConstant.MAX:
      case SqlConstant.MIN:
      case SqlConstant.MAX_BY:
      case SqlConstant.MIN_BY:
        return argumentTypes.get(0);
      case SqlConstant.AVG:
      case SqlConstant.SUM:
      case SqlConstant.STDDEV:
      case SqlConstant.STDDEV_POP:
      case SqlConstant.STDDEV_SAMP:
      case SqlConstant.VARIANCE:
      case SqlConstant.VAR_POP:
      case SqlConstant.VAR_SAMP:
        return DOUBLE;
      case SqlConstant.APPROX_MOST_FREQUENT:
        return STRING;
      default:
        // ignore
    }

    // builtin window function
    // check argument type
    switch (functionName.toLowerCase(Locale.ENGLISH)) {
      case SqlConstant.NTILE:
        if (argumentTypes.size() != 1) {
          throw new SemanticException(
              String.format("Window function [%s] should only have one argument", functionName));
        }
        break;
      case SqlConstant.NTH_VALUE:
        if (argumentTypes.size() != 2 || !isIntegerNumber(argumentTypes.get(1))) {
          throw new SemanticException(
              "Window function [nth_value] should only have two argument, and second argument must be integer type");
        }
        break;
      case SqlConstant.TABLE_FIRST_VALUE:
      case SqlConstant.TABLE_LAST_VALUE:
        if (argumentTypes.size() != 1) {
          throw new SemanticException(
              String.format("Window function [%s] should only have one argument", functionName));
        }
      case SqlConstant.LEAD:
      case SqlConstant.LAG:
        if (argumentTypes.isEmpty() || argumentTypes.size() > 3) {
          throw new SemanticException(
              String.format(
                  "Window function [%s] should only have one to three argument", functionName));
        }
        if (argumentTypes.size() >= 2 && !isIntegerNumber(argumentTypes.get(1))) {
          throw new SemanticException(
              String.format(
                  "Window function [%s]'s second argument must be integer type", functionName));
        }
        break;
      default:
        // ignore
    }

    // get return type
    switch (functionName.toLowerCase(Locale.ENGLISH)) {
      case SqlConstant.RANK:
      case SqlConstant.DENSE_RANK:
      case SqlConstant.ROW_NUMBER:
      case SqlConstant.NTILE:
        return INT64;
      case SqlConstant.PERCENT_RANK:
      case SqlConstant.CUME_DIST:
        return DOUBLE;
      case SqlConstant.TABLE_FIRST_VALUE:
      case SqlConstant.TABLE_LAST_VALUE:
      case SqlConstant.NTH_VALUE:
      case SqlConstant.LEAD:
      case SqlConstant.LAG:
        return argumentTypes.get(0);
      default:
        // ignore
    }

    // User-defined scalar function
    if (TableUDFUtils.isScalarFunction(functionName)) {
      ScalarFunction scalarFunction = TableUDFUtils.getScalarFunction(functionName);
      FunctionArguments functionArguments =
          new FunctionArguments(
              argumentTypes.stream()
                  .map(UDFDataTypeTransformer::transformReadTypeToUDFDataType)
                  .collect(Collectors.toList()),
              Collections.emptyMap());
      try {
        ScalarFunctionAnalysis scalarFunctionAnalysis = scalarFunction.analyze(functionArguments);
        return UDFDataTypeTransformer.transformUDFDataTypeToReadType(
            scalarFunctionAnalysis.getOutputDataType());
      } catch (Exception e) {
        throw new SemanticException("Invalid function parameters: " + e.getMessage());
      } finally {
        scalarFunction.beforeDestroy();
      }
    } else if (TableUDFUtils.isAggregateFunction(functionName)) {
      AggregateFunction aggregateFunction = TableUDFUtils.getAggregateFunction(functionName);
      FunctionArguments functionArguments =
          new FunctionArguments(
              argumentTypes.stream()
                  .map(UDFDataTypeTransformer::transformReadTypeToUDFDataType)
                  .collect(Collectors.toList()),
              Collections.emptyMap());
      try {
        AggregateFunctionAnalysis aggregateFunctionAnalysis =
            aggregateFunction.analyze(functionArguments);
        return UDFDataTypeTransformer.transformUDFDataTypeToReadType(
            aggregateFunctionAnalysis.getOutputDataType());
      } catch (Exception e) {
        throw new SemanticException("Invalid function parameters: " + e.getMessage());
      } finally {
        aggregateFunction.beforeDestroy();
      }
    }

    throw new SemanticException("Unknown function: " + functionName);
  }

  @Override
  public boolean isAggregationFunction(
      final SessionInfo session, final String functionName, final AccessControl accessControl) {
    return TableBuiltinAggregationFunction.getBuiltInAggregateFunctionName()
            .contains(functionName.toLowerCase(Locale.ENGLISH))
        || TableUDFUtils.isAggregateFunction(functionName);
  }

  @Override
  public Type getType(final TypeSignature signature) throws TypeNotFoundException {
    return typeManager.getType(signature);
  }

  @Override
  public boolean canCoerce(final Type from, final Type to) {
    return true;
  }

  @Override
  public IPartitionFetcher getPartitionFetcher() {
    return ClusterPartitionFetcher.getInstance();
  }

  @Override
  public Map<String, List<DeviceEntry>> indexScan(
      final QualifiedObjectName tableName,
      final List<Expression> expressionList,
      final List<String> attributeColumns,
      final MPPQueryContext context) {
    return TableDeviceSchemaFetcher.getInstance()
        .fetchDeviceSchemaForDataQuery(
            tableName.getDatabaseName(),
            tableName.getObjectName(),
            expressionList,
            attributeColumns,
            context);
  }

  @Override
  public Optional<TableSchema> validateTableHeaderSchema(
      String database,
      TableSchema tableSchema,
      MPPQueryContext context,
      boolean allowCreateTable,
      boolean isStrictTagColumn)
      throws LoadAnalyzeTableColumnDisorderException {
    return TableHeaderSchemaValidator.getInstance()
        .validateTableHeaderSchema(
            database, tableSchema, context, allowCreateTable, isStrictTagColumn);
  }

  @Override
  public void validateDeviceSchema(
      ITableDeviceSchemaValidation schemaValidation, MPPQueryContext context) {
    TableDeviceSchemaValidator.getInstance().validateDeviceSchema(schemaValidation, context);
  }

  @Override
  public DataPartition getOrCreateDataPartition(
      final List<DataPartitionQueryParam> dataPartitionQueryParams, final String userName) {
    return partitionFetcher.getOrCreateDataPartition(dataPartitionQueryParams, userName);
  }

  @Override
  public SchemaPartition getOrCreateSchemaPartition(
      final String database, final List<IDeviceID> deviceIDList, final String userName) {
    return partitionFetcher.getOrCreateSchemaPartition(database, deviceIDList, userName);
  }

  @Override
  public SchemaPartition getSchemaPartition(
      final String database, final List<IDeviceID> deviceIDList) {
    return partitionFetcher.getSchemaPartition(database, deviceIDList);
  }

  @Override
  public SchemaPartition getSchemaPartition(final String database) {
    return partitionFetcher.getSchemaPartition(database, null);
  }

  @Override
  public DataPartition getDataPartition(
      String database, List<DataPartitionQueryParam> sgNameToQueryParamsMap) {
    return partitionFetcher.getDataPartition(
        Collections.singletonMap(database, sgNameToQueryParamsMap));
  }

  @Override
  public DataPartition getDataPartitionWithUnclosedTimeRange(
      String database, List<DataPartitionQueryParam> sgNameToQueryParamsMap) {
    return partitionFetcher.getDataPartitionWithUnclosedTimeRange(
        Collections.singletonMap(database, sgNameToQueryParamsMap));
  }

  @Override
  public TableFunction getTableFunction(String functionName) {
    if (TableBuiltinTableFunction.isBuiltInTableFunction(functionName)) {
      return TableBuiltinTableFunction.getBuiltinTableFunction(functionName);
    } else if (TableUDFUtils.isTableFunction(functionName)) {
      return TableUDFUtils.getTableFunction(functionName);
    } else {
      throw new SemanticException("Unknown function: " + functionName);
    }
  }

  @Override
  public IModelFetcher getModelFetcher() {
    return modelFetcher;
  }

  public static boolean isTwoNumericType(List<? extends Type> argumentTypes) {
    return argumentTypes.size() == 2
        && isNumericType(argumentTypes.get(0))
        && isNumericType(argumentTypes.get(1));
  }

  public static boolean isOneNumericType(List<? extends Type> argumentTypes) {
    return argumentTypes.size() == 1 && isNumericType(argumentTypes.get(0));
  }

  public static boolean isTwoSupportedMathNumericType(List<? extends Type> argumentTypes) {
    return argumentTypes.size() == 2
        && isSupportedMathNumericType(argumentTypes.get(0))
        && isSupportedMathNumericType(argumentTypes.get(1));
  }

  public static boolean isOneSupportedMathNumericType(List<? extends Type> argumentTypes) {
    return argumentTypes.size() == 1 && isSupportedMathNumericType(argumentTypes.get(0));
  }

  public static boolean isOneBooleanType(List<? extends Type> argumentTypes) {
    return argumentTypes.size() == 1 && BOOLEAN.equals(argumentTypes.get(0));
  }

  public static boolean isOneCharType(List<? extends Type> argumentTypes) {
    return argumentTypes.size() == 1 && isCharType(argumentTypes.get(0));
  }

  public static boolean isTwoCharType(List<? extends Type> argumentTypes) {
    return argumentTypes.size() == 2
        && isCharType(argumentTypes.get(0))
        && isCharType(argumentTypes.get(1));
  }

  public static boolean isThreeCharType(List<? extends Type> argumentTypes) {
    return argumentTypes.size() == 3
        && isCharType(argumentTypes.get(0))
        && isCharType(argumentTypes.get(1))
        && isCharType(argumentTypes.get(2));
  }

  public static boolean isCharType(Type type) {
    return TEXT.equals(type) || StringType.STRING.equals(type);
  }

  public static boolean isBlobType(Type type) {
    return BlobType.BLOB.equals(type);
  }

  public static boolean isBool(Type type) {
    return BOOLEAN.equals(type);
  }

  public static boolean isSupportedMathNumericType(Type type) {
    return DOUBLE.equals(type) || FLOAT.equals(type) || INT32.equals(type) || INT64.equals(type);
  }

  public static boolean isNumericType(Type type) {
    return DOUBLE.equals(type)
        || FLOAT.equals(type)
        || INT32.equals(type)
        || INT64.equals(type)
        || TIMESTAMP.equals(type);
  }

  public static boolean isTimestampType(Type type) {
    return TIMESTAMP.equals(type);
  }

  public static boolean isUnknownType(Type type) {
    return UNKNOWN.equals(type);
  }

  public static boolean isIntegerNumber(Type type) {
    return INT32.equals(type) || INT64.equals(type);
  }

  public static boolean isTwoTypeComparable(List<? extends Type> argumentTypes) {
    if (argumentTypes.size() != 2) {
      return false;
    }
    Type left = argumentTypes.get(0);
    Type right = argumentTypes.get(1);
    if (left.equals(right)) {
      return true;
    }

    // Boolean type and Binary Type can not be compared with other types
    return (isNumericType(left) && isNumericType(right))
        || (isCharType(left) && isCharType(right))
        || (isUnknownType(left) && (isNumericType(right) || isCharType(right)))
        || ((isNumericType(left) || isCharType(left)) && isUnknownType(right));
  }

  public static boolean areAllTypesSameAndComparable(List<? extends Type> argumentTypes) {
    if (argumentTypes == null || argumentTypes.isEmpty()) {
      return true;
    }
    Type firstType = argumentTypes.get(0);
    if (!firstType.isComparable()) {
      return false;
    }
    return argumentTypes.stream().allMatch(type -> type.equals(firstType));
  }

  public static boolean isArithmeticType(Type type) {
    return INT32.equals(type)
        || INT64.equals(type)
        || FLOAT.equals(type)
        || DOUBLE.equals(type)
        || DATE.equals(type)
        || TIMESTAMP.equals(type);
  }

  public static boolean isTwoTypeCalculable(List<? extends Type> argumentTypes) {
    if (argumentTypes.size() != 2) {
      return false;
    }
    Type left = argumentTypes.get(0);
    Type right = argumentTypes.get(1);
    if ((isUnknownType(left) && isArithmeticType(right))
        || (isUnknownType(right) && isArithmeticType(left))) {
      return true;
    }
    return isArithmeticType(left) && isArithmeticType(right);
  }

  public static void throwTableNotExistsException(final String database, final String tableName) {
    throw new SemanticException(new TableNotExistsException(database, tableName));
  }

  public static void throwColumnNotExistsException(final Object columnName) {
    throw new SemanticException(
        new IoTDBException(
            String.format("Column '%s' cannot be resolved.", columnName),
            TSStatusCode.COLUMN_NOT_EXISTS.getStatusCode()));
  }
}
