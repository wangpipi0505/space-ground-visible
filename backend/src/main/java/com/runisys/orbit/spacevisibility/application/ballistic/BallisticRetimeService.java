package com.runisys.orbit.spacevisibility.application.ballistic;

import com.runisys.orbit.spacevisibility.infrastructure.files.BallisticFileStorage;
import com.runisys.orbit.spacevisibility.domain.ballistic.TargetTrajectoryFitter;
import com.runisys.orbit.spacevisibility.domain.ballistic.TargetTrajectoryFitter.FitRunResult;
import com.runisys.orbit.spacevisibility.domain.ballistic.TargetTrajectoryFitter.RetimeInput;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** 唯一重定时 Web 编排：本地 CSV 原主体负责列与时刻/ECI 运算；存储负责输入租约和输出所有权。 */
@Service
public class BallisticRetimeService {
    private final BallisticFileStorage storage;

    public BallisticRetimeService(BallisticFileStorage storage) {
        this.storage = storage;
    }

    /**
     * 原请求/源路径存在校验先于工作区，目录解析/创建先于输入读取及新增受管理文件检查，保留原错误优先级。
     * 只改五字段的执行副本；省略目录使用 T15 独占目录，显式 null/空白/点目录仍由原主体处理。
     * 输入租约从实际读取前持续到登记完成，防止上传及已生成文件在计算中被 TTL 清理；未登记路径不加约束。
     * 租约关闭还需持久化输入最后使用时间，必须关闭成功后才提交工作区；关闭失败也回滚本次独占结果。
     * 缺省失败回滚自己拥有的结果，显式目录保留调用者文件，成功响应仍为原 FitRunResult，不附加 fileId。
     */
    public FitRunResult retime(RetimeInput request, boolean outputDirSpecified) throws IOException {
        TargetTrajectoryFitter.validateRetimeRequest(request);
        RetimeInput execution = new RetimeInput();
        execution.taskName = request.taskName;
        execution.targetId = request.targetId;
        execution.trajectoryCsvPath = request.trajectoryCsvPath;
        execution.launchTimeUtc = request.launchTimeUtc;
        execution.outputDir = request.outputDir;
        try (BallisticFileStorage.ResultWorkspace workspace = storage.resultWorkspace(request.outputDir, outputDirSpecified)) {
            if (!outputDirSpecified) execution.outputDir = workspace.directory.toString();
            TargetTrajectoryFitter.prepareRetimeOutputDirectory(execution);
            FitRunResult result;
            try (BallisticFileStorage.Lease input = storage.acquirePath(execution.trajectoryCsvPath)) {
                result = TargetTrajectoryFitter.retimeTrajectoryToCsv(execution);
                for (TargetTrajectoryFitter.FileResult file : result.files) {
                    workspace.register(file.csvPath, result.taskName, file.targetId);
                }
            }
            workspace.complete();
            return result;
        }
    }
}
