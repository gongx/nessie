/*
 * Copyright (C) 2020 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.deltalake;

import io.delta.tables.DeltaTable;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoder;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.delta.DeltaLog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.projectnessie.model.Branch;
import org.projectnessie.model.Content;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.DeltaLakeTable;
import scala.Tuple2;

@EnabledOnOs(
    value = {OS.LINUX},
    disabledReason =
        "tests fail on macOS + Windows with 'java.lang.IllegalArgumentException: Can not create a Path from an empty string' via 'org.apache.spark.sql.delta.DeltaLog.ensureLogDirectoryExist()'")
class ITDeltaLogBranches extends AbstractDeltaTest {

  @TempDir File tempPath;

  private static final String BRANCH_NAME = "ITDeltaLogBranches";

  public ITDeltaLogBranches() {
    super(BRANCH_NAME);
  }

  @Test
  void testBranches() throws Exception {
    Dataset<Row> targetTable =
        createKVDataSet(
            Arrays.asList(tuple2(1, 10), tuple2(2, 20), tuple2(3, 30), tuple2(4, 40)),
            "key",
            "value");
    // write some data to table
    targetTable.write().format("delta").save(ensureNamespaceExists(tempPath.getAbsolutePath()));
    // create test at the point where there is only 1 commit
    Branch sourceRef = getBranch();
    api.createReference()
        .sourceRefName(sourceRef.getName())
        .reference(Branch.of("test", sourceRef.getHash()))
        .create();
    // add some more data to current branch
    targetTable.write().format("delta").mode("append").save(tempPath.getAbsolutePath());

    // read current branch and record number of rows
    DeltaTable target = DeltaTable.forPath(spark, tempPath.getAbsolutePath());
    int expectedSize = target.toDF().collectAsList().size();

    /*
    It is hard to change ref in Detla for the following reasons
    * DeltaTables are cached
    * hadoop/spark config don't get updated in the cached tables
    * there is currently no way to pass down a branch or hash via '@' or '#'
    Below we manually invaildate the cache and update the ref before reading the table off test
    As the table itself is cached we can't read from currentBranch w/o invalidating, hence reading from currentBranch above
     */
    DeltaLog.invalidateCache(spark, new Path(tempPath.getAbsolutePath()));
    spark.sparkContext().conf().set("spark.sql.catalog.spark_catalog.ref", "test");
    Dataset<Row> targetBranch = spark.read().format("delta").load(tempPath.getAbsolutePath());

    // we expect the table from test to be half the size of the table from currentBranch
    Assertions.assertEquals(expectedSize * 0.5, targetBranch.collectAsList().size());
  }

  @Test
  void testCheckpoint() throws Exception {
    Dataset<Row> targetTable =
        createKVDataSet(
            Arrays.asList(tuple2(1, 10), tuple2(2, 20), tuple2(3, 30), tuple2(4, 40)),
            "key",
            "value");
    // write some data to table
    targetTable.write().format("delta").save(ensureNamespaceExists(tempPath.getAbsolutePath()));
    // write enough to trigger a checkpoint generation
    for (int i = 0; i < 15; i++) {
      targetTable.write().format("delta").mode("append").save(tempPath.getAbsolutePath());
    }

    DeltaTable target = DeltaTable.forPath(spark, tempPath.getAbsolutePath());
    int expectedSize = target.toDF().collectAsList().size();
    Assertions.assertEquals(64, expectedSize);

    String tableName = tempPath.getAbsolutePath() + "/_delta_log";
    ContentKey key = DeltaContentKeyUtil.fromFilePathString(tableName);
    Content content = api.getContent().key(key).refName(BRANCH_NAME).get().get(key);
    Optional<DeltaLakeTable> table = content.unwrap(DeltaLakeTable.class);
    Assertions.assertTrue(table.isPresent());
    Assertions.assertEquals(1, table.get().getCheckpointLocationHistory().size());
    Assertions.assertEquals(5, table.get().getMetadataLocationHistory().size());
    Assertions.assertNotNull(table.get().getLastCheckpoint());
  }

  private Dataset<Row> createKVDataSet(
      List<Tuple2<Integer, Integer>> data, String keyName, String valueName) {
    Encoder<Tuple2<Integer, Integer>> encoder = Encoders.tuple(Encoders.INT(), Encoders.INT());
    return spark.createDataset(data, encoder).toDF(keyName, valueName);
  }

  private <T1, T2> Tuple2<T1, T2> tuple2(T1 t1, T2 t2) {
    return new Tuple2<>(t1, t2);
  }
}