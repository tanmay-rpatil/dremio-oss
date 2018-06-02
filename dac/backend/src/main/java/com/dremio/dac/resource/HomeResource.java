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
package com.dremio.dac.resource;

import static com.dremio.dac.util.DatasetsUtil.toDatasetConfig;
import static com.dremio.dac.util.DatasetsUtil.toFileConfig;
import static com.dremio.service.namespace.proto.NameSpaceContainer.Type.HOME;
import static java.lang.String.format;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;

import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.utils.PathUtils;
import com.dremio.common.utils.SqlUtils;
import com.dremio.dac.annotations.RestResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.dac.explore.DatasetsResource;
import com.dremio.dac.explore.model.Dataset;
import com.dremio.dac.explore.model.DatasetName;
import com.dremio.dac.explore.model.DatasetPath;
import com.dremio.dac.explore.model.DatasetResourcePath;
import com.dremio.dac.explore.model.DatasetVersionResourcePath;
import com.dremio.dac.explore.model.FileFormatUI;
import com.dremio.dac.explore.model.InitialPreviewResponse;
import com.dremio.dac.homefiles.HomeFileSystemStoragePlugin;
import com.dremio.dac.homefiles.HomeFileTool;
import com.dremio.dac.model.common.DACException;
import com.dremio.dac.model.common.NamespacePath;
import com.dremio.dac.model.folder.Folder;
import com.dremio.dac.model.folder.FolderName;
import com.dremio.dac.model.folder.FolderPath;
import com.dremio.dac.model.job.JobDataFragment;
import com.dremio.dac.model.job.JobUI;
import com.dremio.dac.model.namespace.NamespaceTree;
import com.dremio.dac.model.spaces.Home;
import com.dremio.dac.model.spaces.HomeName;
import com.dremio.dac.model.spaces.HomePath;
import com.dremio.dac.proto.model.dataset.VirtualDatasetUI;
import com.dremio.dac.server.InputValidation;
import com.dremio.dac.service.catalog.CatalogServiceHelper;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.dac.service.errors.ClientErrorException;
import com.dremio.dac.service.errors.DatasetNotFoundException;
import com.dremio.dac.service.errors.DatasetVersionNotFoundException;
import com.dremio.dac.service.errors.FileNotFoundException;
import com.dremio.dac.service.errors.FolderNotFoundException;
import com.dremio.dac.service.errors.HomeNotFoundException;
import com.dremio.dac.service.errors.NewDatasetQueryException;
import com.dremio.dac.service.errors.SourceNotFoundException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.file.File;
import com.dremio.file.FileName;
import com.dremio.file.FilePath;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.jobs.JobRequest;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.jobs.NoOpJobStatusListener;
import com.dremio.service.jobs.SqlQuery;
import com.dremio.service.namespace.BoundedDatasetCount;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.file.FileFormat;
import com.dremio.service.namespace.file.proto.FileConfig;
import com.dremio.service.namespace.file.proto.FileType;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.space.proto.ExtendedConfig;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.dremio.service.namespace.space.proto.HomeConfig;

/**
 * Resource for user home.
 */
@RestResource
@Secured
@RolesAllowed({"admin", "user"})
@Path("/home/{homeName}")
public class HomeResource {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HomeResource.class);

  private final NamespaceService namespaceService;
  private final DatasetVersionMutator datasetService;
  private final SecurityContext securityContext;
  private final JobsService jobsService;
  private final HomeName homeName;
  private final HomePath homePath;
  private final DatasetsResource datasetsResource;
  private final HomeFileTool fileStore;
  private final CatalogServiceHelper catalogServiceHelper;
  private final Catalog catalog;

  @Inject
  public HomeResource(
    NamespaceService namespaceService,
    DatasetVersionMutator datasetService,
    @Context SecurityContext securityContext,
    JobsService jobsService,
    DatasetsResource datasetsResource,
    HomeFileTool fileStore,
    CatalogServiceHelper catalogServiceHelper,
    Catalog catalog,
    @PathParam("homeName") HomeName homeName) {
    this.namespaceService = namespaceService;
    this.datasetService = datasetService;
    this.securityContext = securityContext;
    this.jobsService = jobsService;
    this.datasetsResource = datasetsResource;
    this.homeName = homeName;
    this.homePath = new HomePath(homeName);
    this.fileStore = fileStore;
    this.catalogServiceHelper = catalogServiceHelper;
    this.catalog = catalog;
  }

  protected Dataset newDataset(DatasetResourcePath resourcePath,
      DatasetVersionResourcePath versionedResourcePath,
      DatasetName datasetName,
      String sql,
      VirtualDatasetUI datasetConfig,
      int jobCount) {
    return Dataset.newInstance(resourcePath, versionedResourcePath, datasetName, sql, datasetConfig, jobCount);
  }

  protected File newFile(String id, NamespacePath filePath, FileFormat fileFormat, Integer jobCount,
      boolean isStaged, boolean isHomeFile, boolean isQueryable, DatasetType datasetType) throws Exception {
    return File.newInstance(id, filePath, fileFormat, jobCount, isStaged, isHomeFile, isQueryable);
  }

  protected Folder newFolder(FolderPath folderPath, FolderConfig folderConfig, NamespaceTree contents) throws NamespaceNotFoundException {
    return Folder.newInstance(folderPath, folderConfig, contents, false, false);
  }

  protected Home newHome(HomePath homePath, HomeConfig homeConfig) {
    return new Home(homePath, homeConfig);
  }

  protected NamespaceTree newNamespaceTree(List<NameSpaceContainer> children) throws DatasetNotFoundException, NamespaceException {
    return NamespaceTree.newInstance(datasetService, children, HOME);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Home getHome(@QueryParam("includeContents") @DefaultValue("true") boolean includeContents) throws NamespaceException, HomeNotFoundException, DatasetNotFoundException {
    try {
      long dsCount = namespaceService.getDatasetCount(homePath.toNamespaceKey(), BoundedDatasetCount.SEARCH_TIME_LIMIT_MS, BoundedDatasetCount.COUNT_LIMIT_TO_STOP_SEARCH).getCount();
      final HomeConfig homeConfig = namespaceService.getHome(homePath.toNamespaceKey()).setExtendedConfig(new ExtendedConfig().setDatasetCount(dsCount));
      Home home = newHome(homePath, homeConfig);
      if (includeContents) {
        home.setContents(newNamespaceTree(namespaceService.list(homePath.toNamespaceKey())));
      }
      return home;
    } catch (NamespaceNotFoundException nfe) {
      throw new HomeNotFoundException(homePath.getHomeName(), nfe);
    }
  }

  @GET
  @Path("dataset/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public Dataset getDataset(@PathParam("path") String path)
    throws NamespaceException, FileNotFoundException, DatasetNotFoundException {
    DatasetPath datasetPath = DatasetPath.fromURLPath(homeName, path);
    final DatasetConfig datasetConfig = namespaceService.getDataset(datasetPath.toNamespaceKey());
    final VirtualDatasetUI vds = datasetService.get(datasetPath, datasetConfig.getVirtualDataset().getVersion());
    return newDataset(
      new DatasetResourcePath(datasetPath),
      new DatasetVersionResourcePath(datasetPath, vds.getVersion()),
      datasetPath.getDataset(),
      vds.getSql(),
      vds,
      datasetService.getJobsCount(datasetPath.toNamespaceKey())
    );
  }


  @POST
  @Path("upload_start/{path: .*}")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.APPLICATION_JSON)
  public File uploadFile(@PathParam("path") String path,
                         @FormDataParam("file") InputStream fileInputStream,
                         @FormDataParam("file") FormDataContentDisposition contentDispositionHeader,
                         @FormDataParam("fileName") FileName fileName,
                         @QueryParam("extension") String extension) throws Exception {
    // add some validation
    InputValidation inputValidation = new InputValidation();
    inputValidation.validate(fileName);

    List<String> pathList = PathUtils.toPathComponents(path);
    pathList.add(SqlUtils.quoteIdentifier(fileName.getName()));

    final FilePath filePath = FilePath.fromURLPath(homeName, PathUtils.toFSPathString(pathList));

    final FileConfig config = new FileConfig();
    try {
      // upload file to staging area
      final org.apache.hadoop.fs.Path stagingLocation = fileStore.stageFile(filePath, extension, fileInputStream);
      config.setLocation(stagingLocation.toString());
      config.setName(filePath.getLeaf().getName());
      config.setCtime(System.currentTimeMillis());
      config.setFullPathList(filePath.toPathList());
      config.setOwner(securityContext.getUserPrincipal().getName());
      config.setType(FileFormat.getFileFormatType(Collections.singletonList(extension)));
    } catch (IOException ioe) {
      throw new DACException("Error writing to file at " + filePath, ioe);
    }
    final File file = newFile(filePath.toUrlPath(),
        filePath, FileFormat.getForFile(config), 0, true, true, true,
        DatasetType.PHYSICAL_DATASET_HOME_FILE
    );
    return file;
  }

  @POST
  @Path("upload_cancel/{path: .*}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public void cancelUploadFile(FileFormat fileFormat, @PathParam("path") String path) throws IOException, DACException {
    fileStore.deleteFile(fileFormat.getLocation());
  }

  @POST
  @Path("upload_finish/{path: .*}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public File finishUploadFile(FileFormat fileFormat, @PathParam("path") String path) throws Exception {
    final FilePath filePath = FilePath.fromURLPath(homeName, path);
    if (namespaceService.exists(filePath.toNamespaceKey())) {
      throw UserException.validationError()
          .message(format("File %s already exists", filePath.toPathString()))
          .build(logger);
    }
    final String fileName = filePath.getFileName().getName();
    final org.apache.hadoop.fs.Path finalLocation = fileStore.saveFile(fileFormat.getLocation(), filePath, fileFormat.getFileType());
    // save new name and location, full path
    fileFormat.setLocation(finalLocation.toString());
    fileFormat.setName(fileName);
    fileFormat.setFullPath(filePath.toPathList());
    fileFormat.setVersion(null);
    final DatasetConfig datasetConfig = toDatasetConfig(fileFormat.asFileConfig(), DatasetType.PHYSICAL_DATASET_HOME_FILE,
      securityContext.getUserPrincipal().getName(), null);
    catalog.createOrUpdateDataset(namespaceService, new NamespaceKey(HomeFileSystemStoragePlugin.HOME_PLUGIN_NAME), filePath.toNamespaceKey(), datasetConfig);
    fileFormat.setVersion(datasetConfig.getVersion());
    return newFile(
      datasetConfig.getId().getId(),
      filePath,
      fileFormat,
      datasetService.getJobsCount(filePath.toNamespaceKey()),
      false, true, false,
      DatasetType.PHYSICAL_DATASET_HOME_FILE
    );
  }

  @POST
  @Path("file_preview_unsaved/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public JobDataFragment previewFormatSettingsStaging(FileFormat fileFormat, @PathParam("path") String path)
    throws FileNotFoundException, SourceNotFoundException {
    FilePath filePath = FilePath.fromURLPath(homeName, path);
    logger.info("filePath: " + filePath.toPathString());
    // use file's location directly to query file
    String fileLocation = PathUtils.toDottedPath(new org.apache.hadoop.fs.Path(fileFormat.getLocation()));
    SqlQuery query = new SqlQuery(format("select * from table(%s.%s (%s)) limit 500",
        SqlUtils.quoteIdentifier(HomeFileSystemStoragePlugin.HOME_PLUGIN_NAME), fileLocation, fileFormat.toTableOptions()), securityContext.getUserPrincipal().getName());
    JobUI job = new JobUI(jobsService.submitJob(JobRequest.newBuilder()
        .setSqlQuery(query)
        .setQueryType(QueryType.UI_INITIAL_PREVIEW)
        .build(), NoOpJobStatusListener.INSTANCE));
    return job.getData().truncate(500);
  }

  @GET
  @Path("file/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public File getFile(@PathParam("path") String path)
    throws Exception {
    FilePath filePath = FilePath.fromURLPath(homeName, path);
    try {
      final DatasetConfig datasetConfig = namespaceService.getDataset(filePath.toNamespaceKey());
      final FileConfig fileConfig = toFileConfig(datasetConfig);
      final File file = newFile(
        datasetConfig.getId().getId(),
        filePath,
        FileFormat.getForFile(fileConfig),
        datasetService.getJobsCount(filePath.toNamespaceKey()),
        false, true,
        fileConfig.getType() != FileType.UNKNOWN,
        DatasetType.PHYSICAL_DATASET_HOME_FILE
      );
      return file;
    } catch (NamespaceNotFoundException nfe) {
      throw new FileNotFoundException(filePath, nfe);
    }
  }

  @DELETE
  @Path("file/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public void deleteFile(@PathParam("path") String path, @QueryParam("version") Long version) throws NamespaceException, DACException {
    FilePath filePath = FilePath.fromURLPath(homeName, path);
    if (version == null) {
      throw new ClientErrorException("missing version parameter");
    }
    try {
      catalogServiceHelper.deleteHomeDataset(namespaceService.getDataset(filePath.toNamespaceKey()), version);
    } catch (IOException ioe) {
      throw new DACException("Error deleting to file at " + filePath, ioe);
    }
  }

  @POST
  @Path("file_rename/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public File renameFile(@PathParam("path") String path, @QueryParam("renameTo") FileName renameTo) throws Exception {
    FilePath filePath = FilePath.fromURLPath(homeName, path);
    final FilePath newFilePath = filePath.rename(renameTo.getName());
    final DatasetConfig datasetConfig = namespaceService.renameDataset(filePath.toNamespaceKey(), newFilePath.toNamespaceKey());
    final FileConfig fileConfig = toFileConfig(datasetConfig);
    return newFile(
      datasetConfig.getId().getId(),
      newFilePath,
      FileFormat.getForFile(fileConfig),
      datasetService.getJobsCount(filePath.toNamespaceKey()),
      false, true, false,
      DatasetType.PHYSICAL_DATASET_HOME_FILE
    );
  }

  @GET
  @Path("file_format/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public FileFormatUI getFormatSettings(@PathParam("path") String path)
    throws FileNotFoundException, HomeNotFoundException, NamespaceException {
    FilePath filePath = FilePath.fromURLPath(homeName, path);
    final FileConfig fileConfig = toFileConfig(namespaceService.getDataset(filePath.toNamespaceKey()));
    return new FileFormatUI(FileFormat.getForFile(fileConfig), filePath);
  }

  @PUT
  @Path("file_format/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public FileFormatUI saveFormatSettings(FileFormat fileFormat, @PathParam("path") String path)
      throws FileNotFoundException, HomeNotFoundException, NamespaceException {
    FilePath filePath = FilePath.fromURLPath(homeName, path);
    // merge file configs
    final DatasetConfig existingDSConfig = namespaceService.getDataset(filePath.toNamespaceKey());
    final FileConfig oldConfig = toFileConfig(existingDSConfig);
    final FileConfig newConfig = fileFormat.asFileConfig();
    newConfig.setCtime(oldConfig.getCtime());
    newConfig.setFullPathList(oldConfig.getFullPathList());
    newConfig.setName(oldConfig.getName());
    newConfig.setOwner(oldConfig.getOwner());
    newConfig.setLocation(oldConfig.getLocation());
    catalog.createOrUpdateDataset(namespaceService, new NamespaceKey(HomeFileSystemStoragePlugin.HOME_PLUGIN_NAME), filePath.toNamespaceKey(), toDatasetConfig(newConfig, DatasetType.PHYSICAL_DATASET_HOME_FILE,
      securityContext.getUserPrincipal().getName(), existingDSConfig.getId()));
    return new FileFormatUI(FileFormat.getForFile(newConfig), filePath);
  }

  @POST
  @Path("file_preview/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public JobDataFragment previewFormatSettings(FileFormat fileFormat, @PathParam("path") String path)
      throws FileNotFoundException, SourceNotFoundException {
    FilePath filePath = FilePath.fromURLPath(homeName, path);
    logger.info("filePath: " + filePath.toPathString());
    // TODO, this should be moved to dataset resource and be paginated.
    SqlQuery query = new SqlQuery(format("select * from table(%s (%s)) limit 500", filePath.toPathString(), fileFormat.toTableOptions()), securityContext.getUserPrincipal().getName());
    JobUI job = new JobUI(jobsService.submitJob(JobRequest.newBuilder()
        .setSqlQuery(query)
        .setQueryType(QueryType.UI_INITIAL_PREVIEW)
        .build(), NoOpJobStatusListener.INSTANCE));
    return job.getData().truncate(500);
  }

  @GET
  @Path("/folder/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public Folder getFolder(@PathParam("path") String path, @QueryParam("includeContents") @DefaultValue("true") boolean includeContents) throws Exception {
    FolderPath folderPath = FolderPath.fromURLPath(homeName, path);
    try {
      final FolderConfig folderConfig = namespaceService.getFolder(folderPath.toNamespaceKey());
      final NamespaceTree contents = includeContents
          ? newNamespaceTree(namespaceService.list(folderPath.toNamespaceKey()))
          : null;
      return newFolder(folderPath, folderConfig, contents);
    } catch (NamespaceNotFoundException nfe) {
      throw new FolderNotFoundException(folderPath, nfe);
    }
  }

  @DELETE
  @Path("/folder/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public void deleteFolder(@PathParam("path") String path, @QueryParam("version") Long version) throws NamespaceException, FolderNotFoundException {
    FolderPath folderPath = FolderPath.fromURLPath(homeName, path);
    if (version == null) {
      throw new ClientErrorException("missing version parameter");
    }
    try {
      namespaceService.deleteFolder(folderPath.toNamespaceKey(), version);
    } catch (NamespaceNotFoundException nfe) {
      throw new FolderNotFoundException(folderPath, nfe);
    }
  }

  @POST
  @Path("/folder/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public Folder createFolder(FolderName name, @PathParam("path") String path) throws Exception  {
    String fullPath = PathUtils.toFSPathString(Arrays.asList(path, name.toString()));
    FolderPath folderPath = FolderPath.fromURLPath(homeName, fullPath);

    final FolderConfig folderConfig = new FolderConfig();
    folderConfig.setFullPathList(folderPath.toPathList());
    folderConfig.setName(folderPath.getFolderName().getName());
    try {
      namespaceService.addOrUpdateFolder(folderPath.toNamespaceKey(), folderConfig);
    } catch(NamespaceNotFoundException nfe) {
      throw new ClientErrorException("Parent folder doesn't exist", nfe);
    }

    return newFolder(folderPath, folderConfig, null);
  }

  @POST
  @Path("/new_untitled_from_file/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public InitialPreviewResponse createUntitledFromHomeFile(@PathParam("path") String path)
    throws DatasetNotFoundException, DatasetVersionNotFoundException, NamespaceException, NewDatasetQueryException {
    return datasetsResource.createUntitledFromHomeFile(homeName, path);
  }
}
