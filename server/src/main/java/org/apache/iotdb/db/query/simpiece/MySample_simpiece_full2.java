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

// Sim-Piece code forked from https://github.com/xkitsios/Sim-Piece.git

package org.apache.iotdb.db.query.simpiece;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.List;

public class MySample_simpiece_full2 {
  // After running this,
  // output sample csv and copy them into lts-exp/notebook/segmentResults/.
  // output epsilonArray_*.txt and copy them into lts-exp/tools/.

  public static void main(String[] args) {
    String fileDir = "D:\\desktop\\NISTPV\\";
    // DO NOT change the order of datasets below, as the output is used in exp bash!!!!
    String[] datasetNameList = new String[]{"WindSpeed", "Qloss", "Pyra1", "RTD"};
    int[] noutList =
        new int[]{
            320, 480, 740, 1200, 2000, 3500, 6000, 10000, 15000
        };

    double[][] epsilonArray = {
        {
            10.470000000000255, 9.123788145707294, 7.994484412469319, 7.083431952662977,
            6.40327455919396, 5.796425722036474, 5.25,
            4.733333333332666, 4.316411238825822,
        },
        {
            9.988254314521328E-4, 9.232312522726716E-4, 6.904453093738994E-4, 6.310499056780827E-4,
            5.405860865721479E-4, 4.99947028401948E-4, 8.470329472543003E-22, 8.470329472543003E-22,
            4.2351647362715017E-22,
        },
        {
            500.25713642584833, 462.5099852368403, 409.55148724009814, 366.5155046623204,
            308.34700983663697, 245.95551399070246, 189.4996857321512, 138.35885847007285,
            102.23582262045238,
        },
        {
            13.63730886850226, 9.725488211197444, 6.950157068063163, 4.7039837191559855,
            2.9906666666674937, 1.8915555555558967, 1.20482591549262, 0.7761992380646916,
            0.5509243697479178,
        }
    };

//    double[][] epsilonArray = new double[datasetNameList.length][];
//    for (int i = 0; i < datasetNameList.length; i++) {
//      epsilonArray[i] = new double[noutList.length];
//    }

    for (int y = 0; y < datasetNameList.length; y++) {
      String datasetName = datasetNameList[y];
      int start = 0;
      int end = 1000_0000;
      int N = end - start;
      // apply Sim-Piece on the input file, outputting nout points saved in csvFile
      boolean hasHeader = false;
      try (FileInputStream inputStream = new FileInputStream(fileDir + datasetName + ".csv")) {
        String delimiter = ",";
        TimeSeries ts =
            TimeSeriesReader.getMyTimeSeries(
                inputStream, delimiter, false, N, start, hasHeader, true);
        for (int x = 0; x < noutList.length; x++) {
          int nout = noutList[x];

//          double epsilon = MySample_simpiece2.getSimPieceParam(nout, ts, 1e-12);
//          epsilonArray[y][x] = epsilon;

          double epsilon = epsilonArray[y][x];

          SimPiece simPiece = new SimPiece(ts.data, epsilon);
          System.out.println(
              datasetName
                  + ": n="
                  + N
                  + ",m="
                  + nout
                  + ",epsilon="
                  + epsilon
                  + ",actual m="
                  + simPiece.segments.size() * 2);
          List<SimPieceSegment> segments = simPiece.segments;
          segments.sort(Comparator.comparingLong(SimPieceSegment::getInitTimestamp));
          try (PrintWriter writer =
              new PrintWriter(
                  new FileWriter(
                      datasetName
                          + "-"
                          + N
                          + "-"
                          + nout
                          + "-"
                          + segments.size() * 2
                          + "-simpiece.csv"))) {
            for (int i = 0; i < segments.size() - 1; i++) {
              // start point of this segment
              writer.println(segments.get(i).getInitTimestamp() + "," + segments.get(i).getB());
              // end point of this segment
              double v =
                  (segments.get(i + 1).getInitTimestamp() - segments.get(i).getInitTimestamp())
                      * segments.get(i).getA()
                      + segments.get(i).getB();
              writer.println(segments.get(i + 1).getInitTimestamp() + "," + v);
            }
            // the two end points of the last segment
            writer.println(
                segments.get(segments.size() - 1).getInitTimestamp()
                    + ","
                    + segments.get(segments.size() - 1).getB());
            double v =
                (simPiece.lastTimeStamp - segments.get(segments.size() - 1).getInitTimestamp())
                    * segments.get(segments.size() - 1).getA()
                    + segments.get(segments.size() - 1).getB();
            writer.println(simPiece.lastTimeStamp + "," + v);
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    //    for (int i = 0; i < epsilonArray.length; i++) { // 遍历行
    //      for (int j = 0; j < epsilonArray[i].length; j++) { // 遍历列
    //        System.out.print(epsilonArray[i][j] + ",");
    //      }
    //      System.out.println();
    //    }

    // do not change name of the output file, as the output is used in exp bash
    try (FileWriter writer = new FileWriter("epsilonArray_simpiece.txt")) {
      for (double[] row : epsilonArray) {
        for (double element : row) {
          writer.write(element + " ");
          System.out.print(element + ",");
        }
        writer.write("\n");
        System.out.println();
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  //  public static double getSimPieceParam(int nout, TimeSeries ts, double accuracy)
  //      throws IOException {
  //    double epsilon = 1;
  //    boolean directLess = false;
  //    boolean directMore = false;
  //    boolean skip = false;
  //    int threshold = 2;
  //    while (true) {
  //      SimPiece simPiece = new SimPiece(ts.data, epsilon);
  //      if (simPiece.segments.size() * 2 > nout) { // note *2 for disjoint
  //        if (directMore) {
  //          if (Math.abs(simPiece.segments.size() * 2 - nout) <= threshold) {
  //            skip = true;
  //          }
  //          break;
  //        }
  //        if (!directLess) {
  //          directLess = true;
  //        }
  //        epsilon *= 2;
  //      } else {
  //        if (directLess) {
  //          if (Math.abs(nout - simPiece.segments.size() * 2) <= threshold) {
  //            skip = true;
  //          }
  //          break;
  //        }
  //        if (!directMore) {
  //          directMore = true;
  //        }
  //        epsilon /= 2;
  //      }
  //    }
  //    if (skip) {
  //      return epsilon;
  //    }
  //
  //    // begin dichotomy
  //    double left = 0;
  //    double right = 0;
  //    if (directLess) {
  //      left = epsilon / 2;
  //      right = epsilon;
  //    }
  //    if (directMore) {
  //      left = epsilon;
  //      right = epsilon * 2;
  //    }
  //    while (Math.abs(right - left) > accuracy) {
  //      double mid = (left + right) / 2;
  //      SimPiece simPiece = new SimPiece(ts.data, mid);
  //      if (simPiece.segments.size() * 2 > nout) { // note *2 for disjoint
  //        left = mid;
  //      } else {
  //        right = mid;
  //      }
  //    }
  //    SimPiece simPiece = new SimPiece(ts.data, left);
  //    int n1 = simPiece.segments.size() * 2;
  //    simPiece = new SimPiece(ts.data, right);
  //    int n2 = simPiece.segments.size() * 2;
  //    if (Math.abs(n1 - nout) < Math.abs(n2 - nout)) {
  //      return left;
  //    } else {
  //      return right;
  //    }
  //  }
}
