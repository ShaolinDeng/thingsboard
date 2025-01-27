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
package org.thingsboard.server.edqs.util;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.queue.discovery.HashPartitionService;
import org.thingsboard.server.queue.edqs.EdqsConfig;
import org.thingsboard.server.queue.edqs.EdqsConfig.EdqsPartitioningStrategy;

@Service
@RequiredArgsConstructor
public class EdqsPartitionService {

    private final HashPartitionService hashPartitionService;
    private final EdqsConfig edqsConfig;

    public Integer resolvePartition(TenantId tenantId) {
        if (edqsConfig.getPartitioningStrategy() == EdqsPartitioningStrategy.TENANT) {
            return hashPartitionService.resolvePartitionIndex(tenantId.getId(), edqsConfig.getPartitions());
        } else {
            return null;
        }
    }

}
