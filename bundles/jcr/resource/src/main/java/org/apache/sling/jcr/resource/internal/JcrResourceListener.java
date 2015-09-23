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
package org.apache.sling.jcr.resource.internal;

import static java.lang.Math.min;
import static org.apache.commons.lang.StringUtils.defaultIfEmpty;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;

import org.apache.jackrabbit.api.observation.JackrabbitEvent;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChange.ChangeType;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.resource.internal.JcrResourceChange.Builder;
import org.apache.sling.jcr.resource.internal.helper.jcr.PathMapper;
import org.apache.sling.spi.resource.provider.ObservationReporter;
import org.apache.sling.spi.resource.provider.ObserverConfiguration;
import org.apache.sling.spi.resource.provider.ProviderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>JcrResourceListener</code> listens for JCR observation
 * events and creates resource events which are sent through the
 * OSGi event admin.
 */
public class JcrResourceListener implements EventListener, Closeable {

    /** Logger */
    private final Logger logger = LoggerFactory.getLogger(JcrResourceListener.class);

    /** Is the Jackrabbit event class available? */
    private final boolean hasJackrabbitEventClass;

    /** Helper object. */
    private final Session session;

    private final PathMapper pathMapper;

    private final ObservationReporter reporter;

    private final String mountPrefix;

    private final boolean includeExternal;

    @SuppressWarnings("deprecation")
    public JcrResourceListener(
                    final ProviderContext ctx,
                    final String mountPrefix,
                    final PathMapper pathMapper,
                    final SlingRepository repository)
    throws RepositoryException {
        this.includeExternal = isIncludeExternal(ctx);
        this.pathMapper = pathMapper;
        this.mountPrefix = mountPrefix;
        this.reporter = ctx.getObservationReporter();
        boolean foundClass = false;
        try {
            this.getClass().getClassLoader().loadClass(JackrabbitEvent.class.getName());
            foundClass = true;
        } catch (final Throwable t) {
            // we ignore this
        }
        this.hasJackrabbitEventClass = foundClass;

        this.session = repository.loginAdministrative(repository.getDefaultWorkspace());
        final String absPath = getAbsPath(pathMapper, ctx);
        final int types = getTypes(ctx);

        this.session.getWorkspace().getObservationManager().addEventListener(this, types, absPath, true, null, null, false);
    }

    private boolean isIncludeExternal(ProviderContext ctx) {
        for (ObserverConfiguration c : ctx.getObservationReporter().getObserverConfigurations()) {
            if (c.includeExternal()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dispose this listener.
     */
    public void close() throws IOException {
        // unregister from observations
        try {
            this.session.getWorkspace().getObservationManager().removeEventListener(this);
        } catch (RepositoryException e) {
            logger.warn("Unable to remove session listener: " + this, e);
        }

        // drop any remaining OSGi Events not processed yet
        this.session.logout();
    }

    /**
     * @see javax.jcr.observation.EventListener#onEvent(javax.jcr.observation.EventIterator)
     */
    public void onEvent(final EventIterator events) {
        final Map<String, Builder> addedEvents = new HashMap<String, Builder>();
        final Map<String, Builder> changedEvents = new HashMap<String, Builder>();
        final Map<String, Builder> removedEvents = new HashMap<String, Builder>();
        while ( events.hasNext() ) {
            final Event event = events.nextEvent();
            if (isExternal(event) && !includeExternal) {
                continue;
            }
            try {
                final String eventPath = event.getPath();
                if ( event.getType() == Event.PROPERTY_ADDED
                     || event.getType() == Event.PROPERTY_REMOVED
                     || event.getType() == Event.PROPERTY_CHANGED ) {
                    final int lastSlash = eventPath.lastIndexOf('/');
                    final String nodePath = eventPath.substring(0, lastSlash);
                    final String propName = eventPath.substring(lastSlash + 1);
                    Builder builder = changedEvents.get(nodePath);
                    if (builder == null) {
                        changedEvents.put(nodePath, builder = createResourceChange(event, nodePath, ChangeType.CHANGED));
                    }
                    this.updateResourceChanged(builder, event.getType(), propName);
                } else if ( event.getType() == Event.NODE_ADDED ) {
                    addedEvents.put(eventPath, createResourceChange(event, ChangeType.ADDED));
                } else if ( event.getType() == Event.NODE_REMOVED) {
                    // remove is the strongest operation, therefore remove all removed
                    // paths from added
                    addedEvents.remove(eventPath);
                    removedEvents.put(eventPath, createResourceChange(event, ChangeType.REMOVED));
                }
            } catch (final RepositoryException e) {
                logger.error("Error during modification: {}", e.getMessage());
            }
        }

        final List<ResourceChange> changes = new ArrayList<ResourceChange>();
        for (String path : addedEvents.keySet()) {
            changedEvents.get(path).setChangeType(ChangeType.ADDED);
        }
        buildResourceChanges(changes, removedEvents);
        buildResourceChanges(changes, changedEvents);
        reporter.reportChanges(changes, false);
    }

    private void buildResourceChanges(List<ResourceChange> result, Map<String, Builder> builders) {
        for (Entry<String, Builder> e : builders.entrySet()) {
            result.add(e.getValue().build());
        }
    }

    private void updateResourceChanged(Builder builder, int eventType, final String propName) {
        switch (eventType) {
        case Event.PROPERTY_ADDED:
            builder.addAddedAttributeName(propName);
            break;
        case Event.PROPERTY_CHANGED:
            builder.addChangedAttributeName(propName);
            break;
        case Event.PROPERTY_REMOVED:
            builder.addRemovedAttributeName(propName);
            break;
        }
    }

    private Builder createResourceChange(final Event event, final String path, final ChangeType changeType) throws RepositoryException {
        Builder builder = new Builder();
        String pathWithPrefix = addMountPrefix(mountPrefix, path);
        builder.setPath(pathMapper.mapJCRPathToResourcePath(pathWithPrefix));
        builder.setChangeType(changeType);
        boolean isExternal = this.isExternal(event);
        builder.setExternal(isExternal);
        if (!isExternal) {
            final String userID = event.getUserID();
            if (userID != null) {
                builder.setUserId(userID);
            }
        }
        return builder;
    }

    private Builder createResourceChange(final Event event, final ChangeType changeType) throws RepositoryException {
        return createResourceChange(event, event.getPath(), changeType);
    }

    private boolean isExternal(final Event event) {
        if ( this.hasJackrabbitEventClass && event instanceof JackrabbitEvent) {
            final JackrabbitEvent jEvent = (JackrabbitEvent)event;
            return jEvent.isExternal();
        }
        return false;
    }

    static String getAbsPath(PathMapper pathMapper, ProviderContext ctx) {
        final Set<String> paths = new HashSet<String>();
        for (ObserverConfiguration c : ctx.getObservationReporter().getObserverConfigurations()) {
            final Set<String> includePaths = new HashSet<String>();
            final Set<String> excludePaths = new HashSet<String>();
            for (String p : c.getExcludedPaths()) {
                excludePaths.add(pathMapper.mapResourcePathToJCRPath(p));
            }
            for (String p : c.getPaths()) {
                includePaths.add(pathMapper.mapResourcePathToJCRPath(p));
            }
            includePaths.removeAll(excludePaths);
            paths.addAll(includePaths);
        }
        for (String p : ctx.getExcludedPaths()) {
            paths.remove(pathMapper.mapResourcePathToJCRPath(p));
        }
        return getLongestCommonPrefix(paths);
    }

    private static String getLongestCommonPrefix(Set<String> paths) {
        String prefix = null;
        Iterator<String> it = paths.iterator();
        if (it.hasNext()) {
            prefix = it.next();
        }
        while (it.hasNext()) {
            prefix = getCommonPrefix(prefix, it.next());
        }
        return defaultIfEmpty(prefix, "/");
    }

    private static String getCommonPrefix(String s1, String s2) {
        int length = min(s1.length(), s2.length());
        StringBuilder prefix = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix.append(s1.charAt(i));
            } else {
                break;
            }
        }
        return prefix.toString();
    }

    private int getTypes(ProviderContext ctx) {
        int result = 0;
        for (ObserverConfiguration c : ctx.getObservationReporter().getObserverConfigurations()) {
            for (ChangeType t : c.getChangeTypes()) {
                switch (t) {
                case ADDED:
                    result = result | Event.NODE_ADDED;
                    break;
                case REMOVED:
                    result = result | Event.NODE_REMOVED;
                    break;
                case CHANGED:
                    result = result | Event.PROPERTY_ADDED;
                    result = result | Event.PROPERTY_CHANGED;
                    result = result | Event.PROPERTY_REMOVED;
                    break;
                default:
                    break;
                }
            }
        }
        return result;
    }

    static String addMountPrefix(final String mountPrefix, final String path) {
        final String result;
        if (mountPrefix == null || mountPrefix.isEmpty() || "/".equals(mountPrefix)) {
            result = path;
        } else if (mountPrefix.endsWith("/")) {
            result = path;
        } else {
            result = new StringBuilder(mountPrefix).append('/').append(path).toString();
        }
        return result;
    }
}
