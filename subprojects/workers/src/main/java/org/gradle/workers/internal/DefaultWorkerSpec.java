/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.workers.internal;

import org.gradle.workers.IsolationMode;

import javax.inject.Inject;

public class DefaultWorkerSpec implements WorkerSpecInternal {
    private final IsolationMode isolationMode;
    private String displayName;

    @Inject
    public DefaultWorkerSpec() {
        this(IsolationMode.NONE);
    }

    protected DefaultWorkerSpec(IsolationMode isolationMode) {
        this.isolationMode = isolationMode;
    }

    @Override
    public IsolationMode getIsolationMode() {
        return isolationMode;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
