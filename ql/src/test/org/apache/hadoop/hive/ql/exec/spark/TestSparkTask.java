/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.exec.spark;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.apache.hadoop.hive.common.metrics.common.Metrics;
import org.apache.hadoop.hive.common.metrics.common.MetricsConstant;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.exec.spark.status.RemoteSparkJobMonitor;
import org.apache.hadoop.hive.ql.exec.spark.status.impl.RemoteSparkJobStatus;
import org.apache.hadoop.hive.ql.plan.SparkWork;
import org.apache.hive.spark.client.JobHandle.State;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class TestSparkTask {

  @Test
  public void sparkTask_updates_Metrics() throws IOException {

    Metrics mockMetrics = Mockito.mock(Metrics.class);

    SparkTask sparkTask = new SparkTask();
    sparkTask.updateTaskMetrics(mockMetrics);

    verify(mockMetrics, times(1)).incrementCounter(MetricsConstant.HIVE_SPARK_TASKS);
    verify(mockMetrics, never()).incrementCounter(MetricsConstant.HIVE_TEZ_TASKS);
    verify(mockMetrics, never()).incrementCounter(MetricsConstant.HIVE_MR_TASKS);
  }

  @Test
  public void testRemoteSparkCancel() {
    RemoteSparkJobStatus jobSts = Mockito.mock(RemoteSparkJobStatus.class);
    when(jobSts.getRemoteJobState()).thenReturn(State.CANCELLED);
    //when(jobSts.isRemoteActive()).thenReturn(true);
    HiveConf hiveConf = new HiveConf();
    RemoteSparkJobMonitor remoteSparkJobMonitor = new RemoteSparkJobMonitor(hiveConf, jobSts);
    Assert.assertEquals(remoteSparkJobMonitor.startMonitor(), 3);
  }
}
