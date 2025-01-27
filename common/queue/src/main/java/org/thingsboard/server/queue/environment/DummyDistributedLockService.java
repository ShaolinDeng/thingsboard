/**
 * Copyright © 2016-2024 The Thingsboard Authors
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
package org.thingsboard.server.queue.environment;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReentrantLock;

@Service
@ConditionalOnProperty(prefix = "zk", value = "enabled", havingValue = "false", matchIfMissing = true)
public class DummyDistributedLockService implements DistributedLockService {

    @Override
    public <I> DistributedLock<I> getLock(String key) {
        return new DummyDistributedLock<>();
    }

    @RequiredArgsConstructor
    private static class DummyDistributedLock<I> implements DistributedLock<I> {

        private final ReentrantLock lock = new ReentrantLock();

        @SneakyThrows
        @Override
        public void lock() {
            lock.lock();
        }

        @SneakyThrows
        @Override
        public void unlock() {
            lock.unlock();
        }

    }

}
