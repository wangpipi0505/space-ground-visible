"""A17 一次性边界整理；只改变适配和 Java 类型归属，不改数值语句。"""
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'backend/src/main/java/com/runisys/orbit/spacevisibility'

path = BASE / 'domain/ballistic/TargetTrajectoryFitter.java'
text = path.read_text(encoding='utf-8')
for name in ('FitInput', 'SimulationInput', 'RetimeInput'):
    text = text.replace('public static final class ' + name, 'public static class ' + name)
path.write_text(text, encoding='utf-8')

path = BASE / 'api/ballistic/VisibilityRequest.java'
text = path.read_text(encoding='utf-8').replace('public final class VisibilityRequest {',
    'public final class VisibilityRequest implements com.runisys.orbit.spacevisibility.application.ballistic.BallisticVisibilityInput {')
path.write_text(text, encoding='utf-8')

path = BASE / 'application/ballistic/BallisticVisibilityService.java'
text = path.read_text(encoding='utf-8').replace('import com.runisys.orbit.spacevisibility.api.ballistic.VisibilityRequest;',
    'import com.runisys.orbit.spacevisibility.infrastructure.files.TrajectoryFileResolver;')
text = text.replace('search(VisibilityRequest request)', 'search(BallisticVisibilityInput request)')
text = text.replace('OrbitCalculator::parseBallisticCsvWithReference', 'TrajectoryFileResolver::parseBallisticCsvWithReference')
text = text.replace('原兼容 DTO，单位、缺省值及别名保持原定义', '应用输入视图，单位、缺省值及别名由输入适配器保留')
path.write_text(text, encoding='utf-8')

for file, domain, api in [('BallisticFitController.java','FitInput','FitRequest'),
    ('BallisticSimulationController.java','SimulationInput','SimulationRequest'),
    ('BallisticRetimeController.java','RetimeInput','RetimeRequest')]:
    path = BASE / 'api/ballistic' / file
    text = path.read_text(encoding='utf-8').replace('new ErrorResponse(', 'new BallisticErrorResponse(')
    text = text.replace('TargetTrajectoryFitter.' + domain, 'BallisticTrajectoryRequests.' + api)
    text = text.replace(' * 不继承旧三路由控制器，避免在 T15 提前向主程序注册模拟和重定时。',
                        ' * 拟合、模拟和重定时各自拥有唯一控制器，共用领域输入字段和错误响应。')
    path.write_text(text, encoding='utf-8')

path = BASE / 'domain/ballistic/OrbitCalculator.java'
text = path.read_text(encoding='utf-8')
start = text.index('    public static LaunchSearchResultV2 searchLaunchOffsetsV2(List<BallisticTargetInput> targets,')
end = text.index('    /**\n     * 每个目标的源加载边界', start)
text = text[:start] + text[end:]
start = text.index('    /**\n     * 按位置字符串解析弹道文件：')
end = text.index('    /**\n     * 从UTF-8字节流解析弹道CSV。', start)
text = text[:start] + text[end:]
text = text.replace('原 Java 入口使用原解析器，Web 应用可提供租约解析器；本接口不依赖 Spring 或 HTTP。',
    '调用方显式提供文件解析策略；本接口不依赖 Spring、HTTP 或下载实现。')
path.write_text(text, encoding='utf-8')

path = BASE / 'infrastructure/files/TrajectoryFileResolver.java'
text = path.read_text(encoding='utf-8').replace('import io.minio.GetObjectArgs;',
    'import com.runisys.orbit.spacevisibility.domain.ballistic.OrbitCalculator;\nimport java.util.List;\nimport io.minio.GetObjectArgs;')
marker = '    /**\n     * 判断位置字符串是否为远程地址'
methods = '''    /** 本地或网络地址解析为轨迹点；数值与列解析委托领域 Path 入口。 */
    public static List<OrbitCalculator.BallisticPoint> parseBallisticCsv(String location) throws IOException {
        return OrbitCalculator.parseBallisticCsv(resolveToLocalFile(location));
    }

    /** 地址加载与领域解析的组合入口；返回每份文件自己的首点参考历元。 */
    public static OrbitCalculator.ParsedBallisticCsv parseBallisticCsvWithReference(String location) throws IOException {
        return OrbitCalculator.parseBallisticCsvWithReference(resolveToLocalFile(location));
    }

'''
text = text.replace(marker, methods + marker)
path.write_text(text, encoding='utf-8')

# 文件解析器测试依赖包级协议结构，随所属设施移动；API 测试恢复到实际 API 包。
tests = ROOT / 'backend/src/test/java'
for path in list(tests.rglob('*.java')):
    text = path.read_text(encoding='utf-8')
    if path.name in ('TrajectoryFileResolverTest.java', 'TrajectoryFileScopeTest.java'):
        text = text.replace('package com.runisys.orbit.spacevisibility.domain.ballistic;',
                            'package com.runisys.orbit.spacevisibility.infrastructure.files;')
    text = text.replace('package com.runisys.orbit.spacevisibility.domain.ballistic.api;',
                        'package com.runisys.orbit.spacevisibility.api.ballistic;')
    text = text.replace('public class WindowEngineReference', 'public class WindowEngineReference')
    path.write_text(text, encoding='utf-8')
    package = re.search(r'^package ([^;]+);', text)
    if package:
        dest = tests / package[1].replace('.', '/') / path.name
        if dest != path:
            dest.parent.mkdir(parents=True, exist_ok=True)
            path.rename(dest)
