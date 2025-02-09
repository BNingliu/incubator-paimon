/*
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

package org.apache.paimon.flink.sink;

import org.apache.paimon.flink.compact.UnawareBucketCompactionTopoBuilder;
import org.apache.paimon.table.AppendOnlyFileStoreTable;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.streaming.api.datastream.DataStream;

import javax.annotation.Nullable;

import java.util.Map;

/**
 * Sink for unaware-bucket table.
 *
 * <p>Note: in unaware-bucket mode, we don't shuffle by bucket in inserting. We can assign
 * compaction to the inserting jobs aside.
 */
public abstract class UnawareBucketSink<T> extends FlinkWriteSink<T> {

    protected final AppendOnlyFileStoreTable table;
    protected final LogSinkFunction logSinkFunction;

    @Nullable private final Integer parallelism;
    private final boolean boundedInput;

    public UnawareBucketSink(
            AppendOnlyFileStoreTable table,
            @Nullable Map<String, String> overwritePartitions,
            LogSinkFunction logSinkFunction,
            @Nullable Integer parallelism,
            boolean boundedInput) {
        super(table, overwritePartitions);
        this.table = table;
        this.logSinkFunction = logSinkFunction;
        this.parallelism = parallelism;
        this.boundedInput = boundedInput;
    }

    @Override
    public DataStream<Committable> doWrite(
            DataStream<T> input, String initialCommitUser, @Nullable Integer parallelism) {
        DataStream<Committable> written = super.doWrite(input, initialCommitUser, this.parallelism);

        boolean enableCompaction = !table.coreOptions().writeOnly();
        boolean isStreamingMode =
                input.getExecutionEnvironment()
                                .getConfiguration()
                                .get(ExecutionOptions.RUNTIME_MODE)
                        == RuntimeExecutionMode.STREAMING;
        // if enable compaction, we need to add compaction topology to this job
        if (enableCompaction && isStreamingMode && !boundedInput) {
            // if streaming mode with bounded input, we disable compaction topology
            UnawareBucketCompactionTopoBuilder builder =
                    new UnawareBucketCompactionTopoBuilder(
                            input.getExecutionEnvironment(), table.name(), table);
            builder.withContinuousMode(true);
            written = written.union(builder.fetchUncommitted(initialCommitUser));
        }

        return written;
    }
}
