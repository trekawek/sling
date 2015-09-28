/*
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
package org.apache.sling.resourceresolver.impl.providers;

import static org.apache.sling.resourceresolver.impl.observation.ResourceChangeListenerWhiteboard.TOPIC_RESOURCE_CHANGE_LISTENER_UPDATE;
import static org.osgi.service.event.EventConstants.EVENT_TOPIC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.resource.runtime.dto.FailureReason;
import org.apache.sling.api.resource.runtime.dto.ResourceProviderDTO;
import org.apache.sling.api.resource.runtime.dto.ResourceProviderFailureDTO;
import org.apache.sling.api.resource.runtime.dto.RuntimeDTO;
import org.apache.sling.resourceresolver.impl.observation.BasicObservationReporter;
import org.apache.sling.resourceresolver.impl.observation.ResourceChangeListenerWhiteboard;
import org.apache.sling.spi.resource.provider.ObservationReporter;
import org.apache.sling.spi.resource.provider.ProviderContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventHandler;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Service({ ResourceProviderTracker.class, EventHandler.class })
@Properties({
        @Property(name = EVENT_TOPIC, value = TOPIC_RESOURCE_CHANGE_LISTENER_UPDATE) })
public class ResourceProviderTracker implements EventHandler {

    @SuppressWarnings("unchecked")
    private static final ObservationReporter EMPTY_REPORTER = new BasicObservationReporter(Collections.EMPTY_MAP);

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final Map<ServiceReference, ResourceProviderInfo> infos = new ConcurrentHashMap<ServiceReference, ResourceProviderInfo>();

    private volatile BundleContext bundleContext;

    private volatile ServiceTracker tracker;

    private final Map<String, List<ResourceProviderHandler>> handlers = new HashMap<String, List<ResourceProviderHandler>>();

    private final Map<ResourceProviderInfo, FailureReason> invalidProviders = new HashMap<ResourceProviderInfo, FailureReason>();

    private ObservationReporter reporter = EMPTY_REPORTER;

    @Reference
    private EventAdmin eventAdmin;

    @Reference
    private ResourceChangeListenerWhiteboard resourceChangeListeners;

    @Activate
    protected void activate(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        this.reporter = resourceChangeListeners.getObservationReporter();
        this.tracker = new ServiceTracker(bundleContext,
                ResourceProvider.class.getName(),
                new ServiceTrackerCustomizer() {

            @Override
            public void removedService(final ServiceReference reference, final Object service) {
                final ServiceReference ref = (ServiceReference)service;
                final ResourceProviderInfo info = infos.remove(ref);
                if ( info != null ) {
                    unregister(info);
                }
            }

            @Override
            public void modifiedService(final ServiceReference reference, final Object service) {
                removedService(reference, service);
                addingService(reference);
            }

            @Override
            public Object addingService(final ServiceReference reference) {
                final ResourceProviderInfo info = new ResourceProviderInfo(reference);
                infos.put(reference, info);
                register(info);
                return reference;
            }
        });
        this.tracker.open();
    }

    @Deactivate
    protected void deactivate() {
        if ( this.tracker != null ) {
            this.tracker.close();
            this.tracker = null;
        }
        this.infos.clear();
        this.handlers.clear();
        this.invalidProviders.clear();
    }

    private void register(final ResourceProviderInfo info) {
        if ( info.isValid() ) {
           logger.debug("Registering new resource provider {}", info);
           synchronized ( this.handlers ) {
               List<ResourceProviderHandler> matchingHandlers = this.handlers.get(info.getPath());
               if ( matchingHandlers == null ) {
                   matchingHandlers = new ArrayList<ResourceProviderHandler>();
                   this.handlers.put(info.getPath(), matchingHandlers);
               }
               final ResourceProviderHandler handler = new ResourceProviderHandler(bundleContext, info, eventAdmin);
               matchingHandlers.add(handler);
               Collections.sort(matchingHandlers);
               if ( matchingHandlers.get(0) == handler ) {
                   if ( !this.activate(handler) ) {
                       matchingHandlers.remove(handler);
                       if ( matchingHandlers.isEmpty() ) {
                           this.handlers.remove(info.getPath());
                       }
                   } else {
                       if ( matchingHandlers.size() > 1 ) {
                           this.deactivate(matchingHandlers.get(1));
                       }
                   }
               }
           }
        } else {
            logger.debug("Ignoring invalid resource provider {}", info);
            synchronized ( this.invalidProviders ) {
                this.invalidProviders.put(info, FailureReason.invalid);
            }
        }
    }

    private void unregister(final ResourceProviderInfo info) {
        if ( info.isValid() ) {
            logger.debug("Unregistering resource provider {}", info);
            final List<ResourceProviderHandler> matchingHandlers = this.handlers.get(info.getPath());
            if ( matchingHandlers != null ) {
                boolean activate = false;
                if ( matchingHandlers.get(0).getInfo() == info ) {
                    activate = true;
                    this.deactivate(matchingHandlers.get(0));
                }
                boolean removed = removeHandlerByInfo(info, matchingHandlers);
                if ( removed ) {
                    if ( matchingHandlers.isEmpty() ) {
                        this.handlers.remove(info.getPath());
                    } else {
                        while ( activate ) {
                            if ( !this.activate(matchingHandlers.get(0)) ) {
                                matchingHandlers.remove(0);
                                activate = !this.handlers.isEmpty();
                                if ( !activate ) {
                                    this.handlers.remove(info.getPath());
                                }
                            }
                        }
                    }
                }
            }

        } else {
            logger.debug("Unregistering invalid resource provider {}", info);
            synchronized ( this.invalidProviders ) {
                this.invalidProviders.remove(info);
            }
        }
    }

    private boolean removeHandlerByInfo(final ResourceProviderInfo info, final List<ResourceProviderHandler> infos) {
        Iterator<ResourceProviderHandler> it = infos.iterator();
        boolean removed = false;
        while (it.hasNext()) {
            if (it.next().getInfo() == info) {
                it.remove();
                removed = true;
                break;
            }
        }
        return removed;
    }

    private void deactivate(final ResourceProviderHandler handler) {
        handler.deactivate(createProviderContext(handler));
        logger.debug("Deactivated resource provider {}", handler.getInfo());
    }

    private boolean activate(final ResourceProviderHandler handler) {
        if ( !handler.activate(createProviderContext(handler)) ) {
            logger.debug("Activating resource provider {} failed", handler.getInfo());
            synchronized ( this.invalidProviders ) {
                this.invalidProviders.put(handler.getInfo(), FailureReason.service_not_gettable);
            }
            return false;
        }
        logger.debug("Activated resource provider {}", handler.getInfo());
        return true;
    }

    public void fill(final RuntimeDTO dto) {
        final List<ResourceProviderDTO> dtos = new ArrayList<ResourceProviderDTO>();
        final List<ResourceProviderFailureDTO> failures = new ArrayList<ResourceProviderFailureDTO>();

        synchronized ( this.handlers ) {
            for(final List<ResourceProviderHandler> handlers : this.handlers.values()) {
                boolean isFirst = true;
                for(final ResourceProviderHandler h : handlers) {
                    final ResourceProviderDTO d;
                    if ( isFirst ) {
                        d = new ResourceProviderDTO();
                        dtos.add(d);
                        isFirst = false;
                    } else {
                        d = new ResourceProviderFailureDTO();
                        ((ResourceProviderFailureDTO)d).reason = FailureReason.shadowed;
                        failures.add((ResourceProviderFailureDTO)d);
                    }
                    fill(d, h.getInfo());
                }
            }
        }
        synchronized ( this.invalidProviders ) {
            for(final Map.Entry<ResourceProviderInfo, FailureReason> entry : this.invalidProviders.entrySet()) {
                final ResourceProviderFailureDTO d = new ResourceProviderFailureDTO();
                fill(d, entry.getKey());
                d.reason = entry.getValue();
            }
        }
        dto.providers = dtos.toArray(new ResourceProviderDTO[dtos.size()]);
        dto.failedProviders = failures.toArray(new ResourceProviderFailureDTO[failures.size()]);
    }

    public List<ResourceProviderHandler> getHandlers() {
        List<ResourceProviderHandler> list = new ArrayList<ResourceProviderHandler>();
        for (List<ResourceProviderHandler> h : handlers.values()) {
            list.add(h.get(0));
        }
        Collections.sort(list);
        return list;
    }

    private void fill(final ResourceProviderDTO d, final ResourceProviderInfo info) {
        d.authType = info.getAuthType();
        d.modifiable = info.getModifiable();
        d.name = info.getName();
        d.path = info.getPath();
        d.serviceId = (Long)info.getServiceReference().getProperty(Constants.SERVICE_ID);
        d.useResourceAccessSecurity = info.getUseResourceAccessSecurity();
    }

    @Override
    public void handleEvent(Event event) {
        this.reporter = resourceChangeListeners.getObservationReporter();
        for (ResourceProviderHandler h : getHandlers()) {
            h.getProvider().update(createProviderContext(h));
        }
    }

    private ProviderContext createProviderContext(ResourceProviderHandler handler) {
        final Set<String> excludedPaths = new HashSet<String>();
        String path = handler.getInfo().getPath();
        for (String providerPath : handlers.keySet()) {
            if (providerPath.startsWith(path)) {
                excludedPaths.add(providerPath);
            }
        }
        excludedPaths.remove(path);
        return new BasicProviderContext(reporter, excludedPaths);
    }

    private static class BasicProviderContext implements ProviderContext {

        private final ObservationReporter observationReporter;
        
        private final Set<String> excludedPaths;

        public BasicProviderContext(ObservationReporter observationReporter, Set<String> excludedPaths) {
            super();
            this.observationReporter = observationReporter;
            this.excludedPaths = excludedPaths;
        }

        @Override
        public ObservationReporter getObservationReporter() {
            return observationReporter;
        }

        @Override
        public Set<String> getExcludedPaths() {
            return excludedPaths;
        }
    }
}
