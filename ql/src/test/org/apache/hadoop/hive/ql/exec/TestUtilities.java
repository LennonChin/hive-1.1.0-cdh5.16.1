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

package org.apache.hadoop.hive.ql.exec;

import static org.apache.hadoop.hive.ql.exec.Utilities.getFileExtension;
import static org.apache.hadoop.hive.ql.exec.Utilities.DEPRECATED_MAPRED_DFSCLIENT_PARALLELISM_MAX;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.io.*;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.InputEstimator;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.plan.*;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFFromUtcTimestamp;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RecordReader;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;


public class TestUtilities {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final int NUM_BUCKETS = 3;

  public static final Log LOG = LogFactory.getLog(TestUtilities.class);

  @Test
  public void testGetFileExtension() {
    JobConf jc = new JobConf();
    assertEquals("No extension for uncompressed unknown format", "",
        getFileExtension(jc, false, null));
    assertEquals("No extension for compressed unknown format", "",
        getFileExtension(jc, true, null));
    assertEquals("No extension for uncompressed text format", "",
        getFileExtension(jc, false, new HiveIgnoreKeyTextOutputFormat()));
    assertEquals("Deflate for uncompressed text format", ".deflate",
        getFileExtension(jc, true, new HiveIgnoreKeyTextOutputFormat()));
    assertEquals("No extension for uncompressed default format", "",
        getFileExtension(jc, false));
    assertEquals("Deflate for uncompressed default format", ".deflate",
        getFileExtension(jc, true));

    String extension = ".myext";
    jc.set("hive.output.file.extension", extension);
    assertEquals("Custom extension for uncompressed unknown format", extension,
        getFileExtension(jc, false, null));
    assertEquals("Custom extension for compressed unknown format", extension,
        getFileExtension(jc, true, null));
    assertEquals("Custom extension for uncompressed text format", extension,
        getFileExtension(jc, false, new HiveIgnoreKeyTextOutputFormat()));
    assertEquals("Custom extension for uncompressed text format", extension,
        getFileExtension(jc, true, new HiveIgnoreKeyTextOutputFormat()));
  }

  @Test
  public void testSerializeTimestamp() {
    Timestamp ts = new Timestamp(1374554702000L);
    ts.setNanos(123456);
    ExprNodeConstantDesc constant = new ExprNodeConstantDesc(ts);
    List<ExprNodeDesc> children = new ArrayList<ExprNodeDesc>(1);
    children.add(constant);
    ExprNodeGenericFuncDesc desc = new ExprNodeGenericFuncDesc(TypeInfoFactory.timestampTypeInfo,
        new GenericUDFFromUtcTimestamp(), children);
    assertEquals(desc.getExprString(), Utilities.deserializeExpression(
        Utilities.serializeExpression(desc)).getExprString());
  }

  @Test
  public void testgetDbTableName() throws HiveException{
    String tablename;
    String [] dbtab;
    SessionState.start(new HiveConf(this.getClass()));
    String curDefaultdb = SessionState.get().getCurrentDatabase();

    //test table without db portion
    tablename = "tab1";
    dbtab = Utilities.getDbTableName(tablename);
    assertEquals("db name", curDefaultdb, dbtab[0]);
    assertEquals("table name", tablename, dbtab[1]);

    //test table with db portion
    tablename = "dab1.tab1";
    dbtab = Utilities.getDbTableName(tablename);
    assertEquals("db name", "dab1", dbtab[0]);
    assertEquals("table name", "tab1", dbtab[1]);

    //test invalid table name
    tablename = "dab1.tab1.x1";
    try {
      dbtab = Utilities.getDbTableName(tablename);
      fail("exception was expected for invalid table name");
    } catch(HiveException ex){
      assertEquals("Invalid table name " + tablename, ex.getMessage());
    }
  }

  /**
   * Check that calling {@link Utilities#getInputPaths(JobConf, MapWork, Path, Context, boolean)}
   * can process two different empty tables without throwing any exceptions.
   */
  @Test
  public void testGetInputPathsWithEmptyTables() throws Exception {
    String alias1Name = "alias1";
    String alias2Name = "alias2";

    MapWork mapWork1 = new MapWork();
    MapWork mapWork2 = new MapWork();
    JobConf jobConf = new JobConf();

    String nonExistentPath1 = UUID.randomUUID().toString();
    String nonExistentPath2 = UUID.randomUUID().toString();

    PartitionDesc mockPartitionDesc = mock(PartitionDesc.class);
    TableDesc mockTableDesc = mock(TableDesc.class);

    when(mockTableDesc.isNonNative()).thenReturn(false);
    when(mockTableDesc.getProperties()).thenReturn(new Properties());

    when(mockPartitionDesc.getProperties()).thenReturn(new Properties());
    when(mockPartitionDesc.getTableDesc()).thenReturn(mockTableDesc);
    doReturn(HiveSequenceFileOutputFormat.class).when(
            mockPartitionDesc).getOutputFileFormatClass();

    mapWork1.setPathToAliases(new LinkedHashMap<>(
            ImmutableMap.of(nonExistentPath1, Lists.newArrayList(alias1Name))));
    mapWork1.setAliasToWork(new LinkedHashMap<String, Operator<? extends OperatorDesc>>(
            ImmutableMap.of(alias1Name, (Operator<?>) mock(Operator.class))));
    mapWork1.setPathToPartitionInfo(new LinkedHashMap<>(
            ImmutableMap.of(nonExistentPath1, mockPartitionDesc)));

    mapWork2.setPathToAliases(new LinkedHashMap<>(
            ImmutableMap.of(nonExistentPath2, Lists.newArrayList(alias2Name))));
    mapWork2.setAliasToWork(new LinkedHashMap<String, Operator<? extends OperatorDesc>>(
            ImmutableMap.of(alias2Name, (Operator<?>) mock(Operator.class))));
    mapWork2.setPathToPartitionInfo(new LinkedHashMap<>(
            ImmutableMap.of(nonExistentPath2, mockPartitionDesc)));

    List<Path> inputPaths = new ArrayList<>();
    try {
      Path scratchDir = new Path(HiveConf.getVar(jobConf, HiveConf.ConfVars.LOCALSCRATCHDIR));
      inputPaths.addAll(Utilities.getInputPaths(jobConf, mapWork1, scratchDir,
              mock(Context.class), false));
      inputPaths.addAll(Utilities.getInputPaths(jobConf, mapWork2, scratchDir,
              mock(Context.class), false));
      assertEquals(inputPaths.size(), 2);
    } finally {
      File file;
      for (Path path : inputPaths) {
        file = new File(path.toString());
        if (file.exists()) {
          file.delete();
        }
      }
    }
  }

  @Test
  public void testRemoveTempOrDuplicateFilesOnTezNoDp() throws Exception {
    List<Path> paths = runRemoveTempOrDuplicateFilesTestCase("tez", false);
    assertEquals(0, paths.size());
  }

  @Test
  public void testRemoveTempOrDuplicateFilesOnMrWithDp() throws Exception {
    List<Path> paths = runRemoveTempOrDuplicateFilesTestCase("mr", true);
    assertEquals(NUM_BUCKETS, paths.size());
  }

  private List<Path> runRemoveTempOrDuplicateFilesTestCase(String executionEngine, boolean dPEnabled)
      throws Exception {
    Configuration hconf = new HiveConf(this.getClass());
    // do this to verify that Utilities.removeTempOrDuplicateFiles does not revert to default scheme information
    hconf.set("fs.defaultFS", "hdfs://should-not-be-used/");
    hconf.set(HiveConf.ConfVars.HIVE_EXECUTION_ENGINE.varname, executionEngine);
    FileSystem localFs = FileSystem.getLocal(hconf);
    DynamicPartitionCtx dpCtx = getDynamicPartitionCtx(dPEnabled);
    Path tempDirPath = setupTempDirWithSingleOutputFile(hconf);
    FileSinkDesc conf = getFileSinkDesc(tempDirPath);

    List<Path> paths = Utilities.removeTempOrDuplicateFiles(localFs, tempDirPath, dpCtx);

    String expectedScheme = tempDirPath.toUri().getScheme();
    String expectedAuthority = tempDirPath.toUri().getAuthority();
    assertPathsMatchSchemeAndAuthority(expectedScheme, expectedAuthority, paths);

    return paths;
  }

  private void assertPathsMatchSchemeAndAuthority(String expectedScheme, String expectedAuthority, List<Path> paths) {
    for (Path path : paths) {
      assertEquals(path.toUri().getScheme().toLowerCase(), expectedScheme.toLowerCase());
      assertEquals(path.toUri().getAuthority(), expectedAuthority);
    }
  }

  private DynamicPartitionCtx getDynamicPartitionCtx(boolean dPEnabled) {
    DynamicPartitionCtx dpCtx = null;
    if (dPEnabled) {
      dpCtx = mock(DynamicPartitionCtx.class);
      when(dpCtx.getNumDPCols()).thenReturn(0);
      when(dpCtx.getNumBuckets()).thenReturn(NUM_BUCKETS);
    }
    return dpCtx;
  }

  private FileSinkDesc getFileSinkDesc(Path tempDirPath) {
    Table table = mock(Table.class);
    when(table.getNumBuckets()).thenReturn(NUM_BUCKETS);
    FileSinkDesc conf = new FileSinkDesc(tempDirPath, null, false);
    conf.setTable(table);
    return conf;
  }

  private Path setupTempDirWithSingleOutputFile(Configuration hconf) throws IOException {
    Path tempDirPath = new Path("file://" + temporaryFolder.newFolder().getAbsolutePath());
    Path taskOutputPath = new Path(tempDirPath, Utilities.getTaskId(hconf));
    FileSystem.getLocal(hconf).create(taskOutputPath).close();
    return tempDirPath;
  }

  /**
   * Check that calling {@link Utilities#getInputPaths(JobConf, MapWork, Path, Context, boolean)}
   * can process two different tables that both have empty partitions.
   */
  @Test
  public void testGetInputPathsWithEmptyPartitions() throws Exception {
    String alias1Name = "alias1";
    String alias2Name = "alias2";

    MapWork mapWork1 = new MapWork();
    MapWork mapWork2 = new MapWork();
    JobConf jobConf = new JobConf();
    Configuration conf = new Configuration();

    Path nonExistentPath1 = new Path(UUID.randomUUID().toString());
    Path nonExistentPath2 = new Path(UUID.randomUUID().toString());

    PartitionDesc mockPartitionDesc = mock(PartitionDesc.class);
    TableDesc mockTableDesc = mock(TableDesc.class);

    when(mockTableDesc.isNonNative()).thenReturn(false);
    when(mockTableDesc.getProperties()).thenReturn(new Properties());

    when(mockPartitionDesc.getProperties()).thenReturn(new Properties());
    when(mockPartitionDesc.getTableDesc()).thenReturn(mockTableDesc);
    doReturn(HiveSequenceFileOutputFormat.class).when(
            mockPartitionDesc).getOutputFileFormatClass();

    mapWork1.setPathToAliases(new LinkedHashMap<>(
            ImmutableMap.of(nonExistentPath1.toString(), Lists.newArrayList(alias1Name))));
    mapWork1.setAliasToWork(new LinkedHashMap<>(
            ImmutableMap.<String, Operator<? extends OperatorDesc>>of(alias1Name,
                    (Operator<? extends OperatorDesc>) mock(Operator.class))));
    mapWork1.setPathToPartitionInfo(new LinkedHashMap<>(
            ImmutableMap.of(nonExistentPath1.toString(), mockPartitionDesc)));

    mapWork2.setPathToAliases(new LinkedHashMap<>(
            ImmutableMap.of(nonExistentPath2.toString(), Lists.newArrayList(alias2Name))));
    mapWork2.setAliasToWork(new LinkedHashMap<>(
            ImmutableMap.<String, Operator<? extends OperatorDesc>>of(alias2Name,
                    (Operator<? extends OperatorDesc>) mock(Operator.class))));
    mapWork2.setPathToPartitionInfo(new LinkedHashMap<>(
            ImmutableMap.of(nonExistentPath2.toString(), mockPartitionDesc)));

    List<Path> inputPaths = new ArrayList<>();
    try {
      Path scratchDir = new Path(HiveConf.getVar(jobConf, HiveConf.ConfVars.LOCALSCRATCHDIR));

      List<Path> inputPaths1 = Utilities.getInputPaths(jobConf, mapWork1, scratchDir,
              mock(Context.class), false);
      inputPaths.addAll(inputPaths1);
      assertEquals(inputPaths1.size(), 1);
      assertNotEquals(inputPaths1.get(0), nonExistentPath1);
      assertTrue(inputPaths1.get(0).getFileSystem(conf).exists(inputPaths1.get(0)));
      assertFalse(nonExistentPath1.getFileSystem(conf).exists(nonExistentPath1));

      List<Path> inputPaths2 = Utilities.getInputPaths(jobConf, mapWork2, scratchDir,
              mock(Context.class), false);
      inputPaths.addAll(inputPaths2);
      assertEquals(inputPaths2.size(), 1);
      assertNotEquals(inputPaths2.get(0), nonExistentPath2);
      assertTrue(inputPaths2.get(0).getFileSystem(conf).exists(inputPaths2.get(0)));
      assertFalse(nonExistentPath2.getFileSystem(conf).exists(nonExistentPath2));
    } finally {
      File file;
      for (Path path : inputPaths) {
        file = new File(path.toString());
        if (file.exists()) {
          file.delete();
        }
      }
    }
  }

  /**
   * Check that calling {@link Utilities#getInputPaths(JobConf, MapWork, Path, Context, boolean)}
   * can process two different tables that both have empty partitions when using multiple threads.
   * Some extra logic is placed at the end of the test to validate no race conditions put the
   * {@link MapWork} object in an invalid state.
   */
  @Test
  public void testGetInputPathsWithMultipleThreadsAndEmptyPartitions() throws Exception {
    int numPartitions = 15;
    JobConf jobConf = new JobConf();
    jobConf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname,
            Runtime.getRuntime().availableProcessors() * 2);
    MapWork mapWork = new MapWork();
    Path testTablePath = new Path("testTable");
    Path[] testPartitionsPaths = new Path[numPartitions];

    PartitionDesc mockPartitionDesc = mock(PartitionDesc.class);
    TableDesc mockTableDesc = mock(TableDesc.class);

    when(mockTableDesc.isNonNative()).thenReturn(false);
    when(mockTableDesc.getProperties()).thenReturn(new Properties());
    when(mockPartitionDesc.getProperties()).thenReturn(new Properties());
    when(mockPartitionDesc.getTableDesc()).thenReturn(mockTableDesc);
    doReturn(HiveSequenceFileOutputFormat.class).when(
            mockPartitionDesc).getOutputFileFormatClass();


    for (int i = 0; i < numPartitions; i++) {
      String testPartitionName = "p=" + i;
      testPartitionsPaths[i] = new Path(testTablePath, "p=" + i);
      mapWork.getPathToAliases().put(testPartitionsPaths[i].toString(), Lists.newArrayList(testPartitionName));
      mapWork.getAliasToWork().put(testPartitionName, (Operator<?>) mock(Operator.class));
      mapWork.getPathToPartitionInfo().put(testPartitionsPaths[i].toString(), mockPartitionDesc);

    }

    FileSystem fs = FileSystem.getLocal(jobConf);

    try {
      fs.mkdirs(testTablePath);
      List<Path> inputPaths = Utilities.getInputPaths(jobConf, mapWork,
              new Path(HiveConf.getVar(jobConf, HiveConf.ConfVars.LOCALSCRATCHDIR)), mock(Context.class), false);
      assertEquals(inputPaths.size(), numPartitions);

      for (int i = 0; i < numPartitions; i++) {
        assertNotEquals(inputPaths.get(i), testPartitionsPaths[i]);
      }

      assertEquals(mapWork.getPathToAliases().size(), numPartitions);
      assertEquals(mapWork.getPathToPartitionInfo().size(), numPartitions);
      assertEquals(mapWork.getAliasToWork().size(), numPartitions);

      for (Map.Entry<String, ArrayList<String>> entry : mapWork.getPathToAliases().entrySet()) {
        assertNotNull(entry.getKey());
        assertNotNull(entry.getValue());
        assertEquals(entry.getValue().size(), 1);
        assertTrue(new Path(entry.getKey()).getFileSystem(new Configuration()).exists(new Path(entry.getKey())));
      }
    } finally {
      if (fs.exists(testTablePath)) {
        fs.delete(testTablePath, true);
      }
    }
  }

  /**
   * Check that calling {@link Utilities#getMaxExecutorsForInputListing(Configuration, int)}
   * returns the maximum number of executors to use based on the number of input locations.
   */
  @Test
  public void testGetMaxExecutorsForInputListing() {
    Configuration conf = new Configuration();

    final int ZERO_EXECUTORS = 0;
    final int ONE_EXECUTOR = 1;
    final int TWO_EXECUTORS = 2;

    final int ZERO_THREADS = 0;
    final int ONE_THREAD = 1;
    final int TWO_THREADS = 2;

    final int ZERO_LOCATIONS = 0;
    final int ONE_LOCATION = 1;
    final int TWO_LOCATIONS = 2;
    final int THREE_LOCATIONS = 3;

    conf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, ONE_THREAD);

    assertEquals(ZERO_EXECUTORS, Utilities.getMaxExecutorsForInputListing(conf, ZERO_LOCATIONS));
    assertEquals(ONE_EXECUTOR, Utilities.getMaxExecutorsForInputListing(conf, ONE_LOCATION));
    assertEquals(ONE_EXECUTOR, Utilities.getMaxExecutorsForInputListing(conf, TWO_LOCATIONS));
    assertEquals(ONE_EXECUTOR, Utilities.getMaxExecutorsForInputListing(conf, THREE_LOCATIONS));

    conf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, TWO_THREADS);

    assertEquals(ZERO_EXECUTORS,  Utilities.getMaxExecutorsForInputListing(conf, ZERO_LOCATIONS));
    assertEquals(ONE_EXECUTOR,  Utilities.getMaxExecutorsForInputListing(conf, ONE_LOCATION));
    assertEquals(TWO_EXECUTORS, Utilities.getMaxExecutorsForInputListing(conf, TWO_LOCATIONS));
    assertEquals(TWO_EXECUTORS, Utilities.getMaxExecutorsForInputListing(conf, THREE_LOCATIONS));

    /*
     * The following tests will verify the deprecation variable is still usable.
     */

    conf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, ZERO_THREADS);
    conf.setInt(DEPRECATED_MAPRED_DFSCLIENT_PARALLELISM_MAX, ZERO_THREADS);

    assertEquals(ZERO_EXECUTORS, Utilities.getMaxExecutorsForInputListing(conf, ZERO_LOCATIONS));
    assertEquals(ONE_EXECUTOR, Utilities.getMaxExecutorsForInputListing(conf, ONE_LOCATION));
    assertEquals(ONE_EXECUTOR, Utilities.getMaxExecutorsForInputListing(conf, TWO_LOCATIONS));
    assertEquals(ONE_EXECUTOR, Utilities.getMaxExecutorsForInputListing(conf, THREE_LOCATIONS));

    conf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, ZERO_THREADS);
    conf.setInt(DEPRECATED_MAPRED_DFSCLIENT_PARALLELISM_MAX, ONE_THREAD);

    assertEquals(ZERO_EXECUTORS, Utilities.getMaxExecutorsForInputListing(conf, ZERO_LOCATIONS));
    assertEquals(ONE_EXECUTOR, Utilities.getMaxExecutorsForInputListing(conf, ONE_LOCATION));
    assertEquals(ONE_EXECUTOR, Utilities.getMaxExecutorsForInputListing(conf, TWO_LOCATIONS));
    assertEquals(ONE_EXECUTOR, Utilities.getMaxExecutorsForInputListing(conf, THREE_LOCATIONS));

    conf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, ZERO_THREADS);
    conf.setInt(DEPRECATED_MAPRED_DFSCLIENT_PARALLELISM_MAX, TWO_THREADS);

    assertEquals(ZERO_EXECUTORS, Utilities.getMaxExecutorsForInputListing(conf, ZERO_LOCATIONS));
    assertEquals(ONE_EXECUTOR, Utilities.getMaxExecutorsForInputListing(conf, ONE_LOCATION));
    assertEquals(TWO_EXECUTORS, Utilities.getMaxExecutorsForInputListing(conf, TWO_LOCATIONS));
    assertEquals(TWO_EXECUTORS, Utilities.getMaxExecutorsForInputListing(conf, THREE_LOCATIONS));

    // Check that HIVE_EXEC_INPUT_LISTING_MAX_THREADS has priority overr DEPRECATED_MAPRED_DFSCLIENT_PARALLELISM_MAX

    conf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, TWO_THREADS);
    conf.setInt(DEPRECATED_MAPRED_DFSCLIENT_PARALLELISM_MAX, ONE_THREAD);

    assertEquals(ZERO_EXECUTORS, Utilities.getMaxExecutorsForInputListing(conf, ZERO_LOCATIONS));
    assertEquals(ONE_EXECUTOR, Utilities.getMaxExecutorsForInputListing(conf, ONE_LOCATION));
    assertEquals(TWO_EXECUTORS, Utilities.getMaxExecutorsForInputListing(conf, TWO_LOCATIONS));
    assertEquals(TWO_EXECUTORS, Utilities.getMaxExecutorsForInputListing(conf, THREE_LOCATIONS));
  }

  /**
   * Test for {@link Utilities#getInputPaths(JobConf, MapWork, Path, Context, boolean)} with a single
   * threaded.
   */
  @Test
  public void testGetInputPathsWithASingleThread() throws Exception {
    final int NUM_PARTITIONS = 5;

    JobConf jobConf = new JobConf();

    jobConf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, 1);
    runTestGetInputPaths(jobConf, NUM_PARTITIONS);
  }

  /**
   * Test for {@link Utilities#getInputPaths(JobConf, MapWork, Path, Context, boolean)} with multiple
   * threads.
   */
  @Test
  public void testGetInputPathsWithMultipleThreads() throws Exception {
    final int NUM_PARTITIONS = 5;

    JobConf jobConf = new JobConf();

    jobConf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, 2);
    runTestGetInputPaths(jobConf, NUM_PARTITIONS);
  }

  private void runTestGetInputPaths(JobConf jobConf, int numOfPartitions) throws Exception {
    MapWork mapWork = new MapWork();
    Path scratchDir = new Path(HiveConf.getVar(jobConf, HiveConf.ConfVars.LOCALSCRATCHDIR));

    LinkedHashMap<String, ArrayList<String>> pathToAliasTable = new LinkedHashMap<>();

    String testTableName = "testTable";

    Path testTablePath = new Path(testTableName);
    Path[] testPartitionsPaths = new Path[numOfPartitions];
    for (int i=0; i<numOfPartitions; i++) {
      String testPartitionName = "p=" + i;
      testPartitionsPaths[i] = new Path(testTablePath, "p=" + i);

      pathToAliasTable.put(testPartitionsPaths[i].toString(), Lists.newArrayList(testPartitionName));

      mapWork.getAliasToWork().put(testPartitionName, (Operator<?>) mock(Operator.class));
    }

    mapWork.setPathToAliases(pathToAliasTable);

    FileSystem fs = FileSystem.getLocal(jobConf);
    try {
      fs.mkdirs(testTablePath);

      for (int i=0; i<numOfPartitions; i++) {
        fs.mkdirs(testPartitionsPaths[i]);
        fs.create(new Path(testPartitionsPaths[i], "test1.txt")).close();
      }

      List<Path> inputPaths = Utilities.getInputPaths(jobConf, mapWork, scratchDir, mock(Context.class), false);
      assertEquals(inputPaths.size(), numOfPartitions);
      for (int i=0; i<numOfPartitions; i++) {
        assertEquals(inputPaths.get(i), testPartitionsPaths[i]);
      }
    } finally {
      if (fs.exists(testTablePath)) {
        fs.delete(testTablePath, true);
      }
    }
  }

  @Test
  public void testGetInputSummaryPool() throws ExecutionException, InterruptedException, IOException {
    ExecutorService pool = mock(ExecutorService.class);
    when(pool.submit(any(Runnable.class))).thenReturn(mock(Future.class));

    Set<String> pathNeedProcess = new HashSet<>();
    pathNeedProcess.add("dummy-path1");
    pathNeedProcess.add("dummy-path2");
    pathNeedProcess.add("dummy-path3");

    SessionState.start(new HiveConf());
    JobConf jobConf = new JobConf();
    Context context = new Context(jobConf);

    Utilities.getInputSummaryWithPool(context, pathNeedProcess, mock(MapWork.class), new long[3], pool);
    verify(pool, times(3)).submit(any(Runnable.class));
    verify(pool).shutdown();
    verify(pool).shutdownNow();
  }

  @Test
  public void testGetInputSummaryPoolAndFailure() throws ExecutionException, InterruptedException, IOException {
    ExecutorService pool = mock(ExecutorService.class);
    when(pool.submit(any(Runnable.class))).thenReturn(mock(Future.class));

    Set<String> pathNeedProcess = new HashSet<>();
    pathNeedProcess.add("dummy-path1");
    pathNeedProcess.add("dummy-path2");
    pathNeedProcess.add("dummy-path3");

    SessionState.start(new HiveConf());
    JobConf jobConf = new JobConf();
    Context context = new Context(jobConf);

    Utilities.getInputSummaryWithPool(context, pathNeedProcess, mock(MapWork.class), new long[3], pool);
    verify(pool, times(3)).submit(any(Runnable.class));
    verify(pool).shutdown();
    verify(pool).shutdownNow();
  }

  @Test
  public void testGetInputPathsPool() throws IOException, ExecutionException, InterruptedException {
    List<Path> pathsToAdd = new ArrayList<>();
    Path path = new Path("dummy-path");

    pathsToAdd.add(path);
    pathsToAdd.add(path);
    pathsToAdd.add(path);

    ExecutorService pool = mock(ExecutorService.class);
    Future mockFuture = mock(Future.class);

    when(mockFuture.get()).thenReturn(path);
    when(pool.submit(any(Callable.class))).thenReturn(mockFuture);

    Utilities.getInputPathsWithPool(mock(JobConf.class), mock(MapWork.class), mock(Path.class), mock(Context.class),
            false, pathsToAdd, pool);

    verify(pool, times(3)).submit(any(Callable.class));
    verify(pool).shutdown();
    verify(pool).shutdownNow();
  }

  @Test
  public void testGetInputPathsPoolAndFailure() throws IOException, ExecutionException, InterruptedException {
    List<Path> pathsToAdd = new ArrayList<>();
    Path path = new Path("dummy-path");

    pathsToAdd.add(path);
    pathsToAdd.add(path);
    pathsToAdd.add(path);

    ExecutorService pool = mock(ExecutorService.class);
    Future mockFuture = mock(Future.class);

    when(mockFuture.get()).thenThrow(new RuntimeException());
    when(pool.submit(any(Callable.class))).thenReturn(mockFuture);

    Exception e = null;
    try {
      Utilities.getInputPathsWithPool(mock(JobConf.class), mock(MapWork.class), mock(Path.class), mock(Context.class),
              false, pathsToAdd, pool);
    } catch (Exception thrownException) {
      e = thrownException;
    }
    assertNotNull(e);

    verify(pool, times(3)).submit(any(Callable.class));
    verify(pool).shutdownNow();
  }

  @Test
  public void testGetInputSummaryWithASingleThread() throws IOException {
    final int NUM_PARTITIONS = 5;
    final int BYTES_PER_FILE = 5;

    JobConf jobConf = new JobConf();
    Properties properties = new Properties();

    jobConf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, 0);
    ContentSummary summary = runTestGetInputSummary(jobConf, properties, NUM_PARTITIONS, BYTES_PER_FILE, HiveInputFormat.class);
    assertEquals(NUM_PARTITIONS * BYTES_PER_FILE, summary.getLength());
    assertEquals(NUM_PARTITIONS, summary.getFileCount());
    assertEquals(NUM_PARTITIONS, summary.getDirectoryCount());
  }

  @Test
  public void testGetInputSummaryWithMultipleThreads() throws IOException {
    final int NUM_PARTITIONS = 5;
    final int BYTES_PER_FILE = 5;

    JobConf jobConf = new JobConf();
    Properties properties = new Properties();

    jobConf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, 2);
    ContentSummary summary = runTestGetInputSummary(jobConf, properties, NUM_PARTITIONS, BYTES_PER_FILE, HiveInputFormat.class);
    assertEquals(NUM_PARTITIONS * BYTES_PER_FILE, summary.getLength());
    assertEquals(NUM_PARTITIONS, summary.getFileCount());
    assertEquals(NUM_PARTITIONS, summary.getDirectoryCount());

    // Test deprecated mapred.dfsclient.parallelism.max
    jobConf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, 0);
    jobConf.setInt(Utilities.DEPRECATED_MAPRED_DFSCLIENT_PARALLELISM_MAX, 2);
    summary = runTestGetInputSummary(jobConf, properties, NUM_PARTITIONS, BYTES_PER_FILE, HiveInputFormat.class);
    assertEquals(NUM_PARTITIONS * BYTES_PER_FILE, summary.getLength());
    assertEquals(NUM_PARTITIONS, summary.getFileCount());
    assertEquals(NUM_PARTITIONS, summary.getDirectoryCount());
  }

  @Test
  public void testGetInputSummaryWithInputEstimator() throws IOException, HiveException {
    final int NUM_PARTITIONS = 5;
    final int BYTES_PER_FILE = 10;
    final int NUM_OF_ROWS = 5;

    JobConf jobConf = new JobConf();
    Properties properties = new Properties();

    jobConf.setInt(Utilities.DEPRECATED_MAPRED_DFSCLIENT_PARALLELISM_MAX, 2);

    properties.setProperty(hive_metastoreConstants.META_TABLE_STORAGE, InputEstimatorTestClass.class.getName());
    InputEstimatorTestClass.setEstimation(new InputEstimator.Estimation(NUM_OF_ROWS, BYTES_PER_FILE));

    /* Let's write more bytes to the files to test that Estimator is actually working returning the file size not from the filesystem */
    ContentSummary summary = runTestGetInputSummary(jobConf, properties, NUM_PARTITIONS, BYTES_PER_FILE * 2, HiveInputFormat.class);
    assertEquals(NUM_PARTITIONS * BYTES_PER_FILE, summary.getLength());
    assertEquals(NUM_PARTITIONS * -1, summary.getFileCount());        // Current getInputSummary() returns -1 for each file found
    assertEquals(NUM_PARTITIONS * -1, summary.getDirectoryCount());   // Current getInputSummary() returns -1 for each file found

    // Test deprecated mapred.dfsclient.parallelism.max
    jobConf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, 0);
    jobConf.setInt(HiveConf.ConfVars.HIVE_EXEC_INPUT_LISTING_MAX_THREADS.varname, 2);

    properties.setProperty(hive_metastoreConstants.META_TABLE_STORAGE, InputEstimatorTestClass.class.getName());
    InputEstimatorTestClass.setEstimation(new InputEstimator.Estimation(NUM_OF_ROWS, BYTES_PER_FILE));

    /* Let's write more bytes to the files to test that Estimator is actually working returning the file size not from the filesystem */
    summary = runTestGetInputSummary(jobConf, properties, NUM_PARTITIONS, BYTES_PER_FILE * 2, HiveInputFormat.class);
    assertEquals(NUM_PARTITIONS * BYTES_PER_FILE, summary.getLength());
    assertEquals(NUM_PARTITIONS * -1, summary.getFileCount());        // Current getInputSummary() returns -1 for each file found
    assertEquals(NUM_PARTITIONS * -1, summary.getDirectoryCount());   // Current getInputSummary() returns -1 for each file found
  }

  static class ContentSummaryInputFormatTestClass extends FileInputFormat implements ContentSummaryInputFormat {
    private static ContentSummary summary = new ContentSummary();

    public static void setContentSummary(ContentSummary contentSummary) {
      summary = contentSummary;
    }

    @Override
    public RecordReader getRecordReader(InputSplit inputSplit, JobConf jobConf, Reporter reporter) throws IOException {
      return null;
    }

    @Override
    public ContentSummary getContentSummary(Path p, JobConf job) throws IOException {
      return summary;
    }
  }

  @Test
  public void testGetInputSummaryWithContentSummaryInputFormat() throws IOException {
    final int NUM_PARTITIONS = 5;
    final int BYTES_PER_FILE = 10;

    JobConf jobConf = new JobConf();
    Properties properties = new Properties();

    jobConf.setInt(Utilities.DEPRECATED_MAPRED_DFSCLIENT_PARALLELISM_MAX, 2);

    ContentSummaryInputFormatTestClass.setContentSummary(new ContentSummary(BYTES_PER_FILE, 2, 1));

    /* Let's write more bytes to the files to test that ContentSummaryInputFormat is actually working returning the file size not from the filesystem */
    ContentSummary summary = runTestGetInputSummary(jobConf, properties, NUM_PARTITIONS, BYTES_PER_FILE * 2, ContentSummaryInputFormatTestClass.class);
    assertEquals(NUM_PARTITIONS * BYTES_PER_FILE, summary.getLength());
    assertEquals(NUM_PARTITIONS * 2, summary.getFileCount());
    assertEquals(NUM_PARTITIONS, summary.getDirectoryCount());
  }

  private ContentSummary runTestGetInputSummary(JobConf jobConf, Properties properties, int numOfPartitions, int bytesPerFile, Class<? extends InputFormat> inputFormatClass) throws IOException {
    // creates scratch directories needed by the Context object
    SessionState.start(new HiveConf());

    MapWork mapWork = new MapWork();
    Context context = new Context(jobConf);
    LinkedHashMap<String, PartitionDesc> pathToPartitionInfo = new LinkedHashMap<>();
    LinkedHashMap<String, ArrayList<String>> pathToAliasTable = new LinkedHashMap<>();
    TableScanOperator scanOp = new TableScanOperator();

    PartitionDesc partitionDesc = new PartitionDesc(new TableDesc(inputFormatClass, null, properties), null);

    String testTableName = "testTable";

    Path testTablePath = new Path(testTableName);
    Path[] testPartitionsPaths = new Path[numOfPartitions];
    for (int i=0; i<numOfPartitions; i++) {
      String testPartitionName = "p=" + 1;
      testPartitionsPaths[i] = new Path(testTablePath, "p=" + i);

      pathToPartitionInfo.put(testPartitionsPaths[i].toString(), partitionDesc);

      pathToAliasTable.put(testPartitionsPaths[i].toString(), Lists.newArrayList(testPartitionName));

      mapWork.getAliasToWork().put(testPartitionName, scanOp);
    }

    mapWork.setPathToAliases(pathToAliasTable);
    mapWork.setPathToPartitionInfo(pathToPartitionInfo);

    FileSystem fs = FileSystem.getLocal(jobConf);
    try {
      fs.mkdirs(testTablePath);
      byte[] data = new byte[bytesPerFile];

      for (int i=0; i<numOfPartitions; i++) {
        fs.mkdirs(testPartitionsPaths[i]);
        FSDataOutputStream out = fs.create(new Path(testPartitionsPaths[i], "test1.txt"));
        out.write(data);
        out.close();
      }

      return Utilities.getInputSummary(context, mapWork, null);
    } finally {
      if (fs.exists(testTablePath)) {
        fs.delete(testTablePath, true);
      }
    }
  }
}
