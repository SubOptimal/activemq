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
package org.apache.activemq.transport.mqtt.strategy;

import static org.apache.activemq.transport.mqtt.MQTTProtocolSupport.convertActiveMQToMQTT;
import static org.apache.activemq.transport.mqtt.MQTTProtocolSupport.convertMQTTToActiveMQ;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.activemq.ActiveMQPrefetchPolicy;
import org.apache.activemq.broker.region.QueueRegion;
import org.apache.activemq.broker.region.RegionBroker;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.ConsumerInfo;
import org.apache.activemq.command.DestinationInfo;
import org.apache.activemq.command.Response;
import org.apache.activemq.transport.mqtt.MQTTProtocolConverter;
import org.apache.activemq.transport.mqtt.MQTTProtocolException;
import org.apache.activemq.transport.mqtt.MQTTSubscription;
import org.apache.activemq.transport.mqtt.ResponseHandler;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.codec.CONNECT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscription strategy that converts all MQTT subscribes that would be durable to
 * Virtual Topic Queue subscriptions.  Also maps all publish requests to be prefixed
 * with the VirtualTopic. prefix unless already present.
 */
public class MQTTVirtualTopicSubscriptionStrategy extends AbstractMQTTSubscriptionStrategy {

    private static final String VIRTUALTOPIC_PREFIX = "VirtualTopic.";
    private static final String VIRTUALTOPIC_CONSUMER_PREFIX = "Consumer.";

    private static final Logger LOG = LoggerFactory.getLogger(MQTTVirtualTopicSubscriptionStrategy.class);

    private final Set<ActiveMQQueue> restoredQueues = Collections.synchronizedSet(new HashSet<ActiveMQQueue>());

    @Override
    public void onConnect(CONNECT connect) throws MQTTProtocolException {
        List<ActiveMQQueue> queues = lookupQueues(protocol.getClientId());

        if (connect.cleanSession()) {
            deleteDurableQueues(queues);
        } else {
            restoreDurableQueue(queues);
        }
    }

    @Override
    public byte onSubscribe(String topicName, QoS requestedQoS) throws MQTTProtocolException {
        ActiveMQDestination destination = null;
        ConsumerInfo consumerInfo = new ConsumerInfo(getNextConsumerId());
        if (!protocol.isCleanSession() && protocol.getClientId() != null && requestedQoS.ordinal() >= QoS.AT_LEAST_ONCE.ordinal()) {
            String converted = VIRTUALTOPIC_CONSUMER_PREFIX + protocol.getClientId() + ":" + requestedQoS + "." +
                               VIRTUALTOPIC_PREFIX + convertMQTTToActiveMQ(topicName);
            destination = new ActiveMQQueue(converted);
            consumerInfo.setPrefetchSize(ActiveMQPrefetchPolicy.DEFAULT_QUEUE_PREFETCH);
        } else {
            String converted = convertMQTTToActiveMQ(topicName);
            if (!converted.startsWith(VIRTUALTOPIC_PREFIX)) {
                converted = VIRTUALTOPIC_PREFIX + convertMQTTToActiveMQ(topicName);
            }
            destination = new ActiveMQTopic(converted);
            consumerInfo.setPrefetchSize(ActiveMQPrefetchPolicy.DEFAULT_TOPIC_PREFETCH);
        }

        consumerInfo.setDestination(destination);
        if (protocol.getActiveMQSubscriptionPrefetch() > 0) {
            consumerInfo.setPrefetchSize(protocol.getActiveMQSubscriptionPrefetch());
        }
        consumerInfo.setRetroactive(true);
        consumerInfo.setDispatchAsync(true);

        return doSubscribe(consumerInfo, topicName, requestedQoS);
    }

    @Override
    public void onReSubscribe(MQTTSubscription mqttSubscription) throws MQTTProtocolException {

        ActiveMQDestination destination = mqttSubscription.getDestination();

        // check whether the Topic has been recovered in restoreDurableSubs
        // mark subscription available for recovery for duplicate subscription
        if (restoredQueues.remove(destination)) {
            return;
        }

        if (mqttSubscription.getDestination().isTopic()) {
            super.onReSubscribe(mqttSubscription);
        } else {
            doUnSubscribe(mqttSubscription);
            ConsumerInfo consumerInfo = mqttSubscription.getConsumerInfo();
            consumerInfo.setConsumerId(getNextConsumerId());
            doSubscribe(consumerInfo, mqttSubscription.getTopicName(), mqttSubscription.getQoS());
        }
    }

    @Override
    public void onUnSubscribe(String topicName) throws MQTTProtocolException {
        MQTTSubscription subscription = mqttSubscriptionByTopic.remove(topicName);
        if (subscription != null) {
            doUnSubscribe(subscription);
            if (subscription.getDestination().isQueue()) {
                DestinationInfo remove = new DestinationInfo();
                remove.setConnectionId(protocol.getConnectionId());
                remove.setDestination(subscription.getDestination());
                remove.setOperationType(DestinationInfo.REMOVE_OPERATION_TYPE);

                protocol.sendToActiveMQ(remove, new ResponseHandler() {
                    @Override
                    public void onResponse(MQTTProtocolConverter converter, Response response) throws IOException {
                        // ignore failures..
                    }
                });
            }
        }
    }

    @Override
    public ActiveMQDestination onSend(String topicName) {
        if (!topicName.startsWith(VIRTUALTOPIC_PREFIX)) {
            return new ActiveMQTopic(VIRTUALTOPIC_PREFIX + topicName);
        } else {
            return new ActiveMQTopic(topicName);
        }
    }

    @Override
    public String onSend(ActiveMQDestination destination) {
        String destinationName = destination.getPhysicalName();
        int position = destinationName.indexOf(VIRTUALTOPIC_PREFIX);
        if (position >= 0) {
            destinationName = destinationName.substring(position+VIRTUALTOPIC_PREFIX.length()).substring(0);
        }
        return destinationName;
    }

    @Override
    public boolean isControlTopic(ActiveMQDestination destination) {
        String destinationName = destination.getPhysicalName();
        if (destinationName.startsWith("$") || destinationName.startsWith(VIRTUALTOPIC_PREFIX + "$")) {
            return true;
        }
        return false;
    }

    private void deleteDurableQueues(List<ActiveMQQueue> queues) {
        try {
            for (ActiveMQQueue queue : queues) {
                LOG.debug("Removing subscription for {} ",queue.getPhysicalName());
                DestinationInfo removeAction = new DestinationInfo();
                removeAction.setConnectionId(protocol.getConnectionId());
                removeAction.setDestination(queue);
                removeAction.setOperationType(DestinationInfo.REMOVE_OPERATION_TYPE);

                protocol.sendToActiveMQ(removeAction, new ResponseHandler() {
                    @Override
                    public void onResponse(MQTTProtocolConverter converter, Response response) throws IOException {
                        // ignore failures..
                    }
                });
            }
        } catch (Throwable e) {
            LOG.warn("Could not delete the MQTT durable subs.", e);
        }
    }

    private void restoreDurableQueue(List<ActiveMQQueue> queues) {
        try {
            for (ActiveMQQueue queue : queues) {
                String name = queue.getPhysicalName().substring(VIRTUALTOPIC_CONSUMER_PREFIX.length());
                StringTokenizer tokenizer = new StringTokenizer(name);
                tokenizer.nextToken(":.");
                String qosString = tokenizer.nextToken();
                tokenizer.nextToken();
                String topicName = convertActiveMQToMQTT(tokenizer.nextToken("").substring(1));
                QoS qoS = QoS.valueOf(qosString);
                LOG.trace("Restoring subscription: {}:{}", topicName, qoS);

                ConsumerInfo consumerInfo = new ConsumerInfo(getNextConsumerId());
                consumerInfo.setDestination(queue);
                consumerInfo.setPrefetchSize(ActiveMQPrefetchPolicy.DEFAULT_QUEUE_PREFETCH);
                if (protocol.getActiveMQSubscriptionPrefetch() > 0) {
                    consumerInfo.setPrefetchSize(protocol.getActiveMQSubscriptionPrefetch());
                }
                consumerInfo.setRetroactive(true);
                consumerInfo.setDispatchAsync(true);

                doSubscribe(consumerInfo, topicName, qoS);

                // mark this durable subscription as restored by Broker
                restoredQueues.add(queue);
            }
        } catch (IOException e) {
            LOG.warn("Could not restore the MQTT durable subs.", e);
        }
    }

    List<ActiveMQQueue> lookupQueues(String clientId) throws MQTTProtocolException {
        List<ActiveMQQueue> result = new ArrayList<ActiveMQQueue>();
        RegionBroker regionBroker;

        try {
            regionBroker = (RegionBroker) brokerService.getBroker().getAdaptor(RegionBroker.class);
        } catch (Exception e) {
            throw new MQTTProtocolException("Error recovering queues: " + e.getMessage(), false, e);
        }

        final QueueRegion queueRegion = (QueueRegion) regionBroker.getQueueRegion();
        for (ActiveMQDestination destination : queueRegion.getDestinationMap().keySet()) {
            if (destination.isQueue() && !destination.isTemporary()) {
                if (destination.getPhysicalName().startsWith("Consumer." + clientId)) {
                    LOG.debug("Recovered client sub: {} on connect", destination.getPhysicalName());
                    result.add((ActiveMQQueue) destination);
                }
            }
        }

        return result;
    }
}
