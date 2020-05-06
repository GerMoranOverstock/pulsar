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
package org.apache.pulsar.broker.service.persistent;

import io.netty.util.concurrent.FastThreadLocal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.pulsar.broker.service.BrokerServiceException;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.EntryBatchSizes;
import org.apache.pulsar.broker.service.SendMessageInfo;
import org.apache.pulsar.broker.service.StickyKeyConsumerSelector;
import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PersistentStickyKeyDispatcherMultipleConsumers extends PersistentDispatcherMultipleConsumers {

    private final StickyKeyConsumerSelector selector;

    private boolean isDispatcherStuckOnReplays = false;

    /**
     * When a consumer joins, it will be added to this map with the current read position.
     * This means that, in order to preserve ordering, new consumers can only receive old
     * messages, until the mark-delete position will move past this point.
     */
    private final Map<Consumer, PositionImpl> recentlyJoinedConsumers = new HashMap<>();

    PersistentStickyKeyDispatcherMultipleConsumers(PersistentTopic topic, ManagedCursor cursor,
           Subscription subscription, StickyKeyConsumerSelector selector) {
        super(topic, cursor, subscription);
        this.selector = selector;
    }

    @Override
    public synchronized void addConsumer(Consumer consumer) throws BrokerServiceException {
        super.addConsumer(consumer);
        selector.addConsumer(consumer);

        // If this was the 1st consumer, or if all the messages are already acked, then we
        // don't need to do anything special
        if (consumerList.size() > 1 && cursor.getNumberOfEntriesSinceFirstNotAckedMessage() > 1) {
            recentlyJoinedConsumers.put(consumer, (PositionImpl) cursor.getReadPosition());
        }
    }

    @Override
    public synchronized void removeConsumer(Consumer consumer) throws BrokerServiceException {
        super.removeConsumer(consumer);
        selector.removeConsumer(consumer);

        recentlyJoinedConsumers.remove(consumer);
    }

    private static final FastThreadLocal<Map<Consumer, List<Entry>>> localGroupedEntries = new FastThreadLocal<Map<Consumer, List<Entry>>>() {
        @Override
        protected Map<Consumer, List<Entry>> initialValue() throws Exception {
            return new HashMap<>();
        }
    };

    @Override
    protected void sendMessagesToConsumers(ReadType readType, List<Entry> entries) {
        long totalMessagesSent = 0;
        long totalBytesSent = 0;
        int entriesCount = entries.size();

        // Trigger read more messages
        if (entriesCount == 0) {
            readMoreEntries();
            return;
        }

        if (consumerSet.isEmpty()) {
            entries.forEach(Entry::release);
            cursor.rewind();
            return;
        }

        final Map<Consumer, List<Entry>> groupedEntries = localGroupedEntries.get();
        groupedEntries.clear();

        for (int i = 0; i < entriesCount; i++) {
            Entry entry = entries.get(i);
            Consumer c = selector.select(peekStickyKey(entry.getDataBuffer()));
            groupedEntries.computeIfAbsent(c, k -> new ArrayList<>()).add(entry);
        }

        AtomicInteger keyNumbers = new AtomicInteger(groupedEntries.size());

        for (Map.Entry<Consumer, List<Entry>> current : groupedEntries.entrySet()) {
            Consumer consumer = current.getKey();
            List<Entry> entriesWithSameKey = current.getValue();
            int entriesWithSameKeyCount = entriesWithSameKey.size();

            int maxMessagesForC = Math.min(entriesWithSameKeyCount, consumer.getAvailablePermits());
            if (log.isDebugEnabled()) {
                log.debug("[{}] select consumer {} with messages num {}, read type is {}",
                        name, consumer.consumerName(), maxMessagesForC, readType);
            }

            List<Entry> subList = getEntryForConsumer(consumer, entriesWithSameKey, maxMessagesForC);
            int messagesForC = subList.size();

            if (messagesForC > 0) {
                // remove positions first from replay list first : sendMessages recycles entries
                if (readType == ReadType.Replay) {
                    for (int i = 0; i < messagesForC; i++) {
                        Entry entry = entriesWithSameKey.get(i);
                        messagesToRedeliver.remove(entry.getLedgerId(), entry.getEntryId());
                    }
                }

                SendMessageInfo sendMessageInfo = SendMessageInfo.getThreadLocal();
                EntryBatchSizes batchSizes = EntryBatchSizes.get(messagesForC);
                filterEntriesForConsumer(subList, batchSizes, sendMessageInfo);

                consumer.sendMessages(subList, batchSizes, sendMessageInfo.getTotalMessages(),
                        sendMessageInfo.getTotalBytes(), getRedeliveryTracker()).addListener(future -> {
                            if (future.isSuccess() && keyNumbers.decrementAndGet() == 0) {
                                readMoreEntries();
                            }
                        });

                totalAvailablePermits -= sendMessageInfo.getTotalMessages();
                totalMessagesSent += sendMessageInfo.getTotalMessages();
                totalBytesSent += sendMessageInfo.getTotalBytes();
            }

            if (messagesForC < entriesWithSameKeyCount) {
                // We were not able to push all the messages with given key to its consumer,
                // so we discard for now and mark them for later redelivery
                for (int i = messagesForC; i < entriesWithSameKeyCount; i++) {
                    Entry entry = entriesWithSameKey.get(i);
                    messagesToRedeliver.add(entry.getLedgerId(), entry.getEntryId());
                    entry.release();
                }
            }
        }

        // acquire message-dispatch permits for already delivered messages
        if (serviceConfig.isDispatchThrottlingOnNonBacklogConsumerEnabled() || !cursor.isActive()) {
            if (topic.getDispatchRateLimiter().isPresent()) {
                topic.getDispatchRateLimiter().get().tryDispatchPermit(totalMessagesSent, totalBytesSent);
            }

            if (dispatchRateLimiter.isPresent()) {
                dispatchRateLimiter.get().tryDispatchPermit(totalMessagesSent, totalBytesSent);
            }
        }

        if (totalMessagesSent == 0 && recentlyJoinedConsumers.isEmpty()) {
            // This means, that all the messages we've just read cannot be dispatched right now.
            // This condition can only happen when:
            //  1. We have consumers ready to accept messages (otherwise the would not haven been triggered)
            //  2. All keys in the current set of messages are routing to consumers that are currently busy
            //
            // The solution here is to move on and read next batch of messages which might hopefully contain
            // also keys meant for other consumers.
            //
            // We do it unless that are "recently joined consumers". In that case, we would be looking
            // ahead in the stream while the new consumers are not ready to accept the new messages,
            // therefore would be most likely only increase the distance between read-position and mark-delete
            // position.
            isDispatcherStuckOnReplays = true;
            readMoreEntries();
        }
    }

    private List<Entry> getEntryForConsumer(Consumer consumer, List<Entry> entries, int maxMessages) {
        if (maxMessages == 0) {
            return Collections.emptyList();
        }

        PositionImpl maxReadPosition = recentlyJoinedConsumers.get(consumer);
        if (maxReadPosition == null) {
            // The consumer has not recently joined, so we can send all messages
            return entries.subList(0, maxMessages);
        }

        PositionImpl markDeletePosition = (PositionImpl) cursor.getMarkDeletedPosition();

        if (maxReadPosition.compareTo(markDeletePosition.getNext()) <= 0) {
            // At this point, all the old messages were already consumed and this consumer
            // is now ready to receive any message
            recentlyJoinedConsumers.remove(consumer);
            return entries.subList(0, maxMessages);
        }

        // Here, the consumer is one that has recently joined, so we can only send messages that were
        // published before it has joined.
        for (int i = 0; i < maxMessages; i++) {
            if (((PositionImpl) entries.get(i).getPosition()).compareTo(maxReadPosition) >= 0) {
                // We have already crossed the divider line. All messages in the list are now
                // newer than what we can currently dispatch to this consumer
                return entries.subList(0, i);
            }
        }

        return entries.subList(0, maxMessages);
    }

    @Override
    public synchronized void acknowledgementWasProcessed() {
        if (!recentlyJoinedConsumers.isEmpty()) {
            // After we process acks, we need to check whether the mark-delete position was advanced and we can finally
            // read more messages. It's safe to call readMoreEntries() multiple times.
            readMoreEntries();
        }
    }

    protected synchronized Set<PositionImpl> getMessagesToReplayNow(int maxMessagesToRead) {
        if (isDispatcherStuckOnReplays) {
            // If we're stuck on replay, we want to move forward reading on the topic (until the overall max-unacked
            // messages kicks in), instead of keep replaying the same old messages, since the consumer that these
            // messages are routing to might be busy at the moment
            this.isDispatcherStuckOnReplays = false;
            return Collections.emptySet();
        } else {
            return super.getMessagesToReplayNow(maxMessagesToRead);
        }
    }

    @Override
    public SubType getType() {
        return SubType.Key_Shared;
    }

    @Override
    protected Set<? extends Position> asyncReplayEntries(Set<? extends Position> positions) {
        return cursor.asyncReplayEntries(positions, this, ReadType.Replay, true);
    }

    private static final Logger log = LoggerFactory.getLogger(PersistentStickyKeyDispatcherMultipleConsumers.class);

}
