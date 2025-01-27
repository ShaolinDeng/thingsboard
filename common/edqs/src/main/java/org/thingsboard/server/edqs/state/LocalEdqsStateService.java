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
package org.thingsboard.server.edqs.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.ObjectType;
import org.thingsboard.server.common.data.edqs.EdqsEventType;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.edqs.processor.EdqsProcessor;
import org.thingsboard.server.edqs.util.EdqsRocksDb;
import org.thingsboard.server.gen.transport.TransportProtos.ToEdqsMsg;
import org.thingsboard.server.queue.edqs.EdqsQueue;
import org.thingsboard.server.queue.edqs.InMemoryEdqsComponent;

import java.util.Set;

@Service
@RequiredArgsConstructor
@InMemoryEdqsComponent
@Slf4j
public class LocalEdqsStateService implements EdqsStateService {

    @Autowired @Lazy
    private EdqsProcessor processor;
    @Autowired
    private EdqsRocksDb db;

    private Set<TopicPartitionInfo> partitions;

    @Override
    public void restore(Set<TopicPartitionInfo> partitions) {
        if (this.partitions == null) {
            this.partitions = partitions;
        } else {
            return;
        }

        db.forEach((key, value) -> {
            try {
                ToEdqsMsg edqsMsg = ToEdqsMsg.parseFrom(value);
                log.trace("[{}] Restored msg from RocksDB: {}", key, edqsMsg);
                processor.process(edqsMsg, EdqsQueue.STATE);
            } catch (Exception e) {
                log.error("[{}] Failed to restore value", key, e);
            }
        });
    }

    @Override
    public void save(TenantId tenantId, ObjectType type, String key, EdqsEventType eventType, ToEdqsMsg msg) {
        log.trace("Save to RocksDB: {} {} {} {}", tenantId, type, key, msg);
        try {
            if (eventType == EdqsEventType.DELETED) {
                db.delete(key);
            } else {
                db.put(key, msg.toByteArray());
            }
        } catch (Exception e) {
            log.error("[{}] Failed to save event {}", key, msg, e);
        }
    }

}
