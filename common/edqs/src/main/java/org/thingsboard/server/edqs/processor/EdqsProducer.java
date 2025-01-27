/**
 * Copyright © 2016-2024 ThingsBoard, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.edqs.processor;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.ObjectType;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.edqs.util.EdqsPartitionService;
import org.thingsboard.server.gen.transport.TransportProtos.ToEdqsMsg;
import org.thingsboard.server.queue.TbQueueCallback;
import org.thingsboard.server.queue.TbQueueMsgMetadata;
import org.thingsboard.server.queue.TbQueueProducer;
import org.thingsboard.server.queue.common.TbProtoQueueMsg;
import org.thingsboard.server.queue.edqs.EdqsQueue;
import org.thingsboard.server.queue.kafka.TbKafkaProducerTemplate;

@Slf4j
public class EdqsProducer {

    private final EdqsQueue queue;
    private final EdqsPartitionService partitionService;

    private final TbQueueProducer<TbProtoQueueMsg<ToEdqsMsg>> producer;

    @Builder
    public EdqsProducer(EdqsQueue queue,
                        EdqsPartitionService partitionService,
                        TbQueueProducer<TbProtoQueueMsg<ToEdqsMsg>> producer) {
        this.queue = queue;
        this.partitionService = partitionService;
        this.producer = producer;
    }

    // TODO: queue prefix!

    public void send(TenantId tenantId, ObjectType type, String key, ToEdqsMsg msg) {
        String topic = queue.getTopic();
        TbQueueCallback callback = new TbQueueCallback() {
            @Override
            public void onSuccess(TbQueueMsgMetadata metadata) {
                log.debug("[{}][{}][{}] Published msg to {}: {}", tenantId, type, key, topic, msg); // fixme log levels
            }

            @Override
            public void onFailure(Throwable t) {
                log.warn("[{}][{}][{}] Failed to publish msg to {}: {}", tenantId, type, key, topic, msg, t);
            }
        };
        if (producer instanceof TbKafkaProducerTemplate<TbProtoQueueMsg<ToEdqsMsg>> kafkaProducer) {
            TopicPartitionInfo tpi = TopicPartitionInfo.builder()
                    .topic(topic)
                    .partition(partitionService.resolvePartition(tenantId))
                    .useInternalPartition(true)
                    .build();
            kafkaProducer.send(tpi, key, new TbProtoQueueMsg<>(null, msg), callback); // specifying custom key for compaction
        } else {
            TopicPartitionInfo tpi = TopicPartitionInfo.builder()
                    .topic(topic)
                    .build();
            producer.send(tpi, new TbProtoQueueMsg<>(null, msg), callback);
        }
    }

    public void stop() {
        producer.stop();
    }

}
