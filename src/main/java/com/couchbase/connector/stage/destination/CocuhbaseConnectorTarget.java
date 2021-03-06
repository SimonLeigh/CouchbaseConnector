/*
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
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
package com.couchbase.connector.stage.destination;

import com.coucbase.connector.stage.connection.CouchbaseConnector;
import com.couchbase.client.java.document.json.JsonObject;
import com.couchbase.connector.stage.lib.Errors;

import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseTarget;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.config.JsonMode;
import com.streamsets.pipeline.lib.generator.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This target is a used to connect to a Couchbase NoSQL Database.
 */
public abstract class CocuhbaseConnectorTarget extends BaseTarget {
    
    private CouchbaseConnector connector;
    
    private DataGeneratorFactory generatorFactory;
    
    private static final Logger LOG = LoggerFactory.getLogger(CocuhbaseConnectorTarget.class);

  /** {@inheritDoc} */
  @Override
  protected List<ConfigIssue> init() {
    // Validate configuration values and open any required resources.
    List<ConfigIssue> issues = super.init();

    LOG.info("Connecting to Couchbase with details: " + getURL() + " " + getBucket() + " " + getUsername());
    connector = CouchbaseConnector.getInstance(getURL(), getUsername(), getPassword(), getBucket());
    
    //Data Generator for JSON Objects to Couchbase
    DataGeneratorFactoryBuilder builder = new DataGeneratorFactoryBuilder(
        getContext(),
        DataFormat.JSON.getGeneratorFormat()
    );
    builder.setCharset(StandardCharsets.UTF_8);
    builder.setMode(JsonMode.MULTIPLE_OBJECTS);
    generatorFactory = builder.build();

    // If issues is not empty, the UI will inform the user of each configuration issue in the list.
    return issues;
  }

  /** {@inheritDoc} */
  @Override
  public void destroy() {
    // Clean up any open resources.
    super.destroy();
    connector = null;
    
  }

  /** {@inheritDoc} */
  @Override
  public void write(Batch batch) throws StageException {
    Iterator<Record> batchIterator = batch.getRecords();

    while (batchIterator.hasNext()) {
      Record record = batchIterator.next();
      try {
        write(record);
      } catch (Exception e) {
        switch (getContext().getOnErrorRecord()) {
          case DISCARD:
            break;
          case TO_ERROR:
            getContext().toError(record, Errors.SAMPLE_01, e.toString());
            break;
          case STOP_PIPELINE:
            throw new StageException(Errors.SAMPLE_01, e.toString());
          default:
            throw new IllegalStateException(
                Utils.format("Unknown OnError value '{}'", getContext().getOnErrorRecord(), e)
            );
        }
      }
    }
  }

  /**
   * Writes a single record to the destination.
   *
   * @param record the record to write to the destination.
   * @throws OnRecordErrorException when a record cannot be written.
   */
  private void write(Record record) throws OnRecordErrorException {
    try {
        //Generate data from the record object and create JsonObject from byte ARRAY String   
        LOG.info("Here is the record: " + record);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        DataGenerator generator = generatorFactory.getGenerator(baos);
        generator.write(record);
        generator.close();
        JsonObject jsonObject = JsonObject.fromJson(new String(baos.toByteArray()));
        
        LOG.info("DATA - " + jsonObject);
        Object keyObject = jsonObject.get(getDocumentKey());
        if (keyObject == null)
            throw new NullPointerException("Document Key is Null");
        String keyString = keyObject.toString();
        
        //Write to Couchbase DB
        LOG.info("Writing record with key - " + keyString + " - to Couchbase");
        connector.writeToBucket(keyString, jsonObject);
          
    } catch (NullPointerException ne) {
        LOG.error(ne.getMessage());
    } catch (IOException ioe) {
        LOG.error(ioe.getMessage());
    } catch (DataGeneratorException dge) {
        LOG.error(dge.getMessage());
    }
  }
  
  //Configuration get methods
  public abstract String getURL();
  
  public abstract String getUsername();
  
  public abstract String getPassword();
  
  public abstract String getBucket();
  
  public abstract String getDocumentKey();

  

}
