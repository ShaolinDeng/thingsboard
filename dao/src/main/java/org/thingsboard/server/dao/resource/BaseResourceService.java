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
package org.thingsboard.server.dao.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.RegexUtils;
import org.thingsboard.server.cache.resourceInfo.ResourceInfoCacheKey;
import org.thingsboard.server.cache.resourceInfo.ResourceInfoEvictEvent;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.ResourceType;
import org.thingsboard.server.common.data.TbResource;
import org.thingsboard.server.common.data.TbResourceInfo;
import org.thingsboard.server.common.data.TbResourceInfoFilter;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.HasId;
import org.thingsboard.server.common.data.id.TbResourceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.widget.WidgetTypeDetails;
import org.thingsboard.server.dao.entity.AbstractCachedEntityService;
import org.thingsboard.server.dao.eventsourcing.DeleteEntityEvent;
import org.thingsboard.server.dao.eventsourcing.SaveEntityEvent;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.service.PaginatedRemover;
import org.thingsboard.server.dao.service.Validator;
import org.thingsboard.server.dao.service.validator.ResourceDataValidator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.thingsboard.server.dao.device.DeviceServiceImpl.INCORRECT_TENANT_ID;
import static org.thingsboard.server.dao.service.Validator.validateId;

@Service("TbResourceDaoService")
@Slf4j
@RequiredArgsConstructor
@Primary
public class BaseResourceService extends AbstractCachedEntityService<ResourceInfoCacheKey, TbResourceInfo, ResourceInfoEvictEvent> implements ResourceService {

    public static final String INCORRECT_RESOURCE_ID = "Incorrect resourceId ";
    protected final TbResourceDao resourceDao;
    protected final TbResourceInfoDao resourceInfoDao;
    protected final ResourceDataValidator resourceValidator;
    @Autowired @Lazy
    private ImageService imageService;

    private static final Map<String, String> DASHBOARD_RESOURCES_MAPPING = Map.of(
            "widgets.*.config.actions.*.*.customResources.*.url.id", ""
    );

    private static final Map<String, String> WIDGET_RESOURCES_MAPPING = Map.of(
            "descriptor.resources.*.url.id", ""
    );

    @Override
    public TbResource saveResource(TbResource resource, boolean doValidate) {
        log.trace("Executing saveResource [{}]", resource);
        if (resource.getId() == null) {
            resource.setResourceKey(getUniqueKey(resource.getTenantId(), resource.getResourceType(), StringUtils.defaultIfEmpty(resource.getResourceKey(), resource.getFileName())));
        }
        if (doValidate) {
            resourceValidator.validate(resource, TbResourceInfo::getTenantId);
        }
        if (resource.getData() != null) {
            resource.setEtag(calculateEtag(resource.getData()));
        }
        return doSaveResource(resource);
    }

    @Override
    public TbResource saveResource(TbResource resource) {
        return saveResource(resource, true);
    }

    protected TbResource doSaveResource(TbResource resource) {
        TenantId tenantId = resource.getTenantId();
        try {
            TbResource saved;
            if (resource.getData() != null) {
                saved = resourceDao.save(tenantId, resource);
            } else {
                TbResourceInfo resourceInfo = saveResourceInfo(resource);
                saved = new TbResource(resourceInfo);
            }
            publishEvictEvent(new ResourceInfoEvictEvent(tenantId, resource.getId()));
            eventPublisher.publishEvent(SaveEntityEvent.builder().tenantId(saved.getTenantId()).entityId(saved.getId())
                    .entity(saved).created(resource.getId() == null).build());
            return saved;
        } catch (Exception t) {
            publishEvictEvent(new ResourceInfoEvictEvent(tenantId, resource.getId()));
            ConstraintViolationException e = extractConstraintViolationException(t).orElse(null);
            if (e != null && e.getConstraintName() != null && e.getConstraintName().equalsIgnoreCase("resource_unq_key")) {
                throw new DataValidationException("Resource with such key already exists!");
            } else {
                throw t;
            }
        }
    }

    private TbResourceInfo saveResourceInfo(TbResource resource) {
        return resourceInfoDao.save(resource.getTenantId(), new TbResourceInfo(resource));
    }

    protected String getUniqueKey(TenantId tenantId, ResourceType resourceType, String filename) {
        if (!resourceInfoDao.existsByTenantIdAndResourceTypeAndResourceKey(tenantId, resourceType, filename)) {
            return filename;
        }

        String basename = StringUtils.substringBeforeLast(filename, ".");
        String extension = StringUtils.substringAfterLast(filename, ".");

        Set<String> existing = resourceInfoDao.findKeysByTenantIdAndResourceTypeAndResourceKeyPrefix(
                tenantId, resourceType, basename
        );
        String resourceKey = filename;
        int idx = 1;
        while (existing.contains(resourceKey)) {
            resourceKey = basename + "_(" + idx + ")." + extension;
            idx++;
        }
        log.debug("[{}] Generated unique key {} for {} {}", tenantId, resourceKey, resourceType, filename);
        return resourceKey;
    }

    @Override
    public TbResource findResourceByTenantIdAndKey(TenantId tenantId, ResourceType resourceType, String resourceKey) {
        log.trace("Executing findResourceByTenantIdAndKey [{}] [{}] [{}]", tenantId, resourceType, resourceKey);
        return resourceDao.findResourceByTenantIdAndKey(tenantId, resourceType, resourceKey);
    }

    @Override
    public TbResource findResourceById(TenantId tenantId, TbResourceId resourceId) {
        log.trace("Executing findResourceById [{}] [{}]", tenantId, resourceId);
        Validator.validateId(resourceId, id -> INCORRECT_RESOURCE_ID + id);
        return resourceDao.findById(tenantId, resourceId.getId());
    }

    @Override
    public byte[] getResourceData(TenantId tenantId, TbResourceId resourceId) {
        log.trace("Executing getResourceData [{}] [{}]", tenantId, resourceId);
        return resourceDao.getResourceData(tenantId, resourceId);
    }

    @Override
    public TbResourceInfo findResourceInfoById(TenantId tenantId, TbResourceId resourceId) {
        log.trace("Executing findResourceInfoById [{}] [{}]", tenantId, resourceId);
        Validator.validateId(resourceId, id -> INCORRECT_RESOURCE_ID + id);

        return cache.getAndPutInTransaction(new ResourceInfoCacheKey(tenantId, resourceId),
                () -> resourceInfoDao.findById(tenantId, resourceId.getId()), true);
    }

    @Override
    public TbResourceInfo findResourceInfoByTenantIdAndKey(TenantId tenantId, ResourceType resourceType, String resourceKey) {
        log.trace("Executing findResourceInfoByTenantIdAndKey [{}] [{}] [{}]", tenantId, resourceType, resourceKey);
        return resourceInfoDao.findByTenantIdAndKey(tenantId, resourceType, resourceKey);
    }

    @Override
    public ListenableFuture<TbResourceInfo> findResourceInfoByIdAsync(TenantId tenantId, TbResourceId resourceId) {
        log.trace("Executing findResourceInfoById [{}] [{}]", tenantId, resourceId);
        Validator.validateId(resourceId, id -> INCORRECT_RESOURCE_ID + id);
        return resourceInfoDao.findByIdAsync(tenantId, resourceId.getId());
    }

    @Override
    public void deleteResource(TenantId tenantId, TbResourceId resourceId) {
        deleteResource(tenantId, resourceId, false);
    }

    @Override
    public void deleteResource(TenantId tenantId, TbResourceId resourceId, boolean force) {
        log.trace("Executing deleteResource [{}] [{}]", tenantId, resourceId);
        Validator.validateId(resourceId, id -> INCORRECT_RESOURCE_ID + id);
        if (!force) {
            resourceValidator.validateDelete(tenantId, resourceId);
        }
        TbResource resource = findResourceById(tenantId, resourceId);
        if (resource == null) {
            return;
        }
        resourceDao.removeById(tenantId, resourceId.getId());
        eventPublisher.publishEvent(DeleteEntityEvent.builder().tenantId(tenantId).entity(resource).entityId(resourceId).build());
    }

    @Override
    public void deleteEntity(TenantId tenantId, EntityId id, boolean force) {
        deleteResource(tenantId, (TbResourceId) id, force);
    }

    @Override
    public PageData<TbResourceInfo> findAllTenantResourcesByTenantId(TbResourceInfoFilter filter, PageLink pageLink) {
        TenantId tenantId = filter.getTenantId();
        log.trace("Executing findAllTenantResourcesByTenantId [{}]", tenantId);
        validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        return resourceInfoDao.findAllTenantResourcesByTenantId(filter, pageLink);
    }

    @Override
    public PageData<TbResourceInfo> findTenantResourcesByTenantId(TbResourceInfoFilter filter, PageLink pageLink) {
        TenantId tenantId = filter.getTenantId();
        log.trace("Executing findTenantResourcesByTenantId [{}]", tenantId);
        validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        return resourceInfoDao.findTenantResourcesByTenantId(filter, pageLink);
    }

    @Override
    public List<TbResource> findTenantResourcesByResourceTypeAndObjectIds(TenantId tenantId, ResourceType resourceType, String[] objectIds) {
        log.trace("Executing findTenantResourcesByResourceTypeAndObjectIds [{}][{}][{}]", tenantId, resourceType, objectIds);
        validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        return resourceDao.findResourcesByTenantIdAndResourceType(tenantId, resourceType, null, objectIds, null);
    }

    @Override
    public PageData<TbResource> findAllTenantResources(TenantId tenantId, PageLink pageLink) {
        log.trace("Executing findAllTenantResources [{}][{}]", tenantId, pageLink);
        validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        return resourceDao.findAllByTenantId(tenantId, pageLink);
    }

    @Override
    public PageData<TbResource> findTenantResourcesByResourceTypeAndPageLink(TenantId tenantId, ResourceType resourceType, PageLink pageLink) {
        log.trace("Executing findTenantResourcesByResourceTypeAndPageLink [{}][{}][{}]", tenantId, resourceType, pageLink);
        validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        return resourceDao.findResourcesByTenantIdAndResourceType(tenantId, resourceType, null, pageLink);
    }

    @Override
    public void deleteResourcesByTenantId(TenantId tenantId) {
        log.trace("Executing deleteResourcesByTenantId, tenantId [{}]", tenantId);
        validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        tenantResourcesRemover.removeEntities(tenantId, tenantId);
    }

    @Override
    public void deleteByTenantId(TenantId tenantId) {
        deleteResourcesByTenantId(tenantId);
    }

    @Override
    public Optional<HasId<?>> findEntity(TenantId tenantId, EntityId entityId) {
        return Optional.ofNullable(findResourceInfoById(tenantId, new TbResourceId(entityId.getId())));
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.TB_RESOURCE;
    }

    @Override
    public long sumDataSizeByTenantId(TenantId tenantId) {
        return resourceDao.sumDataSizeByTenantId(tenantId);
    }

    @Override
    public boolean replaceResourcesUsageWithUrls(Dashboard dashboard) {
        return replaceResourcesUsageWithUrls(dashboard.getTenantId(), dashboard.getConfiguration(), DASHBOARD_RESOURCES_MAPPING);
    }

    @Override
    public boolean replaceResourcesUsageWithUrls(WidgetTypeDetails widgetTypeDetails) {
        return replaceResourcesUsageWithUrls(widgetTypeDetails.getTenantId(), widgetTypeDetails.getDescriptor(), WIDGET_RESOURCES_MAPPING);
    }

    private boolean replaceResourcesUsageWithUrls(TenantId tenantId, JsonNode jsonNode, Map<String, String> mapping) {
        AtomicBoolean updated = new AtomicBoolean(false);
        processResources(jsonNode, mapping, value -> {
            if (value.startsWith(DataConstants.TB_RESOURCE_PREFIX + "/api/resource")) { // already a link, ignoring
                return value;
            }

            TbResourceInfo resourceInfo;
            if (value.startsWith("tb-resource:")) { // tag with metadata, probably importing
                String[] metadata = StringUtils.removeStart(value, "tb-resource:").split(":");
                if (metadata.length < 2) {
                    return value;
                }
                ResourceType resourceType = ResourceType.valueOf(decode(metadata[0]));
                String etag = metadata[1];

                resourceInfo = findSystemOrTenantResourceByEtag(tenantId, resourceType, etag);
                if (resourceInfo == null) {
                    return value;
                }
            } else { // probably importing an old dashboard json where resources are referenced by ids
                TbResourceId resourceId;
                try {
                    resourceId = new TbResourceId(UUID.fromString(value));
                } catch (IllegalArgumentException e) {
                    return value;
                }
                resourceInfo = findResourceInfoById(tenantId, resourceId);
                if (resourceInfo == null) {
                    updated.set(true);
                    return "";
                }
            }

            updated.set(true);
            return DataConstants.TB_RESOURCE_PREFIX + resourceInfo.getLink();
        });
        return updated.get();
    }

    @Override
    public List<TbResourceInfo> replaceResourcesUrlsWithTags(Dashboard dashboard) {
        return replaceResourcesUrlsWithTags(dashboard.getTenantId(), dashboard.getConfiguration(), DASHBOARD_RESOURCES_MAPPING);
    }

    @Override
    public List<TbResourceInfo> replaceResourcesUrlsWithTags(WidgetTypeDetails widgetTypeDetails) {
        return replaceResourcesUrlsWithTags(widgetTypeDetails.getTenantId(), widgetTypeDetails.getDescriptor(), WIDGET_RESOURCES_MAPPING);
    }

    private List<TbResourceInfo> replaceResourcesUrlsWithTags(TenantId tenantId, JsonNode jsonNode, Map<String, String> mapping) {
        List<TbResourceInfo> resources = new ArrayList<>();
        processResources(jsonNode, mapping, value -> {
            if (!value.startsWith(DataConstants.TB_RESOURCE_PREFIX + "/api/resource/")) {
                return value;
            }

            ResourceType resourceType;
            String resourceKey;
            TenantId resourceTenantId;
            try {
                String[] parts = StringUtils.removeStart(value, DataConstants.TB_RESOURCE_PREFIX + "/api/resource/").split("/");
                resourceType = ResourceType.valueOf(parts[0].toUpperCase());
                String scope = parts[1];
                resourceKey = parts[2];
                resourceTenantId = scope.equals("system") ? TenantId.SYS_TENANT_ID : tenantId;
            } catch (Exception e) {
                log.warn("[{}] Invalid resource link '{}'", tenantResourcesRemover, value);
                return value;
            }

            TbResourceInfo resourceInfo = findResourceInfoByTenantIdAndKey(resourceTenantId, resourceType, resourceKey);
            if (resourceInfo != null) {
                resources.add(resourceInfo);
                return "tb-resource:" + String.join(":", encode(resourceType.name()), resourceInfo.getEtag());
            } else {
                return value;
            }
        });
        return resources;
    }

    private void processResources(JsonNode jsonNode, Map<String, String> mapping, UnaryOperator<String> processor) {
        JacksonUtil.replaceAllByMapping(jsonNode, mapping, Collections.emptyMap(), (name, value) -> {
            if (StringUtils.isBlank(value)) {
                return value;
            }
            return processor.apply(value);
        });
    }

    @Override
    public TbResource createOrUpdateSystemResource(ResourceType resourceType, String resourceKey, String data) {
        if (resourceType == ResourceType.DASHBOARD) {
            data = checkSystemResourcesUsage(data, ResourceType.JS_MODULE);

            Dashboard dashboard = JacksonUtil.fromString(data, Dashboard.class);
            dashboard.setTenantId(TenantId.SYS_TENANT_ID);
            imageService.replaceBase64WithImageUrl(dashboard);
            data = JacksonUtil.toString(dashboard);
        }

        TbResource resource = findResourceByTenantIdAndKey(TenantId.SYS_TENANT_ID, resourceType, resourceKey);
        if (resource == null) {
            resource = new TbResource();
            resource.setTenantId(TenantId.SYS_TENANT_ID);
            resource.setResourceType(resourceType);
            resource.setResourceKey(resourceKey);
            resource.setFileName(resourceKey);
            resource.setTitle(resourceKey);
        }
        resource.setData(data.getBytes(StandardCharsets.UTF_8));
        log.debug("{} system resource {}", (resource.getId() == null ? "Creating" : "Updating"), resourceKey);
        return saveResource(resource);
    }

    @Override
    public String checkSystemResourcesUsage(String content, ResourceType... usedResourceTypes) {
        return RegexUtils.replace(content, "\\$\\{RESOURCE:(.+)}", matchResult -> {
            String resourceKey = matchResult.group(1);
            for (ResourceType resourceType : usedResourceTypes) {
                TbResourceInfo resource = findResourceInfoByTenantIdAndKey(TenantId.SYS_TENANT_ID, resourceType, resourceKey);
                if (resource != null) {
                    log.trace("Replaced '{}' with resource id {}", matchResult.group(), resource.getUuidId());
                    return resource.getUuidId().toString();
                }
            }
            return "";
        });
    }

    @Override
    public String calculateEtag(byte[] data) {
        return Hashing.sha256().hashBytes(data).toString();
    }

    @Override
    public TbResourceInfo findSystemOrTenantResourceByEtag(TenantId tenantId, ResourceType resourceType, String etag) {
        if (StringUtils.isEmpty(etag)) {
            return null;
        }
        log.trace("Executing findSystemOrTenantResourceByEtag [{}] [{}] [{}]", tenantId, resourceType, etag);
        return resourceInfoDao.findSystemOrTenantResourceByEtag(tenantId, resourceType, etag);
    }

    protected String encode(String data) {
        return encode(data.getBytes(StandardCharsets.UTF_8));
    }

    protected String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        return Base64.getEncoder().encodeToString(data);
    }

    protected String decode(String value) {
        if (value == null) {
            return null;
        }
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private final PaginatedRemover<TenantId, TbResourceId> tenantResourcesRemover = new PaginatedRemover<>() {

        @Override
        protected PageData<TbResourceId> findEntities(TenantId tenantId, TenantId id, PageLink pageLink) {
            return resourceDao.findIdsByTenantId(id.getId(), pageLink);
        }

        @Override
        protected void removeEntity(TenantId tenantId, TbResourceId resourceId) {
            deleteResource(tenantId, resourceId);
        }
    };

    @TransactionalEventListener(classes = ResourceInfoEvictEvent.class)
    @Override
    public void handleEvictEvent(ResourceInfoEvictEvent event) {
        if (event.getResourceId() != null) {
            cache.evict(new ResourceInfoCacheKey(event.getTenantId(), event.getResourceId()));
        }
    }

}
