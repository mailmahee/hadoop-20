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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.server.common.StorageInfo;
import org.apache.hadoop.hdfs.server.namenode.NNStorage.StorageLocationType;
import org.apache.hadoop.hdfs.server.protocol.RemoteEditLog;
import org.apache.hadoop.hdfs.server.protocol.RemoteEditLogManifest;
import org.apache.hadoop.hdfs.util.InjectionEvent;
import org.apache.hadoop.util.InjectionHandler;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimaps;


/**
 * Manages a collection of Journals. None of the methods are synchronized, it is
 * assumed that FSEditLog methods, that use this class, use proper
 * synchronization.
 */
public class JournalSet implements JournalManager {

  static final Log LOG = LogFactory.getLog(FSEditLog.class);
  
  // executor for syncing transactions
  private final ExecutorService executor; 
  
  private int minimumNumberOfJournals;
  private int minimumNumberOfNonLocalJournals;
  private final NNStorage storage;
  
  /**
   * Container for a JournalManager paired with its currently
   * active stream.
   * 
   * If a Journal gets disabled due to an error writing to its
   * stream, then the stream will be aborted and set to null.
   */
  public static class JournalAndStream {
    private final JournalManager journal;
    private boolean disabled = false;
    private EditLogOutputStream stream;
    private boolean required = false;
    private boolean shared = false;
    private boolean remote = false;
    
    public JournalAndStream(JournalManager manager, boolean required,
        boolean shared, boolean remote) {
      this.journal = manager;
      this.required = required;
      this.shared = shared;
      this.remote = remote;
    }

    public void startLogSegment(long txId) throws IOException {
      if(stream != null) {
        throw new IOException("Stream should not be initialized");
      }
      if (disabled) {
        FSEditLog.LOG.info("Restoring journal " + this);
      }
      disabled = false;
      InjectionHandler.processEventIO(
          InjectionEvent.JOURNALANDSTREAM_STARTLOGSEGMENT, required);
      stream = journal.startLogSegment(txId);
    }

    /**
     * Closes the stream, also sets it to null.
     */
    public void closeStream() throws IOException {
      if (stream == null) return;
      stream.close();
      stream = null;
    }

    /**
     * Close the Journal and Stream
     */
    public void close() throws IOException {
      closeStream();

      journal.close();
    }
    
    /**
     * Aborts the stream, also sets it to null.
     */
    public void abort() {
      if (stream == null) return;
      try {
        stream.abort();
      } catch (IOException ioe) {
        LOG.error("Unable to abort stream " + stream, ioe);
      }
      stream = null;
    }

    boolean isActive() {
      return stream != null;
    }
    
    /**
     * Should be used outside JournalSet only for testing.
     */
    EditLogOutputStream getCurrentStream() {
      return stream;
    }
    
    @Override
    public String toString() {
      return "JournalAndStream(mgr=" + journal +
        ", " + "stream=" + stream + ", required=" + required + ")";
    }
    
    public String toStringShort() {
      return journal.toString();
    }

    void setCurrentStreamForTests(EditLogOutputStream stream) {
      this.stream = stream;
    }
    
    JournalManager getManager() {
      return journal;
    }

    private boolean isDisabled() {
      return disabled;
    }

    private void setDisabled(boolean disabled) {
      this.disabled = disabled;
    }
    
    public boolean isResourceAvailable() {
      return !isDisabled();
    }
    
    public boolean isRequired() {
      return required;
    }
    
    public boolean isShared() {
      return shared;
    }
    
    public boolean isRemote() {
      return remote;
    }
  }
  
  private List<JournalAndStream> journals = new ArrayList<JournalAndStream>();
  
  private volatile boolean forceJournalCheck = false;

  JournalSet(Configuration conf, NNStorage storage, int numJournals) {
    minimumNumberOfJournals 
      = conf.getInt("dfs.name.edits.dir.minimum", 1);
    minimumNumberOfNonLocalJournals 
      = conf.getInt("dfs.name.edits.dir.minimum.nonlocal", 0);
    this.storage = storage;
    ThreadFactory namedThreadFactory =
        new ThreadFactoryBuilder()
            .setNameFormat("JournalSet Worker %d")
            .build();
    this.executor = Executors.newFixedThreadPool(numJournals,
        namedThreadFactory);
  }
  
  @Override
  public EditLogOutputStream startLogSegment(final long txId) throws IOException {
    mapJournalsAndReportErrorsParallel(new JournalClosure() {
      @Override
      public void apply(JournalAndStream jas) throws IOException {
        jas.startLogSegment(txId);
      }
    }, "starting log segment " + txId);
    return new JournalSetOutputStream();
  }
  
  @Override
  public void finalizeLogSegment(final long firstTxId, final long lastTxId)
      throws IOException {
    mapJournalsAndReportErrorsParallel(new JournalClosure() {
      @Override
      public void apply(JournalAndStream jas) throws IOException {
        if (jas.isActive()) {
          jas.closeStream();
          jas.getManager().finalizeLogSegment(firstTxId, lastTxId);
        }
      }
    }, "finalize log segment " + firstTxId + ", " + lastTxId);
  }
   
  @Override
  public void close() throws IOException {
    mapJournalsAndReportErrorsParallel(new JournalClosure() {
      @Override
      public void apply(JournalAndStream jas) throws IOException {
        jas.close();
      }
    }, "close journal");
    executor.shutdown();
  }

  
  /**
   * Find the best editlog input stream to read from txid.
   * If a journal throws an CorruptionException while reading from a txn id,
   * it means that it has more transactions, but can't find any from fromTxId. 
   * If this is the case and no other journal has transactions, we should throw
   * an exception as it means more transactions exist, we just can't load them.
   *
   * @param fromTxnId Transaction id to start from.
   * @return A edit log input stream with tranactions fromTxId 
   *         or null if no more exist
   */
  @Override
  public EditLogInputStream getInputStream(long fromTxnId) throws IOException {
    JournalManager bestjm = null;
    long bestjmNumTxns = 0;
    CorruptionException corruption = null;

    for (JournalAndStream jas : journals) {
      JournalManager candidate = jas.getManager();
      long candidateNumTxns = 0;
      try {
        candidateNumTxns = candidate.getNumberOfTransactions(fromTxnId);
      } catch (CorruptionException ce) {
        corruption = ce;
      } catch (IOException ioe) {
        continue; // error reading disk, just skip
      }
      
      // find the journal with most transactions
      // resolve ties by preferring local journals
      if (candidateNumTxns > bestjmNumTxns
          || (candidateNumTxns > 0 && 
              candidateNumTxns == bestjmNumTxns && 
              isLocalJournal(candidate) &&
              !isLocalJournal(bestjm))) {
        bestjm = candidate;
        bestjmNumTxns = candidateNumTxns;
      }
    }
    
    if (bestjm == null) {
      if (corruption != null) {
        throw new IOException("No non-corrupt logs for txid " 
                                        + fromTxnId, corruption);
      } else {
        return null;
      }
    }
    return bestjm.getInputStream(fromTxnId);
  }
  
  @Override
  public EditLogInputStream getInputStream(long fromTxnId,
      boolean validateInProgressSegments) throws IOException {
    throw new IOException("Operation not supported");
  }
  
  /**
   * Check if the given journal is local.
   */
  private boolean isLocalJournal(JournalManager jm) {
    if (!(jm instanceof FileJournalManager)) {
      return false;
    }
    return NNStorage.isPreferred(StorageLocationType.LOCAL,
        ((FileJournalManager) jm).getStorageDirectory());
  }
  
  @Override
  public long getNumberOfTransactions(long fromTxnId) throws IOException {
    long num = 0;
    for (JournalAndStream jas: journals) {
      if (jas.isActive()) {
        long newNum = jas.getManager().getNumberOfTransactions(fromTxnId);
        if (newNum > num) {
          num = newNum;
        }
      }
    }
    return num;
  }

  /**
   * Returns true if there are no journals, all redundant journals are disabled,
   * or any required journals are disabled.
   * 
   * @return True if there no journals, all redundant journals are disabled,
   * or any required journals are disabled.
   */
  public boolean isEmpty() {
    return journals.size() == 0;
  }
  
  /**
   * Called when some journals experience an error in some operation.
   */
  private void disableAndReportErrorOnJournals(
      List<JournalAndStream> badJournals, String status) throws IOException {
    if (badJournals == null || badJournals.isEmpty()) {
      if (forceJournalCheck) {
        // check status here, because maybe some other operation
        // (e.g., rollEditLog failed and disabled journals) but this
        // was missed by logSync() exit runtime
        forceJournalCheck = false;
        checkJournals(status);
      }
      return; // nothing to do
    }
 
    for (JournalAndStream j : badJournals) {
      LOG.error("Disabling journal " + j);
      j.abort();
      j.setDisabled(true);
      
      // report errors on storage directories as well for FJMs
      if (j.journal instanceof FileJournalManager) {
        FileJournalManager fjm = (FileJournalManager) j.journal;
        storage.reportErrorsOnDirectory(fjm.getStorageDirectory());
      }
    }
    checkJournals(status);
  }

  /**
   * Implementations of this interface encapsulate operations that can be
   * iteratively applied on all the journals. For example see
   * {@link JournalSet#mapJournalsAndReportErrors}.
   */
  interface JournalClosure {
    /**
     * The operation on JournalAndStream.
     * @param jas Object on which operations are performed.
     * @throws IOException
     */
    public void apply(JournalAndStream jas) throws IOException;
  }

  /**
   * Apply the given operation across all of the journal managers, disabling
   * any for which the closure throws an IOException.
   * @param closure {@link JournalClosure} object encapsulating the operation.
   * @param status message used for logging errors (e.g. "opening journal")
   * @throws IOException If the operation fails on all the journals.
   */
  private void mapJournalsAndReportErrors(
      JournalClosure closure, String status) throws IOException{
    List<JournalAndStream> badJAS = null;
    for (JournalAndStream jas : journals) {
      try {
        closure.apply(jas);
      } catch (Throwable t) {
        if (badJAS == null)
          badJAS = new LinkedList<JournalAndStream>();
        LOG.error("Error: " + status + " failed for (journal " + jas + ")", t);
        badJAS.add(jas);
      }
    }
    disableAndReportErrorOnJournals(badJAS, status);
  }
  
  /**
   * Apply the given operation across all of the journal managers, disabling
   * any for which the closure throws an IOException. Do it in parallel.
   * @param closure {@link JournalClosure} object encapsulating the operation.
   * @param status message used for logging errors (e.g. "opening journal")
   * @throws IOException If the operation fails on all the journals.
   */
  private void mapJournalsAndReportErrorsParallel(JournalClosure closure,
      String status) throws IOException {

    // set-up calls
    List<Future<JournalAndStream>> jasResponeses = new ArrayList<Future<JournalAndStream>>(
        journals.size());

    for (JournalAndStream jas : journals) {
      jasResponeses.add(executor.submit(new JournalSetWorker(jas, closure,
          status)));
    }

    List<JournalAndStream> badJAS = null;

    // iterate through responses
    for (Future<JournalAndStream> future : jasResponeses) {
      JournalAndStream jas = null;
      try {
        jas = future.get();
      } catch (ExecutionException e) {
        throw new IOException("This should never happen!!!", e);
      } catch (InterruptedException e) {
        throw new IOException("Interrupted whe performing journal operations",
            e);
      }
      if (jas == null)
        continue;

      // the worker returns the journal if the operation failed
      if (badJAS == null)
        badJAS = new LinkedList<JournalAndStream>();

      badJAS.add(jas);
    }
    disableAndReportErrorOnJournals(badJAS, status);
  }
  
  /**
   * Get the number of available journals.
   */
  private void updateJournalMetrics() {
    if (storage == null) {
      return;
    }
    int failedJournals = 0;
    for(JournalAndStream jas : journals) {
      if(jas.isDisabled()) {
        failedJournals++;
      }
    }
    storage.updateJournalMetrics(failedJournals);
  }
  
  /**
   * Checks if the number of journals available is not below
   * minimum. Only invoked at errors.
   */
  protected int checkJournals(String status) throws IOException {
    boolean abort = false;
    int journalsAvailable = 0;
    int nonLocalJournalsAvailable = 0;
    for(JournalAndStream jas : journals) {
      if(jas.isDisabled() && jas.isRequired()) {
        abort = true;
      } else if (jas.isResourceAvailable()) {
        journalsAvailable++;
        if (jas.isRemote() || jas.isShared()) {
          nonLocalJournalsAvailable++;
        }
      }
    }
    // update metrics
    updateJournalMetrics();
    if (abort || journalsAvailable < minimumNumberOfJournals
        || nonLocalJournalsAvailable < minimumNumberOfNonLocalJournals) {
      forceJournalCheck = true;
      String message = status + " failed for too many journals, minimum: "
          + minimumNumberOfJournals + " current: " + journalsAvailable
          + ", non-local: " 
          + minimumNumberOfNonLocalJournals + " current: " + nonLocalJournalsAvailable;
      throw new IOException(message);
    }
    return journalsAvailable;
  }
  
  /**
   * Checks if the shared journal (if present) available)
   */
  protected boolean isSharedJournalAvailable() throws IOException {
    for(JournalAndStream jas : journals) {
      if(jas.isShared() && jas.isResourceAvailable()) {
        return true;
      } 
    }
    return false;
  }
  
  /**
   * An implementation of EditLogOutputStream that applies a requested method on
   * all the journals that are currently active.
   */
  private class JournalSetOutputStream extends EditLogOutputStream {

    JournalSetOutputStream() throws IOException {
      super();
    }

    @Override
    public void write(final FSEditLogOp op)
        throws IOException {
      mapJournalsAndReportErrors(new JournalClosure() {
        @Override
        public void apply(JournalAndStream jas) throws IOException {
          if (jas.isActive()) {
            jas.getCurrentStream().write(op);
          }
        }
      }, "write op");
    }

    @Override
    public void create() throws IOException {
      mapJournalsAndReportErrors(new JournalClosure() {
        @Override
        public void apply(JournalAndStream jas) throws IOException {
          if (jas.isActive()) {
            jas.getCurrentStream().create();
          }
        }
      }, "create");
    }

    @Override
    public void close() throws IOException {
      mapJournalsAndReportErrors(new JournalClosure() {
        @Override
        public void apply(JournalAndStream jas) throws IOException {
          jas.closeStream();
        }
      }, "close");
    }

    @Override
    public void abort() throws IOException {
      mapJournalsAndReportErrors(new JournalClosure() {
        @Override
        public void apply(JournalAndStream jas) throws IOException {
          jas.abort();
        }
      }, "abort");
    }

    @Override
    public void setReadyToFlush() throws IOException {
      mapJournalsAndReportErrors(new JournalClosure() {
        @Override
        public void apply(JournalAndStream jas) throws IOException {
          if (jas.isActive()) {
            jas.getCurrentStream().setReadyToFlush();
          }
        }
      }, "setReadyToFlush");
    }

    @Override
    protected void flushAndSync() throws IOException {
      mapJournalsAndReportErrorsParallel(new JournalClosure() {
        @Override
        public void apply(JournalAndStream jas) throws IOException {
          if (jas.isActive()) {
            jas.getCurrentStream().flushAndSync();
          }
        }
      }, "flushAndSync");
    }
    
    @Override
    public void flush() throws IOException {
      mapJournalsAndReportErrorsParallel(new JournalClosure() {
        @Override
        public void apply(JournalAndStream jas) throws IOException {
          if (jas.isActive()) {
            jas.getCurrentStream().flush();
          }
        }
      }, "flush");
    }
    
    @Override
    public boolean shouldForceSync() {
      for (JournalAndStream js : journals) {
        if (js.isActive() && js.getCurrentStream().shouldForceSync()) {
          return true;
        }
      }
      return false;
    }
    
    @Override
    public long getNumSync() {
      for (JournalAndStream jas : journals) {
        if (jas.isActive()) {
          return jas.getCurrentStream().getNumSync();
        }
      }
      return 0;
    }

    //TODO what is the name of the journalSet?
    @Override
    public String getName() {
      return "JournalSet: ";
    }

    @Override
    public long length() throws IOException {
      // TODO Auto-generated method stub
      return 0;
    }
  }
  
  List<JournalAndStream> getAllJournalStreams() {
    return journals;
  }

  List<JournalManager> getJournalManagers() {
    List<JournalManager> jList = new ArrayList<JournalManager>();
    for (JournalAndStream j : journals) {
      jList.add(j.getManager());
    }
    return jList;
  }

  void add(JournalManager j, boolean required, boolean shared, boolean remote) {
    JournalAndStream jas = new JournalAndStream(j, required, shared, remote);
    journals.add(jas);
    // update journal metrics
    updateJournalMetrics();
  }
  
  void remove(JournalManager j) {
    JournalAndStream jasToRemove = null;
    for (JournalAndStream jas: journals) {
      if (jas.getManager().equals(j)) {
        jasToRemove = jas;
        break;
      }
    }
    if (jasToRemove != null) {
      jasToRemove.abort();
      journals.remove(jasToRemove);
    }
    // update journal metrics
    updateJournalMetrics();
  }

  @Override
  public void purgeLogsOlderThan(final long minTxIdToKeep) throws IOException {
    mapJournalsAndReportErrorsParallel(new JournalClosure() {
      @Override
      public void apply(JournalAndStream jas) throws IOException {
        jas.getManager().purgeLogsOlderThan(minTxIdToKeep);
      }
    }, "purgeLogsOlderThan " + minTxIdToKeep);
  }

  @Override
  public void recoverUnfinalizedSegments() throws IOException {
    mapJournalsAndReportErrorsParallel(new JournalClosure() {
      @Override
      public void apply(JournalAndStream jas) throws IOException {
        jas.getManager().recoverUnfinalizedSegments();
      }
    }, "recoverUnfinalizedSegments");
  }
  
  /**
   * Return a manifest of what edit logs are available. All available
   * edit logs are returned starting from the transaction id passed,
   * including inprogress segments.
   * 
   * @param fromTxId Starting transaction id to read the logs.
   * @return RemoteEditLogManifest object.
   */
  public synchronized RemoteEditLogManifest getEditLogManifest(long fromTxId) {
    // Collect RemoteEditLogs available from each FileJournalManager
    List<RemoteEditLog> allLogs = new ArrayList<RemoteEditLog>();
    for (JournalAndStream j : journals) {
      if (j.getManager() instanceof FileJournalManager) {
        FileJournalManager fjm = (FileJournalManager)j.getManager();
        try {
          allLogs.addAll(fjm.getEditLogManifest(fromTxId).getLogs());
        } catch (Throwable t) {
          LOG.warn("Cannot list edit logs in " + fjm, t);
        }
      }
    }
    
    // Group logs by their starting txid
    ImmutableListMultimap<Long, RemoteEditLog> logsByStartTxId =
      Multimaps.index(allLogs, RemoteEditLog.GET_START_TXID);
    long curStartTxId = fromTxId;

    List<RemoteEditLog> logs = new ArrayList<RemoteEditLog>();
    while (true) {
      ImmutableList<RemoteEditLog> logGroup = logsByStartTxId.get(curStartTxId);
      if (logGroup.isEmpty()) {
        // we have a gap in logs - for example because we recovered some old
        // storage directory with ancient logs. Clear out any logs we've
        // accumulated so far, and then skip to the next segment of logs
        // after the gap.
        SortedSet<Long> startTxIds = new TreeSet<Long>(logsByStartTxId.keySet());
        startTxIds = startTxIds.tailSet(curStartTxId);
        if (startTxIds.isEmpty()) {
          break;
        } else {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Found gap in logs at " + curStartTxId + ": " +
                "not returning previous logs in manifest.");
          }
          logs.clear();
          curStartTxId = startTxIds.first();
          continue;
        }
      }

      // Find the one that extends the farthest forward
      RemoteEditLog bestLog = Collections.max(logGroup);
      logs.add(bestLog);
      // And then start looking from after that point
      curStartTxId = bestLog.getEndTxId() + 1;
    }
    RemoteEditLogManifest ret = new RemoteEditLogManifest(logs);
    
    if (LOG.isDebugEnabled()) {
      LOG.debug("Generated manifest for logs since " + fromTxId + ":"
          + ret);      
    }
    return ret;
  }

  /**
   * Add sync times to the buffer.
   */
  String getSyncTimes() {
    StringBuilder buf = new StringBuilder();
    for (JournalAndStream jas : journals) {
      if (jas.isActive()) {
        buf.append(jas.getCurrentStream().getTotalSyncTime());
        buf.append(" ");
      }
    }
    return buf.toString();
  }

  @Override
  public boolean isSegmentInProgress(long startTxId) throws IOException {
    throw new UnsupportedOperationException();
  }
  
  @Override
  public void format(StorageInfo nsInfo) throws IOException {
    // The iteration is done by FSEditLog itself
    throw new UnsupportedOperationException();
  }
  
  /**
   * Format the non-file journals.
   */
  public void formatNonFileJournals(StorageInfo nsInfo) throws IOException {
    for (JournalManager jm : getJournalManagers()) {
      if (!(jm instanceof FileJournalManager)) {
        jm.format(nsInfo);
      }
    }
  }
  
  @Override
  public boolean hasSomeData() throws IOException {
    // This is called individually on the underlying journals,
    // not on the JournalSet.
    throw new UnsupportedOperationException();
  }
}
