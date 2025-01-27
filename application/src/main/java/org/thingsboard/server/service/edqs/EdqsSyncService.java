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
package org.thingsboard.server.service.edqs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.ObjectType;
import org.thingsboard.server.common.data.edqs.AttributeKv;
import org.thingsboard.server.common.data.edqs.EdqsEventType;
import org.thingsboard.server.common.data.edqs.EdqsObject;
import org.thingsboard.server.common.data.edqs.Entity;
import org.thingsboard.server.common.data.edqs.LatestTsKv;
import org.thingsboard.server.common.data.edqs.fields.EntityFields;
import org.thingsboard.server.common.data.edqs.fields.TenantFields;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageDataIterable;
import org.thingsboard.server.common.data.page.SortOrder;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.dao.Dao;
import org.thingsboard.server.dao.attributes.AttributesDao;
import org.thingsboard.server.dao.dictionary.KeyDictionaryDao;
import org.thingsboard.server.dao.entity.EntityDaoRegistry;
import org.thingsboard.server.dao.group.EntityGroupDao;
import org.thingsboard.server.dao.model.sql.AttributeKvEntity;
import org.thingsboard.server.dao.model.sqlts.dictionary.KeyDictionaryEntry;
import org.thingsboard.server.dao.model.sqlts.latest.TsKvLatestEntity;
import org.thingsboard.server.dao.relation.RelationDao;
import org.thingsboard.server.dao.tenant.TenantDao;
import org.thingsboard.server.dao.timeseries.TimeseriesLatestDao;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.thingsboard.server.common.data.ObjectType.API_USAGE_STATE;
import static org.thingsboard.server.common.data.ObjectType.ASSET;
import static org.thingsboard.server.common.data.ObjectType.ASSET_PROFILE;
import static org.thingsboard.server.common.data.ObjectType.ATTRIBUTE_KV;
import static org.thingsboard.server.common.data.ObjectType.BLOB_ENTITY;
import static org.thingsboard.server.common.data.ObjectType.CONVERTER;
import static org.thingsboard.server.common.data.ObjectType.CUSTOMER;
import static org.thingsboard.server.common.data.ObjectType.DASHBOARD;
import static org.thingsboard.server.common.data.ObjectType.DEVICE;
import static org.thingsboard.server.common.data.ObjectType.DEVICE_PROFILE;
import static org.thingsboard.server.common.data.ObjectType.EDGE;
import static org.thingsboard.server.common.data.ObjectType.ENTITY_GROUP;
import static org.thingsboard.server.common.data.ObjectType.ENTITY_VIEW;
import static org.thingsboard.server.common.data.ObjectType.INTEGRATION;
import static org.thingsboard.server.common.data.ObjectType.LATEST_TS_KV;
import static org.thingsboard.server.common.data.ObjectType.QUEUE_STATS;
import static org.thingsboard.server.common.data.ObjectType.RELATION;
import static org.thingsboard.server.common.data.ObjectType.ROLE;
import static org.thingsboard.server.common.data.ObjectType.RULE_CHAIN;
import static org.thingsboard.server.common.data.ObjectType.SCHEDULER_EVENT;
import static org.thingsboard.server.common.data.ObjectType.TENANT;
import static org.thingsboard.server.common.data.ObjectType.TENANT_PROFILE;
import static org.thingsboard.server.common.data.ObjectType.USER;
import static org.thingsboard.server.common.data.ObjectType.WIDGETS_BUNDLE;
import static org.thingsboard.server.common.data.ObjectType.WIDGET_TYPE;

@Slf4j
public abstract class EdqsSyncService {

    @Autowired
    private EntityDaoRegistry entityDaoRegistry;
    @Autowired
    private TenantDao tenantDao;
    @Autowired
    private AttributesDao attributesDao;
    @Autowired
    private KeyDictionaryDao keyDictionaryDao;
    @Autowired
    private RelationDao relationDao;
    @Autowired
    private EntityGroupDao entityGroupDao;
    @Autowired
    private TimeseriesLatestDao timeseriesLatestDao;
    @Autowired
    @Lazy
    private DefaultEdqsService edqsService;

    private final ConcurrentHashMap<UUID, EntityIdInfo> entityInfoMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> keys = new ConcurrentHashMap<>();

    private final Map<ObjectType, AtomicInteger> counters = new ConcurrentHashMap<>();

    public static final Set<ObjectType> edqsTenantTypes = EnumSet.of(
            TENANT_PROFILE, CUSTOMER, DEVICE_PROFILE, DEVICE, ASSET_PROFILE, ASSET, EDGE, ENTITY_VIEW, USER, DASHBOARD,
            RULE_CHAIN, WIDGET_TYPE, WIDGETS_BUNDLE, CONVERTER, INTEGRATION, SCHEDULER_EVENT, ROLE,
            BLOB_ENTITY, API_USAGE_STATE, QUEUE_STATS
    );

    public abstract boolean isSyncNeeded();

    public void sync() {
        log.info("Synchronizing data to EDQS");
        long startTs = System.currentTimeMillis();
        counters.clear();

        syncTenants();
        syncTenantEntities();
        syncEntityGroups();
        syncRelations();
        loadKeyDictionary();
        syncAttributes();
        syncLatestTimeseries();

        counters.clear();
        log.info("Finishing synchronizing data to EDQS in {} ms", (System.currentTimeMillis() - startTs));
    }

    private void process(TenantId tenantId, ObjectType type, EdqsObject object) {
        AtomicInteger counter = counters.computeIfAbsent(type, t -> new AtomicInteger());
        if (counter.incrementAndGet() % 10000 == 0) {
            log.info("Processed {} {} objects", counter.get(), type);
        }
        edqsService.processEvent(tenantId, type, EdqsEventType.UPDATED, object);
    }

    private void syncTenants() {
        log.info("Synchronizing tenants to EDQS");
        long ts = System.currentTimeMillis();
        var tenants = new PageDataIterable<>(tenantDao::findAllFields, 10000);
        for (EntityFields entityFields : tenants) {
            TenantId tenantId = TenantId.fromUUID(entityFields.getId());
            entityInfoMap.put(entityFields.getId(), new EntityIdInfo(EntityType.TENANT, tenantId));
            process(tenantId, TENANT, new Entity(EntityType.TENANT, entityFields));
        }
        process(TenantId.SYS_TENANT_ID, TENANT, new Entity(EntityType.TENANT, new TenantFields(TenantId.SYS_TENANT_ID.getId(), Long.MAX_VALUE)));
        log.info("Finished synchronizing tenants to EDQS in {} ms", (System.currentTimeMillis() - ts));
    }

    private void syncTenantEntities() {
        for (ObjectType type : edqsTenantTypes) {
            log.info("Synchronizing tenant {} entities to EDQS", type);
            long ts = System.currentTimeMillis();
            EntityType entityType = type.toEntityType();
            Dao<?> dao = entityDaoRegistry.getDao(entityType);
            var entities = new PageDataIterable<>(dao::findAllFields, 10000);
            for (EntityFields entityFields : entities) {
                TenantId tenantId = TenantId.fromUUID(entityFields.getTenantId());
                entityInfoMap.put(entityFields.getId(), new EntityIdInfo(entityType, tenantId));
                process(tenantId, type, new Entity(type.toEntityType(), entityFields));
            }
            log.info("Finished synchronizing tenant {} entities to EDQS in {} ms", type, (System.currentTimeMillis() - ts));
        }
    }

    private void syncEntityGroups() {
        log.info("Synchronizing entity groups to EDQS");
        long ts = System.currentTimeMillis();
        var entityGroups = new PageDataIterable<>(entityGroupDao::findAllFields, 10000);
        for (EntityFields groupFields : entityGroups) {
            EntityIdInfo entityIdInfo = entityInfoMap.get(groupFields.getOwnerId());
            if (entityIdInfo != null) {
                entityInfoMap.put(groupFields.getId(), new EntityIdInfo(EntityType.ENTITY_GROUP, entityIdInfo.tenantId()));
                process(entityIdInfo.tenantId(), ENTITY_GROUP, new Entity(EntityType.ENTITY_GROUP, groupFields));
            } else {
                log.info("Entity group owner not found: " + groupFields.getOwnerId());
            }
        }
        log.info("Finished synchronizing entity groups to EDQS in {} ms", (System.currentTimeMillis() - ts));
    }

    private void syncRelations() {
        log.info("Synchronizing relations to EDQS");
        long ts = System.currentTimeMillis();
        var relations = new PageDataIterable<>(relationDao::findAll, 10000);
        for (EntityRelation relation : relations) {
            if (relation.getTypeGroup() == RelationTypeGroup.COMMON || relation.getTypeGroup() == RelationTypeGroup.FROM_ENTITY_GROUP) {
                EntityIdInfo entityIdInfo = entityInfoMap.get(relation.getFrom().getId());
                if (entityIdInfo != null) {
                    process(entityIdInfo.tenantId(), RELATION, relation);
                } else {
                    log.info("Relation from entity not found: " + relation.getFrom());
                }
            }
        }
        log.info("Finished synchronizing relations to EDQS in {} ms", (System.currentTimeMillis() - ts));
    }

    private void loadKeyDictionary() {
        log.info("Loading key dictionary");
        long ts = System.currentTimeMillis();
        var keyDictionaryEntries = new PageDataIterable<>(keyDictionaryDao::findAll, 10000);
        for (KeyDictionaryEntry keyDictionaryEntry : keyDictionaryEntries) {
            keys.put(keyDictionaryEntry.getKeyId(), keyDictionaryEntry.getKey());
        }
        log.info("Finished loading key dictionary in {} ms", (System.currentTimeMillis() - ts));
    }

    private void syncAttributes() {
        log.info("Synchronizing attributes to EDQS");
        long ts = System.currentTimeMillis();
        var attributes = new PageDataIterable<>(attributesDao::findAll, 10000);
        for (AttributeKvEntity attribute : attributes) {
            attribute.setStrKey(getStrKeyOrFetchFromDb(attribute.getId().getAttributeKey()));
            UUID entityId = attribute.getId().getEntityId();
            EntityIdInfo entityIdInfo = entityInfoMap.get(entityId);
            if (entityIdInfo == null) {
                log.debug("Skipping attribute with entity UUID {} as it is not found in entityInfoMap", entityId);
                continue;
            }
            AttributeKv attributeKv = new AttributeKv(
                    EntityIdFactory.getByTypeAndUuid(entityIdInfo.entityType(), entityId),
                    AttributeScope.valueOf(attribute.getId().getAttributeType()),
                    attribute.toData(),
                    attribute.getVersion());
            process(entityIdInfo.tenantId(), ATTRIBUTE_KV, attributeKv);
        }
        log.info("Finished synchronizing attributes to EDQS in {} ms", (System.currentTimeMillis() - ts));
    }

    private void syncLatestTimeseries() {
        log.info("Synchronizing latest timeseries to EDQS");
        long ts = System.currentTimeMillis();
        var tsKvLatestEntities = new PageDataIterable<>(pageLink -> timeseriesLatestDao.findAllLatest(pageLink), 10000);
        for (TsKvLatestEntity tsKvLatestEntity : tsKvLatestEntities) {
            try {
                String strKey = getStrKeyOrFetchFromDb(tsKvLatestEntity.getKey());
                if (strKey == null) {
                    log.debug("Skipping latest timeseries with key {} as it is not found in key dictionary", tsKvLatestEntity.getKey());
                    continue;
                }
                tsKvLatestEntity.setStrKey(strKey);
                UUID entityUuid = tsKvLatestEntity.getEntityId();
                EntityIdInfo entityIdInfo = entityInfoMap.get(entityUuid);
                if (entityIdInfo != null) {
                    EntityId entityId = EntityIdFactory.getByTypeAndUuid(entityIdInfo.entityType(), entityUuid);
                    LatestTsKv latestTsKv = new LatestTsKv(entityId, tsKvLatestEntity.toData(), tsKvLatestEntity.getVersion());
                    process(entityIdInfo.tenantId(), LATEST_TS_KV, latestTsKv);
                }
            } catch (Exception e) {
                log.error("Failed to sync latest timeseries: {}", tsKvLatestEntity, e);
            }
        }
        log.info("Finished synchronizing latest timeseries to EDQS in {} ms", (System.currentTimeMillis() - ts));
    }

    private String getStrKeyOrFetchFromDb(int key) {
        String strKey = keys.get(key);
        if (strKey != null) {
            return strKey;
        } else {
            strKey = keyDictionaryDao.getKey(key);
            keys.putIfAbsent(key, strKey);
        }
        return strKey;
    }

    public record EntityIdInfo(EntityType entityType, TenantId tenantId) {}

}
