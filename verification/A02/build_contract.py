"""从当前 Java DTO 与 A01 原始 HTTP 证据编制 A02；只生成文档，不启动计算或修改产品。"""
import copy
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A02'
SOURCE = ROOT / 'backend/src/main/java'
SCHEMAS = {}
CLASSES = {}

def read(path):
    return path.read_text(encoding='utf-8-sig')

def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def ref(name):
    return {'$ref': '#/components/schemas/' + name}

def arr(item):
    return {'type': 'array', 'items': item}

def obj(properties, required=(), description=''):
    result = {'type': 'object', 'properties': properties}
    if required:
        result['required'] = list(required)
    if description:
        result['description'] = description
    return result

def mask(text):
    # 保持字符位置与换行，忽略注释和字符串内部花括号，防止误把构造器局部变量当 HTTP 字段。
    return re.sub(r'/\*[\s\S]*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda m: ''.join('\n' if c == '\n' else ' ' for c in m.group()), text)

def extract(path):
    text = read(path)
    clean = mask(text)
    depth = []
    level = 0
    for c in clean:
        depth.append(level)
        if c == '{': level += 1
        if c == '}': level -= 1
    for cm in re.finditer(r'\bpublic\s+(?:static\s+)?(?:final\s+)?class\s+(\w+)(?:\s*<[^{}]+>)?(?:\s+extends\s+(\w+))?\s*\{', clean):
        name, parent = cm.group(1), cm.group(2)
        start, classdepth = cm.end(), depth[cm.end()-1]+1
        end = next((i for i in range(start, len(clean)) if clean[i] == '}' and depth[i] == classdepth), len(clean))
        fields = {}
        for fm in re.finditer(r'\b(public|private|protected)\s+(final\s+)?([\w.]+(?:\s*<[^;=(){}]+>)?(?:\[\])?)\s+(\w+)\s*(?:=\s*([^;]+))?;', text[start:end]):
            pos = start+fm.start()
            if depth[pos] != classdepth or clean[pos:pos+len(fm.group(1))].strip() != fm.group(1): continue
            preceding = text[start:pos]
            # 只取紧邻声明的完整 Javadoc：注释内部不能越过 */，注释之后只允许空白及注解。
            # 不截取固定字符数，避免较长业务说明被切断；不跨相邻字段或 getter/setter。
            comment = re.search(r'/\*\*((?:(?!\*/)[\s\S])*)\*/\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*$', preceding)
            desc = re.sub(r'\s*\*\s*', ' ', comment.group(1)).strip() if comment else ''
            if '@JsonIgnore' in preceding[-120:] or name == 'VisibilityPlanResponse' and fm.group(4) == 'allSkippedPairs': continue
            fields[fm.group(4)] = {'java': fm.group(3).replace(' ', ''), 'defaultExpr': fm.group(5), 'description': desc,
                                  'line': text[:pos].count('\n')+1, 'public': fm.group(1) == 'public'}
        for gm in re.finditer(r'\bpublic\s+([\w.]+(?:\s*<[^(){}]+>)?)\s+(get|is)([A-Z]\w*)\s*\(\s*\)', text[start:end]):
            pos = start+gm.start()
            if depth[pos] != classdepth: continue
            fname = gm.group(3)[0].lower()+gm.group(3)[1:]
            preceding = text[max(start, pos-700):pos]
            annotation_tail = preceding[preceding.rfind('}')+1:]
            if '@JsonIgnore' in annotation_tail:
                fields.pop(fname, None)
                continue
            if fname not in fields:
                fields[fname] = {'java': gm.group(1).replace(' ', ''), 'defaultExpr': None,
                                 'description': '', 'line': text[:pos].count('\n')+1, 'public': False}
            if 'NON_NULL' in annotation_tail: fields[fname]['omit'] = '空值省略'
            if 'NON_EMPTY' in annotation_tail: fields[fname]['omit'] = '空数组省略'
            fields[fname]['getter'] = True
        fields = {n: f for n, f in fields.items() if f['public'] or f.get('getter')}
        if fields:
            CLASSES[name] = {'parent': parent, 'fields': fields, 'source': path.relative_to(ROOT).as_posix()}

for relative in ['com/runisys/orbit/spacevisibility/api/SpaceApiModels.java',
                 'com/runisys/orbit/spacevisibility/api/ballistic/BallisticFileModels.java',
                 'com/tx/alg/api/VisibilityRequest.java', 'com/tx/alg/trajectory/TargetTrajectoryFitter.java',
                 'com/runisys/orbit/spacevisibility/application/VisibilityObservationResult.java',
                 'com/runisys/orbit/spacevisibility/application/VisibilityArc.java',
                 'com/runisys/orbit/spacevisibility/application/VisibilityPairStatistics.java']:
    extract(SOURCE/relative)
for path in (SOURCE/'com/runisys/orbit/spacevisibility/infrastructure/persistence/entity').glob('*Entity.java'):
    extract(path)

if __name__ == '__main__' and '--inventory' in __import__('sys').argv:
    for name, info in CLASSES.items():
        print(name + ': ' + ', '.join(n+':'+f['java']+(('='+f['defaultExpr']) if f['defaultExpr'] else '') for n, f in info['fields'].items()))
    raise SystemExit(0)

MEANINGS = {
 'id':'目录主键；实体响应为 Long 数字，任务记录响应为十进制字符串。',
 'name':'目录或平台名称，空值沿原序列化规则处理。', 'satelliteId':'卫星身份；空间下传为 Long 数字，观测及弹道为字符串。',
 'stationId':'地面站字符串主键。', 'targetId':'目标身份；空间请求为 Long 数字，观测及弹道响应为字符串。',
 'taskId':'异步内存任务不透明字符串标识；重启后不恢复内存任务。',
 'recordId':'持久记录十进制字符串主键，避免浏览器 53 位整数精度丢失。',
 'payloadId':'弹道载荷字符串身份；未给时计算入口生成卫星ID-PAYLOAD-序号，preview只回显原值。',
 'payloadName':'载荷显示名；计算入口缺省回退payloadId，preview只回显原值。',
 'targetName':'目标显示名称。', 'satelliteName':'观测星显示名称。', 'stationName':'站点显示名称。',
 'observerSatelliteId':'观测星字符串身份。', 'observerSatelliteName':'观测星显示名称。',
 'minDistanceKm':'最近距离 km，标准载荷必填有限且≥0；统计响应表示实际最小距离。',
 'maxDistanceKm':'最远距离 km，标准载荷必填；0不限，非零须>minDistanceKm；统计响应表示实际最大距离。',
 'minDistanceM':'最近距离 m；旧载荷请求与公里字段互斥。', 'maxDistanceM':'最远距离 m；旧载荷请求与公里字段互斥，0不限。',
 'azimuthMinDeg':'LVLH方位下限 °，双null不限，否则0≤min<max≤360；0沿速度方向。',
 'azimuthMaxDeg':'LVLH方位上限 °，与下限成对；倒置端点不表示跨0窗口。',
 'elevationMinDeg':'LVLH俯仰下限 °，双null不限；SPACE≥-90、GROUND≥-91。',
 'elevationMaxDeg':'LVLH俯仰上限 °，与下限成对且min<max≤90。',
 'angleRateDegPerSec':'跟踪视线角速度 °/s，标准必填；SPACE 0/-1不限，GROUND 0是真零上限且-1拒绝。',
 'sunAvoidHalfAngleDeg':'太阳回避半角 °，标准必填有限0..180；0关闭，等于边界允许。',
 'startupAngleRateDegPerSec':'启动角速度 °/s；null/-1禁用，其它非负有限；只作元数据，不参与窗口判定。',
 'payloadType':'载荷类别；标准SPACE/GROUND按去空白且忽略大小写归一；旧中文四类保持原分支。',
 'activePayloadType':'当前启用的SPACE/GROUND类型；未配置可为空。',
 'legacyGroundProfile':'历史天底锥角配置标记；true时请按十标准字段重新保存。',
 'sensorFullConeAngleDeg':'历史对地全锥角 °，标准profiles仅读取旧值；保存标准配置时不作为矩形约束。',
 'sensorFovDeg':'旧顶层视场全锥角 °，冻结兼容字段；实际载荷判定仍按原中文类型配置分支。',
 'maxDetectRangeKm':'旧最远探测距离 km，≤0不限；标准摘要仅映射maxDistanceKm。',
 'sunExclusionAngleDeg':'旧太阳回避最小夹角 °；标准摘要仅映射sunAvoidHalfAngleDeg。',
 'configured':'是否有可用SPACE配置；未配置返回false和payloadType=SPACE，其它字段为null。',
 'profiles':'按类型独立保存的载荷配置；保存一种并启用，另一种保留。',
 'startTime':'窗口开始UTC；空间ISO Instant，弹道输出为字面UTC文本。',
 'endTime':'窗口结束UTC，须晚于开始；空间ISO Instant，弹道输出为字面UTC文本。',
 'durationSec':'该时段持续秒数；共同观测父窗口按成员交集计算。',
 'coarseStepSec':'粗选扫描间隔 s，必须有限≥0.001。', 'fineStepSec':'精选间隔 s，必须有限≥0.001且不得大于粗选间隔。',
 'preGuardSec':'前保护秒数；缺省取配置300；非负业务值，负值在链路执行归一化中失败，可能返回200的skippedLinks而非400。', 'postGuardSec':'后保护秒数；缺省取配置0；非负业务值，负值可进入200的skippedLinks。',
 'allowedGapSec':'允许断链秒数；缺省取配置0；非负业务值，负值可进入200的skippedLinks。',
 'satelliteIllumination':'下传观测星光照ANY/SUNLIT/SHADOW，缺省ANY。',
 'satelliteIds':'空间卫星Long数字数组；具体是否必需见所在请求的组合规则。',
 'observerSatelliteIds':'观测星集合；请求Long数字数组，结果字符串数组。',
 'observerSatelliteNames':'与观测星身份顺序对应的显示名称数组。',
 'targetIds':'空间目标Long数字集合；查询响应含全部筛选命中ID，不受分页影响。',
 'belongs':'BELONG_目录编码筛选，空集合不限制。', 'belong':'目录BELONG_原编码，非固定硬编码集合。',
 'minWeight':'目标质量筛选下限 kg，可空，非负。', 'maxWeight':'目标质量筛选上限 kg，可空，非负且须不小于下限。',
 'weight':'目录质量 kg；缺失时不补造。', 'targetWeightKg':'目标质量 kg，缺失省略。',
 'epochStartTime':'轨道历元UTC Instant筛选下限，可空。', 'epochEndTime':'轨道历元UTC Instant筛选上限，可空，须不早于下限。',
 'minTleEpochCode':'TLE目录历元下限YYDDD，默认配置23340；五位字符串。',
 'maxApproximationDays':'星历建议近似传播天数，默认配置14，≥0；超出只产生风险提示，不硬排除。',
 'planningStartTime':'可空的兼容UTC Instant字段；目录查询业务不读取它，无规划时间成对要求，不参与筛选或风险判定。',
 'planningEndTime':'可空的兼容UTC Instant字段；目录查询业务不读取它，无须晚于planningStartTime，不参与筛选或风险判定。',
 'excludeNameRegex':'目标名称排除正则，空白不限；非法正则400。',
 'pageNumber':'页码从1开始；<1拒绝。', 'pageSize':'每页记录数1..200。',
 'total':'筛选总条数。', 'records':'本页列表项，不含任务记录JSON大字段。',
 'targets':'筛选目录目标列表或弹道逐目标摘要，见具体schema。',
 'stations':'本次临时站点约束；不会覆盖站点长期参数。', 'links':'显式卫星—站点选择关系；coverageWindows中为该并集的贡献链路。',
 'minElevationDeg':'仰角/俯仰下限 °，临时站点缺省取实体或配置10；统计为LVLH实际最小俯仰。',
 'maxElevationDeg':'仰角/俯仰上限 °，临时站点缺省取实体或配置90；下传≤0不限制上仰角；统计为LVLH实际最大俯仰。',
 'satelliteStationWindows':'逐站星链路稳定窗口列表。', 'coverageWindows':'重叠或相接的链路窗口并集；存在断档时不合并。',
 'windows':'该链路原始窗口数组。', 'skippedLinks':'计算失败链路明细最多500条，不等于正常无窗。',
 'totalSkippedLinks':'失败链路完整计数，可大于返回明细条数。', 'stage':'失败阶段原代码；下传SCAN，观测由原轨道/扫描过程给出。',
 'reason':'原失败或星历风险原因摘要。', 'satisfied':'完整请求时段是否由下传覆盖；不表示观测可见性。',
 'plan':'用于核对覆盖结论的真实下传规划结果。',
 'observationConstraints':'共同观测组合及各组合最小时长；非空成员集合，成员须在观测星中。',
 'minDurationSec':'组合父窗口最小时长 s，有限非负。',
 'illuminationConstraint':'临时观测星—目标光照组合；null不限。', 'mode':'ANY/TARGET_SUNLIT/OBSERVER_SHADOW_TARGET_SUNLIT/BOTH_SHADOW/CUSTOM；空白或null按ANY。',
 'observerStates':'CUSTOM观测星精确光照状态，至少一项；其它模式忽略。', 'targetStates':'CUSTOM目标精确光照状态，至少一项；其它模式忽略。',
 'results':'按目标及卫星组合分组的实际观测结果。', 'skippedPairs':'失败组合明细最多500条，不含正常业务排除。',
 'totalSkippedPairs':'失败组合完整计数；全量明细单独存入记录skippedPairsJson。',
 'targetFilterBreakdown':'规划候选统计：无显式目标ID时按目录条件逐层统计；显式目标ID快照时五个候选计数均为selectedTargetCount，不重新叠加目录筛选。星历超过建议时长仅记风险告警，不作为硬筛选排除，仍尝试传播；候选数量不等于传播成功数。', 'filterBreakdown':'目录查询逐层候选统计；epochConstraintHit恒等epochHit，无单独任务时间或发射过滤；ephemerisWarningHit/maxApproximationDays固定0，查询未计算风险或采用风险阈值。',
 'ephemerisWarnings':'通过硬筛选并继续计算的星历过期风险，不以假窗口替代计算。',
 'winToolReport':'请求boolean控制是否生成两段式报告；响应string原报告，未请求时省略。',
 'arcs':'满足组合时长的父观测窗口；光照片段不是独立合格窗口。',
 'observationType':'原SINGLE/COMBINATION等观测类型代码，见当前服务输出。', 'observationTypeName':'观测类型中文名。',
 'conciseSummary':'WinTool格式任务及逐星时段展示文本。', 'targetNorad':'目标NORAD文本，目录缺失省略。',
 'firstOrbitLine':'原TLE第一行，非TLE目标省略。', 'secondOrbitLine':'原TLE第二行，非TLE目标省略。',
 'observerIlluminations':'按参与卫星顺序的精确光照状态。', 'targetIllumination':'目标精确光照状态。',
 'illuminationSegments':'父窗口内稳定光照片段，空集合省略。', 'satelliteSegments':'≥2颗星时的各星原始连续窗口，单星省略；父窗口是这些窗口交集。',
 'statistics':'可见采样点统计；单星挂父弧段，多星挂各satelliteSegments，旧数据可空。',
 'state':'精确光照SUNLIT/PENUMBRA/UMBRA；当前圆柱影不产生PENUMBRA，保留兼容枚举。',
 'stateName':'阳照/半影/本影中文名称。', 'samples':'参与累计的实际可见采样点数。',
 'minAzimuthDeg':'LVLH最小方位 °，0沿速度；缺几何可空。', 'maxAzimuthDeg':'LVLH最大方位 °，0沿速度；缺几何可空。',
 'azimuthRelativeMinusXMinDeg':'LVLH方位+180°归一到[-180,180)后排序的最小值 °。',
 'azimuthRelativeMinusXMaxDeg':'LVLH方位+180°归一到[-180,180)后排序的最大值 °。',
 'elevationZenithMinDeg':'视线与天底夹角下限 °=LVLH俯仰+90。', 'elevationZenithMaxDeg':'视线与天底夹角上限 °=LVLH俯仰+90。',
 'maxAngleRateDegPerSec':'可见采样点最大视线角速度 °/s。',
 'status':'原任务状态PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED。', 'taskType':'持久任务类型，当前VISIBILITY。',
 'totalPairs':'总观测星—目标计算组合数。', 'completedPairs':'已计算组合数。', 'totalTargets':'目标总数。', 'completedTargets':'已处理目标数。',
 'errorMessage':'任务失败原因，未失败可空或省略。', 'remark':'备注，null或缺失表示清空；selection备注另随选择保存。',
 'createdAt':'任务创建UTC Instant，三位毫秒带Z。', 'completedAt':'任务终态UTC Instant，未结束为null。',
 'createTime':'记录UTC LocalDateTime，三位毫秒无Z；目录实体保留其默认LocalDateTime序列化。',
 'completeTime':'记录终态UTC LocalDateTime，三位毫秒无Z；未终态为null。',
 'requestJson':'提交时完整VisibilityPlanRequest JSON原文字符串。', 'resultJson':'成功完整VisibilityPlanResponse JSON原文字符串，未成功为null。',
 'skippedPairsJson':'未截断失败组合JSON原文字符串，仅成功且有跳过组合时有值，否则null。', 'selectionJson':'最后一次确认选择JSON原文字符串，未确认按NON_NULL省略。',
 'received':'服务已接受确认选择的回执。', 'selectionCount':'selections数组长度。', 'mergedSegments':'mergedTimeRange.segments数组长度，不由服务重新计算并集。',
 'message':'中文回执或错误文本。', 'detail':'更新后的完整持久记录。',
 'planningWindow':'透明保存的规划窗口JSON；推荐前端结构startTime/endTime。',
 'mergedTimeRange':'透明保存的并集JSON；推荐segments及totalDurationSec；服务不重新计算。',
 'selections':'必填非空JSON数组；每项原文保存，推荐SelectionTarget结构；服务不额外校验每项字段。',
 'code':'错误协议代码或目录编码，见所在模型。', 'count':'该BELONG_编码目录数量。', 'description':'目录编码说明。',
 'belongsHit':'BELONG筛选后命中数。', 'weightHit':'质量筛选后命中数。', 'epochHit':'目录历元筛选后命中数。',
 'epochConstraintHit':'恒等epochHit的兼容计数字段，无单独任务时间或发射过滤。', 'ephemerisWarningHit':'目录查询固定0；规划为风险清单数量，只告警仍尝试传播。', 'excludeHit':'名称排除后候选命中数，不等于传播成功数。',
 'fileId':'上传/登记201响应的不透明UUID；必须来自当次响应，过期或重启未恢复引用须重取。',
 'originalName':'经安全末段处理的客户端原名，保留Unicode，控制字符拒绝。', 'sizeBytes':'实际存储文件字节数。',
 'contentType':'经HTTP媒体类型校验的下载类型。', 'serverPath':'服务器规范绝对路径，可传原csvPath等；不是浏览器本机路径。',
 'csvPath':'服务端现有CSV路径；register-result必须位于管理results根且现存，计算支持其各自旧路径语义。',
 'taskName':'任务名；不同计算缺省见default，null/空白由原算法回退。', 'header':'首个非空记录原表头字符串数组。',
 'rows':'本页原单元格文本二维数组；预览不采样、不改变原文件。', 'totalRows':'不含表头的非空数据记录总数。',
 'page':'预览页码≥1。', 'limit':'预览每页1..配置上限，默认50，上限默认500。',
 'metadata':'仅精确识别已知20列表头时提供timeScale/positionUnit/velocityUnit/frames/dtUnit，否则空映射。',
 'ballisticCsvPath':'旧单目标服务端CSV/Excel或原HTTP/HTTPS/MinIO地址；ballistics非空时忽略。',
 'baseLaunchUtc':'字面UTC文本；单目标缺省用文件历元，多目标用本目标别名优先级。',
 'baseLaunchUTC':'多目标第一优先非空发射UTC。', 'baselaunchUTC':'多目标第三优先非空发射UTC。',
 'ballistics':'非空时按数组顺序多目标输入，否则旧单目标字段生效；各目标独立文件历元。',
 'startOffsetSec':'扫描起点 s，可为负。', 'stepSizeSec':'偏移步长 s，整数>0。', 'numCalculations':'计算次数，整数>0。',
 'targetSampleStepSec':'目标轨迹采样步长 s，≤0按旧自动步长。', 'trajectoryPointOutput':'1含完整轨迹点，0保留摘要与统计但轨迹数组为空。',
 'orbitModel':'TWO_BODY/J2_SECULAR/J2_NUMERICAL及原中文显示名；字段缺省J2_NUMERICAL，显式null/空白回退TWO_BODY。',
 'satellites':'显式平台列表；null/空列表用SAT-A/B内置双星，平近点角30/0。',
 'semiMajorAxisM':'轨道半长轴 m。', 'eccentricity':'偏心率，无量纲。', 'inclinationDeg':'轨道倾角 °。', 'raanDeg':'升交点赤经 °。',
 'argumentOfPerigeeDeg':'近地点幅角 °。', 'meanAnomalyDeg':'平近点角 °；显式单星缺省0。',
 'epochUtc':'字面UTC历元，空白/缺失使用2026/07/01 10:00:00；无epochSec请求字段。',
 'payloads':'平台多载荷列表，缺失/null/空时使用旧顶层配置；每项不可为null。',
 'package102':'原102记录数组，至少两个有效三维观测点；不同目标分别拟合。',
 'taskStartTime':'字面UTC输出开始时刻；拟合须覆盖首观测，模拟与物理起落区间分开。',
 'taskEndTime':'字面UTC输出结束时刻；拟合须覆盖末观测，模拟与物理起落区间分开。',
 'interpolationStepSec':'输出步长 s，原normalizeStep最小0.001。',
 'outputDir':'HTTP未出现字段时分配管理results独占目录；显式值/null/空白/点目录按旧语义，DTO默认点不代表HTTP省略后写源码目录。',
 'coordinateInfo':'102嵌套坐标第一优先入口；优先于coordinate/coords/扁平同名字段。', 'coordinate':'102嵌套坐标第二优先入口。',
 'coords':'102嵌套坐标第三优先入口。', 'targetInfo':'102嵌套目标第一优先入口。', 'target':'102嵌套目标第二优先入口，否则取扁平字段。',
 'tack3d_time_code':'历史拼写时间码字符串，UTC时间；支持原文本/17位毫秒码，避免JS数字丢精度。',
 'track3d_time_code':'正确拼写的时间码别名，tack3d非空优先。',
 'track_tgt_id':'102目标字符串身份；未提供按原UNKNOWN等默认路径处理。',
 'prop_tgt_type_8':'原目标类型码字符串，参与模型选择。', 'prop_tgt_shape_8':'原目标形状码字符串。',
 'prop_tgt_flight_phase_8':'原飞行阶段码字符串，参与拟合策略。', 'prob_tgt_critical_incident':'原关键事件码字符串。',
 'prob_tgt_critical_incident_time_code':'关键事件UTC时间码字符串。',
 'launch_time_code':'102物理发射UTC时间码，非输出窗口端点。', 'impact_time_code':'102物理落点UTC时间码，非输出窗口端点。',
 'launch_coord_frame':'发射点原坐标框架J2000/ECI/ECEF等按原解析。', 'impact_coord_frame':'落点原坐标框架按原解析。',
 'launch_pos_unit':'发射点位置单位原m/km/0.1m解析。', 'impact_pos_unit':'落点位置单位原m/km/0.1m解析。',
 'targetCount':'结果目标数量。', 'files':'每目标CSV摘要，所有路径均取本次真实响应，不猜文件名。',
 'model':'原拟合/模拟/重定时模型名称字符串。', 'pointCount':'文件完整数据点数，非预览页长度。', 'warnings':'未截断原算法告警文本数组。',
 'launchTimeUtc':'必填字面发射UTC文本。', 'impactTimeUtc':'物理落点UTC，优先于impactTime；须晚于发射，未给则原自动求解。',
 'impactTime':'旧落点UTC别名，impactTimeUtc非空优先。',
 'launchPointCoordFrame':'发射端点原框架，默认J2000；支持J2000/ECI/ECIJ2000、ECEF/ITRF/WGS84ECEF、LLA/LLH/BLH/GEODETIC/WGS84/WGS84LLA，归一去空白/横杠/下划线。',
 'impactPointCoordFrame':'落点端点原框架，默认J2000。',
 'launchPointUnit':'发射端点坐标单位，默认m，原m/km/0.1m分支。', 'impactPointUnit':'落点端点坐标单位，默认m。',
 'outputPointDurationSec':'输出间隔 s，优先取正值，否则outputStepSec/1；最终最小0.001。',
 'phaseEvents':'保留的分段事件DTO数组；当前端点求解路径只读取数量并生成预留告警，不参与事件动力学。',
 'trajectoryCsvPath':'重定时本地服务器CSV路径，要求原UTC分列/ECEF列；不直接支持远程URL。',
 'phaseType':'原阶段类型字符串，端点求解路径未读取。', 'eventType':'原事件类型字符串，端点求解路径未读取。',
 'eventTime':'原事件字面UTC文本，端点求解路径未读取。', 'dynamics':'原动力学字符串，端点求解路径未读取。',
 'directionMode':'原方向模式字符串，端点求解路径未读取。', 'accelerationMs2':'加速度 m/s²，兼容元数据。',
 'deltaVms':'速度增量 m/s，兼容元数据。', 'liftDragRatio':'升阻比，无量纲，兼容字段。', 'dragCdAreaOverMass':'阻力系数面积质量比，兼容字段，端点路径未读取。',
}

OWNER_MEANINGS = {
 ('TargetQueryRequest','maxApproximationDays'):'可空的兼容Long字段；目录查询业务不读取它，不套用配置默认阈值，也不做非负业务校验；仍须满足Long类型绑定。',
 ('TargetFilterBreakdownResponse','belongsHit'):'目录查询或无显式ID目录规划：目标ID/国籍前置目录条件后的数量；显式目标ID快照规划：selectedTargetCount。',
 ('TargetFilterBreakdownResponse','weightHit'):'目录查询或无显式ID目录规划：叠加质量条件后的数量；显式目标ID快照规划：selectedTargetCount。',
 ('TargetFilterBreakdownResponse','epochHit'):'目录查询或无显式ID目录规划：叠加实际目录历元条件后的数量；显式目标ID快照规划：selectedTargetCount。',
 ('TargetFilterBreakdownResponse','epochConstraintHit'):'恒等epochHit的兼容输出，无单独任务时间或发射过滤；显式目标ID快照规划：selectedTargetCount。',
 ('TargetFilterBreakdownResponse','excludeHit'):'目录查询或无显式ID目录规划：名称排除后的候选数量；显式目标ID快照规划：selectedTargetCount。候选数量不等于传播成功数。',
 ('TargetFilterBreakdownResponse','maxApproximationDays'):'目录查询固定0，查询未采用风险阈值；无显式ID目录规划或显式目标ID快照规划为本次实际星历风险阈值（天），0是可用阈值，不表示必然没有风险。',
}
STALE_COMMENT_FIELDS = {('VisibilityPlanResponse','targetFilterBreakdown'),
                        ('TargetQueryRequest','planningStartTime'),('TargetQueryRequest','planningEndTime'),
                        ('TargetQueryRequest','maxApproximationDays')}

def meaning(name, java, owner):
    if (owner,name) in OWNER_MEANINGS: return OWNER_MEANINGS[(owner,name)]
    if name in MEANINGS: return MEANINGS[name]
    if re.fullmatch(r'tack3d_tgt_(pos|vel|acc)_[xyz]', name):
        part = name.split('_')[-2]
        return {'pos':'原102位置编码字符串，每单位0.1 m。','vel':'原102速度编码字符串，每单位0.0001 m/s；缺失沿原零值/估计规则。','acc':'原102加速度编码字符串，每单位0.01 m/s²；缺失沿原零值规则。'}[part]
    if re.fullmatch(r'(launch|impact)_pos_[xyz]', name): return '原物理端点坐标分量字符串，按对应pos_unit及coord_frame转换。'
    if re.fullmatch(r'(launch|impact)Point[XYZ]', name): return '物理端点坐标分量，单位和框架由同前缀Unit/CoordFrame声明；有限数、半径0.75..20倍地球半径。'
    if name in ['launchLatDeg','launchLonDeg','azimuthDeg','elevationDeg']: return '旧兼容角字段 °；当前端点约束路径不以此字段替代端点求解。'
    if name in ['launchHeightM','initialSpeedMs','durationSec','integrationStepSec','outputStepSec','boostDurationSec','boostAccelerationMs2','targetTypeCode','targetShapeCode','flightPhaseCode']: return '旧兼容字段；默认及类型见本行，当前端点求解路径不替代物理端点；outputStepSec是输出间隔的后备。'
    catalog = {'type':'目录原类型码','libraryModelId':'目录关联模型Long主键','scid':'目录SCID数字','objectId':'目录对象代号','objectName':'目录对象名称','noradCatId':'目录NORAD编号Long数字','country':'目录国家编码','period':'目录周期原值，导入单位不在HTTP边界强制换算','catalogInclination':'目录倾角原值，与传播inclination分列','apogee':'目录远地点原值，导入单位不在HTTP边界强制换算','perigee':'目录近地点原值，导入单位不在HTTP边界强制换算','rcsSize':'目录雷达截面分类','rcsValue':'目录雷达截面原值，未知导入量纲不推测','launchDate':'目录发射日期 yyyy-MM-dd','catalogComment':'目录原备注','modelType':'轨道模型类型，原TLE_SGP4/KEPLER_J2等代码','epochTime':'轨道UTC LocalDateTime历元，默认序列化无时区后缀','semiMajorAxis':'传播六根数半长轴 m','inclination':'传播六根数倾角 °','meanAnomaly':'传播六根数平近点角 °','raan':'传播六根数升交点赤经 °','argumentOfPerigee':'传播六根数近地点幅角 °','payloadJson':'持久载荷JSON原文字符串','firstOrbit':'TLE第一行原文','secondOrbit':'TLE第二行原文','createdBy':'目录创建人标识','createBy':'目录创建人标识','updateBy':'目录更新人标识','createOrgId':'目录创建组织标识','updateTime':'目录更新LocalDateTime','longitude':'站点经度 °','latitude':'站点纬度 °','height':'站点高度 m','minElevation':'站点最小仰角 °','maxElevation':'站点最大仰角 °'}
    if name in catalog: return catalog[name]+'。'
    raise ValueError('缺少字段含义：'+owner+'.'+name)

def java_schema(java):
    java = java.replace('java.time.', '')
    if java.startswith('List<'): return arr(java_schema(java[5:-1]))
    if java.startswith('Map<'): return {'type':'object','additionalProperties':{'type':'string'}}
    if java in ['long','Long']: return {'type':'integer','format':'int64'}
    if java in ['int','Integer']: return {'type':'integer','format':'int32'}
    if java in ['double','Double','BigDecimal']: return {'type':'number', **({'format':'double'} if java != 'BigDecimal' else {})}
    if java in ['boolean','Boolean']: return {'type':'boolean'}
    if java == 'String': return {'type':'string'}
    if java == 'Instant': return {'type':'string','format':'date-time'}
    if java == 'LocalDate': return {'type':'string','format':'date'}
    if java == 'LocalDateTime': return {'type':'string','pattern':r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?$','description':'UTC LocalDateTime无Z，无偏移。'}
    if java.endswith('JsonNode'): return {'allOf':[ref('JsonValue')]}
    return ref(java.split('.')[-1])

REQUEST_NAMES = {'SpacePayloadConfigurationRequest','PayloadProfileRequest','VisibilityTaskResultRemarkRequest','VisibilityTaskResultSelectionRequest','TargetQueryRequest','DownlinkPlanRequest','StationConstraintRequest','SatelliteStationLinkRequest','VisibilityPlanRequest','IlluminationConstraintRequest','ObservationConstraintRequest','RegisterResultRequest','VisibilityRequest','BallisticDef','SatelliteDef','PayloadDef','FitRequest','Package102Record','CoordinateInfo','TargetInfo','SimulationRequest','RetimeTrajectoryRequest','PhaseEvent'}
required_by = {'PayloadProfileRequest':['payloadType','minDistanceKm','maxDistanceKm','angleRateDegPerSec','sunAvoidHalfAngleDeg'],
 'SpacePayloadConfigurationRequest':['angleRateDegPerSec','sunAvoidHalfAngleDeg'],
 'DownlinkPlanRequest':['startTime','endTime'], 'VisibilityPlanRequest':['startTime','endTime','observerSatelliteIds'],
 'StationConstraintRequest':['stationId'], 'SatelliteStationLinkRequest':['satelliteId','stationId'],
 'ObservationConstraintRequest':['satelliteIds'], 'RegisterResultRequest':['csvPath'], 'BallisticDef':['csvPath'],
 'FitRequest':['package102'], 'SimulationRequest':['launchTimeUtc'], 'RetimeTrajectoryRequest':['trajectoryCsvPath','launchTimeUtc'],
 'VisibilityTaskResultSelectionRequest':['selections']}
skip = {'PageResponse','TrajectorySample','FittedPoint','FittedTrajectory','VisibilityTaskResultEntity'}
for owner, info in CLASSES.items():
    if owner in skip: continue
    fields = {}
    for name, field in info['fields'].items():
        if owner == 'PayloadDef' and name == 'standard': continue
        s = java_schema(field['java'])
        if '$ref' in s: s = {'type':'object','allOf':[s]}
        s['description'] = meaning(name, field['java'], owner)
        if (owner,name) in STALE_COMMENT_FIELDS:
            # 这些原 Javadoc 已与查询兼容绑定或现行风险清单/传播路径矛盾；只修公开合同。
            # 原产品源码只读，extract 与 source-inventory 继续保留实际原注释，避免篡改来源证据。
            s['x-source-comment-status']='原 Javadoc 为历史过期说明，未作为本字段公开合同；原文保留在 source-inventory.json。'
        elif field.get('description'):
            s['description'] += ' 源注释：'+re.sub(r'\{@[^}]*\}|<[^>]+>', '', field['description'])
        s['x-source'] = info['source']+':'+str(field['line'])
        if field.get('omit'): s['description'] += ' '+field['omit']+'。'
        # nullable描述可反序列化/返回null；必填校验的数值排除null，其它引用类型保持原空语义。
        if field['java'] not in ['double','long','int','boolean'] and name not in required_by.get(owner, []): s['nullable'] = True
        if owner not in REQUEST_NAMES and field['java'] not in ['double','long','int','boolean']: s['nullable'] = True
        expr = field['defaultExpr']
        if owner in REQUEST_NAMES:
            if expr:
                if 'ArrayList' in expr: s['default'] = []
                elif expr.startswith('"'): s['default'] = json.loads(expr)
                elif expr == 'OrbitCalculator.DEFAULT_SUN_EXCLUSION_ANGLE_DEG': s['default'] = 45
                elif re.fullmatch(r'-?[\d.]+[Ld]?',expr): s['default'] = float(expr[:-1] if expr.endswith(('L','d')) else expr) if '.' in expr else int(expr.rstrip('L'))
            elif field['java'] in ['double','long','int']: s['default'] = 0
            elif field['java'] == 'boolean': s['default'] = False
        fields[name] = s
        if owner in ['FitRequest','SimulationRequest','RetimeTrajectoryRequest'] and name == 'outputDir':
            # Controller 判断原 JSON 字段存在性；补默认点目录会把 HTTP 缺失改成显式旧目录分支。
            s.pop('default',None)
            s['x-java-initial-value']='.'
            s['x-http-omission-behavior']='缺失时分配管理 results 独占目录；显式传值按旧语义。'
            s['description']+=' 以原 JSON 字段存在性切换分支，Java 内部初始化为点目录，不声明为 HTTP 默认值。'
    if info['parent'] and info['parent'] in CLASSES:
        own_required=required_by.get(owner, []) if owner in REQUEST_NAMES else [n for n,f in info['fields'].items() if n in fields and not f.get('omit')]
        SCHEMAS[owner] = {'type':'object','allOf':[ref(info['parent']),obj(fields, own_required)], 'x-source':info['source']}
    else:
        required = required_by.get(owner, []) if owner in REQUEST_NAMES else [n for n, f in info['fields'].items() if n in fields and not f.get('omit')]
        SCHEMAS[owner] = obj(fields, required)
        SCHEMAS[owner]['x-source'] = info['source']

SCHEMAS['JsonValue'] = {'description':'后端JsonNode透明保存的任意JSON值。这里只是实际开放合同；推荐前端嵌套结构另见SelectionTarget/SelectionMergedTimeRange，不假装服务器强制其字段。',
 'anyOf':[{'type':'string','nullable':True},{'type':'number'},{'type':'boolean'},arr(ref('JsonValue')),{'type':'object','additionalProperties':ref('JsonValue')}]}

def properties(name):
    s = SCHEMAS[name]
    if 'allOf' in s and 'properties' not in s:
        return s['allOf'][-1]['properties']
    return s['properties']

for owner in ['PayloadProfileRequest','PayloadProfileResponse','PayloadProfilesResponse','SpacePayloadConfigurationResponse']:
    field = 'activePayloadType' if owner=='PayloadProfilesResponse' else 'payloadType'
    properties(owner)[field]['enum'] = ['SPACE','GROUND'] if owner != 'SpacePayloadConfigurationResponse' else ['SPACE']
for owner in ['PlanningTaskStatusResponse','VisibilityTaskResultSummaryResponse']:
    properties(owner)['status']['enum'] = ['PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED']
for owner in ['ObserverIllumination','TargetIllumination','SatelliteSegment']:
    properties(owner)['state']['enum'] = ['SUNLIT','PENUMBRA','UMBRA']
properties('IlluminationConstraintRequest')['mode']['enum']=['ANY','TARGET_SUNLIT','OBSERVER_SHADOW_TARGET_SUNLIT','BOTH_SHADOW','CUSTOM']
for key in ['observerStates','targetStates']:
    properties('IlluminationConstraintRequest')[key]['items']['enum']=['SUNLIT','PENUMBRA','UMBRA']
properties('VisibilityRequest')['trajectoryPointOutput']['enum'] = [0,1]
properties('VisibilityRequest')['stepSizeSec']['minimum']=1
properties('VisibilityRequest')['numCalculations']['minimum']=1
for owner in ['TargetQueryRequest']:
    properties(owner)['pageNumber']['minimum']=1
    properties(owner)['pageSize'].update(minimum=1,maximum=200)
for owner in ['PayloadProfileRequest']:
    for field in ['minDistanceKm','maxDistanceKm']: properties(owner)[field]['minimum']=0
    properties(owner)['sunAvoidHalfAngleDeg'].update(minimum=0,maximum=180)
properties('VisibilityPlanRequest')['observerSatelliteIds']['minItems']=1
properties('ObservationConstraintRequest')['satelliteIds']['minItems']=1
properties('VisibilityTaskResultSelectionRequest')['selections'] = {'type':'array','minItems':1,'items':ref('JsonValue'),'description':MEANINGS['selections']}
for name,item in [('SatellitePage','SatelliteSummaryResponse'),('TaskResultPage','VisibilityTaskResultSummaryResponse')]:
    SCHEMAS[name]=obj({'total':{'type':'integer','format':'int64','description':'筛选总数。'},'pageNumber':{'type':'integer','format':'int64','description':'当前页码。'},'pageSize':{'type':'integer','format':'int64','description':'每页数量。'},'records':arr(ref(item))},['total','pageNumber','pageSize','records'])
SCHEMAS['Health']=obj({'status':{'type':'string','enum':['UP'],'description':'服务进程轻量健康状态，不访问数据库。'}},['status'])
SCHEMAS['TaskAccepted']=obj({'taskId':{'type':'string','description':MEANINGS['taskId']}},['taskId'])
SCHEMAS['Defaults']=obj({'preGuardSec':{'type':'number','default':300,'description':MEANINGS['preGuardSec']},'postGuardSec':{'type':'number','default':0,'description':MEANINGS['postGuardSec']},'allowedGapSec':{'type':'number','default':0,'description':MEANINGS['allowedGapSec']},'orbitMaxApproximationDays':{'type':'integer','format':'int64','default':14,'description':'建议近似传播天数，可配置。'},'targetMinTleEpochCode':{'type':'string','default':'23340','description':'默认TLE历元YYDDD，可配置。'}},['preGuardSec','postGuardSec','allowedGapSec','orbitMaxApproximationDays','targetMinTleEpochCode'])
SCHEMAS['CodeError']=obj({'code':{'type':'string','description':'INVALID_ARGUMENT/INTERNAL_ERROR或文件错误代码，按原边界输出。'},'error':{'type':'string','description':'原中文错误信息。'},'requestId':{'type':'string','format':'uuid','description':'只在兜底500提供的追踪号。'}},['code','error'])
SCHEMAS['LegacyError']=obj({'error':{'type':'string','description':'原describeFailure或窗口手写错误文本。'}},['error'])
SCHEMAS['StandardPayloadRequest']=copy.deepcopy(SCHEMAS['PayloadDef'])
SCHEMAS['StandardPayloadRequest']['required']=['payloadType','minDistanceKm','maxDistanceKm','angleRateDegPerSec','sunAvoidHalfAngleDeg']
properties('StandardPayloadRequest')['payloadType'].update(enum=['SPACE','GROUND'])
properties('StandardPayloadRequest')['payloadType'].pop('default',None)
# preview 强制标准类型；宽兼容 PayloadDef 的旧中文分支不读取标准数值，不能无条件施加范围。
for field in ['minDistanceKm','maxDistanceKm']: properties('StandardPayloadRequest')[field]['minimum']=0
properties('StandardPayloadRequest')['sunAvoidHalfAngleDeg'].update(minimum=0,maximum=180)
SCHEMAS['PayloadDef']['description']='兼容宽 DTO：payloadType 去空白忽略大小写为 SPACE/GROUND 时才进入标准分支，标准必填、范围及跨字段校验由 toStandardConstraint 执行；旧中文四类走原 forPayload，仅读取 payloadType/maxDetectRangeKm/sunExclusionAngleDeg，标准字段的无关负值可绑定但被忽略。本共享 schema 不能自动覆盖按类型的全部标准分支约束；严格标准入口另见 StandardPayloadRequest。'
for field in ['minDistanceKm','maxDistanceKm','sunAvoidHalfAngleDeg']:
    properties('PayloadDef')[field]['description']+=' 范围仅对 SPACE/GROUND 标准分支生效；旧中文分支不读取该字段，兼容 DTO 不无条件声明此范围。'
for name in ['maxDetectRangeKm','sunExclusionAngleDeg']:
    properties('StandardPayloadRequest')[name]['description']+=' 此preview入口仍绑定完整PayloadDef，允许发送该旧兼容字段，但标准校验及12字段回显不读取它。'
SCHEMAS['StandardPayloadResponse']=copy.deepcopy(SCHEMAS['StandardPayloadRequest'])
for name in ['maxDetectRangeKm','sunExclusionAngleDeg']: properties('StandardPayloadResponse').pop(name)
SCHEMAS['StandardPayloadResponse'].pop('required',None)
for s in properties('StandardPayloadResponse').values(): s.pop('default',None)

# 手写窗口JSON的实际公开字段；不能以内部Java字段或默认Jackson形状替代它。
def nums(names): return {n:{'type':'number','format':'double','nullable':True,'description':('原UTC日历J2000秒。' if 'J2000' in n else '原时长秒/角度/公里数值，字段单位见名称；非有限值按手写JSON输出null。')} for n in names.split()}
def strings(names): return {n:{'type':'string','description':MEANINGS.get(n, '原字面UTC文本。' if 'Time' in n or 'UTC' in n or 'Utc' in n else '原字符串身份或显示名。')} for n in names.split()}
def ints(names): return {n:{'type':'integer','format':'int64','description':'原整数计数或索引，数组顺序不变。'} for n in names.split()}
SCHEMAS['VisibilityConstraintSummary']=obj({**strings('payloadType'),**nums('maxDetectRangeKm sunExclusionAngleDeg simpleConeHalfAngleDeg halfConeAngleDeg innerConeAngleDeg outerConeAngleDeg')},'payloadType maxDetectRangeKm sunExclusionAngleDeg simpleConeHalfAngleDeg halfConeAngleDeg innerConeAngleDeg outerConeAngleDeg'.split(), '旧摘要：标准载荷只映射类型/最远距离/太阳半角，四个锥角显式null；最小距离/姿态/角速度/startup没有旧摘要字段，完整配置另用preview。')
timeprops={**strings('startTime endTime'),**nums('startJ2000Sec endJ2000Sec durationSeconds')}
SCHEMAS['BallisticTimeWindow']=obj(timeprops,list(timeprops),'最早至最晚包络，可包含不可见间隔；无可见点时整个对象null。')
SCHEMAS['VisibleWindowSummary']=obj({**timeprops,**strings('satelliteId satelliteName payloadId payloadName payloadType'),**ints('satelliteIndex arcIndex pointCount')})
SCHEMAS['VisibleTrajectoryPoint']=obj({**nums('longitudeDeg latitudeDeg heightM absoluteTimeJ2000Sec relativeTimeSec'),**strings('absoluteTimeUtc sourceTrajectoryUtc')})
for n,s in properties('VisibleTrajectoryPoint').items():
    s['description']={'longitudeDeg':'经度 °。','latitudeDeg':'纬度 °。','heightM':'高度 m。','absoluteTimeJ2000Sec':'当前点原UTC日历J2000秒。','relativeTimeSec':'相对输入文件参考历元秒。','absoluteTimeUtc':'偏移后的字面UTC毫秒文本。','sourceTrajectoryUtc':'输入原文件对应点字面UTC毫秒文本。'}[n]
SCHEMAS['VisibleArc']=obj({**timeprops,**ints('arcIndex pointCount'),**strings('payloadId payloadName payloadType'),'trajectoryPoints':arr(ref('VisibleTrajectoryPoint'))})
SCHEMAS['PayloadTargetResult']=obj({**strings('targetId targetName payloadId payloadName payloadType'),'visibleArcs':arr(ref('VisibleArc'))})
nullable_window={'type':'object','allOf':[ref('BallisticTimeWindow')],'nullable':True}
SCHEMAS['SatellitePayloadResult']=obj({**strings('payloadId payloadName payloadType'),'visibilityConstraint':ref('VisibilityConstraintSummary'),'finalVisibleWindow':nullable_window,'targets':arr(ref('PayloadTargetResult'))})
SCHEMAS['BallisticSatelliteResult']=obj({**strings('satelliteId satelliteName'),**ints('satelliteIndex'),'finalVisibleWindow':nullable_window,'payloadResults':arr(ref('SatellitePayloadResult'))})
SCHEMAS['BallisticTargetSummary']=obj({**strings('targetId targetName csvPath baseLaunchUTC launchUTC payloadType'),**nums('launchTimeJ2000Sec'),'finalVisibleWindow':nullable_window,'visibleWindows':arr(ref('VisibleWindowSummary'))})
SCHEMAS['LaunchOffsetResult']=obj({**ints('offsetSecond visibleArcCount visiblePointCount'),**strings('launchTimeUtc payloadType'),**nums('launchTimeJ2000Sec visibleDurationSec'),'visibilityConstraint':ref('VisibilityConstraintSummary'),'finalVisibleWindow':nullable_window,'targets':arr(ref('BallisticTargetSummary')),'visibleSatellites':arr(ref('BallisticSatelliteResult'))})
SCHEMAS['LaunchSearchResult']=obj({**strings('referenceUtc payloadType'),'visibilityConstraint':ref('VisibilityConstraintSummary'),'best':{'type':'object','allOf':[ref('LaunchOffsetResult')],'nullable':True},'allResults':arr(ref('LaunchOffsetResult'))},['referenceUtc','payloadType','visibilityConstraint','best','allResults'], 'allResults按startOffsetSec+i*stepSizeSec输入顺序；best按累计double时长严格>更新，并列保留首次。逐载荷弧段时长相加不去交叠。')
for name in ['VisibleWindowSummary','VisibleTrajectoryPoint','VisibleArc','PayloadTargetResult','SatellitePayloadResult','BallisticSatelliteResult','BallisticTargetSummary','LaunchOffsetResult']:
    SCHEMAS[name]['required']=list(properties(name))

SCHEMAS['SelectionSatellite']=obj({**strings('satelliteId satelliteName startTime endTime'),**nums('durationSec distanceMinKm distanceMaxKm azimuthRelativeMinusXMinDeg azimuthRelativeMinusXMaxDeg elevationZenithMinDeg elevationZenithMaxDeg maxAngleRateDegPerSec')},'satelliteId satelliteName startTime endTime durationSec'.split(),'Vue确认选择推荐结构；服务器透明保存，不强制每项字段。')
SCHEMAS['SelectionTarget']=obj({**strings('targetId targetName noradCatId firstOrbitLine secondOrbitLine combination'),**nums('weightKg totalDurationSec'),'mergedWindows':arr(ref('WindowResponse')),'satellites':arr(ref('SelectionSatellite'))},'targetId targetName combination mergedWindows totalDurationSec satellites'.split(),'Vue逐目标推荐结构，源码frontend/src/api/types.ts。')
SCHEMAS['SelectionMergedTimeRange']=obj({'segments':arr(ref('WindowResponse')),'totalDurationSec':{'type':'number','description':'前端合并弧段累计秒数。'}},['segments','totalDurationSec'],'Vue推荐结构；不表示服务器重算或强制校验。')
properties('Defaults')['orbitMaxApproximationDays'].update(type='number',format='double')
properties('Preview')['metadata']=obj({name:{'type':'string','enum':[value],'description':description} for name,value,description in [('timeScale','UTC','原20列时间尺度。'),('positionUnit','m','位置单位米。'),('velocityUnit','m/s','速度单位米每秒。'),('frames','ECI,ECEF','原坐标框架。'),('dtUnit','ms','dt毫秒。')]},description=MEANINGS['metadata'])
properties('VisibilityTaskResultRemarkRequest')['remark'].update(maxLength=512)
for owner in ['TargetQueryRequest','VisibilityPlanRequest']:
    for key in ['minWeight','maxWeight']: properties(owner)[key]['minimum']=0
properties('VisibilityPlanRequest')['maxApproximationDays']['minimum']=0
properties('VisibilityTaskResultSelectionRequest')['remark']['description']='可选备注，随selectionJson原文保存；当前代码未把它持久更新到记录remark，记录备注请调用单独PUT备注接口。'
for owner in ['DownlinkPlanRequest','VisibilityPlanRequest']:
    for key in ['coarseStepSec','fineStepSec']: properties(owner)[key]['minimum']=0.001
properties('SatelliteDef')['epochUtc']['description']+=' HTTP没有epochSec字段。'
properties('VisibilityRequest')['orbitModel']['description']+=' 原中文显示名为二体Kepler/J2长期摄动/J2数值积分RK4；含RK4/NUMERICAL/J2_NUM→J2_NUMERICAL，含SECULAR/J2_SEC→J2_SECULAR，含TWO/KEPLER/2BODY及其余未知文本→TWO_BODY。'
SCHEMAS['DownlinkPlanRequest']['required'] += ['satelliteIds','stations','links']
for key in ['satelliteIds','stations','links']:
    properties('DownlinkPlanRequest')[key]['minItems']=1
    properties('DownlinkPlanRequest')[key].pop('nullable',None)
properties('FitRequest')['package102']['minItems']=2
for owner in ['DownlinkPlanRequest','VisibilityPlanRequest','ObservationConstraintRequest','FitRequest']:
    for field in properties(owner).values():
        if field.get('default')==[] and field.get('minItems',0)>0:
            field.pop('default')
            field['description']+=' DTO初始化为空数组，但业务强制非空，省略会失败，不把空数组声明为有效默认请求。'
for owner in ['PayloadProfileRequest','StandardPayloadRequest']:
    properties(owner)['payloadType'].pop('enum',None)
    properties(owner)['payloadType']['pattern']=r'^\s*([Ss][Pp][Aa][Cc][Ee]|[Gg][Rr][Oo][Uu][Nn][Dd])\s*$'
    properties(owner)['payloadType']['x-canonical-values']=['SPACE','GROUND']
properties('IlluminationConstraintRequest')['mode'].pop('enum',None)
properties('IlluminationConstraintRequest')['mode']['x-canonical-values']=['ANY','TARGET_SUNLIT','OBSERVER_SHADOW_TARGET_SUNLIT','BOTH_SHADOW','CUSTOM']
for key in ['observerStates','targetStates']:
    properties('IlluminationConstraintRequest')[key]['items'].pop('enum',None)
    properties('IlluminationConstraintRequest')[key]['items']['description']='去空白、忽略大小写解析SUNLIT/PENUMBRA/UMBRA。'
for owner in ['PayloadProfileRequest','StandardPayloadRequest']:
    for key in owner and required_by.get('PayloadProfileRequest',[]):
        if key in properties(owner): properties(owner)[key].pop('nullable',None)
SCHEMAS['StandardPayloadResponse']['required']=list(properties('StandardPayloadResponse'))
for key in ['payloadId','payloadName','azimuthMinDeg','azimuthMaxDeg','elevationMinDeg','elevationMaxDeg','startupAngleRateDegPerSec']:
    properties('StandardPayloadResponse')[key]['nullable']=True

S='/api/v1/space'
B='/api/v1/ballistic'
# 每个映射明确请求及响应组件；不存在以any对象替代业务响应的路由。
ROUTES=[
 ('GET',S+'/health','空间健康检查',None,'Health'),
 ('GET',S+'/satellites','完整观测星目录',None,'SatelliteEntity[]'),
 ('GET',S+'/satellites/page','观测星分页目录',None,'SatellitePage'),
 ('GET',S+'/satellites/options','观测星轻量选项',None,'SatelliteOptionResponse[]'),
 ('GET',S+'/satellites/{satelliteId}/payload','读取旧对空配置',None,'SpacePayloadConfigurationResponse'),
 ('PUT',S+'/satellites/{satelliteId}/payload','保存旧对空配置','SpacePayloadConfigurationRequest','SpacePayloadConfigurationResponse'),
 ('GET',S+'/satellites/{satelliteId}/payload-profiles','读取按类独立载荷配置',None,'PayloadProfilesResponse'),
 ('PUT',S+'/satellites/{satelliteId}/payload-profiles','保存标准配置并启用类型','PayloadProfileRequest','PayloadProfilesResponse'),
 ('GET',S+'/stations','完整地面站目录',None,'StationEntity[]'),
 ('GET',S+'/defaults','读取规划配置默认值',None,'Defaults'),
 ('POST',S+'/targets/query','筛选并分页目标目录','TargetQueryRequest','TargetQueryResponse'),
 ('GET',S+'/targets/belong-options','目录实际国籍编码选项',None,'BelongOptionResponse[]'),
 ('POST',S+'/downlink/plan','站星链路稳定窗口及覆盖并集','DownlinkPlanRequest','DownlinkPlanResponse'),
 ('POST',S+'/downlink/validate-window','完整时段下传覆盖判定','DownlinkPlanRequest','WindowValidationResponse'),
 ('POST',S+'/visibility/plan','同步观测规划','VisibilityPlanRequest','VisibilityPlanResponse'),
 ('POST',S+'/visibility/tasks','受理异步观测规划','VisibilityPlanRequest','TaskAccepted'),
 ('GET',S+'/visibility/tasks/{taskId}','查询异步状态与进度',None,'PlanningTaskStatusResponse'),
 ('GET',S+'/visibility/tasks/{taskId}/result','查询成功任务完整结果',None,'VisibilityPlanResponse'),
 ('DELETE',S+'/visibility/tasks/{taskId}','受理协作取消并回传真实状态',None,'PlanningTaskStatusResponse'),
 ('GET',S+'/visibility/task-results','持久记录分页',None,'TaskResultPage'),
 ('GET',S+'/visibility/task-results/{id}','持久记录完整明细',None,'VisibilityTaskResultDetailResponse'),
 ('PUT',S+'/visibility/task-results/{id}','更新备注或null清空','VisibilityTaskResultRemarkRequest','VisibilityTaskResultDetailResponse'),
 ('PUT',S+'/visibility/task-results/{id}/selection','保存确认选择原文并返回回执','VisibilityTaskResultSelectionRequest','VisibilityTaskResultSelectionResponse'),
 ('DELETE',S+'/visibility/task-results/{id}','删除持久记录',None,None),
 ('POST',B+'/files','上传受管理文件','MultipartUpload','FileReference'),
 ('POST',B+'/files/register-result','登记管理结果根现有文件','RegisterResultRequest','FileReference'),
 ('GET',B+'/files/{fileId}/preview','原表头原单元格分页预览',None,'Preview'),
 ('GET',B+'/files/{fileId}/download','下载原文件字节',None,'binary'),
 ('POST',B+'/payloads/preview','校验并回显标准载荷','StandardPayloadRequest','StandardPayloadResponse'),
 ('GET','/api/orbit/health','原窗口纯文本健康检查',None,'text'),
 ('POST','/api/orbit/visibility/search','多目标多星多载荷偏移搜索','VisibilityRequest','LaunchSearchResult'),
 ('GET','/api/trajectory/health','原轨迹纯文本健康检查',None,'text'),
 ('POST','/api/trajectory/fit/package102','原102观测拟合并写CSV','FitRequest','FitRunResult'),
 ('POST','/api/trajectory/simulate','原端点约束模拟并写CSV','SimulationRequest','FitRunResult'),
 ('POST','/api/trajectory/retime','保留ECEF轨迹重定时并写CSV','RetimeTrajectoryRequest','FitRunResult')]
SCHEMAS['MultipartUpload']=obj({'file':{'type':'string','format':'binary','description':'multipart字段file必填，原始字节；单文件上限默认67108864B，空文件沿原上传规则。'},'purpose':{'type':'string','nullable':True,'description':'可选用途文本，仅登记关联，不选择算法。'}},['file'])

summary=json.loads(read(ROOT/'verification/A01/final/http-extended-summary.json'))
EVENTS=[e for phase in summary['phases'].values() for e in phase.get('events',[])]
evidence_root=ROOT/'verification/runtime/A01/final-candidate/http-extended'
BYTE_FILES={}
for p in evidence_root.rglob('*response.bin'):
    BYTE_FILES.setdefault(hashlib.sha256(p.read_bytes()).hexdigest(),[]).append(p)

def load_event(event):
    paths=BYTE_FILES.get(event['sha256'],[])
    if not paths: raise ValueError('A01原字节缺失 '+event['name'])
    candidates=[p for p in paths if event['name'] in p.name]
    p=(candidates or paths)[0]
    data=p.read_bytes()
    response=json.loads(data.decode('utf-8')) if 'json' in event.get('headers',{}).get('Content-Type','') and data else (data.decode('utf-8') if data == b'OK' else None)
    request_path=p.with_name(p.name.replace('-response.bin','-request.bin'))
    request=None
    if request_path.exists():
        try: request=json.loads(request_path.read_bytes().decode('utf-8'))
        except (ValueError,UnicodeError): pass
    return {'event':event,'response':response,'request':request,'responseSource':p.relative_to(ROOT).as_posix(),
            'requestSource':request_path.relative_to(ROOT).as_posix() if request_path.exists() else None}

EXAMPLES={}
for method,path,title,req,res in ROUTES:
    key=method+' '+path
    examples=[]
    for status in sorted({e['status'] for e in EVENTS if e.get('route')==key}):
        matching=[e for e in EVENTS if e.get('route')==key and e['status']==status]
        # 最小真实响应保持完整；大型CSV二进制仅保存SHA/字节长度，不复制生产式运行结果。
        chosen=next((e for e in matching if key=='POST /api/orbit/visibility/search' and e['name']=='window-legacy'),min(matching,key=lambda e:e['bytes']))
        examples.append(load_event(chosen))
    EXAMPLES[key]=examples

def schema_for(name):
    return arr(ref(name[:-2])) if name.endswith('[]') else ref(name)

def param(name,where,kind,description,default=None,minimum=None,maximum=None):
    schema={'type':kind}
    if kind=='integer': schema['format']='int64'
    if default is not None: schema['default']=default
    if minimum is not None: schema['minimum']=minimum
    if maximum is not None: schema['maximum']=maximum
    return {'name':name,'in':where,'required':where=='path','schema':schema,'description':description}

PATHS={}
for index,(method,path,title,req,res) in enumerate(ROUTES,1):
    key=method+' '+path
    operation={'operationId':'operation'+str(index).zfill(2),'summary':title,'tags':['空间' if path.startswith(S) else '弹道辅助' if path.startswith(B) else '弹道原计算'],
               'description':'真实活动HTTP合同。请求/响应完整字段见组件；以下example来自A01非生产隔离验收，动态ID/服务器路径已过期，不可直接复用。', 'responses':{},'x-a01-statuses':[x['event']['status'] for x in EXAMPLES[key]]}
    parameters=[]
    for name in re.findall(r'\{(\w+)\}',path):
        kind='integer' if name=='satelliteId' else 'string'
        description={'satelliteId':'空间卫星Long数字主键，未知400。','taskId':'当次受理返回的任务字符串ID，未知404；内存缓存重启丢失。','id':'持久记录主键十进制文本，Controller实际Long绑定；合法未知数字404，非数字原500，不改写为404。','fileId':'当次上传或登记201取得的不透明ID，未知/过期404。'}[name]
        p=param(name,'path',kind,description)
        if name=='id': p['schema']['pattern']=r'^[+-]?\d+$'
        parameters.append(p)
    if path==S+'/satellites/page' or path==S+'/visibility/task-results':
        parameters += [param('pageNumber','query','integer','页码1起。',1,1),param('pageSize','query','integer','每页1..200。',50 if path.endswith('satellites/page') else 20,1,200),param('keyword','query','string','卫星名/目录信息或任务ID/备注关键字，可空。')]
        if path.endswith('task-results'): parameters.append(param('status','query','string','PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED，空白不限；非法400。'))
    if path.endswith('/preview') and '{fileId}' in path:
        parameters += [param('page','query','integer','页码≥1。',1,1),param('limit','query','integer','1..可配置preview-max-rows（默认500），默认50。',50,1)]
    if parameters: operation['parameters']=parameters
    if req:
        content_type='multipart/form-data' if req=='MultipartUpload' else 'application/json'
        media={'schema':ref(req)}
        good=next(x for x in EXAMPLES[key] if x['event']['status']<300)
        if good['request'] is not None: media['example']=good['request']
        operation['requestBody']={'required':True,'content':{content_type:media}}
    success='201' if path in [B+'/files',B+'/files/register-result'] else '200'
    if res is None: response={'description':'删除成功空体，不返回伪造对象。'}
    elif res=='text': response={'description':'原纯文本OK。','content':{'text/plain':{'schema':{'type':'string'},'example':'OK'}}}
    elif res=='binary': response={'description':'原二进制；Content-Type取已登记的合法contentType，支持text/plain及Excel等动态媒体类型，以*/*范围覆盖；原字节不经CSV重写。','headers':{'Content-Disposition':{'description':'attachment; filename ASCII fallback及UTF-8 filename*。','schema':{'type':'string'}},'Content-Length':{'description':'租约获取时校验的字节数。','schema':{'type':'integer','format':'int64'}},'X-Content-Type-Options':{'schema':{'type':'string','enum':['nosniff']}}},'content':{'application/octet-stream':{'schema':{'type':'string','format':'binary'}},'text/csv':{'schema':{'type':'string','format':'binary'}},'*/*':{'schema':{'type':'string','format':'binary'}}}}
    else:
        good=next(x for x in EXAMPLES[key] if x['event']['status']<300)
        response={'description':'原成功结构；普通可空引用保留null，仅明确NON_NULL/NON_EMPTY字段省略；手写窗口null保留。','content':{'application/json':{'schema':schema_for(res),'example':good['response']}}}
    operation['responses'][success]=response
    statuses={x['event']['status'] for x in EXAMPLES[key] if x['event']['status']>=400}
    # 代码保证的错误边界和A01实测状态分开描述；没有实测的状态不伪造example。
    if path.startswith(S) and not path.endswith('/health'):
        statuses|={500}
        if req or parameters: statuses.add(400)
    elif path.startswith(B): statuses|={400,500}
    elif req: statuses|={400,500}
    if '{taskId}' in path or '/task-results/{id}' in path or '{fileId}' in path: statuses.add(404)
    if path.endswith('/result') and '{taskId}' in path: statuses.add(409)
    if path.startswith(B+'/files'):
        statuses.add(413)
        if path.endswith('/register-result'): statuses.add(404)
    for status in sorted(statuses):
        sampled=next((x for x in EXAMPLES[key] if x['event']['status']==status),None)
        if status==404 and path.startswith(S): err={'description':'未知任务/合法数字记录ID，404空体。'}
        else:
            error_schema='LegacyError' if status==409 or path.startswith('/api/orbit/') or path.startswith('/api/trajectory/') else 'CodeError'
            description={400:'参数错误，保留本路由原异常边界。',404:'未知/过期文件引用，原文件错误对象。',409:'任务尚未SUCCEEDED：PENDING/RUNNING/FAILED/CANCELLED均409；仅error字段。',413:'超过文件或multipart字节上限。',500:'原未预期异常或绑定失败；空间非法Instant、非数字BIGINT记录ID保持原500。'}[status]
            if path.startswith('/api/trajectory/') or path.startswith('/api/orbit/'):
                schema={'anyOf':[ref(error_schema),ref('CodeError')]} if status==500 else ref(error_schema)
                description+=' 计算内部错误为error；MVC绑定异常可走全局code/error/requestId，流式传输已提交后不保证JSON错误体。'
            else: schema=ref(error_schema)
            media={'schema':schema}
            if sampled and sampled['response'] is not None: media['example']=sampled['response']
            err={'description':description+(' A01该状态有实际证据。' if sampled else ' 由代码异常边界声明，本项未补跑HTTP。'),'content':{'application/json':media}}
        operation['responses'][str(status)]=err
    if '{taskId}' in path:
        operation['description']+=' DELETE 200可仍为RUNNING，只表示受理协作取消，须继续轮询CANCELLED；成功先完成时保留SUCCEEDED。任务缓存TTL默认2小时，持久记录独立。'
    if req in ['FitRequest','SimulationRequest','RetimeTrajectoryRequest']:
        operation['description']+=' outputDir字段缺失才分配管理results独占目录；显式null/空白/点目录仍旧兼容；成功自动登记CSV，失败只回滚自己独占目录。'
    operation['x-a01-example-sources']=[{'status':x['event']['status'],'sha256':x['event']['sha256'],'source':x['responseSource'],'requestSource':x['requestSource'],'actualPath':x['event']['path']} for x in EXAMPLES[key]]
    PATHS.setdefault(path,{})[method.lower()]=operation

def normalize_nullable(value):
    # OAS3.0.3的nullable只能放在同层显式type上；allOf所引用非空类型仍会拒绝null。
    # 因此可空对象引用使用规范anyOf的独立“仅null”对象分支，不把nullable放在$ref旁。
    if isinstance(value,dict):
        for child in list(value.values()): normalize_nullable(child)
        if value.get('nullable') and 'enum' in value and None not in value['enum']:
            value['enum'].append(None)
        if value.get('nullable') and 'allOf' in value:
            original={'allOf':value.pop('allOf')}
            value.pop('nullable',None)
            value.pop('type',None)
            value['anyOf']=[original,{'type':'object','nullable':True,'enum':[None]}]
    elif isinstance(value,list):
        for child in value: normalize_nullable(child)
normalize_nullable(SCHEMAS)

OPENAPI={'openapi':'3.0.3','info':{'title':'空间与弹道目标可见性完整HTTP合同','version':'A02-ba5821a','description':'当前正式扫描35活动操作：24空间+6原弹道+5辅助。Java8/Spring Boot2.7.18/Orekit12.0。A01实际HTTP与源码交叉核验；无统一包装、无新增身份认证。'},'servers':[{'url':'http://127.0.0.1:8080','description':'本机隔离demo示例；生产由独立同源代理部署配置决定。'}],'paths':PATHS,'components':{'schemas':SCHEMAS}}

def permits_null(s):
    if '$ref' in s: return permits_null(SCHEMAS[s['$ref'].rsplit('/',1)[1]]) if s['$ref'].rsplit('/',1)[1]=='JsonValue' else False
    if s.get('nullable'): return True
    if 'anyOf' in s: return any(permits_null(x) for x in s['anyOf'])
    if 'allOf' in s: return all(permits_null(x) for x in s['allOf'])
    return False

def display_type(schema):
    if schema.get('enum')==[None]: return 'null'
    if '$ref' in schema: return '['+schema['$ref'].rsplit('/',1)[1]+'](#'+schema['$ref'].rsplit('/',1)[1].lower()+')'
    if 'allOf' in schema: return ' + '.join(display_type(s) for s in schema['allOf'] if s.get('$ref')) or 'object'
    if 'anyOf' in schema: return ' / '.join(display_type(s) for s in schema['anyOf'])
    if schema.get('type')=='array': return display_type(schema['items'])+'[]'
    return schema.get('type','JSON') + (' ('+schema['format']+')' if schema.get('format') else '')

def excerpt(value):
    # 人读文档提供完整字段首项摘录，不把投影冒称完整HTTP体；完整真实值保存在examples.json。
    if isinstance(value,dict): return {k:excerpt(v) for k,v in value.items()}
    if isinstance(value,list): return [excerpt(value[0])] if value else []
    if isinstance(value,str) and len(value)>1200: return value[:1200]+'…（字符串展示截取；完整原文见examples.json）'
    return value

INTRO=r'''# 空间与弹道目标可见性完整接口文档（A02）

本文对应基准 `ba5821a692e6a60e6cd9148ec88999cd08c339d2`，以正式扫描 Controller、DTO/getter、Jackson、应用服务、Vue 实际调用与 A01 原 HTTP 字节交叉编制。共有 **35 操作：24 空间 + 6 弹道原计算 + 5 辅助**。配套机器合同为 [OpenAPI 3.0.3 JSON](openapi/openapi.json)，完整实际样例及来源为 [A02 样例集合](../verification/A02/examples.json)，核验范围见 [A02 验证报告](../verification/A02/验证报告.md)。本项不改变产品、数值模型或旧文件格式。

## 共同约定

- 服务示例根 `http://127.0.0.1:8080`；Vue 同源 `/api`，正式部署反向代理及根变量见 [部署说明](../deploy/部署说明.md) 与 [前端配置说明](前端载荷配置说明.md)。没有新增认证、统一包装或伪分页。
- 空间请求卫星/目标 ID 是 Long JSON 数字；目录实体及下传响应仍为数字。观测结果身份是字符串；持久记录主键是十进制字符串，路径实际绑定 Long；弹道身份全部字符串。不能把所有 Long 泛化成字符串，也不能把记录 ID 转成 JavaScript Number。
- 空间时间为 UTC ISO Instant（窗口、任务输出 `yyyy-MM-ddTHH:mm:ss.SSSZ`）；记录 `createTime/completeTime` 为 UTC LocalDateTime 三位毫秒**无 Z**。弹道输入/输出是 `yyyy/MM/dd HH:mm:ss[.SSS]` 或 `yyyy-MM-dd HH:mm:ss[.SSS]` 字面 UTC，不经过浏览器 Date 时区加减。
- 默认值仅在字段缺失时初始化；显式 null、空白、空集合是否回退见各字段。`nullable` 与 `required` 独立：属性可不出现不等于其允许 null；标准必填数值明确拒绝 null。默认Jackson保留显式null，只有标注NON_NULL的selectionJson、winToolReport、观测目标元数据/单星statistics等空字段省略，satelliteSegments/illuminationSegments按NON_EMPTY省略空数组。窗口手写JSON保留非有限数的null，不能把两种序列化混同。
- 正文各操作请求/响应指向完整组件字段表，数组、继承及嵌套组件继续沿链接展开。`requestJson/resultJson/skippedPairsJson/selectionJson` 始终是 JSON **原文字符串**，不是嵌套对象。未知目录原值的物理单位不猜测。
- 样例来自 A01 的非生产隔离测试；每例保存原字节 SHA、事件名、实际状态和源位置。正文为**结构摘录**（数组只展示首项、长 JSON 原文字符串展示前缀），完整响应位于样例集合；计算统计不得由正文摘录数组重新相加。动态 `taskId/fileId/recordId/serverPath/csvPath` 属于当次运行，已过期引用不可直接重放。

## 标准载荷与旧兼容摘要

标准十业务字段为 `payloadType,minDistanceKm,maxDistanceKm,azimuthMinDeg,azimuthMaxDeg,elevationMinDeg,elevationMaxDeg,angleRateDegPerSec,sunAvoidHalfAngleDeg,startupAngleRateDegPerSec`；弹道另有 `payloadId/payloadName` 两身份元数据。必填为类型、最近/最远距离、跟踪角速度、太阳半角；姿态双端可空，startup 可空。SPACE/GROUND 去空白、不区分大小写归一。

| 字段组合 | 真实约束 |
| --- | --- |
| min/max 距离 | km，有限非负；max=0 不限，max非0须>min；边界闭区间，零视线拒绝；乘1000溢出拒绝 |
| 方位双端 | 双null不限；否则0≤min<max≤360；不能倒置表达跨0范围 |
| 俯仰双端 | 双null不限；SPACE min≥-90，GROUND min≥-91；min<max≤90 |
| 跟踪率 | °/s；SPACE 0/-1 不限，GROUND 0 真零上限、-1 拒绝 |
| 太阳半角 | °，0..180；0关闭；等于半角允许，小于拒绝 |
| startup | °/s；null/-1禁用，其它有限非负；只保存/回显元数据，改变它不改变窗口 |

`/satellites/{satelliteId}/payload` 旧入口仅 SPACE；公里与米距离必须二选一且不能混发。`payload-profiles` 按类型保存并启用，保留另一类。历史 GROUND 锥角 `legacyGroundProfile=true` 只回显旧锥角，须重新保存标准字段；标准请求中兼容锥角字段虽然可绑定，保存标准配置不会拿它替代矩形约束。

旧弹道顶层/内嵌载荷中文四类 `对地监视/对空监视/对地跟踪/对空跟踪` 继续原分支，不能当成 SPACE/GROUND 别名。内嵌 `PayloadDef` 仅当 payloadType 去空白且忽略大小写为 SPACE/GROUND 时校验标准字段；中文兼容分支只读取类型/maxDetectRangeKm/sunExclusionAngleDeg，其它标准字段（包括无关负值）绑定后被忽略。共享 PayloadDef 保留宽兼容类型，不能自动校验标准分支的全部范围/必填及跨字段组合；严格标准 preview 使用 StandardPayloadRequest，计算入口的标准规则仍以正文及源码为准。窗口旧 `visibilityConstraint` 只有类型、最大距离、太阳角及四个锥角；标准载荷将四锥角显式输出 null，只映射类型/maxDistance/sunAvoid，不能从摘要恢复十字段。完整标准配置通过 preview 读取，不在旧计算响应增加字段。详见 [载荷时间与文件口径](载荷时间与文件口径.md)。

## 时间、坐标与 CSV 文件合同

旧弹道 J2000 秒以 UTC 日历 `2000-01-01 12:00:00` 为零，不是 Orekit TT `AbsoluteDate.J2000_EPOCH` 的偏移。空间 TLE 输出 TEME；空间六根数在原 EME2000 中心引力+J2 RK4 后转 TEME。弹道 ECI 保留原 J2000，ECEF 保留原岁差/章动/恒星时链。位置 m、速度 m/s、加速度 m/s²、角度 °。

拟合/模拟固定21列、原顺序如下：

```text
YYYY,MM,dd,HH,mm,ss.SSS,ECEF_X,ECEF_Y,ECEF_Z,energy,dt,ECI_X,ECI_Y,ECI_Z,ECI_VX,ECI_VY,ECI_VZ,ECEF_Vx,ECEF_Vy,ECEF_Vz,isOriginal
```

| 列 | 类型/单位/含义 |
| --- | --- |
| YYYY,MM,dd,HH,mm,ss.SSS | UTC 年/月/日/时/分/秒文本，秒三位毫秒 |
| ECEF_X,ECEF_Y,ECEF_Z | ECEF 位置 m，文本三位小数 |
| energy | `0.5\|v\|²-μ/\|r\|` m²/s²，九位小数 |
| dt | 拟合/模拟相邻间隔 ms，首点0；重定时有该列时改为距新首点累计 ms，无该列不追加 |
| ECI_X,ECI_Y,ECI_Z | ECI 位置 m，三位小数 |
| ECI_VX,ECI_VY,ECI_VZ | ECI 速度 m/s，九位小数 |
| ECEF_Vx,ECEF_Vy,ECEF_Vz | 原 STK Fixed 旋转速度 m/s，九位小数，不等于含地球自转项的地固坐标导数 |
| isOriginal | 原始硬约束点标记；重定时继承原单元格，不因时间变化重判 |
| timeIntervalMs（重定时） | 相邻间隔 ms，首点0，负间隔按原规则夹0；已有列更新，否则追加 |

重定时要求 UTC六分列、ECEF_X/Y/Z、ECI_X/Y/Z、ECI_VX/VY/VZ、ECEF_Vx/Vy/Vz 等原列名；保留原表头次序、行序、未知列和 isOriginal 单元格，保留 ECEF 原位置/速度单元格，在新 UTC 按原 `ecefToEciPos/ecefToEciVel` 重算 ECI。固定输出舍入位数不是内部数值精度保证。窗口20列标准参考CSV（含单位后缀）不是同一重定时输入合同。

preview 精确识别下列20列表头时 metadata 才有 `timeScale=UTC,positionUnit=m,velocityUnit=m/s,frames=ECI,ECEF,dtUnit=ms`；其它表头 metadata 为 `{}`，不猜单位。

```text
YYYY,MM,dd,HH,mm,ss.SSS,ECEF_X(m),ECEF_Y(m),ECEF_Z(m),energy,dt(ms),ECI_X(m),ECI_Y(m),ECI_Z(m),ECI_Vx(m/s),ECI_Vy(m/s),ECI_Vz(m/s),ECEF_Vx(m/s),ECEF_Vy(m/s),ECEF_Vz(m/s)
```

102 `coordinateInfo > coordinate > coords > 扁平字段`，`targetInfo > target > 扁平字段`，时间 `tack3d_time_code` 非空优先于 `track3d_time_code`。编码位置0.1m/速度0.0001m/s/加速度0.01m/s²，保留原 targetOrbit 单位比例自动识别与 warnings；17位时间码应发送字符串，浏览器未引号数字会损失精度。物理 launch/impact 时间与任务输出时间分开。

端点框架归一去横杠/下划线/空格并大写：J2000/ECI/ECIJ2000、ECEF/ITRF/WGS84ECEF、LLA/LLH/BLH/GEODETIC/WGS84/WGS84LLA；地理框架 x=经度°、y=纬度°、z=高度m。位置单位 m/meter/meters、km/kilometer/kilometers、0.1m/dm/package102/102，或原数值倍率；空单位原倍率0.1m。模拟字段缺省unit=m；显式空值与缺省不同。phaseEvents及旧速度/推力字段可绑定，当前端点求解路径没有读取其事件动力学，不额外宣称生效。

窗口 `allResults` 按输入偏移顺序回填；best按累计double时长严格大于才替换，并列保留首次。累计逐卫星/目标/载荷弧段相加、重叠不去重；有点但非正时长旧回退按点数。finalVisibleWindow 是最早到最晚包络，可能含不可见间隔；稳定弧段至少2点且≥0.001秒，退出滞回保持原1e-6度。points=0只去点数组，统计保留。空间组合求交，最小时长判父窗口，光照明细与逐星原窗口不能重复计为独立合格窗口。

## 文件生命周期与取消/错误

浏览器先上传并确认201，从真实响应取 `fileId/serverPath`，再送入原路径字段；本机路径/fakepath不能代替服务端引用。窗口原计算继续支持服务器路径及原 HTTP/HTTPS/MinIO，远程兼容不意味着纳入管理下载。重定时原DTO只接受服务器本地CSV路径。

未提交 outputDir 的三轨迹计算分配独占管理 results 工作区，成功自动登记；之后用真实 `files[].csvPath` 调 register-result 201 获得引用，重复有效登记复用ID。显式 outputDir（含null/空白/点目录）按旧语义；结果越出管理根时不向浏览器开放登记。管理目录拒绝路径越界、符号链接替换和非现存文件。失败只回滚自己拥有的独占结果/标记，旧显式目录不任意删除。

| 配置/生命周期 | 当前实际默认或行为 |
| --- | --- |
| 单文件上限 | `BALLISTIC_MAX_FILE_BYTES=67108864`（64MiB）；multipart总上限自动+65536B，只给边界/请求头空间 |
| 预览 | page=1、limit=50；limit上限 `BALLISTIC_PREVIEW_MAX_ROWS=500`；CSV/TXT/XLS/XLSX/XLSM，Excel首工作表，原单元格文本 |
| TTL | `BALLISTIC_FILE_TTL_MILLIS=86400000`（24h），按最后访问；默认清理间隔60000ms，租约覆盖完整解析/预览/下载 |
| 到期 | 到期且无活动租约才清理字节/元数据，之后404；不能继续使用旧ID；租约释放或中断后自然进入清理 |
| 重启 | 同运行根登记元数据/文件及数据库记录可恢复；异步内存task缓存不恢复，重启后其查询404 |
| 下载 | 原二进制、Content-Type来自登记、Content-Length为校验字节数；Content-Disposition含ASCII filename及UTF-8 filename*，nosniff |

异步 `PENDING→RUNNING→SUCCEEDED/FAILED/CANCELLED`。DELETE 200可能仍RUNNING，只表示受理协作取消，须继续轮询确认；先成功再取消保留SUCCEEDED。GET result 对所有未SUCCEEDED状态均409，不能仅凭409断言已取消。任务缓存默认TTL2h，持久记录独立；取消任务与删除记录是不同操作。

空间业务400 `{code,error}`、兜底500 `{code,error,requestId}`；非法Instant绑定和非数字BIGINT记录ID保持原500。未知task/合法未知数字记录404为空体。旧窗口/三轨迹计算内部400/500为 `{error}`；MVC绑定可进入原全局500，已提交的流式传输故障不保证JSON错误体。文件400/404/413/500使用自身 `{code,error,requestId?}`，payload preview非法400使用 `{code:INVALID_ARGUMENT,error}`。逐操作列出源码边界与A01实际状态，不把未运行状态称为实测。

确认选择后端只强制 `selections` 非空数组，planningWindow/mergedTimeRange和每项用 JsonNode 透明保存；推荐前端完整嵌套结构见 [SelectionTarget](#selectiontarget)、[SelectionMergedTimeRange](#selectionmergedtimerange)。服务不重算前端并集，也不强制每项推荐字段；OpenAPI使用递归真实JSON值合同，未伪造强校验。

## PowerShell curl 执行约定

从仓库根运行 `curl.exe`，避免PowerShell同名别名。每个JSON请求配套文件来自A01真实成功请求（[请求文件目录](../verification/A02/requests)），上传引用/结果路径须换为**当前服务当次响应**。下列公共变量只能从成功响应提取，不能直接使用正文历史ID：

```powershell
$api = 'http://127.0.0.1:8080'
$satelliteId = ((curl.exe --fail-with-body "$api/api/v1/space/satellites/options") | ConvertFrom-Json)[0].id
$upload = (curl.exe --fail-with-body -F 'file=@test-data/reference/ballistic/trajectory-0.1s-standard.csv' "$api/api/v1/ballistic/files") | ConvertFrom-Json
$fileId = $upload.fileId
$serverPath = $upload.serverPath
# $taskId 取操作16受理响应.taskId；$id 取操作20分页.records[].id，保留字符串。
# 计算成功后 $csvPath 取真实响应.files[].csvPath，登记后的$fileId与serverPath重新赋值。
```

POST/PUT下面使用 `--data-binary '@verification/A02/requests/operationNN.json'`，不内联手工转义JSON。样例中的动态路径、ID来自已结束的A01实例，调用前须按当前引用替换；样例目的为合同与语法，不是可访问历史服务的保证。只更换文件位置/身份所依赖的请求值，不更改数值模型/采样或CSV。完整带生命周期执行示例仍可参见 [既有HTTP脚本](examples/http_flow.py)。本项以临时本机HTTP接收器核验35条curl方法、参数、JSON字节与multipart，不把接收器当成业务服务。

## 逐操作合同与实际样例
'''

def markdown():
    parts=[INTRO]
    curl_cases=[]
    for index,(method,path,title,req,res) in enumerate(ROUTES,1):
        op=PATHS[path][method.lower()]
        key=method+' '+path
        parts += ['\n### '+str(index).zfill(2)+' '+title+'\n', '`'+key+'`\n',op['description']+'\n']
        if op.get('parameters'):
            parts += ['\n| 参数 | 位置 | 必填 | 类型 | 默认/范围 | 含义 |\n| --- | --- | --- | --- | --- | --- |\n']
            for p in op['parameters']:
                s=p['schema']
                constraints=', '.join(k+'='+json.dumps(s[k],ensure_ascii=False) for k in ['default','minimum','maximum','pattern'] if k in s) or '无固定默认'
                parts += ['| '+p['name']+' | '+p['in']+' | '+('是' if p['required'] else '否')+' | '+display_type(s)+' | '+constraints+' | '+p['description']+' |\n']
        parts += ['\n请求：'+(display_type(ref(req))+'；必填请求体；Content-Type='+('multipart/form-data' if req=='MultipartUpload' else 'application/json') if req else '无请求体')+'。\n',
                  '\n| 状态 | 内容类型/结构 | 真实语义 |\n| --- | --- | --- |\n']
        for status,response in op['responses'].items():
            types=', '.join(c+' '+display_type(v['schema']) for c,v in response.get('content',{}).items()) or '空体'
            parts += ['| '+status+' | '+types+' | '+response['description']+' |\n']
        concrete=re.sub(r'\{(\w+)\}',lambda m:'$'+m.group(1),path)
        sample=next(e for e in EXAMPLES[key] if e['event']['status']<300)
        query=urlsplit(sample['event']['path']).query
        if query: concrete+='?'+query
        arguments=['--fail-with-body','-X',method]
        if req=='MultipartUpload': arguments+=['-F','file=@test-data/reference/ballistic/trajectory-0.1s-standard.csv']
        elif req:
            request_path='verification/A02/requests/operation'+str(index).zfill(2)+'.json'
            dump(ROOT/request_path,sample['request'])
            arguments+=['-H','Content-Type: application/json','--data-binary','@'+request_path]
        command='curl.exe '+ ' '.join("'"+a+"'" if ' ' in a or a.startswith(('@','file=')) else a for a in arguments)+' "$api'+concrete+'"'
        if res=='binary': command+=' --output "$env:TEMP/ballistic-download.csv"'
        parts += ['\n```powershell\n'+command+'\n```\n']
        curl_cases.append({'operation':key,'method':method,'path':path,'query':query,'arguments':arguments,'body':sample['request'] if req!='MultipartUpload' else None})
        for example in EXAMPLES[key]:
            e=example['event']
            parts += ['\nA01 '+str(e['status'])+'实际样例：`'+e['name']+'`，原响应 '+str(e['bytes'])+'B，SHA256 `'+e['sha256']+'`。\n']
            if e['status']<300 and example['request'] is not None:
                parts+=['\n请求结构摘录：\n\n```json\n'+json.dumps(excerpt(example['request']),ensure_ascii=False,indent=2)+'\n```\n']
            if example['response'] is not None:
                parts+=['\n响应结构摘录：\n\n```json\n'+json.dumps(excerpt(example['response']),ensure_ascii=False,indent=2)+'\n```\n']
            else:
                parts+=['\n响应为'+('空体' if not e['bytes'] else '原文件二进制，不能当作JSON')+'；见样例集合的字节长度/原SHA与原HTTP头。\n']
    parts+=['\n## 完整字段组件\n\n本节和OpenAPI由同一schema源生成，逐字段列明类型、必填、nullable、缺省及业务含义。响应可不出现的字段不纳入required；null省略不表示补0。继承父模型字段按父组件展开。标准载荷及时间/文件跨字段约束见前文，OpenAPI3.0.3不能完整表达成对null、类型相关0/-1、路径根/租约和状态时序，正文为其必要合同。\n']
    for name,schema in SCHEMAS.items():
        parts+=['\n### '+name+'\n']
        if schema.get('description'): parts+=[schema['description']+'\n']
        if schema.get('x-source'): parts+=['\n源码：[类型定义](../'+schema['x-source']+')。\n']
        if 'allOf' in schema and 'properties' not in schema:
            parents=[x['$ref'].rsplit('/',1)[1] for x in schema['allOf'] if '$ref' in x]
            parts+=['\n继承：'+', '.join('['+p+'](#'+p.lower()+')' for p in parents)+'，下表为追加字段。\n']
            own=schema['allOf'][-1]
        else: own=schema
        if 'anyOf' in schema:
            parts+=['\n允许值：'+display_type(schema)+'；对象键和数组元素递归遵循JsonValue，null允许。\n']
            continue
        parts+=['\n| 字段 | 类型/嵌套 | 必填 | 可null | 缺省/枚举 | 含义、单位、范围与特殊值 |\n| --- | --- | --- | --- | --- | --- |\n']
        for fname,field in own.get('properties',{}).items():
            options=[]
            if 'default' in field: options.append('缺失='+json.dumps(field['default'],ensure_ascii=False))
            if field.get('x-http-omission-behavior'): options.append(field['x-http-omission-behavior'])
            if 'enum' in field: options.append('枚举='+json.dumps(field['enum'],ensure_ascii=False))
            if field.get('x-canonical-values'): options.append('规范值='+json.dumps(field['x-canonical-values'],ensure_ascii=False))
            for k in ['minimum','maximum','minItems','pattern']:
                if k in field: options.append(k+'='+str(field[k]))
            desc=field.get('description','按所引用完整嵌套组件解释，数组保持原顺序。').replace('|','\\|').replace('\n',' ')
            parts+=['| `'+fname+'` | '+display_type(field)+' | '+('是' if fname in own.get('required',[]) else '否')+' | '+('是' if permits_null(field) else '否')+' | '+'; '.join(options).replace('|','\\|')+' | '+desc+' |\n']
    parts+=['\n## 来源、验证与普通 Java 工具入口\n\n原 T03 [接口与数值口径基线](接口与数值口径基线.md) 与 T29 [接口使用说明](接口使用说明.md) 保留为历史来源，不替代本完整字段合同。A01 [测试报告](../verification/A01/测试报告.md) 和 [35路由原矩阵](../verification/A01/final/http-extended-summary.json) 提供原业务验证。普通Java8外推工具的签名、独立类路径与线程边界见 [独立外推工具与后续方案](独立外推工具与后续方案.md)；它们不是新增HTTP路由，D01—D06仍暂缓。\n']
    (ROOT/'docs/接口文档.md').write_text(''.join(parts),encoding='utf-8')
    dump(OUT/'curl-cases.json',curl_cases)

if __name__=='__main__':
    dump(ROOT/'docs/openapi/openapi.json',OPENAPI)
    dump(OUT/'examples.json',EXAMPLES)
    source_paths={ROOT/info['source'] for info in CLASSES.values()} | {SOURCE/'com/tx/alg/OrbitCalculator.java',SOURCE/'com/runisys/orbit/spacevisibility/SpaceTargetVisibleApplication.java',ROOT/'backend/src/main/resources/application.yml',ROOT/'frontend/src/api/types.ts',ROOT/'frontend/src/api/ballisticWindowTypes.ts'} | set((SOURCE/'com/runisys/orbit/spacevisibility/api').rglob('*.java')) | set((SOURCE/'com/runisys/orbit/spacevisibility/application').glob('*.java')) | set((SOURCE/'com/runisys/orbit/spacevisibility/application/ballistic').glob('*.java')) | set((SOURCE/'com/runisys/orbit/spacevisibility/infrastructure/files').glob('*.java'))
    jar=ROOT/'verification/runtime/A01/final-candidate/release/backend/server.jar'
    dump(OUT/'source-inventory.json',{'classes':CLASSES,'routes':[m+' '+p for m,p,*_ in ROUTES], 'jar':{'path':jar.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'note':'本项只重新计算既有A01正式JAR指纹，不新构建或运行服务。'},'sources':[{'path':p.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(source_paths)]})
    markdown()
    print(json.dumps({'operations':sum(len(v) for v in PATHS.values()),'schemas':len(SCHEMAS),'examples':sum(len(v) for v in EXAMPLES.values())},ensure_ascii=False))
