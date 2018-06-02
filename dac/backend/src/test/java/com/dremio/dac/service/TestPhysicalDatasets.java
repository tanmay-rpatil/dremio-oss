/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.dac.service;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;

import org.apache.hadoop.fs.Path;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.dremio.common.util.FileUtils;
import com.dremio.common.utils.PathUtils;
import com.dremio.dac.explore.model.FileFormatUI;
import com.dremio.dac.explore.model.InitialPreviewResponse;
import com.dremio.dac.model.folder.Folder;
import com.dremio.dac.model.job.JobDataFragment;
import com.dremio.dac.model.job.JobUI;
import com.dremio.dac.model.namespace.NamespaceTree;
import com.dremio.dac.model.sources.PhysicalDataset;
import com.dremio.dac.model.sources.SourcePath;
import com.dremio.dac.model.sources.SourceUI;
import com.dremio.dac.server.BaseTestServer;
import com.dremio.dac.service.source.SourceService;
import com.dremio.exec.store.dfs.NASConf;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.jobs.JobRequest;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.jobs.NoOpJobStatusListener;
import com.dremio.service.jobs.SqlQuery;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.file.FileFormat;
import com.dremio.service.namespace.file.proto.ExcelFileConfig;
import com.dremio.service.namespace.file.proto.FileType;
import com.dremio.service.namespace.file.proto.JsonFileConfig;
import com.dremio.service.namespace.file.proto.ParquetFileConfig;
import com.dremio.service.namespace.file.proto.TextFileConfig;
import com.dremio.service.namespace.file.proto.XlsFileConfig;

/**
 * Tests to create, update and execute queries on physical datasets..
 */
public class TestPhysicalDatasets extends BaseTestServer {

  @Before
  public void setup() throws Exception {
    clearAllDataExceptUser();
    final SourceService sourceService = newSourceService();
    {
      final NASConf nas = new NASConf();
      nas.path = "/";
      SourceUI source = new SourceUI();
      source.setName("dacfs_test");
      source.setConfig(nas);
      sourceService.registerSourceWithRuntime(source);
//      namespaceService.addOrUpdateSource(new SourcePath(new SourceName(nas.getName())).toNamespaceKey(), nas.asSourceConfig());
    }
  }

  private static String getSchemaPath(String file) throws IOException {
    return "dacfs_test." + PathUtils.constructFullPath(PathUtils.toPathComponents(new Path(FileUtils.getResourceAsFile(file).getAbsolutePath())));
  }

  private static SqlQuery createQuery(String file) throws IOException {
    return new SqlQuery(format("select * from %s", getSchemaPath(file)), DEFAULT_USERNAME);
  }

  private static String getUrlPath(String file) throws IOException {
    return new Path(FileUtils.getResourceAsFile(file).getAbsolutePath()).toString();
  }

  private void checkCounts(String parentPath, String name, boolean isQueryable, int jobCount, int descendants, int datasetCount) throws Exception {
    Folder parent = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/folder/" + parentPath)).buildGet(), Folder.class);
    for (com.dremio.file.File file : parent.getContents().getFiles()) {
      if (name.equals(file.getName())) {
        assertEquals("jobCount for file " + parentPath + "/" + name, jobCount, (int)file.getJobCount());
        assertEquals("isQueryable for file " + parentPath + "/" + name, isQueryable, file.isQueryable());
        return;
      }
    }
    for (PhysicalDataset physicalDataset : parent.getContents().getPhysicalDatasets()) {
      if (name.equals(physicalDataset.getDatasetName())) {
        assertEquals("jobCount for physical dataset " + parentPath + "/" + name, jobCount, (int)physicalDataset.getJobCount());
        return;
      }
    }
    for (Folder folder : parent.getContents().getFolders()) {
      if (name.equals(folder.getName())) {
        assertEquals("isQueryable for folder " + parentPath + "/" + name, isQueryable, folder.isQueryable());
        return;
      }
    }
  }

  @Test
  public void testJsonFile() throws Exception {
    final JobsService jobsService = l(JobsService.class);
    JobUI job = new JobUI(jobsService.submitJob(JobRequest.newBuilder()
        .setSqlQuery(createQuery("/datasets/users.json"))
        .setQueryType(QueryType.UI_RUN)
        .build(), NoOpJobStatusListener.INSTANCE));
    JobDataFragment jobData = job.getData().truncate(500);
    assertEquals(3, jobData.getReturnedRowCount());
    assertEquals(2, jobData.getColumns().size());


    String fileUrlPath = getUrlPath("/datasets/users.json");
    String fileParentUrlPath = getUrlPath("/datasets/");

    JsonFileConfig jsonFileConfig = new JsonFileConfig();
    doc("preview json source file");
    JobDataFragment data = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_preview" + fileUrlPath)).buildPost(Entity.json(jsonFileConfig)), JobDataFragment.class);
    assertEquals(3, data.getReturnedRowCount());
    assertEquals(2, data.getColumns().size());

    doc("creating dataset from source file");
    InitialPreviewResponse createResponse = expectSuccess(getBuilder(getAPIv2().path(
        "source/dacfs_test/new_untitled_from_file/" + getUrlPath("/datasets/users.json"))).buildPost(Entity.json("")),
        InitialPreviewResponse.class);
    assertEquals(2, createResponse.getData().getColumns().size());

    checkCounts(fileParentUrlPath, "users.json", true, 2, 0, 0);
  }

  @Test
  public void testCommaSeparatedTextFile() throws Exception {
    TextFileConfig fileConfig = new TextFileConfig();
    fileConfig.setFieldDelimiter(",");
    fileConfig.setLineDelimiter("\n");
    fileConfig.setName("comma.txt");

    String fileUrlPath = getUrlPath("/datasets/text/comma.txt");
    String fileParentUrlPath = getUrlPath("/datasets/text/");

    doc("preview data for source file");
    JobDataFragment data = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_preview" + fileUrlPath)).buildPost(Entity.json(fileConfig)), JobDataFragment.class);
    assertEquals(4, data.getReturnedRowCount());
    assertEquals(3, data.getColumns().size());
    checkCounts(fileParentUrlPath, "comma.txt", false /* false because we have not saved dataset yet */, 0, 0, 0); // previews are internal queries
  }

  @Test
  public void testCommaSeparatedCsv() throws Exception {
    final JobsService jobsService = l(JobsService.class);
    TextFileConfig fileConfig = new TextFileConfig();
    fileConfig.setFieldDelimiter(",");
    fileConfig.setLineDelimiter("\n");
    fileConfig.setName("comma.csv");
    String fileUrlPath = getUrlPath("/datasets/text/comma.txt");
    String fileParentUrlPath = getUrlPath("/datasets/text/");

    doc("preview data for source file");
    JobDataFragment data = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_preview/" + fileUrlPath)).buildPost(Entity.json(fileConfig)), JobDataFragment.class);
    assertEquals(4, data.getReturnedRowCount());
    assertEquals(3, data.getColumns().size());

    fileConfig.setExtractHeader(true);
//    fileConfig.setVersion(0L);
    expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_format/" + fileUrlPath)).buildPut(Entity.json(fileConfig)));
    data = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_preview/"+ fileUrlPath)).buildPost(Entity.json(fileConfig)), JobDataFragment.class);
    assertEquals(3, data.getReturnedRowCount());
    assertEquals(3, data.getColumns().size());

    JobUI job = new JobUI(jobsService.submitJob(JobRequest.newBuilder()
        .setSqlQuery(createQuery("/datasets/text/comma.txt"))
        .setQueryType(QueryType.UI_RUN)
        .build(), NoOpJobStatusListener.INSTANCE));
    JobDataFragment jobData = job.getData().truncate(500);
    assertEquals(3, jobData.getReturnedRowCount());
    assertEquals(3, jobData.getColumns().size());

    checkCounts(fileParentUrlPath, "comma.txt", true, 1, 0, 0);
  }

  @Test
  public void testCommaSeparatedCsvWindowsLineEndings() throws Exception {
    final JobsService jobsService = l(JobsService.class);
    TextFileConfig fileConfig = new TextFileConfig();
    fileConfig.setFieldDelimiter(",");
    fileConfig.setName("comma_windows_lineseparator.csv");
    String fileUrlPath = getUrlPath("/datasets/csv/comma_windows_lineseparator.csv");
    String fileParentUrlPath = getUrlPath("/datasets/csv/");

    doc("preview data for source file");
    JobDataFragment data = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_preview/" + fileUrlPath)).buildPost(Entity.json(fileConfig)), JobDataFragment.class);
    assertEquals(4, data.getReturnedRowCount());
    assertEquals(3, data.getColumns().size());

    fileConfig.setExtractHeader(true);
//    fileConfig.setVersion(0L);
    expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_format/" + fileUrlPath)).buildPut(Entity.json(fileConfig)));
    data = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_preview/"+ fileUrlPath)).buildPost(Entity.json(fileConfig)), JobDataFragment.class);
    assertEquals(3, data.getReturnedRowCount());
    assertEquals(3, data.getColumns().size());

    JobUI job = new JobUI(jobsService.submitJob(JobRequest.newBuilder()
        .setSqlQuery(createQuery("/datasets/csv/comma_windows_lineseparator.csv"))
        .setQueryType(QueryType.UI_RUN)
        .build(), NoOpJobStatusListener.INSTANCE));
    JobDataFragment jobData = job.getData().truncate(500);
    assertEquals(3, jobData.getReturnedRowCount());
    assertEquals(3, jobData.getColumns().size());

    checkCounts(fileParentUrlPath, "comma_windows_lineseparator.csv", true, 1, 0, 0);
  }

  @Test
  public void testCommaSeparatedCsvTrimHeader() throws Exception {
    final JobsService jobsService = l(JobsService.class);
    TextFileConfig fileConfig = new TextFileConfig();
    fileConfig.setFieldDelimiter(",");
    // we set the wrong delimiter to test the header trimming
    fileConfig.setLineDelimiter("\n");
    fileConfig.setTrimHeader(true);
    fileConfig.setExtractHeader(true);
    fileConfig.setName("comma_windows_lineseparator.csv");
    String fileUrlPath = getUrlPath("/datasets/csv/comma_windows_lineseparator.csv");

    JobDataFragment data = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_preview/" + fileUrlPath)).buildPost(Entity.json(fileConfig)), JobDataFragment.class);
    assertEquals(3, data.getReturnedRowCount());
    assertEquals(3, data.getColumns().size());
    // the column name would be address\r if trimHeader was false
    assertEquals("address", data.getColumns().get(2).getName());

    fileConfig.setTrimHeader(false);
    JobDataFragment data2 = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_preview/" + fileUrlPath)).buildPost(Entity.json(fileConfig)), JobDataFragment.class);
    assertEquals(3, data2.getReturnedRowCount());
    assertEquals(3, data2.getColumns().size());
    // with header trimming turned off, we should see the \r character
    assertEquals("address\r", data2.getColumns().get(2).getName());
  }

  @Test
  public void testParquetFile() throws Exception {
    final JobsService jobsService = l(JobsService.class);
    String fileUrlPath = getUrlPath("/singlefile_parquet_dir/0_0_0.parquet");
    String fileParentUrlPath = getUrlPath("/singlefile_parquet_dir/");

    ParquetFileConfig fileConfig = new ParquetFileConfig();
    JobDataFragment data = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_preview/" + fileUrlPath)).buildPost(Entity.json(fileConfig)), JobDataFragment.class);
    assertEquals(25, data.getReturnedRowCount());
    assertEquals(4, data.getColumns().size());

    JobUI job = new JobUI(jobsService.submitJob(JobRequest.newBuilder()
        .setSqlQuery(createQuery("/singlefile_parquet_dir/0_0_0.parquet"))
        .setQueryType(QueryType.UI_RUN)
        .build(), NoOpJobStatusListener.INSTANCE));
    JobDataFragment jobData = job.getData().truncate(500);
    assertEquals(25, jobData.getReturnedRowCount());
    assertEquals(4, jobData.getColumns().size());

    checkCounts(fileParentUrlPath, "0_0_0.parquet", true, 1, 0, 0);
  }

  @Test
  public void testCreateExternalDatasetOnFile() throws Exception {
    final JobsService jobsService = l(JobsService.class);

    JobUI job = new JobUI(jobsService.submitJob(JobRequest.newBuilder()
        .setSqlQuery(createQuery("/datasets/csv/comma.csv"))
        .setQueryType(QueryType.UI_RUN)
        .build(), NoOpJobStatusListener.INSTANCE));
    job.getData().loadIfNecessary();
    String filePath1 = getUrlPath("/datasets/csv/comma.csv");
    String fileParentUrlPath = getUrlPath("/datasets/");

    TextFileConfig format1 = (TextFileConfig) expectSuccess(getBuilder(getAPIv2().path(
      "/source/dacfs_test/file_format/" + filePath1)).buildGet(), FileFormatUI.class).getFileFormat();
    assertNotNull(format1);
    assertEquals(FileType.TEXT, format1.getFileType());

    job = new JobUI(jobsService.submitJob(JobRequest.newBuilder()
        .setSqlQuery(createQuery("/datasets/tab.tsv"))
        .setQueryType(QueryType.UI_RUN)
        .build(), NoOpJobStatusListener.INSTANCE));
    job.getData().loadIfNecessary();
    filePath1 = getUrlPath("/datasets/tab.tsv");
    format1 = (TextFileConfig) expectSuccess(getBuilder(getAPIv2().path(
      "/source/dacfs_test/file_format/" + filePath1)).buildGet(), FileFormatUI.class).getFileFormat();
    assertNotNull(format1);
    // TODO (Amit H) define separate classes for each type in FileFormatDefinitions
    assertEquals(FileType.TEXT, format1.getFileType());

    checkCounts(fileParentUrlPath, "comma.csv", true, 1, 0, 0);
    checkCounts(fileParentUrlPath, "tab.tsv", true, 1, 0, 0);
  }

  @Test
  public void testQueryOnFolder() throws Exception {
    TextFileConfig fileConfig = new TextFileConfig();
    fileConfig.setFieldDelimiter(",");
    fileConfig.setName("comma.txt");

    String filePath = getUrlPath("/datasets/folderdataset");
    String fileParentPath = getUrlPath("/datasets/");

    doc("preview data for source folder");
    JobDataFragment data = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/folder_preview/" + filePath)).buildPost(Entity.json(fileConfig)), JobDataFragment.class);
    assertEquals(12, data.getReturnedRowCount());

    expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/folder_format/" + filePath)).buildPut(Entity.json(fileConfig)));

    doc("creating dataset from source folder");
    expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/new_untitled_from_folder/" + filePath)).buildPost(Entity.json("")), InitialPreviewResponse.class);

    checkCounts(fileParentPath, "folderdataset", true, 1, 0, 0);
  }

  /*
  @Test
  public void testSubSchemaListing() throws Exception {
    final StoragePluginRegistry pluginRegistry = getCurrentDremioDaemon().getDremio().getStoragePluginRegistry();
    final FileSystemPlugin plugin = (FileSystemPlugin) pluginRegistry.getPlugin("dacfs_test");

    SchemaPlus rootSchema = CalciteSchema.createRootSchema(false, false).plus();
    Constructor<SchemaConfig> config = SchemaConfig.class.getDeclaredConstructor(String.class, SchemaConfigInfoProvider.class,
      boolean.class);
    config.setAccessible(true);
    SchemaConfig schemaConfig = config.newInstance("test_user", null, true);
    plugin.registerSchemas(schemaConfig, rootSchema);
    assertEquals(0, rootSchema.getSubSchema("dacfs_test").getSubSchemaNames().size());
    assertEquals(0, rootSchema.getSubSchema("dacfs_test").getTableNames().size());

    getNamespaceService().tryCreatePhysicalDataset(new PhysicalDatasetPath("dacfs_test.tmp.foo1").toNamespaceKey(),
      toDatasetConfig(new PhysicalDatasetConfig().setType(DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER)));
    getNamespaceService().tryCreatePhysicalDataset(new PhysicalDatasetPath("dacfs_test.home.bar").toNamespaceKey(),
      toDatasetConfig(new PhysicalDatasetConfig().setType(DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER)));
    getNamespaceService().tryCreatePhysicalDataset(new PhysicalDatasetPath("dacfs_test.tmp.foo2").toNamespaceKey(),
      toDatasetConfig(new PhysicalDatasetConfig().setType(DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER)));

    rootSchema = CalciteSchema.createRootSchema(false, false).plus();
    config.setAccessible(true);
    plugin.registerSchemas(schemaConfig, rootSchema);

    assertEquals(0, rootSchema.getSubSchema("dacfs_test").getSubSchemaNames().size());
    assertEquals(3, rootSchema.getSubSchema("dacfs_test").getTableNames().size());
  }
  */

  @Test
  public void listSource() throws Exception {
    SourceUI source = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test")).buildGet(), SourceUI.class);
    System.out.println(source.getContents());
  }

  @Test
  public void listFolder() throws Exception {
    doc("list source folder");
    String filePath = getUrlPath("/datasets/text");
    Folder folder = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/folder/"+filePath)).buildGet(), Folder.class);
    NamespaceTree ns = folder.getContents();
    assertEquals(0, ns.getFolders().size());
    assertEquals(3, ns.getFiles().size());
    assertEquals(0, ns.getPhysicalDatasets().size());

    for(int i=0; i<3; i++) {
      assertFalse(ns.getFiles().get(i).isQueryable());
    }

    TextFileConfig fileConfig = new TextFileConfig();
    fileConfig.setFieldDelimiter(",");
    fileConfig.setName("comma.txt");

    expectSuccess(getBuilder(getAPIv2().path(ns.getFiles().get(0).getLinks().get("format"))).
      buildPut(Entity.json(fileConfig)), FileFormatUI.class);

    expectSuccess(getBuilder(getAPIv2().path(ns.getFiles().get(1).getLinks().get("format"))).
      buildPut(Entity.json(fileConfig)), FileFormatUI.class);

    expectSuccess(getBuilder(getAPIv2().path(ns.getFiles().get(2).getLinks().get("format"))).
      buildPut(Entity.json(fileConfig)), FileFormatUI.class);

    folder = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/folder/" + filePath)).buildGet(), Folder.class);
    ns = folder.getContents();
    assertEquals(0, ns.getFolders().size());
    assertEquals(3, ns.getFiles().size());
    assertEquals(0, ns.getPhysicalDatasets().size());
    assertTrue(ns.getFiles().get(0).isQueryable());
    assertNotNull(ns.getFiles().get(0).getId());
    assertEquals(0, (long) ns.getFiles().get(0).getJobCount()); // ui preview queries dont count towards job count
    assertTrue(ns.getFiles().get(1).isQueryable());
    assertNotNull(ns.getFiles().get(1).getId());
    assertEquals(0, (long) ns.getFiles().get(1).getJobCount());
    assertTrue(ns.getFiles().get(2).isQueryable());
    assertNotNull(ns.getFiles().get(2).getId());
    assertEquals(0, (long) ns.getFiles().get(2).getJobCount());
  }

  @Test
  public void testPhysicalDatasetSourceFolders() throws Exception {
    populateInitialData();

    Files.createDirectories(new File(getPopulator().getPath().toFile(), "tmp/_dac/folder1").toPath());
    Files.createDirectories(new File(getPopulator().getPath().toFile(), "tmp/_dac/folder2").toPath());
    Files.createDirectories(new File(getPopulator().getPath().toFile(), "tmp/_dac/folder3").toPath());

    doc("get default format for folder");
    String folderFormatUrl = "/source/LocalFS1/folder_format/tmp/_dac/folder1";
    FileFormatUI defaultFormat = expectSuccess(getBuilder(getAPIv2().path(folderFormatUrl)).buildGet(), FileFormatUI.class);
    assertEquals(folderFormatUrl, defaultFormat.getLinks().get("self"));

    TextFileConfig fileConfig = new TextFileConfig();
    fileConfig.setComment("#");
    fileConfig.setFieldDelimiter("|");
    //fileConfig.setSkipFirstLine(true);
    //fileConfig.setExtractHeader(true);
    fileConfig.setName("fff");
    fileConfig.setVersion(null);

    FileFormat fileFormat1 = FileFormat.getForFolder(fileConfig.asFileConfig());
    fileFormat1.setName("tmp._dac.folder1");

    FileFormat fileFormat2 = FileFormat.getForFolder(fileConfig.asFileConfig());
    fileFormat2.setName("tmp._dac.folder2");

    doc("create physical dataset from source folder");
    expectSuccess(getBuilder(getAPIv2().path(
      "/source/LocalFS1/folder_format/tmp/_dac/folder1")).buildPut(Entity.json(fileFormat1)));

    expectSuccess(getBuilder(getAPIv2().path(
      "/source/LocalFS1/folder_format/tmp/_dac/folder2")).buildPut(Entity.json(fileFormat2)));

    doc("get physical dataset config from source folder");
    fileFormat1 = expectSuccess(getBuilder(getAPIv2().path(
      "/source/LocalFS1/folder_format/tmp/_dac/folder1")).buildGet(), FileFormatUI.class).getFileFormat();
    assertEquals(fileFormat1.getName(), fileFormat1.getName());

    fileFormat2 = expectSuccess(getBuilder(getAPIv2().path(
      "/source/LocalFS1/folder_format/tmp/_dac/folder2")).buildGet(), FileFormatUI.class).getFileFormat();
    assertEquals(fileFormat2.getName(), fileFormat2.getName());


    doc("test updating the format settings of folder dataset");
    {
      fileConfig.setFieldDelimiter(",");
      fileConfig.setVersion(fileFormat1.getVersion());
      fileFormat1 = FileFormat.getForFolder(fileConfig.asFileConfig());

      expectSuccess(getBuilder(getAPIv2().path(
          "/source/LocalFS1/folder_format/tmp/_dac/folder1")).buildPut(Entity.json(fileFormat1)));

      // retrieve the format back and check the updates are present.
      fileFormat1 = expectSuccess(getBuilder(getAPIv2().path(
          "/source/LocalFS1/folder_format/tmp/_dac/folder1")).buildGet(), FileFormatUI.class).getFileFormat();

      assertTrue(fileFormat1 instanceof TextFileConfig);
      assertEquals(",", ((TextFileConfig)fileFormat1).getFieldDelimiter());
    }

    doc("list source folder and see if folder1 and folder2 are marked as physical dataset");
    Folder folder = expectSuccess(getBuilder(getAPIv2().path("/source/LocalFS1/folder/tmp/_dac")).buildGet(), Folder.class);
    NamespaceTree ns = folder.getContents();
    assertEquals(3, ns.getFolders().size());
    assertEquals(0, ns.getFiles().size());
    assertEquals(0, ns.getPhysicalDatasets().size());

    for (Folder f : ns.getFolders()) {
      if ("folder1".equals(f.getName())) {
        assertTrue(f.isQueryable());
      } else if ("folder2".equals(f.getName())) {
        assertTrue(f.isQueryable());
      } else if ("folder3".equals(f.getName())) {
        assertFalse(f.isQueryable());
      } else {
        fail("Invalid folder found " + f.getFullPathList());
      }
    }

    doc("delete physical dataset for source folder");
    expectSuccess(getBuilder(getAPIv2().path("/source/LocalFS1/folder_format/tmp/_dac/folder1").queryParam("version", fileFormat1.getVersion())).buildDelete());

    FileFormat fileFormat = expectSuccess(getBuilder(getAPIv2().path("/source/LocalFS1/folder_format/tmp/_dac/folder1")).buildGet(), FileFormatUI.class).getFileFormat();
    assertEquals(FileType.UNKNOWN, fileFormat.getFileType());

    folder = expectSuccess(getBuilder(getAPIv2().path("/source/LocalFS1/folder/tmp/_dac")).buildGet(), Folder.class);
    ns = folder.getContents();
    assertEquals(3, ns.getFolders().size());
    assertEquals(0, ns.getFiles().size());
    assertEquals(0, ns.getPhysicalDatasets().size());

    for (Folder f : ns.getFolders()) {
      if ("folder1".equals(f.getName())) {
        assertFalse(f.isQueryable());
      } else if ("folder2".equals(f.getName())) {
        assertTrue(f.isQueryable());
      } else if ("folder3".equals(f.getName())) {
        assertFalse(f.isQueryable());
      } else {
        fail("Invalid folder found " + f.getFullPathList());
      }
    }

  }

  @Test
  @Ignore("DX-10523")
  public void testPhysicalDatasetSourceFiles() throws Exception {
    final NASConf config = new NASConf();
    config.path = "/";
    SourceUI source = new SourceUI();
    source.setName("src");
    source.setCtime(1000L);
    source.setConfig(config);

    NamespaceService namespaceService = newNamespaceService();
    namespaceService.addOrUpdateSource(new SourcePath("src").toNamespaceKey(), source.asSourceConfig());

    TextFileConfig fileConfig = new TextFileConfig();
    fileConfig.setComment("#");
    fileConfig.setFieldDelimiter("|");
    fileConfig.setExtractHeader(true);
    fileConfig.setName("fff");

    doc("create physical dataset from source file/ set format settings on a file in source");
    expectSuccess(getBuilder(getAPIv2().path(
      "/source/src/file_format/file1")).buildPut(Entity.json(fileConfig)));

    doc("get physical dataset config from source file/get format settings on a file in source");
    TextFileConfig format1 = (TextFileConfig) expectSuccess(getBuilder(getAPIv2().path(
      "/source/src/file_format/file1")).buildGet(), FileFormatUI.class).getFileFormat();

    assertEquals(fileConfig.getName(), format1.getName());
    assertEquals(fileConfig.getFieldDelimiter(), format1.getFieldDelimiter());
    assertEquals(fileConfig.getExtractHeader(), format1.getExtractHeader());
    assertEquals(fileConfig.asFileConfig().getType(), format1.asFileConfig().getType());
    assertEquals(fileConfig.asFileConfig().getOwner(), format1.asFileConfig().getOwner());

    doc("delete physical dataset for source file/delete format settings on a file in source");
    expectSuccess(getBuilder(getAPIv2().path("/source/src/file_format/file1").queryParam("version", format1.getVersion())).buildDelete());

    FileFormat fileFormat = expectSuccess(getBuilder(getAPIv2().path("/source/src/file_format/file1")).buildGet(), FileFormatUI.class).getFileFormat();
    assertEquals(FileType.UNKNOWN, fileFormat.getFileType());
  }

  @Test
  public void testExcelWithHeaderAndMergeCellExpansion() throws Exception {
    Invocation inv = getExcelTestQueryInvocation(getUrlPath("/testfiles/excel.xlsx"), "sheet 1", true, true);
    JobDataFragment data = expectSuccess(inv, JobDataFragment.class);
    assertEquals(5, data.getReturnedRowCount());
    assertEquals(5, data.getColumns().size());
  }

  @Test
  public void testXlsWithHeaderAndMergeCellExpansion() throws Exception {
    Invocation inv = getXlsTestQueryInvocation(getUrlPath("/testfiles/excel.xls"), "sheet 1", true, true);
    JobDataFragment data = expectSuccess(inv, JobDataFragment.class);
    assertEquals(5, data.getReturnedRowCount());
    assertEquals(5, data.getColumns().size());
  }

  @Test
  public void testExcelWithHeaderAndNoMergeCellExpansion() throws Exception {
    Invocation inv = getExcelTestQueryInvocation(getUrlPath("/testfiles/excel.xlsx"), "sheet 1", true, false);
    JobDataFragment data = expectSuccess(inv, JobDataFragment.class);
    assertEquals(5, data.getReturnedRowCount());
    assertEquals(5, data.getColumns().size());
  }

  @Test
  public void testXlsWithHeaderAndNoMergeCellExpansion() throws Exception {
    Invocation inv = getXlsTestQueryInvocation(getUrlPath("/testfiles/excel.xls"), "sheet 1", true, false);
    JobDataFragment data = expectSuccess(inv, JobDataFragment.class);
    assertEquals(5, data.getReturnedRowCount());
    assertEquals(5, data.getColumns().size());
  }

  @Test
  public void testExcelNoWithHeaderAndNoMergeCellExpansion() throws Exception {
    Invocation inv = getExcelTestQueryInvocation(getUrlPath("/testfiles/excel.xlsx"), "Sheet 1", false, false);
    JobDataFragment data = expectSuccess(inv, JobDataFragment.class);
    assertEquals(6, data.getReturnedRowCount());
    assertEquals(5, data.getColumns().size());
  }

  @Test
  public void testXlsNoWithHeaderAndNoMergeCellExpansion() throws Exception {
    Invocation inv = getXlsTestQueryInvocation(getUrlPath("/testfiles/excel.xls"), "Sheet 1", false, false);
    JobDataFragment data = expectSuccess(inv, JobDataFragment.class);
    assertEquals(6, data.getReturnedRowCount());
    assertEquals(5, data.getColumns().size());
  }

  @Test
  public void testExcelNullSheetName() throws Exception {
    Invocation inv = getExcelTestQueryInvocation(getUrlPath("/testfiles/excel.xlsx"), null, false, false);
    JobDataFragment data = expectSuccess(inv, JobDataFragment.class);
    assertEquals(6, data.getReturnedRowCount());
    assertEquals(5, data.getColumns().size());
  }

  @Test
  public void testXlsNullSheetName() throws Exception {
    Invocation inv = getXlsTestQueryInvocation(getUrlPath("/testfiles/excel.xls"), null, false, false);
    JobDataFragment data = expectSuccess(inv, JobDataFragment.class);
    assertEquals(6, data.getReturnedRowCount());
    assertEquals(5, data.getColumns().size());
  }

  private Invocation getExcelTestQueryInvocation(String filePath, String sheet, boolean extractHeader,
                                                 boolean hasMergedCells) throws Exception {
    ExcelFileConfig fileConfig = new ExcelFileConfig();
    fileConfig.setSheetName(sheet);
    if (extractHeader) {
      fileConfig.setExtractHeader(true);
    } // false is the default value
    if (hasMergedCells) {
      fileConfig.setHasMergedCells(true);
    } // false is the default value

    return getBuilder(getAPIv2().path("/source/dacfs_test/file_preview" + filePath))
      .buildPost(Entity.json(fileConfig));
  }

  private Invocation getXlsTestQueryInvocation(String filePath, String sheet, boolean extractHeader,
                                                 boolean hasMergedCells) throws Exception {
    XlsFileConfig fileConfig = new XlsFileConfig();
    fileConfig.setSheetName(sheet);
    if (extractHeader) {
      fileConfig.setExtractHeader(true);
    } // false is the default value
    if (hasMergedCells) {
      fileConfig.setHasMergedCells(true);
    } // false is the default value

    return getBuilder(getAPIv2().path("/source/dacfs_test/file_preview" + filePath))
            .buildPost(Entity.json(fileConfig));
  }

  // Based off data created by TestParquetMetadataCache:testCache
  @Test
  public void testQueryOnParquetDirWithMetadata() throws Exception {
    final JobsService jobsService = l(JobsService.class);

    JobUI job = new JobUI(jobsService.submitJob(JobRequest.newBuilder()
        .setSqlQuery(createQuery("/nation_ctas"))
        .build(), NoOpJobStatusListener.INSTANCE));
    JobDataFragment jobData = job.getData().truncate(500);
    assertEquals(50, jobData.getReturnedRowCount());
    // extra column for "dir" (t1 and t2 are directories under nation_ctas)
    assertEquals(5, jobData.getColumns().size());
  }

  @Test
  public void testQueryOnParquetDirWithSingleFile() throws Exception {
    final JobsService jobsService = l(JobsService.class);
    JobUI job = new JobUI(jobsService.submitJob(JobRequest.newBuilder()
        .setSqlQuery(createQuery("/singlefile_parquet_dir"))
        .build(), NoOpJobStatusListener.INSTANCE));
    JobDataFragment jobData = job.getData().truncate(500);

    assertEquals(25, jobData.getReturnedRowCount());
    assertEquals(4, jobData.getColumns().size());
  }

  @Test
  public void testDefaultFileFormatForParquetFolder() throws Exception {
    doc("get physical dataset config from source folder");
    String folderPath = getUrlPath("/singlefile_parquet_dir");
    FileFormat fileFormat = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/folder_format/" + folderPath)).buildGet(),
      FileFormatUI.class).getFileFormat();
    assertEquals(FileType.PARQUET, fileFormat.getFileType());
  }

  @Test
  public void testDefaultFileFormatForTextFolder() throws Exception {
    String folderPath = getUrlPath("/datasets/text");
    FileFormat fileFormat = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/folder_format/" + folderPath)).buildGet(),
      FileFormatUI.class).getFileFormat();
    assertEquals(FileType.TEXT, fileFormat.getFileType());
  }

  @Test
  public void testDefaultFileFormatForCsvFolder() throws Exception {
    String folderPath = getUrlPath("/datasets/csv");
    FileFormat fileFormat = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/folder_format/" + folderPath)).buildGet(),
      FileFormatUI.class).getFileFormat();
    assertEquals(FileType.TEXT, fileFormat.getFileType());
  }

  @Test
  public void testDefaultFileFormatForJsonFolder() throws Exception {
    String folderPath = getUrlPath("/json");
    FileFormat fileFormat = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/folder_format/" + folderPath)).buildGet(),
      FileFormatUI.class).getFileFormat();
    assertEquals(FileType.JSON, fileFormat.getFileType());
  }

  @Test
  public void testDefaultFileFormatForParquetFile() throws Exception {
    String filePath = getUrlPath("/singlefile_parquet_dir/0_0_0.parquet");
    FileFormat fileFormat = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_format/" + filePath)).buildGet(),
      FileFormatUI.class).getFileFormat();
    assertEquals(FileType.PARQUET, fileFormat.getFileType());
  }

  @Test
  public void testDefaultFileFormatForCsvFile() throws Exception {
    String filePath = getUrlPath("/datasets/csv/comma.csv");
    FileFormat fileFormat = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_format/" + filePath)).buildGet(),
      FileFormatUI.class).getFileFormat();
    assertEquals(FileType.TEXT, fileFormat.getFileType());
  }

  @Test
  public void testDefaultFileFormatForTextFile() throws Exception {
    String filePath = getUrlPath("/datasets/text/comma.txt");
    FileFormat fileFormat = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_format/" + filePath)).buildGet(),
      FileFormatUI.class).getFileFormat();
    assertEquals(FileType.TEXT, fileFormat.getFileType());
  }

  @Test
  public void testDefaultFileFormatForJsonFile() throws Exception {
    String filePath = getUrlPath("/json/mixed.json");
    FileFormat fileFormat = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_format/" + filePath)).buildGet(),
      FileFormatUI.class).getFileFormat();
    assertEquals(FileType.JSON, fileFormat.getFileType());
  }

  @Test
  public void testPreviewTinyAcqWithHeader() throws Exception {
    TextFileConfig fileConfig = new TextFileConfig();
    fileConfig.setFieldDelimiter("|");
    fileConfig.setLineDelimiter("\n");
    fileConfig.setName("tinyacq.txt");
    fileConfig.setExtractHeader(true);

    String fileUrlPath = getUrlPath("/datasets/tinyacq.txt");

    doc("preview data for source file");
    JobDataFragment data = expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_preview/" + fileUrlPath)).buildPost(Entity.json(fileConfig)), JobDataFragment.class);
    assertEquals(23, data.getColumns().size());
    assertEquals(500, data.getReturnedRowCount());
  }

  @Test
  public void testQueryTinyAcqWithHeader() throws Exception {
    final JobsService jobsService = l(JobsService.class);
    TextFileConfig fileConfig = new TextFileConfig();
    fileConfig.setFieldDelimiter("|");
    fileConfig.setLineDelimiter("\n");
    fileConfig.setName("tinyacq.txt");
    fileConfig.setExtractHeader(true);

    String fileUrlPath = getUrlPath("/datasets/tinyacq.txt");

    expectSuccess(getBuilder(getAPIv2().path("/source/dacfs_test/file_format/" + fileUrlPath)).buildPut(Entity.json(fileConfig)));
    JobUI job = new JobUI(jobsService.submitJob(JobRequest.newBuilder()
        .setSqlQuery(createQuery("/datasets/tinyacq.txt"))
        .build(), NoOpJobStatusListener.INSTANCE));
    JobDataFragment jobData = job.getData().truncate(500);
    assertEquals(23, jobData.getColumns().size());
    assertEquals(500, jobData.getReturnedRowCount());
  }

  /*
  @Test
  public void testInfoSchema() throws Exception {
    TextFileConfig fileConfig = new TextFileConfig();
    fileConfig.setName("blah");

    getNamespaceService().tryCreatePhysicalDataset(new PhysicalDatasetPath(getSchemaPath("/json")).toNamespaceKey(),
      toDatasetConfig(new PhysicalDatasetConfig()
        .setName("json")
        .setFormatSettings(fileConfig.asFileConfig())
        .setType(DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER)));
    getNamespaceService().addOrUpdateDataset(new PhysicalDatasetPath(getSchemaPath("/nation_ctas")).toNamespaceKey(),
      toDatasetConfig(new PhysicalDatasetConfig()
        .setName("nation_ctas")
        .setFormatSettings(fileConfig.asFileConfig())
        .setType(DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER)));
    getNamespaceService().tryCreatePhysicalDataset(new PhysicalDatasetPath(getSchemaPath("/datasets/csv/comma.csv")).toNamespaceKey(),
      toDatasetConfig(new PhysicalDatasetConfig()
        .setName("comma.csv")
        .setFormatSettings(fileConfig.asFileConfig())
        .setType(DatasetType.PHYSICAL_DATASET_SOURCE_FILE)));

    String query = "select * from INFORMATION_SCHEMA.\"TABLES\" where TABLE_SCHEMA like '%dacfs_test%'";
    Job job1 = l(JobsService.class).submitExternalJob(new SqlQuery(query, "test_user"), QueryType.UNKNOWN);
    JobData job1Data = job1.getData().trunc(500);
    assertEquals(3, job1Data.getReturnedRowCount());

    getNamespaceService().tryCreatePhysicalDataset(new PhysicalDatasetPath(getSchemaPath("/datasets/text/comma.txt")).toNamespaceKey(),
      toDatasetConfig(new PhysicalDatasetConfig()
        .setName("comma.txt")
        .setFormatSettings(fileConfig.asFileConfig())
        .setType(DatasetType.PHYSICAL_DATASET_SOURCE_FILE)));

    Job job2 = l(JobsService.class).submitExternalJob(new SqlQuery(query, "test_user"), QueryType.UNKNOWN);
    JobData job2Data = job2.getData().trunc(500);
    assertEquals(4, job2Data.getReturnedRowCount());

    SchemaPlus rootSchema = CalciteSchema.createRootSchema(false, false).plus();
    Constructor<SchemaConfig> config = SchemaConfig.class.getDeclaredConstructor(String.class, SchemaConfigInfoProvider.class,
      boolean.class);
    config.setAccessible(true);
    SchemaConfig schemaConfig = config.newInstance("test_user", null, true);
    getCurrentDremioDaemon().getDremio().getStoragePluginRegistry().getSchemaFactory().registerSchemas(schemaConfig, rootSchema);

    for (int i = 0; i < 4; ++i) {
      assertNotNull(rootSchema.getSubSchema((job2Data.extractValue("TABLE_SCHEMA", i)).toString()).
        getTable((job2Data.extractValue("TABLE_NAME", i)).toString()));
    }
    getNamespaceService().deleteDataset(new PhysicalDatasetPath(getSchemaPath("/nation_ctas")).toNamespaceKey(), 0);
    getNamespaceService().deleteDataset(new PhysicalDatasetPath(getSchemaPath("/json")).toNamespaceKey(), 0);
    Job job3 = l(JobsService.class).submitExternalJob(new SqlQuery(query, "test_user"), QueryType.UNKNOWN);
    JobData job3Data = job3.getData().trunc(500);
    assertEquals(2, job3Data.getReturnedRowCount());
  }
  */
}

