package com.runisys.orbit.spacevisibility.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.runisys.orbit.spacevisibility.api.SpaceApiModels;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证规划门面输出可审计的摘要日志，但不把轨道根数等大字段写入日志。 */
class PlanningFacadeServiceLoggingTest {

    @Test
    void formatsConciseSummaryTimesAsUtcWithMilliseconds() throws Exception {
        PlanningFacadeService service = new PlanningFacadeService(
                mock(ReferenceDataService.class), mock(OrbitStateProviderFactory.class), mock(PayloadConstraintFactory.class),
                new VisibilityPlanningService(Runnable::run, 1), new DownlinkPlanningService(Runnable::run, 1),
                new PlanningTaskService(Runnable::run, 2L), mock(VisibilityTaskResultService.class),
                300.0, 0.0, 0.0, 10.0, 87.0, 14L, "23340");
        Map<String, List<double[]>> intervalsByObserver = new LinkedHashMap<String, List<double[]>>();
        intervalsByObserver.put("1", Collections.singletonList(new double[]{0.882, 60.0}));
        Method conciseSummary = PlanningFacadeService.class.getDeclaredMethod("conciseSummary", Map.class, List.class, Instant.class);
        conciseSummary.setAccessible(true);

        String summary = (String) conciseSummary.invoke(service, intervalsByObserver,
                Collections.singletonList(1L), Instant.parse("2026-01-18T13:49:33Z"));

        assertThat(summary).contains("2026-01-18T13:49:33.882Z");
    }

    @Test
    void logsTargetQuerySummaryWithoutOrbitDetails() {
        ReferenceDataService referenceDataService = mock(ReferenceDataService.class);
        when(referenceDataService.findTargetPage(any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(new SpaceApiModels.TargetQueryResponse(2L, 1L, 50L, Collections.emptyList()));
        PlanningFacadeService service = new PlanningFacadeService(
                referenceDataService,
                mock(OrbitStateProviderFactory.class),
                mock(PayloadConstraintFactory.class),
                new VisibilityPlanningService(Runnable::run, 1),
                new DownlinkPlanningService(Runnable::run, 1),
                new PlanningTaskService(Runnable::run, 2L),
                mock(VisibilityTaskResultService.class),
                300.0, 0.0, 0.0, 10.0, 87.0, 14L, "23340");
        SpaceApiModels.TargetQueryRequest request = new SpaceApiModels.TargetQueryRequest();
        request.setBelongs(Collections.singletonList("CIS"));
        request.setMinWeight(new java.math.BigDecimal("250"));
        request.setPageNumber(1L);
        request.setPageSize(50L);

        Logger logger = (Logger) LoggerFactory.getLogger(PlanningFacadeService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.queryTargets(request);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("目标筛选完成").contains("命中目标数=2"))
                    .noneMatch(message -> message.contains("1 54699U"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
