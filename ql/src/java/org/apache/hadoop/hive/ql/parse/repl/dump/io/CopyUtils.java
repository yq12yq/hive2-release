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

package org.apache.hadoop.hive.ql.parse.repl.dump.io;

import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.FileUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.io.HdfsUtils;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.hadoop.hive.io.HdfsUtils.HadoopFileStatus;

class CopyUtils {

  private static final Logger LOG = LoggerFactory.getLogger(CopyUtils.class);

  private final HiveConf hiveConf;
  private final long maxCopyFileSize;
  private final long maxNumberOfFiles;
  private final boolean hiveInTest, inheritPermissions;
  private final String copyAsUser;

  CopyUtils(HiveConf hiveConf) {
    this.hiveConf = hiveConf;
    maxNumberOfFiles = hiveConf.getLongVar(HiveConf.ConfVars.HIVE_EXEC_COPYFILE_MAXNUMFILES);
    maxCopyFileSize = hiveConf.getLongVar(HiveConf.ConfVars.HIVE_EXEC_COPYFILE_MAXSIZE);
    hiveInTest = hiveConf.getBoolVar(HiveConf.ConfVars.HIVE_IN_TEST);
    copyAsUser = hiveConf.getVar(HiveConf.ConfVars.HIVE_DISTCP_DOAS_USER);
    inheritPermissions = hiveConf.getBoolVar(HiveConf.ConfVars.HIVE_WAREHOUSE_SUBDIR_INHERIT_PERMS);
  }

  void doCopy(Path destination, List<Path> srcPaths) throws IOException {
    Map<FileSystem, List<Path>> map = fsToFileMap(srcPaths);
    FileSystem destinationFs = destination.getFileSystem(hiveConf);
    HadoopFileStatus sourceStatus =
        new HadoopFileStatus(hiveConf, destinationFs, destination.getParent());

    for (Map.Entry<FileSystem, List<Path>> entry : map.entrySet()) {
      if (regularCopy(destinationFs, entry)) {
        Path[] paths = entry.getValue().toArray(new Path[] {});
        FileUtil.copy(entry.getKey(), paths, destinationFs, destination, false, true, hiveConf);
        if (inheritPermissions) {
          try {
            HdfsUtils.setFullFileStatus(hiveConf, sourceStatus, destinationFs, destination, true);
          } catch (Exception e) {
            LOG.warn("Error setting permissions or group of " + destination, e);
          }
        }
      } else {
        FileUtils.distCp(
            entry.getKey(),   // source file system
            entry.getValue(), // list of source paths
            destination,
            false,
            copyAsUser,
            hiveConf,
            ShimLoader.getHadoopShims()
        );
      }
    }
  }

  /*
      Check for conditions that will lead to local copy, checks are:
      1. we are testing hive.
      2. both source and destination are same FileSystem
      3. either source or destination is a "local" FileSystem("file")
      4. aggregate fileSize of all source Paths(can be directory /  file) is less than configured size.
      5. number of files of all source Paths(can be directory /  file) is less than configured size.
  */
  private boolean regularCopy(FileSystem destinationFs, Map.Entry<FileSystem, List<Path>> entry)
      throws IOException {
    if (hiveInTest) {
      return true;
    }
    FileSystem sourceFs = entry.getKey();
    boolean isLocalFs = isLocal(sourceFs) || isLocal(destinationFs);
    boolean sameFs = sourceFs.equals(destinationFs);
    if (isLocalFs || sameFs) {
      return true;
    }

    /*
       we have reached the point where we are transferring files across fileSystems.
    */
    long size = 0;
    long numberOfFiles = 0;

    for (Path path : entry.getValue()) {
      ContentSummary contentSummary = sourceFs.getContentSummary(path);
      size += contentSummary.getLength();
      numberOfFiles += contentSummary.getFileCount();
      if (limitReachedForLocalCopy(size, numberOfFiles)) {
        return false;
      }
    }
    return true;
  }

  private boolean limitReachedForLocalCopy(long size, long numberOfFiles) {
    boolean result = size > maxCopyFileSize || numberOfFiles > maxNumberOfFiles;
    if (result) {
      LOG.info("Source is {} bytes. (MAX: {})", size, maxCopyFileSize);
      LOG.info("Source is {} files. (MAX: {})", numberOfFiles, maxNumberOfFiles);
      LOG.info("going to launch distributed copy (distcp) job.");
    }
    return result;
  }

  private boolean isLocal(FileSystem fs) {
    return fs.getScheme().equals("file");
  }

  private Map<FileSystem, List<Path>> fsToFileMap(List<Path> srcPaths) throws IOException {
    Map<FileSystem, List<Path>> result = new HashMap<>();
    for (Path path : srcPaths) {
      FileSystem fileSystem = path.getFileSystem(hiveConf);
      if (!result.containsKey(fileSystem)) {
        result.put(fileSystem, new ArrayList<Path>());
      }
      result.get(fileSystem).add(path);
    }
    return result;
  }
}
