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
package org.apache.hadoop.hdfs;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.util.Random;
import java.util.zip.CRC32;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.raid.ErasureCodeType;
import org.apache.hadoop.raid.ParityFilePair;
import org.apache.hadoop.raid.RaidNode;
import org.apache.hadoop.raid.RaidUtils;
import org.apache.hadoop.util.StringUtils;

public class TestRaidDfs extends TestCase {
  final static String TEST_DIR = new File(System.getProperty("test.build.data",
      "build/contrib/raid/test/data")).getAbsolutePath();
  final static long RELOAD_INTERVAL = 1000;
  final static Log LOG = LogFactory.getLog("org.apache.hadoop.raid.TestRaidDfs");
  final static int NUM_DATANODES = 3;
  static {
    ParityFilePair.disableCacheUsedInTestOnly();
  }

  Configuration conf;
  String namenode = null;
  String hftp = null;
  MiniDFSCluster dfs = null;
  FileSystem fileSys = null;
  String jobTrackerName = null;
  ErasureCodeType code;
  int stripeLength;

  private void mySetup(
      String erasureCode, int rsParityLength) throws Exception {

    new File(TEST_DIR).mkdirs(); // Make sure data directory exists
    conf = new Configuration();

    conf.setInt("raid.encoder.bufsize", 128);
    conf.setInt("raid.decoder.bufsize", 128);
    conf.setInt(RaidNode.RS_PARITY_LENGTH_KEY, rsParityLength);

    // scan all policies once every 5 second
    conf.setLong("raid.policy.rescan.interval", 5000);

    // make all deletions not go through Trash
    conf.set("fs.shell.delete.classname", "org.apache.hadoop.hdfs.DFSClient");
    // Reduce run time for the test.
    conf.setInt("dfs.client.max.block.acquire.failures", 1);
    conf.setInt("dfs.client.baseTimeWindow.waitOn.BlockMissingException", 10);

    // do not use map-reduce cluster for Raiding
    conf.set("raid.classname", "org.apache.hadoop.raid.LocalRaidNode");

    conf.set("raid.server.address", "localhost:0");
    conf.setInt("hdfs.raid.stripeLength", stripeLength);
    conf.set("xor".equals(erasureCode) ? RaidNode.RAID_LOCATION_KEY :
             RaidNode.RAIDRS_LOCATION_KEY, "/destraid");

    dfs = new MiniDFSCluster(conf, NUM_DATANODES, true, null);
    dfs.waitActive();
    fileSys = dfs.getFileSystem();
    namenode = fileSys.getUri().toString();
    hftp = "hftp://localhost.localdomain:" + dfs.getNameNodePort();

    FileSystem.setDefaultUri(conf, namenode);
  }

  private void myTearDown() throws Exception {
    if (dfs != null) { dfs.shutdown(); }
  }
  
  private LocatedBlocks getBlockLocations(Path file, long length)
    throws IOException {
    DistributedFileSystem dfs = (DistributedFileSystem) fileSys;
    return RaidDFSUtil.getBlockLocations(
      dfs, file.toUri().getPath(), 0, length);
  }

  private LocatedBlocks getBlockLocations(Path file)
    throws IOException {
    FileStatus stat = fileSys.getFileStatus(file);
    return getBlockLocations(file, stat.getLen());
  }

  private DistributedRaidFileSystem getRaidFS() throws IOException {
    DistributedFileSystem dfs = (DistributedFileSystem)fileSys;
    Configuration clientConf = new Configuration(conf);
    clientConf.set("fs.hdfs.impl", "org.apache.hadoop.hdfs.DistributedRaidFileSystem");
    clientConf.set("fs.raid.underlyingfs.impl", "org.apache.hadoop.hdfs.DistributedFileSystem");
    clientConf.setBoolean("fs.hdfs.impl.disable.cache", true);
    URI dfsUri = dfs.getUri();
    return (DistributedRaidFileSystem)FileSystem.get(dfsUri, clientConf);
  }

  public static void waitForFileRaided(
    Log logger, FileSystem fileSys, Path file, Path destPath)
  throws IOException, InterruptedException {
    FileStatus parityStat = null;
    String fileName = file.getName().toString();
    // wait till file is raided
    while (parityStat == null) {
      logger.info("Waiting for files to be raided.");
      try {
        FileStatus[] listPaths = fileSys.listStatus(destPath);
        if (listPaths != null) {
          for (FileStatus f : listPaths) {
            logger.info("File raided so far : " + f.getPath());
            String found = f.getPath().getName().toString();
            if (fileName.equals(found)) {
              parityStat = f;
              break;
            }
          }
        }
      } catch (FileNotFoundException e) {
        //ignore
      }
      Thread.sleep(1000);                  // keep waiting
    }

    while (true) {
      LocatedBlocks locations = null;
      DistributedFileSystem dfs = (DistributedFileSystem) fileSys;
      locations = RaidDFSUtil.getBlockLocations(
        dfs, file.toUri().getPath(), 0, parityStat.getLen());
      if (!locations.isUnderConstruction()) {
        break;
      }
      Thread.sleep(1000);
    }

    while (true) {
      FileStatus stat = fileSys.getFileStatus(file);
      if (stat.getReplication() == 1) break;
      Thread.sleep(1000);
    }
  }

  private void corruptBlockAndValidate(Path srcFile, Path destPath,
    int[] listBlockNumToCorrupt, long blockSize, int numBlocks)
  throws IOException, InterruptedException {
    int repl = 1;
    long crc = createTestFilePartialLastBlock(fileSys, srcFile, repl,
                  numBlocks, blockSize);
    long length = fileSys.getFileStatus(srcFile).getLen();

    RaidNode.doRaid1(conf, fileSys.getFileStatus(srcFile),
      destPath, code, new RaidNode.Statistics(), RaidUtils.NULL_PROGRESSABLE,
      false, repl, repl, stripeLength);

    // Delete first block of file
    for (int blockNumToCorrupt : listBlockNumToCorrupt) {
      LOG.info("Corrupt block " + blockNumToCorrupt + " of file " + srcFile);
      LocatedBlocks locations = getBlockLocations(srcFile);
      corruptBlock(srcFile, locations.get(blockNumToCorrupt).getBlock(),
            NUM_DATANODES, true);
    }

    // Validate
    DistributedRaidFileSystem raidfs = getRaidFS();
    assertTrue(validateFile(raidfs, srcFile, length, crc));
  }

  /**
   * Create a file, corrupt several blocks in it and ensure that the file can be
   * read through DistributedRaidFileSystem by ReedSolomon coding.
   */
  public void testRaidDfsRs() throws Exception {
    LOG.info("Test testRaidDfs started.");

    code = ErasureCodeType.RS;
    long blockSize = 8192L;
    int numBlocks = 8;
    stripeLength = 3;
    mySetup("rs", 3);

    Path destPath = new Path("/destraid/user/dhruba/raidtest");
    int[][] corrupt = {{1, 2, 3}, {1, 4, 7}, {3, 6, 7}};
    try {
      for (int i = 0; i < corrupt.length; i++) {
        Path file = new Path("/user/dhruba/raidtest/file" + i);
        corruptBlockAndValidate(
            file, new Path("/destraid"), corrupt[i], blockSize, numBlocks);
      }
    } catch (Exception e) {
      LOG.info("testRaidDfs Exception " + e +
                StringUtils.stringifyException(e));
      throw e;
    } finally {
      myTearDown();
    }
    LOG.info("Test testRaidDfs completed.");
  }

  /**
   * Test DistributedRaidFileSystem.readFully()
   */
  public void testReadFully() throws Exception {
    code = ErasureCodeType.XOR;
    stripeLength = 3;
    mySetup("xor", 1);

    try {
      Path file = new Path("/user/raid/raidtest/file1");
      long crc = createTestFile(fileSys, file, 1, 8, 8192L);
      FileStatus stat = fileSys.getFileStatus(file);
      LOG.info("Created " + file + ", crc=" + crc + ", len=" + stat.getLen());

      byte[] filebytes = new byte[(int)stat.getLen()];
      // Test that readFully returns the correct CRC when there are no errors.
      DistributedRaidFileSystem raidfs = getRaidFS();
      FSDataInputStream stm = raidfs.open(file);
      stm.readFully(0, filebytes);
      assertEquals(crc, bufferCRC(filebytes));
      stm.close();

      // Generate parity.
      RaidNode.doRaid1(conf, fileSys.getFileStatus(file),
        new Path("/destraid"), code, new RaidNode.Statistics(),
        RaidUtils.NULL_PROGRESSABLE,
        false, 1, 1, stripeLength);
      int[] corrupt = {0, 4, 7}; // first, last and middle block
      for (int blockIdx : corrupt) {
        LOG.info("Corrupt block " + blockIdx + " of file " + file);
        LocatedBlocks locations = getBlockLocations(file);
        corruptBlock(file, locations.get(blockIdx).getBlock(),
            NUM_DATANODES, true);
      }
      // Test that readFully returns the correct CRC when there are errors.
      stm = raidfs.open(file);
      stm.readFully(0, filebytes);
      assertEquals(crc, bufferCRC(filebytes));
    } finally {
      myTearDown();
    }
  }

  /**
   * Test that access time and mtime of a source file do not change after
   * raiding.
   */
  public void testAccessTime() throws Exception {
    LOG.info("Test testAccessTime started.");

    code = ErasureCodeType.XOR;
    long blockSize = 8192L;
    int numBlocks = 8;
    int repl = 1;
    stripeLength = 3;
    mySetup("xor", 1);

    Path file = new Path("/user/dhruba/raidtest/file");
    Path destPath = new Path("/destraid/user/dhruba/raidtest");
    createTestFilePartialLastBlock(fileSys, file, repl, numBlocks, blockSize);
    FileStatus stat = fileSys.getFileStatus(file);

    try {
      RaidNode.doRaid1(conf, fileSys.getFileStatus(file),
        new Path("/destraid"), code, new RaidNode.Statistics(),
        RaidUtils.NULL_PROGRESSABLE, false, repl, repl, stripeLength);

      FileStatus newStat = fileSys.getFileStatus(file);

      assertEquals(stat.getModificationTime(), newStat.getModificationTime());
      assertEquals(stat.getAccessTime(), newStat.getAccessTime());
    } finally {
      myTearDown();
    }
  }

  /**
   * Create a file, corrupt a block in it and ensure that the file can be
   * read through DistributedRaidFileSystem by XOR code.
   */
  public void testRaidDfsXor() throws Exception {
    LOG.info("Test testRaidDfs started.");

    code = ErasureCodeType.XOR;
    long blockSize = 8192L;
    int numBlocks = 8;
    stripeLength = 3;
    mySetup("xor", 1);

    Path destPath = new Path("/destraid/user/dhruba/raidtest");
    int[][] corrupt = {{0}, {4}, {7}}; // first, last and middle block
    try {
      for (int i = 0; i < corrupt.length; i++) {
        Path file = new Path("/user/dhruba/raidtest/" + i);
        corruptBlockAndValidate(
            file, new Path("/destraid"), corrupt[i], blockSize, numBlocks);
      }
    } catch (Exception e) {
      LOG.info("testRaidDfs Exception " + e +
                StringUtils.stringifyException(e));
      throw e;
    } finally {
      myTearDown();
    }
    LOG.info("Test testRaidDfs completed.");
  }

  public void testRead() throws Exception {
    mySetup("xor", 1);
    try {
      Path p = new Path("/tmp/testRead." + System.currentTimeMillis());
      long crc = createTestFile(fileSys, p, 1, 8, 8192L);
      FileStatus stat = fileSys.getFileStatus(p);
      LOG.info("Created " + p + ", crc=" + crc + ", len=" + stat.getLen());

      // Test that readFully returns the correct CRC when there are no errors.
      DistributedRaidFileSystem raidfs = getRaidFS();
      FSDataInputStream stm = raidfs.open(p);
      // Should be able to read all bytes.
      for (int i = 0; i < stat.getLen(); i++) {
        int val = stm.read();
        if (val < 0) {
          LOG.error("Invalid value " + val + " at index " + i);
        }
        assertTrue(val >= 0);
      }
      stm.close();
      // One more read should return -1 to indicate EOF.
      assertEquals(-1, stm.read());
    } finally {
      myTearDown();
    }
  }

  public void testZeroLengthFile() throws Exception {
    mySetup("xor", 1);
    try {
      Path p = new Path("/tmp/testZero." + System.currentTimeMillis());
      fileSys.create(p).close();
      FileStatus stat = fileSys.getFileStatus(p);
      LOG.info("Created " + p + ", len=" + stat.getLen());

      // Test that readFully returns the correct CRC when there are no errors.
      DistributedRaidFileSystem raidfs = getRaidFS();
      FSDataInputStream stm = raidfs.open(p);
      assertEquals(-1, stm.read());
    } finally {
      myTearDown();
    }
  }

  //
  // creates a file and populate it with random data. Returns its crc.
  //
  public static long createTestFile(FileSystem fileSys, Path name, int repl,
                        int numBlocks, long blocksize)
    throws IOException {
    CRC32 crc = new CRC32();
    Random rand = new Random();
    FSDataOutputStream stm = fileSys.create(name, true,
                                            fileSys.getConf().getInt("io.file.buffer.size", 4096),
                                            (short)repl, blocksize);
    // fill random data into file
    final byte[] b = new byte[(int)blocksize];
    for (int i = 0; i < numBlocks; i++) {
      rand.nextBytes(b);
      stm.write(b);
      crc.update(b);
    }
    stm.close();
    return crc.getValue();
  }

  //
  // Creates a file with partially full last block. Populate it with random
  // data. Returns its crc.
  //
  public static long createTestFilePartialLastBlock(
      FileSystem fileSys, Path name, int repl, int numBlocks, long blocksize)
    throws IOException {
    CRC32 crc = new CRC32();
    Random rand = new Random();
    FSDataOutputStream stm = fileSys.create(name, true,
                                            fileSys.getConf().getInt("io.file.buffer.size", 4096),
                                            (short)repl, blocksize);
    // Write whole blocks.
    byte[] b = new byte[(int)blocksize];
    for (int i = 1; i < numBlocks; i++) {
      rand.nextBytes(b);
      stm.write(b);
      crc.update(b);
    }
    // Write partial block.
    b = new byte[(int)blocksize/2 - 1];
    rand.nextBytes(b);
    stm.write(b);
    crc.update(b);

    stm.close();
    return crc.getValue();
  }

  static long bufferCRC(byte[] buf) {
    CRC32 crc = new CRC32();
    crc.update(buf, 0, buf.length);
    return crc.getValue();
  }

  //
  // validates that file matches the crc.
  //
  public static boolean validateFile(FileSystem fileSys, Path name, long length,
                                  long crc) 
    throws IOException {

    long numRead = 0;
    CRC32 newcrc = new CRC32();
    FSDataInputStream stm = fileSys.open(name);
    final byte[] b = new byte[4192];
    int num = 0;
    while (num >= 0) {
      num = stm.read(b);
      if (num < 0) {
        break;
      }
      numRead += num;
      newcrc.update(b, 0, num);
    }
    stm.close();

    if (numRead != length) {
      LOG.info("Number of bytes read " + numRead +
               " does not match file size " + length);
      return false;
    }

    LOG.info(" Newcrc " + newcrc.getValue() + " old crc " + crc);
    if (newcrc.getValue() != crc) {
      LOG.info("CRC mismatch of file " + name + ": " + newcrc + " vs. " + crc);
    }
    return true;
  }

  /*
   * The Data directories for a datanode
   */
  private static File[] getDataNodeDirs(int i) throws IOException {
    File base_dir = new File(System.getProperty("test.build.data"), "dfs/");
    File data_dir = new File(base_dir, "data");
    File dir1 = new File(data_dir, "data"+(2*i+1));
    File dir2 = new File(data_dir, "data"+(2*i+2));
    if (dir1.isDirectory() && dir2.isDirectory()) {
      File[] dir = new File[2];
      dir[0] = new File(dir1, "current");
      dir[1] = new File(dir2, "current"); 
      return dir;
    }
    return new File[0];
  }

  //
  // Delete/Corrupt specified block of file
  //
  public static void corruptBlock(Path file, Block blockNum,
                    int numDataNodes, boolean delete) throws IOException {
    long id = blockNum.getBlockId();

    // Now deliberately remove/truncate data blocks from the block.
    int numDeleted = 0;
    int numCorrupted = 0;
    for (int i = 0; i < numDataNodes; i++) {
      File[] dirs = getDataNodeDirs(i);

      for (int j = 0; j < dirs.length; j++) {
        File[] blocks = dirs[j].listFiles();
        assertTrue("Blocks do not exist in data-dir", (blocks != null) && (blocks.length >= 0));
        for (int idx = 0; idx < blocks.length; idx++) {
          if (blocks[idx].getName().startsWith("blk_" + id) &&
              !blocks[idx].getName().endsWith(".meta")) {
            if (delete) {
              blocks[idx].delete();
              LOG.info("Deleted block " + blocks[idx]);
              numDeleted++;
            } else {
              // Corrupt
              File f = blocks[idx];
              long seekPos = f.length()/2;
              RandomAccessFile raf = new RandomAccessFile(f, "rw");
              raf.seek(seekPos);
              int data = raf.readInt();
              raf.seek(seekPos);
              raf.writeInt(data+1);
              LOG.info("Corrupted block " + blocks[idx]);
              numCorrupted++;
            }
          }
        }
      }
    }
    assertTrue("Nothing corrupted or deleted",
              (numCorrupted + numDeleted) > 0);
  }

  public static void corruptBlock(Path file, Block blockNum,
                    int numDataNodes, long offset) throws IOException {
    long id = blockNum.getBlockId();

    // Now deliberately remove/truncate data blocks from the block.
    //
    for (int i = 0; i < numDataNodes; i++) {
      File[] dirs = getDataNodeDirs(i);
      
      for (int j = 0; j < dirs.length; j++) {
        File[] blocks = dirs[j].listFiles();
        assertTrue("Blocks do not exist in data-dir", (blocks != null) && (blocks.length >= 0));
        for (int idx = 0; idx < blocks.length; idx++) {
          if (blocks[idx].getName().startsWith("blk_" + id) &&
              !blocks[idx].getName().endsWith(".meta")) {
            // Corrupt
            File f = blocks[idx];
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            raf.seek(offset);
            int data = raf.readInt();
            raf.seek(offset);
            raf.writeInt(data+1);
            LOG.info("Corrupted block " + blocks[idx]);
          }
        }
      }
    }
  }
}
