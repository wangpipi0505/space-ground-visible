package com.runisys.orbit.spacevisibility.application.ballistic;

import com.runisys.orbit.spacevisibility.infrastructure.files.BallisticFileStorage;
import com.runisys.orbit.spacevisibility.domain.ballistic.TargetTrajectoryFitter;
import com.runisys.orbit.spacevisibility.domain.ballistic.TargetTrajectoryFitter.FitRunResult;
import com.runisys.orbit.spacevisibility.domain.ballistic.TargetTrajectoryFitter.SimulationInput;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** 模拟唯一 Web 应用编排：原端点 Lambert/RK4 数值主体不变，结果复用既有工作区及自动登记生命周期。 */
@Service
public class BallisticSimulationService {
    private final BallisticFileStorage storage;

    public BallisticSimulationService(BallisticFileStorage storage) {
        this.storage = storage;
    }

    /**
     * 原前置校验先于路径解析，保持请求非空、输出步长、发射时刻再目录的错误顺序。
     * 未提交 outputDir 时只修改执行副本，输出进入配置 results 独占目录；显式 null/空白/点目录仍按原语义。
     * 完成时自动登记实际 CSV；失败仅由 T15 工作区回滚自己拥有的结果，显式目录按原行为保留。
     */
    public FitRunResult simulate(SimulationInput request, boolean outputDirSpecified) throws IOException {
        TargetTrajectoryFitter.validateSimulationRequest(request);
        SimulationInput execution = copyRequest(request);
        try (BallisticFileStorage.ResultWorkspace workspace = storage.resultWorkspace(request.outputDir, outputDirSpecified)) {
            if (!outputDirSpecified) execution.outputDir = workspace.directory.toString();
            FitRunResult result = TargetTrajectoryFitter.simulateToCsv(execution);
            for (TargetTrajectoryFitter.FileResult file : result.files) {
                workspace.register(file.csvPath, result.taskName, file.targetId);
            }
            workspace.complete();
            return result;
        }
    }

    /** 原 DTO 使用公开字段而没有 Bean getter，明确复制全部字段；算法只读 phaseEvents，保留原列表与 null 语义。 */
    private SimulationInput copyRequest(SimulationInput request) {
        SimulationInput execution = new SimulationInput();
        execution.taskName = request.taskName;
        execution.targetId = request.targetId;
        execution.taskStartTime = request.taskStartTime;
        execution.taskEndTime = request.taskEndTime;
        execution.launchTimeUtc = request.launchTimeUtc;
        execution.impactTimeUtc = request.impactTimeUtc;
        execution.impactTime = request.impactTime;
        execution.launchPointX = request.launchPointX;
        execution.launchPointY = request.launchPointY;
        execution.launchPointZ = request.launchPointZ;
        execution.launchPointCoordFrame = request.launchPointCoordFrame;
        execution.launchPointUnit = request.launchPointUnit;
        execution.impactPointX = request.impactPointX;
        execution.impactPointY = request.impactPointY;
        execution.impactPointZ = request.impactPointZ;
        execution.impactPointCoordFrame = request.impactPointCoordFrame;
        execution.impactPointUnit = request.impactPointUnit;
        execution.outputPointDurationSec = request.outputPointDurationSec;
        execution.phaseEvents = request.phaseEvents;
        execution.targetTypeCode = request.targetTypeCode;
        execution.targetShapeCode = request.targetShapeCode;
        execution.flightPhaseCode = request.flightPhaseCode;
        execution.launchLatDeg = request.launchLatDeg;
        execution.launchLonDeg = request.launchLonDeg;
        execution.launchHeightM = request.launchHeightM;
        execution.initialSpeedMs = request.initialSpeedMs;
        execution.azimuthDeg = request.azimuthDeg;
        execution.elevationDeg = request.elevationDeg;
        execution.durationSec = request.durationSec;
        execution.integrationStepSec = request.integrationStepSec;
        execution.outputStepSec = request.outputStepSec;
        execution.boostDurationSec = request.boostDurationSec;
        execution.boostAccelerationMs2 = request.boostAccelerationMs2;
        execution.dragCdAreaOverMass = request.dragCdAreaOverMass;
        execution.outputDir = request.outputDir;
        return execution;
    }
}
