/*
 * Copyright 2013 Cloudera Inc.
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
package org.kitesdk.morphline.solr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrServer;
import org.apache.solr.client.solrj.impl.ConcurrentUpdateSolrServer;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

/**
 * A vehicle to load (or delete) documents into a local or remote {@link SolrServer}.
 * This class should be considered private and it's API is subject to change without notice.
 */
public class SolrServerDocumentLoader implements DocumentLoader {

  private final SolrServer server; // proxy to local or remote solr server
  private long numSentItems = 0; // number of requests sent in the current transaction
  private final int batchSize;
  private final List batch = new ArrayList();

  private static final Logger LOGGER = LoggerFactory.getLogger(SolrServerDocumentLoader.class);

  public SolrServerDocumentLoader(SolrServer server, int batchSize) {
    if (server == null) {
      throw new IllegalArgumentException("solr server must not be null");
    }
    this.server = server;
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be a positive number: " + batchSize);      
    }
    this.batchSize = batchSize;
  }
  
  @Override
  public void beginTransaction() {
    LOGGER.trace("beginTransaction");
    batch.clear();
    numSentItems = 0;
    if (server instanceof SafeConcurrentUpdateSolrServer) {
      ((SafeConcurrentUpdateSolrServer) server).clearException();
    }
  }

  @Override
  public void load(SolrInputDocument doc) throws IOException, SolrServerException {
    Preconditions.checkNotNull(doc);
    LOGGER.trace("load doc: {}", doc);
    batch.add(doc);
    if (batch.size() >= batchSize) {
      sendBatch();
    }
  }

  @Override
  public void deleteById(String id) throws IOException, SolrServerException {
    Preconditions.checkNotNull(id);
    LOGGER.trace("deleteById: {}", id);
    batch.add(id);
    if (batch.size() >= batchSize) {
      sendBatch();
    }    
  }

  @Override
  public void deleteByQuery(String query) throws IOException, SolrServerException {
    Preconditions.checkNotNull(query);
    LOGGER.trace("deleteByQuery: {}", query);
    batch.add(new StringBuilder(query));
    if (batch.size() >= batchSize) {
      sendBatch();
    }    
  }

  @Override
  public void commitTransaction() throws SolrServerException, IOException {
    LOGGER.trace("commitTransaction");
    if (batch.size() > 0) {
      sendBatch();
    }
    if (numSentItems > 0) {
      if (server instanceof ConcurrentUpdateSolrServer) {
        ((ConcurrentUpdateSolrServer) server).blockUntilFinished();
      }
    }
  }

  private void sendBatch() throws SolrServerException, IOException {    
    numSentItems += batch.size();
    try {
      List<SolrInputDocument> loads = new ArrayList(batch.size());
      List<String> deleteByIds = new ArrayList(batch.size());
      
      for (Object item : batch) {
        if (item instanceof SolrInputDocument) { // it's a load request
          sendDeleteByIds(deleteByIds);
          loads.add((SolrInputDocument) item);
        } else if (item instanceof String) { // it's a deleteById request
          sendLoads(loads);         
          deleteByIds.add((String) item);
        } else if (item instanceof StringBuilder) { // it's a deleteByQuery request
          sendLoads(loads);         
          sendDeleteByIds(deleteByIds);
          log(server.deleteByQuery(item.toString()));
        } else {
          throw new IllegalStateException("unreachable");
        }
      }
      
      sendLoads(loads);
      sendDeleteByIds(deleteByIds);
    } finally {
      batch.clear();
    }
  }

  private void sendLoads(List<SolrInputDocument> loads) throws SolrServerException, IOException {
    if (loads.size() > 0) {
      log(server.add(loads));
      loads.clear();
    }
  }

  private void sendDeleteByIds(List<String> deleteByIds) throws SolrServerException, IOException {
    if (deleteByIds.size() > 0) {
      log(server.deleteById(deleteByIds));
      deleteByIds.clear();
    }
  }
  
  private void log(UpdateResponse response) {    
  }

  @Override
  public UpdateResponse rollbackTransaction() throws SolrServerException, IOException {
    LOGGER.trace("rollback");
    if (!(server instanceof CloudSolrServer)) {
      return server.rollback();
    } else {
      return new UpdateResponse();
    }
  }

  @Override
  public void shutdown() {
    LOGGER.trace("shutdown");
    server.shutdown();
  }

  @Override
  public SolrPingResponse ping() throws SolrServerException, IOException {
    LOGGER.trace("ping");
    return server.ping();
  }

  public SolrServer getSolrServer() {
    return server;
  }
  
}
