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
package com.yahoo.pulsar.broker.loadbalance;

import com.yahoo.pulsar.broker.PulsarServerException;
import com.yahoo.pulsar.broker.PulsarService;
import com.yahoo.pulsar.common.naming.ServiceUnitId;
import com.yahoo.pulsar.common.policies.data.loadbalancer.ServiceLookupData;
import com.yahoo.pulsar.zookeeper.ZooKeeperCache.Deserializer;

/**
 * New proposal for a load manager interface which attempts to use more intuitive method names and provide a starting
 * place for new load manager proposals.
 */
public interface ModularLoadManager {

    /**
     * As any broker, disable the broker this manager is running on.
     * 
     * @throws PulsarServerException
     *             If ZooKeeper failed to disable the broker.
     */
    void disableBroker() throws PulsarServerException;

    /**
     * As the leader broker, select bundles for the namespace service to unload so that they may be reassigned to new
     * brokers.
     */
    void doLoadShedding();

    /**
     * As the leader broker, attempt to automatically detect and split hot namespace bundles.
     */
    void doNamespaceBundleSplit();

    /**
     * Initialize this load manager using the given pulsar service.
     */
    void initialize(PulsarService pulsar);

    /**
     * As the leader broker, find a suitable broker for the assignment of the given bundle.
     * 
     * @param serviceUnit
     *            ServiceUnitId for the bundle.
     * @return The name of the selected broker, as it appears on ZooKeeper.
     */
    String selectBrokerForAssignment(ServiceUnitId serviceUnit);

    /**
     * As any broker, start the load manager.
     * 
     * @throws PulsarServerException
     *             If an unexpected error prevented the load manager from being started.
     */
    void start() throws PulsarServerException;

    /**
     * As any broker, stop the load manager.
     * 
     * @throws PulsarServerException
     *             If an unexpected error occurred when attempting to stop the load manager.
     */
    void stop() throws PulsarServerException;

    /**
     * As any broker, retrieve the namespace bundle stats and system resource usage to update data local to this broker.
     */
    void updateLocalBrokerData();

    /**
     * As any broker, write the local broker data to ZooKeeper.
     */
    void writeBrokerDataOnZooKeeper();

    /**
     * As the leader broker, write bundle data aggregated from all brokers to ZooKeeper.
     */
    void writeBundleDataOnZooKeeper();

    /**
     * Return :{@link Deserializer} to deserialize load-manager load report
     * 
     * @return
     */
    Deserializer<? extends ServiceLookupData> getLoadReportDeserializer();
}
