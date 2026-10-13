"""本事项一次性注释补充清单；每处根据已读类职责说明，不修改任何执行语句。

JavaBean访问器、框架继承的空Mapper、纯数据构造器用类/字段说明共同约束，
不机械重复每一行get/set。运行前后完整主类-g:none字节必须一致。
"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'backend/src/main/java'
DESCRIPTIONS = {
    'SpaceTargetVisibleApplication': '统一 Spring Boot 启动入口；扫描空间及正式弹道Web分层，数据库与外置Orekit数据按配置初始化。\n * 生产计算经HTTP服务执行，弹道原兼容类不注册第二套Controller或桌面窗口。',
    'SpaceVisibilityController': '空间目录、载荷持久化、下传与同步观测的HTTP边界，直接返回既有DTO。\n * 请求使用UTC Instant，卫星/目标ID绑定Long；算法和筛选由应用服务执行，不在此改变模型。',
    'SpaceApiModels': '空间HTTP请求/响应模型及JavaBean访问器集合；JSON名称与旧合同一致，不因注释整理改字段。\n * Instant为UTC，窗口序列化保留三位毫秒；记录LocalDateTime保留无Z格式。\n * 业务数值的范围/特殊标记见请求字段；简单get/set不额外校验，统一由应用及领域边界执行。',
    'ApiExceptionHandler': '既有空间HTTP异常转换边界：业务IllegalArgumentException返回400，其他异常返回500及requestId。\n * 原消息绑定异常也可能走500；不以弹道文件辅助接口错误协议覆盖此兼容行为。',
    'DownlinkPair': '一次卫星—站点下传扫描的不可变参数；UTC区间、步长/保护/断链值均以秒计，仰角以度计。\n * 轨道provider与站点坐标已由应用层解析，构造器保持旧有效性检查，各访问器仅读取原值。',
    'DownlinkPairResult': '单条站星链路的UTC扫描窗口结果；ID保持原类型，窗口列表复制并只读，避免调用者改写汇总。',
    'DownlinkPlanningService': '编排有限批次的站星并行下传计算，使用领域DownlinkPlanner及原光照/保护规则。\n * 结果按卫星和站点标识整理；单链路异常单独记录，不把失败链路当作正常空窗口。',
    'DownlinkWindowValidator': '已计算下传窗口的完整时段覆盖判定；优先使用coverageWindows，无汇总时按原规则合并。\n * 只在某一覆盖窗口含整个请求UTC区间时满足，不把含断链的总包络当连续覆盖。',
    'PayloadConstraintFactory': '持久化载荷JSON与有效领域约束的转换工厂，维护SPACE/GROUND独立profile及活动类型。\n * 新保存入口严格校验标准字段；旧JSON缺字段/圆锥兼容由原读取分支处理，不能伪映射为LVLH窗口。',
    'PlanningFacadeService': '空间任务应用入口，组合目录筛选、轨道provider、临时约束与同步/异步规划。\n * 只在应用层转换持久化实体及UTC时间；公共外推工具不读取数据库，结果沿用原DTO与报告口径。',
    'ReferenceDataService': '空间卫星、站点及目标目录访问与筛选服务；按原排序/分页及参数范围返回数据。\n * 载荷更新仅写指定卫星配置；目录字段不是弹道手工根数，浏览器不能据此臆造轨道。',
    'VisibilityArc': '某一光照状态下的可见弧段数据：时间为UTC Instant，时长单位秒。\n * 该对象描述单段，组合与累计时长由规划器处理，不把不连续弧段自动并为连续窗口。',
    'VisibilityExecutorConfiguration': 'Spring注入的空间计算线程池及规划服务配置；worker/queue/batch/maxPairTasks分别限定资源。\n * 原容量与拒绝策略保持，服务关闭由容器管理；该执行器不等同于MVC流式响应执行器。',
    'VisibilityObserver': '观测星的计算定义，聚合字符串身份、轨道provider与有效载荷约束。\n * 应用层从实体构造；扫描器只查询该定义，不跨请求改写实体或根数。',
    'VisibilityPairResult': '单观测星—目标的扫描结果及统计，保留弧段顺序和各光照状态，供组合交集与报告使用。',
    'VisibilityPlanningService': '以有限批次并行计算观测星—目标组合，再执行原共同观测窗口与最小时长约束。\n * 进度及取消在既有批次边界协作，输入顺序/输出整理规则保持；失败组合有独立跳过原因。',
    'VisibilityTarget': '目标的字符串身份及原轨道provider定义，由应用层创建，单位与坐标遵守provider合同。',
    'MybatisPlusConfiguration': 'MyBatis-Plus分页拦截配置；根据既有mysql/dm标识选择方言，演示H2使用MySQL兼容模式。',
    'SatelliteEntity': 'ZG_XZ_MANAGE观测卫星实体，目录与原轨道/载荷字段保持数据库映射。\n * 六根数A为米、角元素为度，EPOCH_TIME由应用层解释为UTC；目录字段不代替根数。',
    'StationEntity': 'ZG_STATION站点实体；经纬度/仰角为度，高度为米，ID为字符串。\n * 一次任务临时站点约束独立于这里的持久化基础参数。',
    'TargetEntity': 'ZG_XZ_MANAGE_TARGET目标目录及原轨道实体；筛选和根数/两行数解释由应用服务负责。\n * 原字段名称/类型及Jackson标注保持；不假定任意导入目录数据的未知物理单位。',
    'SatelliteMapper': 'MyBatis-Plus观测卫星表访问接口，继承BaseMapper既有CRUD，不添加SQL或业务计算。',
    'StationMapper': 'MyBatis-Plus站点表访问接口；字符串主键与StationEntity映射保持，继承原CRUD。',
    'TargetMapper': 'MyBatis-Plus目标表访问接口，筛选条件由ReferenceDataService组织，继承原CRUD。',
    'VisibilityTaskResultMapper': 'MyBatis-Plus任务记录表访问接口；原Long主键在API读取边界转字符串，继承原CRUD。',
    'DownlinkPlanner': '纯领域站星下传判定及稳定窗口处理，保留原地球遮挡、站点仰角、光照、断链与保护顺序。\n * 坐标转换由既有TEME/地固几何链完成，时间为UTC Instant，步长及窗口长度以秒计。',
    'GroundStationGeometry': 'WGS84站点几何及空间视线遮挡入口，位置单位米，仰角单位度。\n * 共用几何主体保留空间椭球及其相切/端点政策，不改用弹道历史球体口径。',
    'KeplerOrbitStateProvider': '原空间六根数传播器：EME2000中心引力加J2、固定步长RK4，TEME输出位置米。\n * 步长默认由配置取10秒；ThreadLocal积分历史、60秒锚点和8192上限保持。\n * 原速度由位置前后1秒差分取得，不能为抽取工具换成解析PV或跨线程共享积分历史。',
    'OrekitDataInitializer': 'Orekit默认数据上下文的一次性同步初始化及DE405交付文件校验。\n * 外置目录优先系统属性/环境变量；启动后不随请求重新装载星历，不自动用DE440或解析太阳替代。',
    'IlluminationState': '原空间光照状态标签，供筛选/窗口分段；具体太阳和本影判据由SolarGeometry执行。',
    'PayloadType': '标准载荷类别SPACE（对空）与GROUND（对地），原中文圆锥类型由弹道兼容入口独立解析。',
    'PayloadVisibilityEvaluator': '空间载荷几何/光照联合采样器；复用距离、LVLH角及视线角速度数学，保留DE405太阳。\n * 前一视线来自精选采样，观察星本影按原规则豁免太阳回避；该上下文不同于弹道解析太阳。',
    'CombinationConstraint': '共同观测卫星集合与最小持续时长（秒），复制有序ID且拒绝重复/空身份，访问器仅读取。',
    'CombinationWindow': '共同观测卫星集合及相对扫描起点的秒制区间；复制集合，保留窗口计算器产生的顺序。',
    'TimeWindowScanner': '基于UTC Instant的原空间粗精选状态扫描；相对秒内部保留纳秒转换，递归探测受深度上限约束。\n * 退出边界与最后可见精选点的差别由scan/scanStates既有政策保留，不新增连续事件根求解。',
    'V2__ReplaceNameUniqueKeys': 'Flyway V2迁移：删除卫星/目标名称单列唯一约束，按实际元数据补充SCID单列唯一索引。\n * H2与MySQL删除约束语法分别处理；只处理固定表名，不从用户输入拼接SQL。',
}


def edit():
    changed = []
    for path in JAVA.rglob('*.java'):
        description = DESCRIPTIONS.get(path.stem)
        if not description:
            continue
        text = path.read_text(encoding='utf-8-sig')
        declaration = re.search(r'^public (?:final |abstract )?(?:class|interface|enum) \w+', text, re.M)
        assert declaration
        # Javadoc放在该类注解之前，避免当作类内部或上一段import的说明。
        index = declaration.start()
        preceding_lines = text[:index].splitlines(keepends=True)
        while preceding_lines and preceding_lines[-1].lstrip().startswith('@'):
            index -= len(preceding_lines.pop())
        text = text[:index] + '/**\n * ' + description + '\n */\n' + text[index:]
        path.write_text(text, encoding='utf-8', newline='\n')
        changed.append(path.relative_to(ROOT).as_posix())
    path = JAVA / 'com/tx/alg/api/VisibilityRequest.java'
    text = path.read_text(encoding='utf-8-sig')
    wrong = '    /**\n     * 单颗卫星六根数定义。\n     * epochUtc 为历元时刻的UTC字符串，优先级高于 epochSec。\n     */'
    assert text.count(wrong) == 2
    text = text.replace(wrong, '    /**\n     * 单目标文件定义：csvPath为服务端上传路径或原远程地址，不是浏览器本机路径。\n     * 发射UTC别名依次取非空baseLaunchUTC、baseLaunchUtc、baselaunchUTC；均缺省时使用文件参考历元。\n     * targetId/targetName为字符串身份和名称，不按名称合并不同目标。\n     */', 1)
    text = text.replace(wrong, '    /**\n     * 单颗卫星六根数定义：半长轴米，偏心率无量纲，倾角/RAAN/近地点幅角/平近点角为度。\n     * epochUtc按字面UTC解析；为空使用原2026/07/01 10:00:00，无epochSec请求字段。\n     * 显式单星平近点角缺省0；空列表默认SAT-A/SAT-B星座与此单星默认不同。\n     */', 1)
    path.write_text(text, encoding='utf-8', newline='\n')
    changed.append(path.relative_to(ROOT).as_posix())
    path = JAVA / 'com/runisys/orbit/spacevisibility/api/SpaceApiModels.java'
    text = path.read_text(encoding='utf-8')
    fields = {
        'minDistanceKm': '最近距离，公里；标准保存必填有限非负，旧米入口另按米字段解析。',
        'maxDistanceKm': '最远距离，公里；标准保存必填，0不限，非零须大于最近距离。',
        'azimuthMinDeg': 'LVLH方位下限，度；上下限双null不限，否则0≤下限<上限≤360。',
        'azimuthMaxDeg': 'LVLH方位上限，度；必须与下限成对，跨0范围不能用倒置端点表示。',
        'elevationMinDeg': 'LVLH俯仰下限，度；双null不限，SPACE下界-90、GROUND下界-91。',
        'elevationMaxDeg': 'LVLH俯仰上限，度；必须成对且下限<上限≤90。',
        'angleRateDegPerSec': '跟踪视线角速度上限，度/秒；SPACE 0/-1不限，GROUND 0真零上限且拒绝-1。',
        'sunAvoidHalfAngleDeg': '太阳回避半角，度；必填有限0..180，0关闭。',
        'startupAngleRateDegPerSec': '启动角速度，度/秒；null/-1禁用，其余非负有限，当前仅保存元数据。',
    }
    # 仅前两个标准保存请求补充字段说明；响应/同名访问器不重复复制政策。
    for class_name in ('SpacePayloadConfigurationRequest', 'PayloadProfileRequest'):
        start = text.index('    public static class ' + class_name)
        end = text.index('        public ', start + len('    public static class ' + class_name))
        block = text[start:end]
        for name, comment in fields.items():
            old = '        private Double ' + name + ';'
            assert old in block
            block = block.replace(old, '        /** ' + comment + ' */\n' + old)
        text = text[:start] + block + text[end:]
    path.write_text(text, encoding='utf-8', newline='\n')
    path = JAVA / 'com/runisys/orbit/spacevisibility/api/PlanningTaskController.java'
    text = path.read_text(encoding='utf-8-sig').replace('     * @return 取消后的任务状态；任务不存在时返回 404', '     * @return 当前真实状态；200/RUNNING仅表示已受理取消，需继续轮询至CANCELLED；已成功任务保留结果，未知任务404')
    path.write_text(text, encoding='utf-8', newline='\n')
    changed.append(path.relative_to(ROOT).as_posix())
    (ROOT / 'verification/T29/comment-changes.json').write_text(json.dumps(changed, ensure_ascii=False, indent=2), encoding='utf-8')
    print('comment files', len(changed))


if __name__ == '__main__':
    edit()
