/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.aggregations.bucket.histogram;

import org.opensearch.common.Rounding;
import org.opensearch.common.Strings;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.io.stream.Writeable;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.ToXContentFragment;
import org.opensearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Objects;

/**
 * The interval the date histogram is based on.
 */
public class DateHistogramInterval implements Writeable, ToXContentFragment {

    public static final DateHistogramInterval SECOND = new DateHistogramInterval("1s");
    public static final DateHistogramInterval MINUTE = new DateHistogramInterval("1m");
    public static final DateHistogramInterval HOUR = new DateHistogramInterval("1h");
    public static final DateHistogramInterval DAY = new DateHistogramInterval("1d");
    public static final DateHistogramInterval WEEK = new DateHistogramInterval("1w");
    public static final DateHistogramInterval MONTH = new DateHistogramInterval("1M");
    public static final DateHistogramInterval QUARTER = new DateHistogramInterval("1q");
    public static final DateHistogramInterval YEAR = new DateHistogramInterval("1y");

    public static DateHistogramInterval seconds(int sec) {
        return new DateHistogramInterval(sec + "s");
    }

    public static DateHistogramInterval minutes(int min) {
        return new DateHistogramInterval(min + "m");
    }

    public static DateHistogramInterval hours(int hours) {
        return new DateHistogramInterval(hours + "h");
    }

    public static DateHistogramInterval days(int days) {
        return new DateHistogramInterval(days + "d");
    }

    public static DateHistogramInterval weeks(int weeks) {
        return new DateHistogramInterval(weeks + "w");
    }

    private final String expression;

    public DateHistogramInterval(String expression) {
        this.expression = expression;
    }

    /**
     * Read from a stream.
     */
    public DateHistogramInterval(StreamInput in) throws IOException {
        expression = in.readString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(expression);
    }


    @Override
    public String toString() {
        return expression;
    }

    @Override
    public int hashCode() {
        return Objects.hash(expression);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DateHistogramInterval other = (DateHistogramInterval) obj;
        return Objects.equals(expression, other.expression);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return builder.value(toString());
    }

    /**
     * Converts this DateHistogramInterval into a millisecond representation.  If this is a calendar
     * interval, it is an approximation of milliseconds based on the fixed equivalent (e.g. `1h` is treated as 60
     * fixed minutes, rather than the hour at a specific point in time.
     *
     * This is merely a convenience helper for quick comparisons and should not be used for situations that
     * require precise durations.
     */
    public long estimateMillis() {
        if (Strings.isNullOrEmpty(expression) == false && DateHistogramAggregationBuilder.DATE_FIELD_UNITS.containsKey(expression)) {
            Rounding.DateTimeUnit intervalUnit = DateHistogramAggregationBuilder.DATE_FIELD_UNITS.get(expression);
            return intervalUnit.getField().getBaseUnit().getDuration().getSeconds() * 1000;
        } else {
            return TimeValue.parseTimeValue(expression, "DateHistogramInterval#estimateMillis").getMillis();
        }
    }
}
