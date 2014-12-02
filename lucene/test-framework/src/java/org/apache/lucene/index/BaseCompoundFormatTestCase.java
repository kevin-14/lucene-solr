package org.apache.lucene.index;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.FlushInfo;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.MockDirectoryWrapper;
import org.apache.lucene.store.NRTCachingDirectory;
import org.apache.lucene.util.StringHelper;
import org.apache.lucene.util.TestUtil;
import org.apache.lucene.util.Version;

/**
 * Abstract class to do basic tests for a compound format.
 * NOTE: This test focuses on the compound impl, nothing else.
 * The [stretch] goal is for this test to be
 * so thorough in testing a new CompoundFormat that if this
 * test passes, then all Lucene/Solr tests should also pass.  Ie,
 * if there is some bug in a given CompoundFormat that this
 * test fails to catch then this test needs to be improved! */
public abstract class BaseCompoundFormatTestCase extends BaseIndexFileFormatTestCase {
    
  // test that empty CFS is empty
  public void testEmpty() throws IOException {
    Directory dir = newDirectory();
    
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Collections.<String>emptyList(), MergeState.CheckAbort.NONE, IOContext.DEFAULT);
    Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);
    assertEquals(0, cfs.listAll().length);
    cfs.close();
    dir.close();
  }
  
  /** 
   * This test creates compound file based on a single file.
   * Files of different sizes are tested: 0, 1, 10, 100 bytes.
   */
  public void testSingleFile() throws IOException {
    int data[] = new int[] { 0, 1, 10, 100 };
    for (int i=0; i<data.length; i++) {
      String testfile = "_" + i + ".test";
      Directory dir = newDirectory();
      createSequenceFile(dir, testfile, (byte) 0, data[i]);
      
      SegmentInfo si = newSegmentInfo(dir, "_" + i);
      si.getCodec().compoundFormat().write(dir, si, Collections.singleton(testfile), MergeState.CheckAbort.NONE, IOContext.DEFAULT);
      Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);
      
      IndexInput expected = dir.openInput(testfile, newIOContext(random()));
      IndexInput actual = cfs.openInput(testfile, newIOContext(random()));
      assertSameStreams(testfile, expected, actual);
      assertSameSeekBehavior(testfile, expected, actual);
      expected.close();
      actual.close();
      cfs.close();
      dir.close();
    }
  }
  
  /** 
   * This test creates compound file based on two files.
   */
  public void testTwoFiles() throws IOException {
    String files[] = { "_123.d1", "_123.d2" };
    Directory dir = newDirectory();
    createSequenceFile(dir, files[0], (byte) 0, 15);
    createSequenceFile(dir, files[1], (byte) 0, 114);
    
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Arrays.asList(files), MergeState.CheckAbort.NONE, IOContext.DEFAULT);
    Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);

    for (String file : files) {
      IndexInput expected = dir.openInput(file, newIOContext(random()));
      IndexInput actual = cfs.openInput(file, newIOContext(random()));
      assertSameStreams(file, expected, actual);
      assertSameSeekBehavior(file, expected, actual);
      expected.close();
      actual.close();
    }

    cfs.close();
    dir.close();
  }
  
  // test that a second call to close() behaves according to Closeable
  public void testDoubleClose() throws IOException {
    final String testfile = "_123.test";

    Directory dir = newDirectory();
    IndexOutput out = dir.createOutput(testfile, IOContext.DEFAULT);
    out.writeInt(3);
    out.close();
    
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Collections.singleton(testfile), MergeState.CheckAbort.NONE, IOContext.DEFAULT);
    Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);
    assertEquals(1, cfs.listAll().length);
    cfs.close();
    cfs.close(); // second close should not throw exception
    dir.close();
  }
  
  // LUCENE-5724: things like NRTCachingDir rely upon IOContext being properly passed down
  public void testPassIOContext() throws IOException {
    final String testfile = "_123.test";
    final IOContext myContext = new IOContext();

    Directory dir = new FilterDirectory(newDirectory()) {
      @Override
      public IndexOutput createOutput(String name, IOContext context) throws IOException {
        assertSame(myContext, context);
        return super.createOutput(name, context);
      }
    };
    IndexOutput out = dir.createOutput(testfile, myContext);
    out.writeInt(3);
    out.close();
    
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Collections.singleton(testfile), MergeState.CheckAbort.NONE, myContext);
    dir.close();
  }
  
  // LUCENE-5724: actually test we play nice with NRTCachingDir and massive file
  public void testLargeCFS() throws IOException {   
    final String testfile = "_123.test";
    IOContext context = new IOContext(new FlushInfo(0, 512*1024*1024));

    Directory dir = new NRTCachingDirectory(newFSDirectory(createTempDir()), 2.0, 25.0);

    IndexOutput out = dir.createOutput(testfile, context);
    byte[] bytes = new byte[512];
    for(int i=0;i<1024*1024;i++) {
      out.writeBytes(bytes, 0, bytes.length);
    }
    out.close();
    
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Collections.singleton(testfile), MergeState.CheckAbort.NONE, context);

    dir.close();
  }
  
  // Just tests that we can open all files returned by listAll
  public void testListAll() throws Exception {
    Directory dir = newDirectory();
    if (dir instanceof MockDirectoryWrapper) {
      // test lists files manually and tries to verify every .cfs it finds,
      // but a virus scanner could leave some trash.
      ((MockDirectoryWrapper)dir).setEnableVirusScanner(false);
    }
    // riw should sometimes create docvalues fields, etc
    RandomIndexWriter riw = new RandomIndexWriter(random(), dir);
    Document doc = new Document();
    // these fields should sometimes get term vectors, etc
    Field idField = newStringField("id", "", Field.Store.NO);
    Field bodyField = newTextField("body", "", Field.Store.NO);
    doc.add(idField);
    doc.add(bodyField);
    for (int i = 0; i < 100; i++) {
      idField.setStringValue(Integer.toString(i));
      bodyField.setStringValue(TestUtil.randomUnicodeString(random()));
      riw.addDocument(doc);
      if (random().nextInt(7) == 0) {
        riw.commit();
      }
    }
    riw.close();
    SegmentInfos infos = SegmentInfos.readLatestCommit(dir);
    for (SegmentCommitInfo si : infos) {
      if (si.info.getUseCompoundFile()) {
        try (Directory cfsDir = si.info.getCodec().compoundFormat().getCompoundReader(dir, si.info, newIOContext(random()))) {
          for (String cfsFile : cfsDir.listAll()) {
            try (IndexInput cfsIn = cfsDir.openInput(cfsFile, IOContext.DEFAULT)) {
              assert cfsIn != null;
            }
          }
        }
      }
    }
    dir.close();
  }
  
  // test that cfs reader is read-only
  public void testCreateOutputDisabled() throws IOException {
    Directory dir = newDirectory();
    
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Collections.<String>emptyList(), MergeState.CheckAbort.NONE, IOContext.DEFAULT);
    Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);
    try {
      cfs.createOutput("bogus", IOContext.DEFAULT);
      fail("didn't get expected exception");
    } catch (UnsupportedOperationException expected) {
      // expected UOE
    }
    cfs.close();
    dir.close();
  }
  
  // test that cfs reader is read-only
  public void testDeleteFileDisabled() throws IOException {
    final String testfile = "_123.test";

    Directory dir = newDirectory();
    IndexOutput out = dir.createOutput(testfile, IOContext.DEFAULT);
    out.writeInt(3);
    out.close();
 
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Collections.<String>emptyList(), MergeState.CheckAbort.NONE, IOContext.DEFAULT);
    Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);
    try {
      cfs.deleteFile(testfile);
      fail("didn't get expected exception");
    } catch (UnsupportedOperationException expected) {
      // expected UOE
    }
    cfs.close();
    dir.close();
  }
  
  // test that cfs reader is read-only
  public void testRenameFileDisabled() throws IOException {
    final String testfile = "_123.test";

    Directory dir = newDirectory();
    IndexOutput out = dir.createOutput(testfile, IOContext.DEFAULT);
    out.writeInt(3);
    out.close();
 
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Collections.<String>emptyList(), MergeState.CheckAbort.NONE, IOContext.DEFAULT);
    Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);
    try {
      cfs.renameFile(testfile, "bogus");
      fail("didn't get expected exception");
    } catch (UnsupportedOperationException expected) {
      // expected UOE
    }
    cfs.close();
    dir.close();
  }
  
  // test that cfs reader is read-only
  public void testSyncDisabled() throws IOException {
    final String testfile = "_123.test";

    Directory dir = newDirectory();
    IndexOutput out = dir.createOutput(testfile, IOContext.DEFAULT);
    out.writeInt(3);
    out.close();
 
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Collections.<String>emptyList(), MergeState.CheckAbort.NONE, IOContext.DEFAULT);
    Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);
    try {
      cfs.sync(Collections.singleton(testfile));
      fail("didn't get expected exception");
    } catch (UnsupportedOperationException expected) {
      // expected UOE
    }
    cfs.close();
    dir.close();
  }
  
  // test that cfs reader is read-only
  public void testMakeLockDisabled() throws IOException {
    final String testfile = "_123.test";

    Directory dir = newDirectory();
    IndexOutput out = dir.createOutput(testfile, IOContext.DEFAULT);
    out.writeInt(3);
    out.close();
 
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Collections.<String>emptyList(), MergeState.CheckAbort.NONE, IOContext.DEFAULT);
    Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);
    try {
      cfs.makeLock("foobar");
      fail("didn't get expected exception");
    } catch (UnsupportedOperationException expected) {
      // expected UOE
    }
    cfs.close();
    dir.close();
  }
  
  /** 
   * This test creates a compound file based on a large number of files of
   * various length. The file content is generated randomly. The sizes range
   * from 0 to 1Mb. Some of the sizes are selected to test the buffering
   * logic in the file reading code. For this the chunk variable is set to
   * the length of the buffer used internally by the compound file logic.
   */
  public void testRandomFiles() throws IOException {
    Directory dir = newDirectory();
    // Setup the test segment
    String segment = "_123";
    int chunk = 1024; // internal buffer size used by the stream
    createRandomFile(dir, segment + ".zero", 0);
    createRandomFile(dir, segment + ".one", 1);
    createRandomFile(dir, segment + ".ten", 10);
    createRandomFile(dir, segment + ".hundred", 100);
    createRandomFile(dir, segment + ".big1", chunk);
    createRandomFile(dir, segment + ".big2", chunk - 1);
    createRandomFile(dir, segment + ".big3", chunk + 1);
    createRandomFile(dir, segment + ".big4", 3 * chunk);
    createRandomFile(dir, segment + ".big5", 3 * chunk - 1);
    createRandomFile(dir, segment + ".big6", 3 * chunk + 1);
    createRandomFile(dir, segment + ".big7", 1000 * chunk);
    
    String files[] = dir.listAll();
    
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Arrays.asList(files), MergeState.CheckAbort.NONE, IOContext.DEFAULT);
    Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);
    
    for (int i = 0; i < files.length; i++) {
      IndexInput check = dir.openInput(files[i], newIOContext(random()));
      IndexInput test = cfs.openInput(files[i], newIOContext(random()));
      assertSameStreams(files[i], check, test);
      assertSameSeekBehavior(files[i], check, test);
      test.close();
      check.close();
    }
    cfs.close();
    dir.close();
  }
  
  // Make sure we don't somehow use more than 1 descriptor
  // when reading a CFS with many subs:
  public void testManySubFiles() throws IOException {
    final MockDirectoryWrapper dir = newMockFSDirectory(createTempDir("CFSManySubFiles"));
    
    final int FILE_COUNT = atLeast(500);
    
    for (int fileIdx = 0; fileIdx < FILE_COUNT; fileIdx++) {
      IndexOutput out = dir.createOutput("_123." + fileIdx, newIOContext(random()));
      out.writeByte((byte) fileIdx);
      out.close();
    }
    
    assertEquals(0, dir.getFileHandleCount());
    
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, Arrays.asList(dir.listAll()), MergeState.CheckAbort.NONE, IOContext.DEFAULT);
    Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);
    
    final IndexInput[] ins = new IndexInput[FILE_COUNT];
    for (int fileIdx = 0; fileIdx < FILE_COUNT; fileIdx++) {
      ins[fileIdx] = cfs.openInput("_123." + fileIdx, newIOContext(random()));
    }
    
    assertEquals(1, dir.getFileHandleCount());

    for (int fileIdx = 0; fileIdx < FILE_COUNT; fileIdx++) {
      assertEquals((byte) fileIdx, ins[fileIdx].readByte());
    }
    
    assertEquals(1, dir.getFileHandleCount());
    
    for(int fileIdx=0;fileIdx<FILE_COUNT;fileIdx++) {
      ins[fileIdx].close();
    }
    cfs.close();
    
    dir.close();
  }
  
  public void testClonedStreamsClosing() throws IOException {
    Directory dir = newDirectory();
    Directory cr = createLargeCFS(dir);
    
    // basic clone
    IndexInput expected = dir.openInput("_123.f11", newIOContext(random()));
    
    IndexInput one = cr.openInput("_123.f11", newIOContext(random()));
    
    IndexInput two = one.clone();
    
    assertSameStreams("basic clone one", expected, one);
    expected.seek(0);
    assertSameStreams("basic clone two", expected, two);
    
    // Now close the compound reader
    cr.close();
    expected.close();
    dir.close();
  }
  
  /** This test opens two files from a compound stream and verifies that
   *  their file positions are independent of each other.
   */
  public void testRandomAccess() throws IOException {
    Directory dir = newDirectory();
    Directory cr = createLargeCFS(dir);
    
    // Open two files
    IndexInput e1 = dir.openInput("_123.f11", newIOContext(random()));
    IndexInput e2 = dir.openInput("_123.f3", newIOContext(random()));
    
    IndexInput a1 = cr.openInput("_123.f11", newIOContext(random()));
    IndexInput a2 = dir.openInput("_123.f3", newIOContext(random()));
    
    // Seek the first pair
    e1.seek(100);
    a1.seek(100);
    assertEquals(100, e1.getFilePointer());
    assertEquals(100, a1.getFilePointer());
    byte be1 = e1.readByte();
    byte ba1 = a1.readByte();
    assertEquals(be1, ba1);
    
    // Now seek the second pair
    e2.seek(1027);
    a2.seek(1027);
    assertEquals(1027, e2.getFilePointer());
    assertEquals(1027, a2.getFilePointer());
    byte be2 = e2.readByte();
    byte ba2 = a2.readByte();
    assertEquals(be2, ba2);
    
    // Now make sure the first one didn't move
    assertEquals(101, e1.getFilePointer());
    assertEquals(101, a1.getFilePointer());
    be1 = e1.readByte();
    ba1 = a1.readByte();
    assertEquals(be1, ba1);
    
    // Now more the first one again, past the buffer length
    e1.seek(1910);
    a1.seek(1910);
    assertEquals(1910, e1.getFilePointer());
    assertEquals(1910, a1.getFilePointer());
    be1 = e1.readByte();
    ba1 = a1.readByte();
    assertEquals(be1, ba1);
    
    // Now make sure the second set didn't move
    assertEquals(1028, e2.getFilePointer());
    assertEquals(1028, a2.getFilePointer());
    be2 = e2.readByte();
    ba2 = a2.readByte();
    assertEquals(be2, ba2);
    
    // Move the second set back, again cross the buffer size
    e2.seek(17);
    a2.seek(17);
    assertEquals(17, e2.getFilePointer());
    assertEquals(17, a2.getFilePointer());
    be2 = e2.readByte();
    ba2 = a2.readByte();
    assertEquals(be2, ba2);
    
    // Finally, make sure the first set didn't move
    // Now make sure the first one didn't move
    assertEquals(1911, e1.getFilePointer());
    assertEquals(1911, a1.getFilePointer());
    be1 = e1.readByte();
    ba1 = a1.readByte();
    assertEquals(be1, ba1);
    
    e1.close();
    e2.close();
    a1.close();
    a2.close();
    cr.close();
    dir.close();
  }
  
  /** This test opens two files from a compound stream and verifies that
   *  their file positions are independent of each other.
   */
  public void testRandomAccessClones() throws IOException {
    Directory dir = newDirectory();
    Directory cr = createLargeCFS(dir);
    
    // Open two files
    IndexInput e1 = cr.openInput("_123.f11", newIOContext(random()));
    IndexInput e2 = cr.openInput("_123.f3", newIOContext(random()));
    
    IndexInput a1 = e1.clone();
    IndexInput a2 = e2.clone();
    
    // Seek the first pair
    e1.seek(100);
    a1.seek(100);
    assertEquals(100, e1.getFilePointer());
    assertEquals(100, a1.getFilePointer());
    byte be1 = e1.readByte();
    byte ba1 = a1.readByte();
    assertEquals(be1, ba1);
    
    // Now seek the second pair
    e2.seek(1027);
    a2.seek(1027);
    assertEquals(1027, e2.getFilePointer());
    assertEquals(1027, a2.getFilePointer());
    byte be2 = e2.readByte();
    byte ba2 = a2.readByte();
    assertEquals(be2, ba2);
    
    // Now make sure the first one didn't move
    assertEquals(101, e1.getFilePointer());
    assertEquals(101, a1.getFilePointer());
    be1 = e1.readByte();
    ba1 = a1.readByte();
    assertEquals(be1, ba1);
    
    // Now more the first one again, past the buffer length
    e1.seek(1910);
    a1.seek(1910);
    assertEquals(1910, e1.getFilePointer());
    assertEquals(1910, a1.getFilePointer());
    be1 = e1.readByte();
    ba1 = a1.readByte();
    assertEquals(be1, ba1);
    
    // Now make sure the second set didn't move
    assertEquals(1028, e2.getFilePointer());
    assertEquals(1028, a2.getFilePointer());
    be2 = e2.readByte();
    ba2 = a2.readByte();
    assertEquals(be2, ba2);
    
    // Move the second set back, again cross the buffer size
    e2.seek(17);
    a2.seek(17);
    assertEquals(17, e2.getFilePointer());
    assertEquals(17, a2.getFilePointer());
    be2 = e2.readByte();
    ba2 = a2.readByte();
    assertEquals(be2, ba2);
    
    // Finally, make sure the first set didn't move
    // Now make sure the first one didn't move
    assertEquals(1911, e1.getFilePointer());
    assertEquals(1911, a1.getFilePointer());
    be1 = e1.readByte();
    ba1 = a1.readByte();
    assertEquals(be1, ba1);
    
    e1.close();
    e2.close();
    a1.close();
    a2.close();
    cr.close();
    dir.close();
  }
  
  public void testFileNotFound() throws IOException {
    Directory dir = newDirectory();
    Directory cr = createLargeCFS(dir);
    
    // Open bogus file
    try {
      cr.openInput("bogus", newIOContext(random()));
      fail("File not found");
    } catch (IOException e) {
      /* success */;
    }
    
    cr.close();
    dir.close();
  }
  
  public void testReadPastEOF() throws IOException {
    Directory dir = newDirectory();
    Directory cr = createLargeCFS(dir);
    IndexInput is = cr.openInput("_123.f2", newIOContext(random()));
    is.seek(is.length() - 10);
    byte b[] = new byte[100];
    is.readBytes(b, 0, 10);
    
    try {
      is.readByte();
      fail("Single byte read past end of file");
    } catch (IOException e) {
      /* success */
    }
    
    is.seek(is.length() - 10);
    try {
      is.readBytes(b, 0, 50);
      fail("Block read past end of file");
    } catch (IOException e) {
      /* success */
    }
    
    is.close();
    cr.close();
    dir.close();
  }
  
  /** Returns a new fake segment */
  protected static SegmentInfo newSegmentInfo(Directory dir, String name) {
    return new SegmentInfo(dir, Version.LATEST, name, 10000, false, Codec.getDefault(), null, StringHelper.randomId(), new HashMap<String,String>());
  }
  
  /** Creates a file of the specified size with random data. */
  protected static void createRandomFile(Directory dir, String name, int size) throws IOException {
    IndexOutput os = dir.createOutput(name, newIOContext(random()));
    for (int i=0; i<size; i++) {
      byte b = (byte) (Math.random() * 256);
      os.writeByte(b);
    }
    os.close();
  }
  
  /** Creates a file of the specified size with sequential data. The first
   *  byte is written as the start byte provided. All subsequent bytes are
   *  computed as start + offset where offset is the number of the byte.
   */
  protected static void createSequenceFile(Directory dir, String name, byte start, int size) throws IOException {
    IndexOutput os = dir.createOutput(name, newIOContext(random()));
    for (int i=0; i < size; i++) {
      os.writeByte(start);
      start ++;
    }
    os.close();
  }
  
  protected static void assertSameStreams(String msg, IndexInput expected, IndexInput test) throws IOException {
    assertNotNull(msg + " null expected", expected);
    assertNotNull(msg + " null test", test);
    assertEquals(msg + " length", expected.length(), test.length());
    assertEquals(msg + " position", expected.getFilePointer(), test.getFilePointer());
    
    byte expectedBuffer[] = new byte[512];
    byte testBuffer[] = new byte[expectedBuffer.length];
    
    long remainder = expected.length() - expected.getFilePointer();
    while (remainder > 0) {
      int readLen = (int) Math.min(remainder, expectedBuffer.length);
      expected.readBytes(expectedBuffer, 0, readLen);
      test.readBytes(testBuffer, 0, readLen);
      assertEqualArrays(msg + ", remainder " + remainder, expectedBuffer, testBuffer, 0, readLen);
      remainder -= readLen;
    }
  }
  
  protected static void assertSameStreams(String msg, IndexInput expected, IndexInput actual, long seekTo) throws IOException {
    if (seekTo >= 0 && seekTo < expected.length()) {
      expected.seek(seekTo);
      actual.seek(seekTo);
      assertSameStreams(msg + ", seek(mid)", expected, actual);
    }
  }
  
  protected static void assertSameSeekBehavior(String msg, IndexInput expected, IndexInput actual) throws IOException {
    // seek to 0
    long point = 0;
    assertSameStreams(msg + ", seek(0)", expected, actual, point);
    
    // seek to middle
    point = expected.length() / 2l;
    assertSameStreams(msg + ", seek(mid)", expected, actual, point);
    
    // seek to end - 2
    point = expected.length() - 2;
    assertSameStreams(msg + ", seek(end-2)", expected, actual, point);
    
    // seek to end - 1
    point = expected.length() - 1;
    assertSameStreams(msg + ", seek(end-1)", expected, actual, point);
    
    // seek to the end
    point = expected.length();
    assertSameStreams(msg + ", seek(end)", expected, actual, point);
    
    // seek past end
    point = expected.length() + 1;
    assertSameStreams(msg + ", seek(end+1)", expected, actual, point);
  }
  
  protected static void assertEqualArrays(String msg, byte[] expected, byte[] test, int start, int len) {
    assertNotNull(msg + " null expected", expected);
    assertNotNull(msg + " null test", test);
    
    for (int i=start; i<len; i++) {
      assertEquals(msg + " " + i, expected[i], test[i]);
    }
  }
  
  /** 
   * Setup a large compound file with a number of components, each of
   * which is a sequential file (so that we can easily tell that we are
   * reading in the right byte). The methods sets up 20 files - _123.0 to _123.19,
   * the size of each file is 1000 bytes.
   */
  protected static Directory createLargeCFS(Directory dir) throws IOException {
    List<String> files = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      createSequenceFile(dir, "_123.f" + i, (byte) 0, 2000);
      files.add("_123.f" + i);
    }
    
    SegmentInfo si = newSegmentInfo(dir, "_123");
    si.getCodec().compoundFormat().write(dir, si, files, MergeState.CheckAbort.NONE, IOContext.DEFAULT);
    Directory cfs = si.getCodec().compoundFormat().getCompoundReader(dir, si, IOContext.DEFAULT);
    return cfs;
  }

  @Override
  protected void addRandomFields(Document doc) {
    doc.add(new StoredField("foobar", TestUtil.randomSimpleString(random())));
  }

  @Override
  public void testMergeStability() throws Exception {
    assumeTrue("test does not work with CFS", true);
  }
}
