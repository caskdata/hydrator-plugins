/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.plugin.teradata.test;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.table.Row;
import co.cask.cdap.api.dataset.table.Scanner;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.api.plugin.PluginClass;
import co.cask.cdap.api.plugin.PluginPropertyField;
import co.cask.cdap.etl.batch.config.ETLBatchConfig;
import co.cask.cdap.etl.batch.mapreduce.ETLMapReduce;
import co.cask.cdap.etl.common.ETLStage;
import co.cask.cdap.etl.common.Plugin;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.ProgramRunStatus;
import co.cask.cdap.proto.RunRecord;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.DataSetManager;
import co.cask.cdap.test.MapReduceManager;
import co.cask.cdap.test.TestBase;
import co.cask.hydrator.plugin.DBRecord;
import co.cask.hydrator.plugin.batch.ETLBatchTestBase;
import co.cask.hydrator.plugin.common.Properties;
import co.cask.hydrator.plugin.teradata.batch.source.DataDrivenETLDBInputFormat;
import co.cask.hydrator.plugin.teradata.batch.source.TeradataSource;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.hsqldb.Server;
import org.hsqldb.jdbc.JDBCDriver;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.rowset.serial.SerialBlob;

/**
 * Test for ETL using databases.
 */
public class TeradataPluginTest extends ETLBatchTestBase {
  private static final long currentTs = System.currentTimeMillis();
  private static final String clobData = "this is a long string with line separators \n that can be used as \n a clob";
  private static HSQLDBServer hsqlDBServer;
  private static Schema schema;

  @ClassRule
  public static TemporaryFolder temporaryFolder = new TemporaryFolder();

  @BeforeClass
  public static void setup() throws Exception {
    // add artifact for batch sources and sinks
    addPluginArtifact(Id.Artifact.from(Id.Namespace.DEFAULT, "teradata-plugins", "1.0.0"), APP_ARTIFACT_ID,
                      TeradataSource.class, DataDrivenETLDBInputFormat.class, DBRecord.class);

    // add hypersql 3rd party plugin
    PluginClass hypersql = new PluginClass("jdbc", "hypersql", "hypersql jdbc driver", JDBCDriver.class.getName(),
                                           null, Collections.<String, PluginPropertyField>emptyMap());
    addPluginArtifact(Id.Artifact.from(Id.Namespace.DEFAULT, "hsql-jdbc", "1.0.0"), APP_ARTIFACT_ID,
                      Sets.newHashSet(hypersql), JDBCDriver.class);

    String hsqlDBDir = temporaryFolder.newFolder("hsqldb").getAbsolutePath();
    hsqlDBServer = new HSQLDBServer(hsqlDBDir, "testdb");
    hsqlDBServer.start();
    try (Connection conn = hsqlDBServer.getConnection()) {
      createTestUser(conn);
      createTestTables(conn);
      prepareTestData(conn);
    }

    Schema nullableString = Schema.nullableOf(Schema.of(Schema.Type.STRING));
    Schema nullableBoolean = Schema.nullableOf(Schema.of(Schema.Type.BOOLEAN));
    Schema nullableInt = Schema.nullableOf(Schema.of(Schema.Type.INT));
    Schema nullableLong = Schema.nullableOf(Schema.of(Schema.Type.LONG));
    Schema nullableFloat = Schema.nullableOf(Schema.of(Schema.Type.FLOAT));
    Schema nullableDouble = Schema.nullableOf(Schema.of(Schema.Type.DOUBLE));
    Schema nullableBytes = Schema.nullableOf(Schema.of(Schema.Type.BYTES));
    schema = Schema.recordOf("student",
                             Schema.Field.of("ID", Schema.of(Schema.Type.INT)),
                             Schema.Field.of("NAME", Schema.of(Schema.Type.STRING)),
                             Schema.Field.of("SCORE", nullableDouble),
                             Schema.Field.of("GRADUATED", nullableBoolean),
                             Schema.Field.of("TINY", nullableInt),
                             Schema.Field.of("SMALL", nullableInt),
                             Schema.Field.of("BIG", nullableLong),
                             Schema.Field.of("FLOAT_COL", nullableFloat),
                             Schema.Field.of("REAL_COL", nullableFloat),
                             Schema.Field.of("NUMERIC_COL", nullableDouble),
                             Schema.Field.of("DECIMAL_COL", nullableDouble),
                             Schema.Field.of("BIT_COL", nullableBoolean),
                             Schema.Field.of("DATE_COL", nullableLong),
                             Schema.Field.of("TIME_COL", nullableLong),
                             Schema.Field.of("TIMESTAMP_COL", nullableLong),
                             Schema.Field.of("BINARY_COL", nullableBytes),
                             Schema.Field.of("BLOB_COL", nullableBytes),
                             Schema.Field.of("CLOB_COL", nullableString));
  }

  private static void createTestUser(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE USER \"emptyPwdUser\" PASSWORD '' ADMIN");
    }
  }

  private static void createTestTables(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      // note that the tables need quotation marks around them; otherwise, hsql creates them in upper case
      stmt.execute("CREATE TABLE \"my_table\"" +
                     "(" +
                     "ID INT NOT NULL, " +
                     "NAME VARCHAR(40) NOT NULL, " +
                     "SCORE DOUBLE, " +
                     "GRADUATED BOOLEAN, " +
                     "NOT_IMPORTED VARCHAR(30), " +
                     "TINY TINYINT, " +
                     "SMALL SMALLINT, " +
                     "BIG BIGINT, " +
                     "FLOAT_COL FLOAT, " +
                     "REAL_COL REAL, " +
                     "NUMERIC_COL NUMERIC(10, 2), " +
                     "DECIMAL_COL DECIMAL(10, 2), " +
                     "BIT_COL BIT, " +
                     "DATE_COL DATE, " +
                     "TIME_COL TIME, " +
                     "TIMESTAMP_COL TIMESTAMP, " +
                     "BINARY_COL BINARY(100)," +
                     "BLOB_COL BLOB(100), " +
                     "CLOB_COL CLOB(100)" +
                     ")");
      stmt.execute("CREATE TABLE \"MY_DEST_TABLE\" AS (" +
                     "SELECT * FROM \"my_table\") WITH DATA");
      stmt.execute("CREATE TABLE \"your_table\" AS (" +
                     "SELECT * FROM \"my_table\") WITH DATA");
    }
  }

  private static void prepareTestData(Connection conn) throws SQLException {
    try (
      PreparedStatement pStmt1 =
        conn.prepareStatement("INSERT INTO \"my_table\" " +
                                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
      PreparedStatement pStmt2 =
        conn.prepareStatement("INSERT INTO \"your_table\" " +
                                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
    ) {
      // insert the same data into both tables: my_table and your_table
      final PreparedStatement[] preparedStatements = {pStmt1, pStmt2};
        for (PreparedStatement pStmt : preparedStatements) {
        for (int i = 1; i <= 5; i++) {
          String name = "user" + i;
          pStmt.setInt(1, i);
          pStmt.setString(2, name);
          pStmt.setDouble(3, 123.45 + i);
          pStmt.setBoolean(4, (i % 2 == 0));
          pStmt.setString(5, "random" + i);
          pStmt.setShort(6, (short) i);
          pStmt.setShort(7, (short) i);
          pStmt.setLong(8, (long) i);
          pStmt.setFloat(9, (float) 123.45 + i);
          pStmt.setFloat(10, (float) 123.45 + i);
          pStmt.setDouble(11, 123.45 + i);
          if ((i % 2 == 0)) {
            pStmt.setNull(12, Types.DOUBLE);
          } else {
            pStmt.setDouble(12, 123.45 + i);
          }
          pStmt.setBoolean(13, (i % 2 == 1));
          pStmt.setDate(14, new Date(currentTs));
          pStmt.setTime(15, new Time(currentTs));
          pStmt.setTimestamp(16, new Timestamp(currentTs));
          pStmt.setBytes(17, name.getBytes(Charsets.UTF_8));
          pStmt.setBlob(18, new SerialBlob(name.getBytes(Charsets.UTF_8)));
          pStmt.setClob(19, new InputStreamReader(new ByteArrayInputStream(clobData.getBytes(Charsets.UTF_8))));
          pStmt.executeUpdate();
        }
      }
    }
  }

  @Test
  @SuppressWarnings("ConstantConditions")
  public void testDBSource() throws Exception {
    String importQuery = "SELECT ID, NAME, SCORE, GRADUATED, TINY, SMALL, BIG, FLOAT_COL, REAL_COL, NUMERIC_COL, " +
      "DECIMAL_COL, BIT_COL, DATE_COL, TIME_COL, TIMESTAMP_COL, BINARY_COL, BLOB_COL, CLOB_COL FROM \"my_table\"" +
      "WHERE ID < 3 AND $CONDITIONS";
    String boundingQuery = "SELECT MIN(ID),MAX(ID) from \"my_table\"";
    String splitBy = "ID";
    Plugin sourceConfig = new Plugin(
      "Teradata",
      ImmutableMap.<String, String>builder()
        .put(Properties.DB.CONNECTION_STRING, hsqlDBServer.getConnectionUrl())
        .put(Properties.DB.TABLE_NAME, "my_table")
        .put(Properties.DB.IMPORT_QUERY, importQuery)
        .put(TeradataSource.TeradataSourceConfig.BOUNDING_QUERY, boundingQuery)
        .put(TeradataSource.TeradataSourceConfig.SPLIT_BY, splitBy)
        .put(Properties.DB.JDBC_PLUGIN_NAME, "hypersql")
        .build()
    );

    Plugin sinkConfig = new Plugin("Table", ImmutableMap.of(
      "name", "outputTable",
      Properties.Table.PROPERTY_SCHEMA, schema.toString(),
      Properties.Table.PROPERTY_SCHEMA_ROW_FIELD, "ID"));

    ETLStage source = new ETLStage("dbSource2", sourceConfig);
    ETLStage sink = new ETLStage("tableSink2", sinkConfig);
    ETLBatchConfig etlConfig = new ETLBatchConfig("* * * * *", source, sink, Lists.<ETLStage>newArrayList());

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(ETLBATCH_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "dbSourceTest");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    MapReduceManager mrManager = appManager.getMapReduceManager(ETLMapReduce.NAME);
    mrManager.start();
    mrManager.waitForFinish(5, TimeUnit.MINUTES);

    DataSetManager<Table> outputManager = getDataset("outputTable");
    Table outputTable = outputManager.get();

    // Using get to verify the rowkey
    Assert.assertEquals(17, outputTable.get(Bytes.toBytes(1)).getColumns().size());
    // In the second record, the 'decimal' column is null
    Assert.assertEquals(16, outputTable.get(Bytes.toBytes(2)).getColumns().size());
    // Scanner to verify number of rows
    Scanner scanner = outputTable.scan(null, null);
    Row row1 = scanner.next();
    Row row2 = scanner.next();
    Assert.assertNotNull(row1);
    Assert.assertNotNull(row2);
    Assert.assertNull(scanner.next());
    scanner.close();
    // Verify data
    Assert.assertEquals("user1", row1.getString("NAME"));
    Assert.assertEquals("user2", row2.getString("NAME"));
    Assert.assertEquals(124.45, row1.getDouble("SCORE"), 0.000001);
    Assert.assertEquals(125.45, row2.getDouble("SCORE"), 0.000001);
    Assert.assertEquals(false, row1.getBoolean("GRADUATED"));
    Assert.assertEquals(true, row2.getBoolean("GRADUATED"));
    Assert.assertNull(row1.get("NOT_IMPORTED"));
    Assert.assertNull(row2.get("NOT_IMPORTED"));
    // TODO: Reading from table as SHORT seems to be giving the wrong value.
    Assert.assertEquals(1, (int) row1.getInt("TINY"));
    Assert.assertEquals(2, (int) row2.getInt("TINY"));
    Assert.assertEquals(1, (int) row1.getInt("SMALL"));
    Assert.assertEquals(2, (int) row2.getInt("SMALL"));
    Assert.assertEquals(1, (long) row1.getLong("BIG"));
    Assert.assertEquals(2, (long) row2.getLong("BIG"));
    // TODO: Reading from table as FLOAT seems to be giving back the wrong value.
    Assert.assertEquals(124.45, row1.getDouble("FLOAT_COL"), 0.00001);
    Assert.assertEquals(125.45, row2.getDouble("FLOAT_COL"), 0.00001);
    Assert.assertEquals(124.45, row1.getDouble("REAL_COL"), 0.00001);
    Assert.assertEquals(125.45, row2.getDouble("REAL_COL"), 0.00001);
    Assert.assertEquals(124.45, row1.getDouble("NUMERIC_COL"), 0.000001);
    Assert.assertEquals(125.45, row2.getDouble("NUMERIC_COL"), 0.000001);
    Assert.assertEquals(124.45, row1.getDouble("DECIMAL_COL"), 0.000001);
    Assert.assertEquals(null, row2.get("DECIMAL_COL"));
    Assert.assertEquals(true, row1.getBoolean("BIT_COL"));
    Assert.assertEquals(false, row2.getBoolean("BIT_COL"));
    // Verify time columns
    java.util.Date date = new java.util.Date(currentTs);
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    long expectedDateTimestamp = Date.valueOf(sdf.format(date)).getTime();
    sdf = new SimpleDateFormat("H:mm:ss");
    long expectedTimeTimestamp = Time.valueOf(sdf.format(date)).getTime();
    Assert.assertEquals(expectedDateTimestamp, (long) row1.getLong("DATE_COL"));
    Assert.assertEquals(expectedDateTimestamp, (long) row2.getLong("DATE_COL"));
    Assert.assertEquals(expectedTimeTimestamp, (long) row1.getLong("TIME_COL"));
    Assert.assertEquals(expectedTimeTimestamp, (long) row2.getLong("TIME_COL"));
    Assert.assertEquals(currentTs, (long) row1.getLong("TIMESTAMP_COL"));
    Assert.assertEquals(currentTs, (long) row2.getLong("TIMESTAMP_COL"));
    // verify binary columns
    Assert.assertEquals("user1", Bytes.toString(row1.get("BINARY_COL"), 0, 5));
    Assert.assertEquals("user2", Bytes.toString(row2.get("BINARY_COL"), 0, 5));
    Assert.assertEquals("user1", Bytes.toString(row1.get("BLOB_COL"), 0, 5));
    Assert.assertEquals("user2", Bytes.toString(row2.get("BLOB_COL"), 0, 5));
    Assert.assertEquals(clobData, Bytes.toString(row1.get("CLOB_COL"), 0, clobData.length()));
    Assert.assertEquals(clobData, Bytes.toString(row2.get("CLOB_COL"), 0, clobData.length()));
  }

  @Test
  public void testDBSourceWithLowerCaseColNames() throws Exception {
    // all lower case since we are going to set db column name case to be lower
    Schema schema = Schema.recordOf("student",
                                    Schema.Field.of("id", Schema.of(Schema.Type.INT)),
                                    Schema.Field.of("name", Schema.of(Schema.Type.STRING)));

    String importQuery = "SELECT ID, NAME FROM \"my_table\" WHERE ID < 3 AND $CONDITIONS";
    String boundingQuery = "SELECT MIN(ID),MAX(ID) from \"my_table\"";
    String splitBy = "ID";
    Plugin sourceConfig = new Plugin("Teradata", ImmutableMap.<String, String>builder()
      .put(Properties.DB.CONNECTION_STRING, hsqlDBServer.getConnectionUrl())
      .put(Properties.DB.TABLE_NAME, "my_table")
      .put(Properties.DB.IMPORT_QUERY, importQuery)
      .put(TeradataSource.TeradataSourceConfig.BOUNDING_QUERY, boundingQuery)
      .put(TeradataSource.TeradataSourceConfig.SPLIT_BY, splitBy)
      .put(Properties.DB.JDBC_PLUGIN_NAME, "hypersql")
      .put(Properties.DB.COLUMN_NAME_CASE, "lower")
      .build()
    );

    ETLStage source = new ETLStage("dbSource1", sourceConfig);
    Plugin sinkConfig = new Plugin("Table", ImmutableMap.of(
      "name", "outputTable1",
      Properties.Table.PROPERTY_SCHEMA, schema.toString(),
      // smaller case since we have set the db data's column case to be lower
      Properties.Table.PROPERTY_SCHEMA_ROW_FIELD, "id"));
    ETLStage sink = new ETLStage("tableSink1", sinkConfig);
    ETLBatchConfig etlConfig = new ETLBatchConfig("* * * * *", source, sink, new ArrayList<ETLStage>());

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(ETLBatchTestBase.ETLBATCH_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "teradataSourceTest");
    ApplicationManager appManager = TestBase.deployApplication(appId, appRequest);

    MapReduceManager mrManager = appManager.getMapReduceManager(ETLMapReduce.NAME);
    mrManager.start();
    mrManager.waitForFinish(5, TimeUnit.MINUTES);
    List<RunRecord> runRecords = mrManager.getHistory();
    Assert.assertEquals(ProgramRunStatus.COMPLETED, runRecords.get(0).getStatus());

    // records should be written
    DataSetManager<Table> outputManager = getDataset("outputTable1");
    Table outputTable = outputManager.get();
    Scanner scanner = outputTable.scan(null, null);
    Row row1 = scanner.next();
    Row row2 = scanner.next();
    Assert.assertNotNull(row1);
    Assert.assertNotNull(row2);
    Assert.assertNull(scanner.next());
    scanner.close();
    // Verify data
    Assert.assertEquals("user1", row1.getString("name"));
    Assert.assertEquals("user2", row2.getString("name"));
    Assert.assertEquals(1, Bytes.toInt(row1.getRow()));
    Assert.assertEquals(2, Bytes.toInt(row2.getRow()));
  }

  @Test
  public void testDbSourceMultipleTables() throws Exception {
    Schema schema = Schema.recordOf("student",
                                    Schema.Field.of("ID", Schema.of(Schema.Type.INT)),
                                    Schema.Field.of("NAME", Schema.of(Schema.Type.STRING)));

    // have the same data in both tables ('\"my_table\"' and '\"your_table\"'), and select the ID and NAME fields from
    // separate tables
    String importQuery = "SELECT \"my_table\".ID, \"your_table\".NAME FROM \"my_table\", \"your_table\"" +
      "WHERE \"my_table\".ID < 3 and \"my_table\".ID = \"your_table\".ID and $CONDITIONS";
    String boundingQuery = "SELECT MIN(MIN(\"my_table\".ID), MIN(\"your_table\".ID)), " +
      "MAX(MAX(\"my_table\".ID), MAX(\"your_table\".ID))";
    String splitBy = "\"my_table\".ID";
    Plugin sourceConfig = new Plugin("Teradata", ImmutableMap.<String, String>builder()
      .put(Properties.DB.CONNECTION_STRING, hsqlDBServer.getConnectionUrl())
      .put(Properties.DB.TABLE_NAME, "my_table")
      .put(Properties.DB.IMPORT_QUERY, importQuery)
      .put(TeradataSource.TeradataSourceConfig.BOUNDING_QUERY, boundingQuery)
      .put(TeradataSource.TeradataSourceConfig.SPLIT_BY, splitBy)
      .put(Properties.DB.JDBC_PLUGIN_NAME, "hypersql")
      .build()
    );

    Plugin sinkConfig = new Plugin("Table", ImmutableMap.of(
      "name", "outputTable1",
      Properties.Table.PROPERTY_SCHEMA, schema.toString(),
      Properties.Table.PROPERTY_SCHEMA_ROW_FIELD, "ID"));

    ETLStage source = new ETLStage("dbSource3", sourceConfig);
    ETLStage sink = new ETLStage("tableSink3", sinkConfig);
    ETLBatchConfig etlConfig = new ETLBatchConfig("* * * * *", source, sink, Lists.<ETLStage>newArrayList());

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(ETLBATCH_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "dbSourceTest");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    MapReduceManager mrManager = appManager.getMapReduceManager(ETLMapReduce.NAME);
    mrManager.start();
    mrManager.waitForFinish(5, TimeUnit.MINUTES);
    List<RunRecord> runRecords = mrManager.getHistory();
    Assert.assertEquals(ProgramRunStatus.COMPLETED, runRecords.get(0).getStatus());

    // records should be written
    DataSetManager<Table> outputManager = getDataset("outputTable1");
    Table outputTable = outputManager.get();
    Scanner scanner = outputTable.scan(null, null);
    Row row1 = scanner.next();
    Row row2 = scanner.next();
    Assert.assertNotNull(row1);
    Assert.assertNotNull(row2);
    Assert.assertNull(scanner.next());
    scanner.close();
    // Verify data
    Assert.assertEquals("user1", row1.getString("NAME"));
    Assert.assertEquals("user2", row2.getString("NAME"));
    Assert.assertEquals(1, Bytes.toInt(row1.getRow()));
    Assert.assertEquals(2, Bytes.toInt(row2.getRow()));
  }

  @Test
  public void testUserNamePasswordCombinations() throws Exception {
    String importQuery = "SELECT * FROM \"my_table\" WHERE $CONDITIONS";
    String boundingQuery = "SELECT MIN(ID),MAX(ID) from \"my_table\"";
    String splitBy = "ID";

    Plugin tableConfig = new Plugin("Table", ImmutableMap.of(
      "name", "outputTable",
      Properties.Table.PROPERTY_SCHEMA, schema.toString(),
      Properties.Table.PROPERTY_SCHEMA_ROW_FIELD, "ID"));

    List<ETLStage> transforms = new ArrayList<>();

    Map<String, String> baseSourceProps = ImmutableMap.<String, String>builder()
      .put(Properties.DB.CONNECTION_STRING, hsqlDBServer.getConnectionUrl())
      .put(Properties.DB.TABLE_NAME, "my_table")
      .put(Properties.DB.JDBC_PLUGIN_NAME, "hypersql")
      .put(Properties.DB.IMPORT_QUERY, importQuery)
      .put(TeradataSource.TeradataSourceConfig.BOUNDING_QUERY, boundingQuery)
      .put(TeradataSource.TeradataSourceConfig.SPLIT_BY, splitBy)
      .build();

    Map<String, String> baseSinkProps = ImmutableMap.of(
      Properties.DB.CONNECTION_STRING, hsqlDBServer.getConnectionUrl(),
      Properties.DB.TABLE_NAME, "my_table",
      Properties.DB.JDBC_PLUGIN_NAME, "hypersql",
      Properties.DB.COLUMNS, "*");

    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "dbTest");

    // null user name, null password. Should succeed.
    // as source
    Plugin dbConfig = new Plugin("Teradata", baseSourceProps);
    ETLStage table = new ETLStage("uniqueTableSink" , tableConfig);
    ETLStage database = new ETLStage("databaseSource" , dbConfig);
    ETLBatchConfig etlConfig = new ETLBatchConfig("* * * * *", database, table, transforms);
    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(ETLBATCH_ARTIFACT, etlConfig);
    deployApplication(appId, appRequest);

    // non null user name, null password. Should fail.
    // as source
    Map<String, String> noPassword = new HashMap<>(baseSourceProps);
    noPassword.put(Properties.DB.USER, "emptyPwdUser");
    database = new ETLStage("databaseSource", new Plugin("Teradata", noPassword));
    etlConfig = new ETLBatchConfig("* * * * *", database, table, transforms);
    assertDeploymentFailure(
      appId, etlConfig, "Deploying DB Source with non-null username but null password should have failed.");

    // null user name, non-null password. Should fail.
    // as source
    Map<String, String> noUser = new HashMap<>(baseSourceProps);
    noUser.put(Properties.DB.PASSWORD, "password");
    database = new ETLStage("databaseSource", new Plugin("Teradata", noUser));
    etlConfig = new ETLBatchConfig("* * * * *", database, table, transforms);
    assertDeploymentFailure(
      appId, etlConfig, "Deploying DB Source with null username but non-null password should have failed.");

    // non-null username, non-null, but empty password. Should succeed.
    // as source
    Map<String, String> emptyPassword = new HashMap<>(baseSourceProps);
    emptyPassword.put(Properties.DB.USER, "emptyPwdUser");
    emptyPassword.put(Properties.DB.PASSWORD, "");
    database = new ETLStage("databaseSource", new Plugin("Teradata", emptyPassword));
    etlConfig = new ETLBatchConfig("* * * * *", database, table, transforms);
    appRequest = new AppRequest<>(ETLBATCH_ARTIFACT, etlConfig);
    deployApplication(appId, appRequest);
  }

  @Test
  public void testNonExistentDBTable() throws Exception {
    // source
    String importQuery = "SELECT ID, NAME FROM dummy WHERE ID < 3 AND $CONDITIONS";
    String boundingQuery = "SELECT MIN(ID),MAX(ID) FROM dummy";
    String splitBy = "ID";
    //TODO: Also test for bad connection:
    Plugin tableConfig = new Plugin("Table", ImmutableMap.of(
      Properties.BatchReadableWritable.NAME, "table",
      Properties.Table.PROPERTY_SCHEMA, schema.toString(),
      Properties.Table.PROPERTY_SCHEMA_ROW_FIELD, "ID"));
    Plugin sourceBadNameConfig = new Plugin("Teradata", ImmutableMap.<String, String>builder()
      .put(Properties.DB.CONNECTION_STRING, hsqlDBServer.getConnectionUrl())
      .put(Properties.DB.TABLE_NAME, "dummy")
      .put(Properties.DB.IMPORT_QUERY, importQuery)
      .put(TeradataSource.TeradataSourceConfig.BOUNDING_QUERY, boundingQuery)
      .put(Properties.DB.JDBC_PLUGIN_NAME, "hypersql")
      .put(TeradataSource.TeradataSourceConfig.SPLIT_BY, splitBy)
      .build());
    List<ETLStage> transforms = new ArrayList<>();
    ETLStage table = new ETLStage("tableName", tableConfig);
    ETLStage sourceBadName = new ETLStage("sourceBadName", sourceBadNameConfig);

    ETLBatchConfig etlConfig = new ETLBatchConfig("* * * * *", sourceBadName, table, transforms);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "dbSourceTest");
    assertDeploymentFailure(appId, etlConfig, "ETL Application with DB Source should have failed because of a " +
      "non-existent source table.");

    // Bad connection
    String badConnection = String.format("jdbc:hsqldb:hsql://localhost/%sWRONG", hsqlDBServer.database);
    Plugin sourceBadConnConfig = new Plugin("Teradata", ImmutableMap.of(
      Properties.DB.CONNECTION_STRING, badConnection,
      Properties.DB.IMPORT_QUERY, importQuery,
      Properties.DB.COUNT_QUERY, boundingQuery,
      Properties.DB.JDBC_PLUGIN_NAME, "hypersql"
    ));
    ETLStage sourceBadConn = new ETLStage("sourceBadConn", sourceBadConnConfig);
    etlConfig = new ETLBatchConfig("* * * * *", sourceBadConn, table, transforms);
    assertDeploymentFailure(appId, etlConfig, "ETL Application with DB Source should have failed because of a " +
      "non-existent source database.");

    // sink
    Plugin sinkBadNameConfig = new Plugin("Teradata", ImmutableMap.of(
      Properties.DB.CONNECTION_STRING, hsqlDBServer.getConnectionUrl(),
      Properties.DB.TABLE_NAME, "dummy",
      Properties.DB.COLUMNS, "ID, NAME",
      Properties.DB.JDBC_PLUGIN_NAME, "hypersql"
    ));
    ETLStage sinkBadName = new ETLStage("sourceBadConn", sinkBadNameConfig);
    etlConfig = new ETLBatchConfig("* * * * *", table, sinkBadName, transforms);
    appId = Id.Application.from(Id.Namespace.DEFAULT, "dbSinkTestBadName");
    assertDeploymentFailure(appId, etlConfig, "ETL Application with DB Sink should have failed because of a " +
      "non-existent sink table.");

    // Bad connection
    Plugin sinkBadConnConfig = new Plugin("Teradata", ImmutableMap.of(
      Properties.DB.CONNECTION_STRING, badConnection,
      Properties.DB.TABLE_NAME, "dummy",
      Properties.DB.COLUMNS, "ID, NAME",
      Properties.DB.JDBC_PLUGIN_NAME, "hypersql"
    ));
    ETLStage sinkBadConn = new ETLStage("sourceBadConn", sinkBadConnConfig);
    etlConfig = new ETLBatchConfig("* * * * *", table, sinkBadConn, transforms);
    assertDeploymentFailure(appId, etlConfig, "ETL Application with DB Sink should have failed because of a " +
      "non-existent sink database.");
  }

  @AfterClass
  public static void tearDown() throws SQLException {
    try (
      Connection conn = hsqlDBServer.getConnection();
      Statement stmt = conn.createStatement()
    ) {
      stmt.execute("DROP TABLE \"my_table\"");
      stmt.execute("DROP TABLE \"your_table\"");
      stmt.execute("DROP TABLE \"MY_DEST_TABLE\"");
      stmt.execute("DROP USER \"emptyPwdUser\"");
    }

    hsqlDBServer.stop();
  }

  private void assertDeploymentFailure(Id.Application appId, ETLBatchConfig etlConfig,
                                       String failureMessage) throws Exception {
    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(ETLBATCH_ARTIFACT, etlConfig);
    try {
      deployApplication(appId, appRequest);
      Assert.fail(failureMessage);
    } catch (IllegalStateException e) {
      // expected
    }
  }

  private static class HSQLDBServer {

    private final String locationUrl;
    private final String database;
    private final String connectionUrl;
    private final Server server;
    private final String hsqlDBDriver = "org.hsqldb.jdbcDriver";

    private HSQLDBServer(String location, String database) {
      this.locationUrl = String.format("%s/%s", location, database);
      this.database = database;
      this.connectionUrl = String.format("jdbc:hsqldb:hsql://localhost/%s", database);
      this.server = new Server();
    }

    public int start() {
      server.setDatabasePath(0, locationUrl);
      server.setDatabaseName(0, database);
      return server.start();
    }

    public int stop() {
      return server.stop();
    }

    public Connection getConnection() {
      try {
        Class.forName(hsqlDBDriver);
        return DriverManager.getConnection(connectionUrl);
      } catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }

    public String getConnectionUrl() {
      return this.connectionUrl;
    }
  }
}
