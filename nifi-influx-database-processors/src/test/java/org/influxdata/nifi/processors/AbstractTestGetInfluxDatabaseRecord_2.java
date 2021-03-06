/*
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
package org.influxdata.nifi.processors;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.google.common.collect.Lists;
import com.influxdb.Cancellable;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.client.domain.Query;
import com.influxdb.query.FluxRecord;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.serialization.record.ArrayListRecordWriter;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.util.MockComponentLog;
import org.apache.nifi.util.MockProcessContext;
import org.apache.nifi.util.MockProcessorInitializationContext;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.influxdata.nifi.services.InfluxDatabaseService_2;
import org.influxdata.nifi.services.StandardInfluxDatabaseService_2;
import org.junit.Before;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import static org.influxdata.nifi.services.InfluxDatabaseService_2.INFLUX_DB_ACCESS_TOKEN;

/**
 * @author Jakub Bednar (24/07/2019 08:18)
 */
abstract class AbstractTestGetInfluxDatabaseRecord_2 {

    TestRunner runner;
    MockComponentLog logger;

    Answer queryAnswer = invocation -> Void.class;
    Exception queryOnErrorValue = null;
    List<FluxRecord> queryOnResponseRecords = Lists.newArrayList();

    GetInfluxDatabaseRecord_2 processor;
    ArrayListRecordWriter writer;

    @Before
    public void before() throws IOException, GeneralSecurityException, InitializationException {

        InfluxDBClient mockInfluxDBClient = Mockito.mock(InfluxDBClient.class);
        QueryApi mockQueryApi = Mockito.mock(QueryApi.class);
        Mockito.doAnswer(invocation -> mockQueryApi).when(mockInfluxDBClient).getQueryApi();
        Mockito.doAnswer(invocation -> {
            if (queryOnErrorValue != null) {
                //noinspection unchecked
                Consumer<Exception> onError = invocation.getArgument(3, Consumer.class);
                onError.accept(queryOnErrorValue);
            }

            queryOnResponseRecords.forEach(record -> {
                //noinspection unchecked
                BiConsumer<Cancellable, FluxRecord> onRecord = invocation.getArgument(2, BiConsumer.class);
                onRecord.accept(Mockito.mock(Cancellable.class), record);
            });

            boolean wasException = queryOnErrorValue != null;
            try {
                return queryAnswer.answer(invocation);
            } catch (Exception e){
                wasException = true;
                throw e;
            } finally {
                if (!wasException) {
                    Runnable onComplete = invocation.getArgument(4, Runnable.class);
                    onComplete.run();
                }
            }
        }).when(mockQueryApi).query(Mockito.any(Query.class), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(Runnable.class));

        processor = Mockito.spy(new GetInfluxDatabaseRecord_2());

        runner = TestRunners.newTestRunner(processor);
        runner.setProperty(GetInfluxDatabaseRecord_2.ORG, "my-org");
        runner.setProperty(GetInfluxDatabaseRecord_2.QUERY, "from(bucket:\"my-bucket\") |> range(start: 0) |> last()");
        runner.setProperty(GetInfluxDatabaseRecord_2.INFLUX_DB_SERVICE, "influxdb-service");
        runner.setProperty(GetInfluxDatabaseRecord_2.WRITER_FACTORY, "writer");

        InfluxDatabaseService_2 influxDatabaseService = Mockito.spy(new StandardInfluxDatabaseService_2());
        Mockito.doAnswer(invocation -> mockInfluxDBClient).when(influxDatabaseService).create();

        runner.addControllerService("influxdb-service", influxDatabaseService);
        runner.setProperty(influxDatabaseService, INFLUX_DB_ACCESS_TOKEN, "my-token");
        runner.enableControllerService(influxDatabaseService);

        writer = new ArrayListRecordWriter(null){
            @Override
            public RecordSchema getSchema(final Map<String, String> variables, final RecordSchema readSchema) {
                return readSchema;
            }
        };
        runner.addControllerService("writer", writer);
        runner.enableControllerService(writer);

        MockProcessContext context = new MockProcessContext(processor);
        MockProcessorInitializationContext initContext = new MockProcessorInitializationContext(processor, context);
        logger = initContext.getLogger();
        processor.initialize(initContext);
        processor.onScheduled(runner.getProcessContext());
        processor.initWriterFactory(runner.getProcessContext());
    }
}
