package com.runisys.orbit.spacevisibility.application.ballistic;

import com.runisys.orbit.spacevisibility.infrastructure.files.BallisticFileConfiguration;
import com.runisys.orbit.spacevisibility.infrastructure.files.BallisticFileStorage;
import com.tx.alg.OrbitCalculator;
import com.tx.alg.TrajectoryFileResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Web 弹道文件解析应用入口：接受上传 serverPath 或原本地/HTTP/HTTPS/MinIO 地址。
 * 调用不修改静态 referenceEpochSec 的原 WithReference 解析器，让每个目标独立携带源历元；
 * UTC 分字段、Excel 自动识别和原列/单位逻辑全部由原算法完成。
 */
@Service
public class BallisticTrajectoryService {
    private final BallisticFileStorage storage;
    private final BallisticFileConfiguration config;

    public BallisticTrajectoryService(BallisticFileStorage storage, BallisticFileConfiguration config) {
        this.storage = storage;
        this.config = config;
    }

    /**
     * 解析完成后临时下载立即关闭；上传文件租约覆盖完整解析。
     * sourceLocation 保留调用方原逻辑 URI，未来 sourceCsvPath/csvPath 回显不能替换成下载临时路径。
     */
    public ParsedTrajectory parse(String sourceLocation) throws IOException {
        if (sourceLocation == null || sourceLocation.trim().isEmpty()) {
            throw new IllegalArgumentException("弹道文件位置不能为空");
        }
        if (TrajectoryFileResolver.isRemoteLocation(sourceLocation)) {
            try (BallisticFileStorage.TemporaryWorkspace workspace = storage.temporaryWorkspace();
                 TrajectoryFileResolver.ResolvedFile file = TrajectoryFileResolver.resolveScoped(
                         sourceLocation, workspace.directory, config.maxBytes)) {
                return new ParsedTrajectory(sourceLocation, OrbitCalculator.parseBallisticCsvWithReference(file.path));
            }
        }
        try (BallisticFileStorage.Lease lease = storage.acquirePath(sourceLocation);
             TrajectoryFileResolver.ResolvedFile file = TrajectoryFileResolver.resolveScoped(
                     sourceLocation, config.temp, config.maxBytes)) {
            return new ParsedTrajectory(sourceLocation,
                    OrbitCalculator.parseBallisticCsvWithReference(lease == null ? file.path : lease.path));
        }
    }

    /** 原路径标识与原解析结果分开保存，不把显示标识与短期实际读取路径混为一体。 */
    public static final class ParsedTrajectory {
        public final String sourceLocation;
        public final OrbitCalculator.ParsedBallisticCsv parsed;

        private ParsedTrajectory(String sourceLocation, OrbitCalculator.ParsedBallisticCsv parsed) {
            this.sourceLocation = sourceLocation;
            this.parsed = parsed;
        }
    }
}
