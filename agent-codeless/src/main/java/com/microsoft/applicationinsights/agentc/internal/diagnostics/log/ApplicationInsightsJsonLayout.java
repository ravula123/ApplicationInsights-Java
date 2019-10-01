/*
 * ApplicationInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package com.microsoft.applicationinsights.agentc.internal.diagnostics.log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.contrib.json.classic.JsonLayout;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

public class ApplicationInsightsJsonLayout extends JsonLayout {

    @VisibleForTesting
    static final String UNKNOWN_VALUE = "unknown";

    final List<DiagnosticsValueFinder> valueFinders = new ArrayList<>();

    public ApplicationInsightsJsonLayout() {
        valueFinders.add(new ResourceIdFinder());
        valueFinders.add(new SiteNameFinder());
        valueFinders.add(new InstrumentationKeyFinder());
        valueFinders.add(new AgentExtensionVersionFinder());
        valueFinders.add(new SdkVersionFinder());
    }

    @Override
    protected void addCustomDataToJsonMap(Map<String, Object> map, ILoggingEvent event) {
        for (DiagnosticsValueFinder finder : valueFinders) {
            String value = finder.getValue();
            map.put(finder.getName(), Strings.isNullOrEmpty(value) ? UNKNOWN_VALUE : value);
        }
    }
}
