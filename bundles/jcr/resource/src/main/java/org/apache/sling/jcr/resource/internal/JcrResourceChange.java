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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.ArrayUtils;
import org.apache.sling.api.resource.observation.ResourceChange;

public class JcrResourceChange extends ResourceChange {

    private final String userId;

    private final String[] changedAttributeNames;

    private final String[] addedAttributeNames;

    private final String[] removedAttributeNames;

    private JcrResourceChange(Builder builder) {
        super(builder.changeType, builder.path, builder.isExternal);
        this.userId = builder.userId;
        this.changedAttributeNames = toArray(builder.changedAttributeNames);
        this.addedAttributeNames = toArray(builder.addedAttributeNames);
        this.removedAttributeNames = toArray(builder.removedAttributeNames);
    }

    private static String[] toArray(List<String> list) {
        if (list == null) {
            return new String[0];
        } else {
            return list.toArray(new String[list.size()]);
        }
    }

    @Override
    public String getUserId() {
        return userId;
    }

    @Override
    public String[] getChangedAttributeNames() {
        return changedAttributeNames;
    }

    @Override
    public String[] getAddedAttributeNames() {
        return addedAttributeNames;
    }

    @Override
    public String[] getRemovedAttributeNames() {
        return removedAttributeNames;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append("ResourceChange[type=").append(this.getType()).append(", path=").append(this.getPath());
        if (addedAttributeNames.length > 0) {
            b.append(", added=").append(ArrayUtils.toString(addedAttributeNames));
        }
        if (changedAttributeNames.length > 0) {
            b.append(", changed=").append(ArrayUtils.toString(changedAttributeNames));
        }
        if (removedAttributeNames.length > 0) {
            b.append(", removed=").append(ArrayUtils.toString(removedAttributeNames));
        }
        b.append("]");
        return b.toString();
    }
    
    public static class Builder {

        private String path;

        private ChangeType changeType;

        private boolean isExternal;

        private String userId;

        private List<String> changedAttributeNames;

        private List<String> addedAttributeNames;

        private List<String> removedAttributeNames;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public ChangeType getChangeType() {
            return changeType;
        }

        public void setChangeType(ChangeType changeType) {
            this.changeType = changeType;
        }

        public boolean isExternal() {
            return isExternal;
        }

        public void setExternal(boolean isExternal) {
            this.isExternal = isExternal;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public List<String> getChangedAttributeNames() {
            return changedAttributeNames;
        }

        public void addChangedAttributeName(String propName) {
            if (changedAttributeNames == null) {
                changedAttributeNames = new ArrayList<String>();
            }
            if (!changedAttributeNames.contains(propName)) {
                changedAttributeNames.add(propName);
            }
        }

        public List<String> getAddedAttributeNames() {
            return addedAttributeNames;
        }

        public void addAddedAttributeName(String propName) {
            if (addedAttributeNames == null) {
                addedAttributeNames = new ArrayList<String>();
            }
            if (!addedAttributeNames.contains(propName)) {
                addedAttributeNames.add(propName);
            }
        }

        public List<String> getRemovedAttributeNames() {
            return removedAttributeNames;
        }

        public void addRemovedAttributeName(String propName) {
            if (removedAttributeNames == null) {
                removedAttributeNames = new ArrayList<String>();
            }
            if (!removedAttributeNames.contains(propName)) {
                removedAttributeNames.add(propName);
            }
        }

        public ResourceChange build() {
            return new JcrResourceChange(this);
        }
    }

}
