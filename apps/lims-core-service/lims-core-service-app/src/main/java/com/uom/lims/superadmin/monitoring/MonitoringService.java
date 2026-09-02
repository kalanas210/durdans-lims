package com.uom.lims.superadmin.monitoring;

import com.uom.lims.api.superadmin.monitoring.dto.LogEventResponse;
import com.uom.lims.api.superadmin.monitoring.dto.MetricDataResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.monitoring.cloudwatch.enabled", havingValue = "true")
public class MonitoringService {

    private final CloudWatchClient cloudWatchClient;
    private final CloudWatchLogsClient cloudWatchLogsClient;
    private final com.uom.lims.config.LabTimeZone labTimeZone;
    private static final String NAMESPACE = "LimsApplication"; // Update as needed
    private static final String LOG_GROUP = "/aws/ecs/lims-application"; // Update as needed

    @Cacheable(value = "monitoringMetrics", key = "#metricName + '-' + #hoursAgo")
    public List<MetricDataResponse> getMetrics(String metricName, int hoursAgo) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(hoursAgo, ChronoUnit.HOURS);

            GetMetricDataRequest request = GetMetricDataRequest.builder()
                    .startTime(startTime)
                    .endTime(endTime)
                    .metricDataQueries(MetricDataQuery.builder()
                            .id("m1")
                            .metricStat(MetricStat.builder()
                                    .metric(Metric.builder()
                                            .namespace(NAMESPACE)
                                            .metricName(metricName)
                                            .build())
                                    .period(300) // 5 minutes
                                    .stat("Average")
                                    .build())
                            .returnData(true)
                            .build())
                    .build();

            GetMetricDataResponse response = cloudWatchClient.getMetricData(request);

            List<MetricDataResponse> result = new ArrayList<>();
            if (!response.metricDataResults().isEmpty()) {
                MetricDataResult dataResult = response.metricDataResults().get(0);
                for (int i = 0; i < dataResult.timestamps().size(); i++) {
                    result.add(MetricDataResponse.builder()
                            .timestamp(dataResult.timestamps().get(i).toString())
                            .value(dataResult.values().get(i))
                            .build());
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Error fetching metrics from CloudWatch", e);
            return List.of();
        }
    }

    @Cacheable(value = "monitoringLogs")
    public List<LogEventResponse> getLogs(int limit) {
        try {
            FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                    .logGroupName(LOG_GROUP)
                    .limit(limit)
                    .build();

            FilterLogEventsResponse response = cloudWatchLogsClient.filterLogEvents(request);

            return response.events().stream().map(event -> LogEventResponse.builder()
                    .timestamp(Instant.ofEpochMilli(event.timestamp()).atZone(labTimeZone.zone())
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .message(event.message())
                    .level("INFO") // Basic extraction, could parse from message
                    .build()
            ).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching logs from CloudWatch", e);
            return List.of();
        }
    }
}
