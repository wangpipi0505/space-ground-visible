package com.runisys.orbit.spacevisibility.application.ballistic;

import com.runisys.orbit.spacevisibility.infrastructure.files.BallisticFileStorage;
import com.tx.alg.trajectory.TargetTrajectoryFitter;
import com.tx.alg.trajectory.TargetTrajectoryFitter.FitRequest;
import com.tx.alg.trajectory.TargetTrajectoryFitter.FitRunResult;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** 102 拟合唯一 Web 应用编排：调用既有数值主体、管理结果作用域并自动登记，不改变 CSV 或原结果 DTO。 */
@Service
public class BallisticFitService {
    private final BallisticFileStorage storage;

    public BallisticFitService(BallisticFileStorage storage) {
        this.storage = storage;
    }

    /**
     * outputDirSpecified 为原 HTTP JSON 是否包含此字段；显式 null/空白/"." 均仍按原算法回退工作目录。
     * 未提交时在配置 results 下分配独占目录。只复制请求顶层字段，拟合器只读的观测列表保持原样，
     * 绝不写回调用方请求；观测硬约束、时间单位及完整 ECI/ECEF 输出完全由原算法执行。
     */
    public FitRunResult fitPackage102(FitRequest request, boolean outputDirSpecified) throws IOException {
        TargetTrajectoryFitter.validateFitRequest(request);
        FitRequest execution = new FitRequest();
        execution.taskName = request.taskName;
        execution.package102 = request.package102;
        execution.taskStartTime = request.taskStartTime;
        execution.taskEndTime = request.taskEndTime;
        execution.interpolationStepSec = request.interpolationStepSec;
        execution.outputDir = request.outputDir;
        try (BallisticFileStorage.ResultWorkspace workspace = storage.resultWorkspace(
                request.outputDir, outputDirSpecified)) {
            if (!outputDirSpecified) {
                execution.outputDir = workspace.directory.toString();
            }
            FitRunResult result = TargetTrajectoryFitter.fitPackage102ToCsv(execution);
            for (TargetTrajectoryFitter.FileResult file : result.files) {
                workspace.register(file.csvPath, result.taskName, file.targetId);
            }
            workspace.complete();
            return result;
        }
    }
}
