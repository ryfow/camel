/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.hawtdb;

import org.apache.camel.Service;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.fusesource.hawtdb.api.BTreeIndexFactory;
import org.fusesource.hawtdb.api.Index;
import org.fusesource.hawtdb.api.OptimisticUpdateException;
import org.fusesource.hawtdb.api.Transaction;
import org.fusesource.hawtdb.api.TxPageFile;
import org.fusesource.hawtdb.api.TxPageFileFactory;
import org.fusesource.hawtdb.util.buffer.Buffer;
import org.fusesource.hawtdb.util.marshaller.IntegerMarshaller;
import org.fusesource.hawtdb.util.marshaller.StringMarshaller;
import org.fusesource.hawtdb.util.marshaller.VariableBufferMarshaller;

/**
 * Manages access to a shared <a href="http://hawtdb.fusesource.org/">HawtDB</a> file.
 * <p/>
 * Will by default not sync writes which allows it to be faster.
 * You can force syncing by setting the sync option to <tt>true</tt>.
 */
public class HawtDBFile extends TxPageFileFactory implements Service {

    private static final transient Log LOG = LogFactory.getLog(HawtDBFile.class);

    // the root which contains an index with name -> page for the real indexes
    private static final BTreeIndexFactory<String, Integer> ROOT_INDEXES_FACTORY = new BTreeIndexFactory<String, Integer>();
    // the real indexes where we store persisted data in buffers
    private static final BTreeIndexFactory<Buffer, Buffer> INDEX_FACTORY = new BTreeIndexFactory<Buffer, Buffer>();

    private TxPageFile pageFile;

    static {
        ROOT_INDEXES_FACTORY.setKeyMarshaller(StringMarshaller.INSTANCE);
        ROOT_INDEXES_FACTORY.setValueMarshaller(IntegerMarshaller.INSTANCE);
        ROOT_INDEXES_FACTORY.setDeferredEncoding(true);
        INDEX_FACTORY.setKeyMarshaller(VariableBufferMarshaller.INSTANCE);
        INDEX_FACTORY.setValueMarshaller(VariableBufferMarshaller.INSTANCE);
        INDEX_FACTORY.setDeferredEncoding(true);
    }

    public HawtDBFile() {
        setSync(false);
    }

    public void start() {
        if (getFile() == null) {
            throw new IllegalArgumentException("A file must be configured");
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Starting HawtDB using file: " + getFile());
        }

        open();
        pageFile = getTxPageFile();

        execute(new Work<Boolean>() {
            public Boolean execute(Transaction tx) {
                int page = tx.allocator().alloc(1);
                if (page == 0) {
                    // if we just created the file, first allocated page should be 0
                    ROOT_INDEXES_FACTORY.create(tx, 0);
                    LOG.info("Aggregation repository data store created using file: " + getFile());
                } else {
                    // Was previously created.. so free up the test page
                    tx.allocator().free(page, 1);
                    Index<String, Integer> indexes = ROOT_INDEXES_FACTORY.open(tx, 0);
                    LOG.info("Aggregation repository data store loaded using file: " + getFile()
                            + " containing " + indexes.size() + " repositories.");
                }
                return true;
            }
        });
    }

    public void stop() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Stopping HawtDB using file: " + getFile());
        }

        close();
        pageFile = null;
    }

    public <T> T execute(Work<T> work) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Executing work +++ start +++ " + work);
        }

        Transaction tx = pageFile.tx();
        T answer = doExecute(work, tx, pageFile);

        if (LOG.isTraceEnabled()) {
            LOG.trace("Executing work +++ done  +++ " + work);
        }
        return answer;
    }

    public Index<Buffer, Buffer> getRepositoryIndex(Transaction tx, String name, boolean create) {
        Index<Buffer, Buffer> answer = null;

        Index<String, Integer> indexes = ROOT_INDEXES_FACTORY.open(tx, 0);
        Integer location = indexes.get(name);

        if (create && location == null) {
            // create it..
            int page = tx.allocator().alloc(1);
            Index<Buffer, Buffer> created = INDEX_FACTORY.create(tx, page);

            // add it to indexes so we can find it the next time
            indexes.put(name, page);

            if (LOG.isDebugEnabled()) {
                LOG.debug("Created new repository index with name " + name + " at location " + page);
            }

            answer = created;
        } else if (location != null) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Repository index with name " + name + " at location " + location);
            }
            answer = INDEX_FACTORY.open(tx, location);
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("Repository index with name " + name + " -> " + answer);
        }
        return answer;
    }

    private static <T> T doExecute(Work<T> work, Transaction tx, TxPageFile page) {
        T answer = null;

        boolean done = false;
        int attempt = 0;
        while (!done) {
            try {
                // only log at DEBUG level if we are retrying
                if (attempt > 0 && LOG.isDebugEnabled()) {
                    LOG.debug("Attempt " + attempt + " to execute work " + work);
                }
                attempt++;

                answer = work.execute(tx);
                // commit work
                tx.commit();
                // and flush so we ensure data is spooled to disk
                page.flush();
                // and we are done
                done = true;
            } catch (OptimisticUpdateException e) {
                // retry as we hit an optimistic update error
                LOG.warn("OptimisticUpdateException occurred at attempt " + attempt + " executing work " + work + " will do rollback and retry.");
                // no harm doing rollback before retry and no wait is needed
                tx.rollback();
            } catch (RuntimeException e) {
                LOG.warn("Error executing work " + work + " will do rollback.", e);
                tx.rollback();
                throw e;
            }
        }

        return answer;
    }

}