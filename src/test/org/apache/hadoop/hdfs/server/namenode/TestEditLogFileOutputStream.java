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

package org.apache.hadoop.hdfs.server.namenode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;

import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.DU;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.server.common.Storage.StorageDirectory;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogLoader.EditLogValidation;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class TestEditLogFileOutputStream {
  
  private final static long PREALLOCATION_LENGTH = (1024 * 1024);
  private final static int HEADER_LEN = 17;
  private static final File TEST_EDITS =
    new File(System.getProperty("test.build.data","/tmp"),
             "editLogStream.dat");

  @Before
  public void deleteEditsFile() {
    TEST_EDITS.delete();
  }
  
  @Test
  public void testPreallocation() throws IOException {
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = new MiniDFSCluster(conf, 0, true, null);

    StorageDirectory sd = cluster.getNameNode().getFSImage()
      .storage.getStorageDir(0);
    File editLog = NNStorage.getInProgressEditsFile(sd, 0);

    EditLogValidation validation = EditLogFileInputStream.validateEditLog(editLog);
    assertEquals("Edit log should contain a header as valid length",
        HEADER_LEN, validation.getValidLength());
    assertEquals(1, validation.getNumTransactions());
    assertEquals("Edit log should have 1MB pre-allocated",
        PREALLOCATION_LENGTH, editLog.length());
    

    cluster.getFileSystem().mkdirs(new Path("/tmp"),
        new FsPermission((short)777));

    long oldLength = validation.getValidLength();
    validation = EditLogFileInputStream.validateEditLog(editLog);
    assertTrue("Edit log should have more valid data after writing a txn " +
        "(was: " + oldLength + " now: " + validation.getValidLength() + ")",
        validation.getValidLength() > oldLength);
    assertEquals(2, validation.getNumTransactions());

    assertEquals("Edit log should be 1MB long, plus 4 bytes for the version number",
        PREALLOCATION_LENGTH, editLog.length());
  }
  
  @Test
  public void testClose() throws IOException {
    String errorMessage = "TESTING: fc.truncate() threw IOE";
    
    File testDir = new File(System.getProperty("test.build.data", "/tmp"));
    assertTrue("could not create test directory", testDir.exists() || testDir.mkdirs());
    File f = new File(testDir, "edits");
    assertTrue("could not create test file", f.createNewFile());
    EditLogFileOutputStream elos = new EditLogFileOutputStream(f, null);
    
    FileChannel mockFc = Mockito.spy(elos.getFileChannelForTesting());
    Mockito.doThrow(new IOException(errorMessage)).when(mockFc).truncate(Mockito.anyLong());
    elos.setFileChannelForTesting(mockFc);
    
    try {
      elos.close();
      fail("elos.close() succeeded, but should have thrown");
    } catch (IOException e) {
      assertEquals("wrong IOE thrown from elos.close()", e.getMessage(), errorMessage);
    }
    
    assertEquals("fc was not nulled when elos.close() failed", elos.getFileChannelForTesting(), null);
  }

  /**
   * Tests EditLogFileOutputStream doesn't throw NullPointerException on
   * close/abort sequence. See HDFS-2011.
   */
  @Test
  public void testEditLogFileOutputStreamCloseAbort() throws IOException {
    // abort after a close should just ignore
    EditLogFileOutputStream editLogStream =
      new EditLogFileOutputStream(TEST_EDITS, null);
    editLogStream.close();
    editLogStream.abort();
  }

  /**
   * Tests EditLogFileOutputStream doesn't throw NullPointerException on
   * close/close sequence. See HDFS-2011.
   */
  @Test
  public void testEditLogFileOutputStreamCloseClose() throws IOException {
    // close after a close should result in an IOE
    EditLogFileOutputStream editLogStream =
      new EditLogFileOutputStream(TEST_EDITS, null);
    editLogStream.close();
    try {
      editLogStream.close();
    } catch (IOException ioe) {
      String msg = StringUtils.stringifyException(ioe);
      assertTrue(msg, msg.contains("Trying to use aborted output stream"));
    }
  }
  
  /**
   * Tests EditLogFileOutputStream doesn't throw NullPointerException on being
   * abort/abort sequence. See HDFS-2011.
   */
  @Test
  public void testEditLogFileOutputStreamAbortAbort() throws IOException {
    // abort after a close should just ignore
    EditLogFileOutputStream editLogStream =
      new EditLogFileOutputStream(TEST_EDITS, null);
    editLogStream.abort();
    editLogStream.abort();
  }

}
