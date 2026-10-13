package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.api.SpaceApiModels;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadType;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.SatelliteEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证目标观测与对地资料 profile 同时存在时，始终选择对空载荷。 */
class PlanningFacadeServicePayloadTest {

    @Test
    void usesSpaceProfileWhenGroundProfileWasMostRecentlySaved() {
        ReferenceDataService referenceDataService = mock(ReferenceDataService.class);
        OrbitStateProviderFactory orbitStateProviderFactory = mock(OrbitStateProviderFactory.class);
        PayloadConstraintFactory payloadConstraintFactory = mock(PayloadConstraintFactory.class);
        VisibilityPlanningService visibilityPlanningService = new VisibilityPlanningService(Runnable::run, 1);

        SatelliteEntity observerSatellite = new SatelliteEntity();
        observerSatellite.setId(1L);
        observerSatellite.setName("观测星");
        observerSatellite.setPayloadJson("dual-profile");
        TargetEntity target = new TargetEntity();
        target.setId(2L);
        target.setName("目标");
        target.setEpochTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        OrbitStateProvider observerOrbit = instant -> new Vector3(7_000_000.0, 0.0, 0.0);
        OrbitStateProvider targetOrbit = instant -> new Vector3(7_000_000.0, 1_000_000.0, 0.0);

        when(referenceDataService.findSatellites(Collections.singletonList(1L)))
                .thenReturn(Collections.singletonList(observerSatellite));
        when(referenceDataService.findTargets(eq(Collections.singletonList(2L)), any(), any(), any(), any()))
                .thenReturn(Collections.singletonList(target));
        when(referenceDataService.findTargetsByIds(Collections.singletonList(2L)))
                .thenReturn(Collections.singletonList(target));
        when(orbitStateProviderFactory.fromSatellite(observerSatellite)).thenReturn(observerOrbit);
        when(orbitStateProviderFactory.fromTarget(target)).thenReturn(targetOrbit);
        when(payloadConstraintFactory.parseProfile("dual-profile", PayloadType.SPACE))
                .thenReturn(PayloadConstraint.space(0.0, 4_000_000.0, null, null, null, null,
                        0.0, 0.0, null));
        PlanningFacadeService service = new PlanningFacadeService(referenceDataService, orbitStateProviderFactory,
                payloadConstraintFactory, visibilityPlanningService, new DownlinkPlanningService(Runnable::run, 1),
                new PlanningTaskService(Runnable::run, 2L), mock(VisibilityTaskResultService.class),
                300.0, 0.0, 0.0, 10.0, 87.0, 14L, "23340");
        SpaceApiModels.VisibilityPlanRequest request = new SpaceApiModels.VisibilityPlanRequest();
        request.setObserverSatelliteIds(Collections.singletonList(1L));
        request.setTargetIds(Collections.singletonList(2L));
        request.setStartTime(Instant.parse("2026-01-01T00:00:00Z"));
        request.setEndTime(Instant.parse("2026-01-01T00:00:01Z"));
        request.setCoarseStepSec(1.0);
        request.setFineStepSec(1.0);

        service.planVisibility(request);

        verify(payloadConstraintFactory).parseProfile("dual-profile", PayloadType.SPACE);
        verify(payloadConstraintFactory, never()).parse("dual-profile");
    }
}
