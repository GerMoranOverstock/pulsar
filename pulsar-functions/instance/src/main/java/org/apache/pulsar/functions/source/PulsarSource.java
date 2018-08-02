/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.source;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.ConsumerCryptoFailureAction;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.MultiTopicsConsumerImpl;
import org.apache.pulsar.client.impl.TopicMessageImpl;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.utils.FunctionConfig;
import org.apache.pulsar.functions.utils.Reflections;
import org.apache.pulsar.io.core.PushSource;
import org.apache.pulsar.io.core.SourceContext;

@Slf4j
public class PulsarSource<T> extends PushSource<T> implements MessageListener<T> {

    private final PulsarClient pulsarClient;
    private final PulsarSourceConfig pulsarSourceConfig;
    private List<String> inputTopics;
    private final List<Consumer<T>> inputConsumers = new ArrayList<>();
    private final TopicSchema topicSchema;

    public PulsarSource(PulsarClient pulsarClient, PulsarSourceConfig pulsarConfig) {
        this.pulsarClient = pulsarClient;
        this.pulsarSourceConfig = pulsarConfig;
        this.topicSchema = new TopicSchema(pulsarClient);
    }

    @Override
    public void open(Map<String, Object> config, SourceContext sourceContext) throws Exception {
        // Setup schemas
        log.info("Opening pulsar source with config: {}", pulsarSourceConfig);
        Map<String, ConsumerConfig<T>> configs = setupConsumerConfigs();

        configs.entrySet().stream().map(e -> {
            String topic = e.getKey();
            ConsumerConfig<T> conf = e.getValue();
            log.info("Creating consumers for topic : {}",  topic);
            ConsumerBuilder<T> cb = pulsarClient.newConsumer(conf.getSchema())
                    // consume message even if can't decrypt and deliver it along with encryption-ctx
                    .cryptoFailureAction(ConsumerCryptoFailureAction.CONSUME)
                    .subscriptionName(pulsarSourceConfig.getSubscriptionName())
                    .subscriptionType(pulsarSourceConfig.getSubscriptionType())
                    .messageListener(this);

            if (conf.isRegexPattern) {
                cb.topicsPattern(topic);
            } else {
                cb.topic(topic);
            }

            if (pulsarSourceConfig.getTimeoutMs() != null) {
                cb.ackTimeout(pulsarSourceConfig.getTimeoutMs(), TimeUnit.MILLISECONDS);
            }

            return cb.subscribeAsync();
        }).collect(Collectors.toList()).stream().map(CompletableFuture::join).map(inputConsumers::add);

        inputTopics = inputConsumers.stream().flatMap(c -> {
            return (c instanceof MultiTopicsConsumerImpl) ? ((MultiTopicsConsumerImpl<?>) c).getTopics().stream()
                    : Collections.singletonList(c.getTopic()).stream();
        }).collect(Collectors.toList());
    }

    @Override
    public void received(Consumer<T> consumer, Message<T> message) {
        String topicName;

        // If more than one topics are being read than the Message return by the consumer will be TopicMessageImpl
        // If there is only topic being read then the Message returned by the consumer wil be MessageImpl
        if (message instanceof TopicMessageImpl) {
            topicName = ((TopicMessageImpl<?>) message).getTopicName();
        } else {
            topicName = consumer.getTopic();
        }

        Record<T> record = PulsarRecord.<T>builder()
                .message(message)
                .topicName(topicName)
                .ackFunction(() -> {
                    if (pulsarSourceConfig.getProcessingGuarantees() == FunctionConfig.ProcessingGuarantees.EFFECTIVELY_ONCE) {
                        consumer.acknowledgeCumulativeAsync(message);
                    } else {
                        consumer.acknowledgeAsync(message);
                    }
                }).failFunction(() -> {
                    if (pulsarSourceConfig.getProcessingGuarantees() == FunctionConfig.ProcessingGuarantees.EFFECTIVELY_ONCE) {
                        throw new RuntimeException("Failed to process message: " + message.getMessageId());
                    }
                })
                .build();

        consume(record);
    }

    @Override
    public void close() throws Exception {
        inputConsumers.forEach(consumer -> {
            try {
                consumer.close();
            } catch (PulsarClientException e) {
            }
        });
    }

    @SuppressWarnings("unchecked")
    @VisibleForTesting
    Map<String, ConsumerConfig<T>> setupConsumerConfigs() throws ClassNotFoundException {
        Map<String, ConsumerConfig<T>> configs = new TreeMap<>();

        Class<?> typeArg = Reflections.loadClass(this.pulsarSourceConfig.getTypeClassName(),
                Thread.currentThread().getContextClassLoader());

        checkArgument(!Void.class.equals(typeArg), "Input type of Pulsar Function cannot be Void");

        // Check new config with schema types or classnames
        pulsarSourceConfig.getTopicSchema().forEach((topic, conf) -> {
            Schema<T> schema = (Schema<T>) topicSchema.getSchema(topic, typeArg, conf.getSchemaTypeOrClassName());
            configs.put(topic,
                    ConsumerConfig.<T> builder().schema(schema).isRegexPattern(conf.isRegexPattern()).build());
        });

        return configs;
    }

    public List<String> getInputTopics() {
        return inputTopics;
    }

    @Data
    @Builder
    private static class ConsumerConfig<T> {
        private Schema<T> schema;
        private boolean isRegexPattern;
    }

}
