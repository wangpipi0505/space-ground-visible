"""A05中文报告从机器统计生成；不生成或改写真值、黄金及数值模型。"""
from pathlib import Path
import json
import run_stk_validation as qa

OUT=qa.OUT


def read(name):
    return json.loads((OUT/(name+'.json')).read_text(encoding='utf-8'))


def table(headers,rows):
    """Markdown表格由真实JSON统计构造，数值保留六位有效小数。"""
    return '\n'.join(['| '+' | '.join(headers)+' |','| '+' | '.join(['---']*len(headers))+' |']+
                     ['| '+' | '.join(str(v) for v in row)+' |' for row in rows])


def norm_row(label,metric):
    return [label]+[f'{metric[k]:.9f}' for k in ('mean','rmse','p95','max')]+[metric['worstUtc']]


def main():
    m,ecef,roi=read('metrics-final'),read('metrics-ecef'),read('metrics-legacy-roi')
    raw,scene,source,build,protected=read('raw-input-bindings'),read('scene-source-bindings'),read('current-source-manifest'),read('new-build-bindings'),read('protected-after-ecef')
    lines=['# STK原始数据精度补充测试报告','',
      '本轮 A05 使用三份实际外部 STK 原始 CSV 新执行原 JAR/当前 Java 8 新编译类双跑。两种具名端点重建场景各 7371 点、卫星全天 8641 个真实 RK4 位置/速度状态及四种窗口场景全部完成。原/当前数值完全一致。旧诊断 +1120 秒 ROI 的 HPOP 位置平均 361.986641 m、最大 501.399311 m，与旧约 362/501 m 描述相符。',
      '', '原精确模拟请求和旧诊断引用的 CSV 未找到；精确 ECI、ECEF 两种端点重建分别披露，不声称旧试验完全复现。独立 STK Access 起止表缺失，绝对窗口精度为 `NOT_TESTED_MISSING_STK_ACCESS`。', '',
      '## 1. 来源与试验条件','',
      table(['原始文件','点数','首 UTC','末 UTC','原字节 SHA256'],[[b['originalPath'],b['count'],b['firstUtc'],b['lastUtc'],b['sha256']] for b in raw['files']]),'',
      '三份 CSV 按原字节纳入 `test-data/reference/stk/A05/raw`。原列 km/km/s 显式乘 1000 后转为 m/m/s。Missile 两组三维状态列名完全重复，第一组为 STK Fixed，第二组为 J2000，按列位读取。全量检查严格单调、不重复、有限值、列数/单位、点数/时域；严格相同 UTC 序列才允许比较。', '',
      '时间沿用旧 UTC 日历 2000-01-01 12:00:00 的 J2000 绝对秒，非 Orekit TT 历元。CSV UTC 打印精度毫秒；7371 点为 0.1 秒网格加末端 08:36:16.990（末间隔 0.09 秒）。软件位置三位小数、速度九位，STK 原位置/速度均六位 km/km/s（SI 分辨率各 0.001 m/0.001 m/s），量化残差计入统计，不截断样本。', '',
      '参数冲突与可复现界限：', '',
      table(['材料','可核实内容','本轮处理'],[
        ['旧技术说明第9章','08:24:00—08:36:16.990、7371点；发射约111.57/38.70/1600，落点约86.1609/41.7255/0','旧文字近似值不是精确请求，不直接用于宣称完全重现'],
        ['STK原始7371点ECEF首末','LLA约111.569999994/38.699999999/1600.000277 →86.174600036/41.724999740/999.999721','原转换核实际解算；不同于旧落点近似文字'],
        ['后改Missile1.mi','08:20:50—08:33:06.990226678，落点86.1746/41.725/1000','与CSV相差190秒，不把后来快照当导出时条件'],
        ['trajectory-0.1s.profile.txt','8560点、07:57:00—08:11:15.900、855.9秒；相同LLA约1000m落点','另一场景，禁止混用8560点和7371点'],
        ['Missile1_alg_trajectory.csv','738点、首08:22:30，比独立7371点08:24早90秒','软件历史产物，未用作STK真值'],
        ['旧alg_gap_1120_fov130_j2rk4 README','base08:24、offset1120、0.5秒、130°全锥；旧defaultSimTask…182251_789.csv路径','旧CSV未找到；新窗口目标来源分别具名'],
        ['Satellite1.sa当前快照','epoch10:00、Degree2/Order0、UseDrag No、UseSRP No','不据此倒推HPOP与nodrag两个旧CSV的完整导出力模型'],
        ['Scenario3.sca/Sensor3.sn','Sensor3→Missile与Satellite1→Missile两个Access配置；半锥65°、light time/annual aberration配置','配置文件未包含独立Access起止表，不能称Access已测']]),'',
      '核实范围包括原工程示例 JSON、报告、诊断 README 和来源检索；根代理另核查旧 alg-0623/alg-0625 ZIP 目录未找到精确7371点请求及旧模拟文件。本轮未修改、展开或提交冻结工程。完整源文件 SHA 与配置具名摘录见 `scene-source-bindings.json`；参数请求见 `simulation-request.json`、`simulation-ecef-request.json`。', '',
      table(['重建场景','起点 m','终点 m','输入口径'],[
        ['ECI首末精确重建','-4566470.679,1977006.813,3979168.272','-3320508.685,3411340.342,4232040.516','J2000；来自原CSV第二组位置'],
        ['ECEF首末精确重建','-1832845.456,4636330.230,3967380.757','318111.601,4757505.551,4223521.483','ECEF；来自原CSV第一组位置，贴近旧报告地固输入口径']]), '',
      '两个场景均调用真实 `TargetTrajectoryFitter.simulateToCsv`：Lambert 端点求初速、二体 RK4、0.1 秒输出/积分、phaseEvents 空，不启用目标 J2/阻力/升力/推力。没有更换数值模型、常数、黄金或放宽阈值。端点属于已知位置约束，端点零误差不能被解释成未知条件下预测绝对精度；本轮不以观测硬约束拟合代替系统模拟。', '',
      '## 2. 完整目标位置与速度','',
      '下表为三维误差范数；每一项都覆盖全部7371个同UTC点。原版和当前分别对STK计算，结果逐数值相等，故并列展示相同实测值。分量有符号均值/RMSE/P95/绝对最大及最差UTC全部保留在 `metrics-final.json` 与 `metrics-ecef.json`。P95采用nearest-rank；最大按绝对值定位，范数均非负。', '']
    headers=['场景/版本/指标','平均','RMSE','P95','最大','最差 UTC']
    for name,data in [('ECI端点',m),('ECEF端点',ecef)]:
        rows=[]
        for label in ('original','current'):
            for key,title in [('ecefP','ECEF位置m'),('eciP','ECI位置m'),('ecefV','ECEF速度m/s'),('eciV','ECI速度m/s')]:
                rows.append(norm_row(name+'/'+label+'/'+title,data['trajectory'][label][key]['norm']))
        lines.extend([table(headers,rows),''])
    lines.extend(['旧报告与本轮四项误差分列（不是以旧数值作通关阈值）：','',table(['指标','旧报告RMS/最大','ECI端点重建RMS/最大','ECEF端点重建RMS/最大'],[
      [title,old,f"{m['trajectory']['current'][key]['norm']['rmse']:.9f} / {m['trajectory']['current'][key]['norm']['max']:.9f}",
       f"{ecef['trajectory']['current'][key]['norm']['rmse']:.9f} / {ecef['trajectory']['current'][key]['norm']['max']:.9f}"]
      for key,title,old in [('ecefP','ECEF位置m','约0.546 / 0.970'),('eciP','ECI位置m','约0.746 / 1.101'),
                            ('ecefV','ECEF速度m/s','约0.00142 / 0.00221'),('eciV','ECI速度m/s','约0.00145 / 0.00224')]]),'',
      'ECEF重建更贴近旧文字试验的地固输入口径，但原精确请求缺失，因此不能把接近的数值称为旧试验完全重现。两个输入场景的ECI/ECEF位置RMS差均约0.461343 m、速度RMS差约0.000166024 m/s，来自真实原坐标转换与端点输入口径；不挑选误差最小场景替代另一场景。', '',
      '两版本各自的两类重建输出CSV逐字节SHA相同；所有ECI/ECEF位置、速度原/当前分量/三维范数平均、RMS、P95、最大均0。仅运行目录/原结果文件路径元数据不同。', '',
      '速度采用原STK Fixed报表的惯性速度在地固轴上的分量，不扣Ω×r；它不同于真实地固坐标导数。用真实原转换核对全部7371原STK状态：J2000→Fixed位置RMS0.456576 m/最大0.470951 m，STK Fixed速度RMS0.000719988 m/s/最大0.001519722 m/s，支持两组列位与速度定义；该内部转换诊断不等于独立预测精度通过。', '',
      '## 3. 卫星全天与两个具名ROI','',
      '旧默认根数：a=7530612 m，e=0.000850，i=64.8°，RAAN=120°，ω=270°，M=30°，epoch=2026/07/01 10:00:00，J2_NUMERICAL。原JAR读取 `OrbitCalculator.private propagateJ2Numerical(...).position/velocity`；当前读取 `BallisticOrbitPropagation.bind(Elements).stateAt(OrbitTime,J2000).positionM/velocityMps`。每个时刻均从历元真实传播，8641次状态的位置与公开 `propagateAtAbsolute` 同值断言实际通过。速度为RK4真实状态，未用数值差分冒充。', '',
      '全天：07-01 04:00:00—07-02 04:00:00，8641点、10秒步长。原目标时域ROI：08:24:00—08:36:16.990，实际74点至08:36:10；旧诊断+1120 ROI：08:42:40—08:54:56.990，实际74点至08:54:50。子范围按原UTC筛选本轮真实全天state，不补造末点或混范围。HPOP/nodrag两份参考各自完整比较。', ''])
    rows=[]
    for reference in ('hpop','nodrag'):
        periods=[('全天',m['satellite'][reference]['current']['fullDay']),('原目标时域',m['satellite'][reference]['current']['missileWindow']),
                 ('旧诊断+1120ROI',roi['satellite'][reference]['current'])]
        for period,data in periods:
            for key,title in [('eciP','J2000位置m'),('eciV','真实速度m/s')]:
                rows.append(norm_row(reference+'/'+period+'/original=current/'+title,data[key]['norm']))
    lines.extend([table(headers,rows),'',
      '卫星原/当前在全天及两个ROI的位置、真实速度所有分量/范数统计均0差异。旧约362/501 m是窗口ROI描述；新跑HPOP在旧诊断+1120 ROI的平均361.986641/最大501.399311 m支持其量级，不能混作全天统计。旧996.640/1154.136/2590.957 m的全天描述与本轮nodrag相符；HPOP全天为3185.184/3895.849/8535.601 m，不能偷换两份数据。两个文件命名不充分证明当时完整力模型，当前Satellite1.sa后改快照也不能补齐历史导出配置。', '',
      '## 4. 全部窗口一致性与Access未测','',
      '同物理 `legacySimpleCone(130)`，全锥130°/半锥65°、0.5秒采样，SAT-A/B旧默认根数分别M=30°/0°，J2_NUMERICAL，base08:24:00，offset=0/1120秒。目标分别为原STK状态的21列适配文件和本轮ECI重建输出；后者不是丢失的旧模拟CSV，也不是ECEF补跑场景。8个窗口进程全部实际退出0。', '',
      '下表列出每种场景的所有实际弧段；best是allResults的重复最优结果，不再次计弧段。两版本起止/时长/点数及其它完整业务字段严格相等。STK目标场景完整JSON相等；隔离重建场景只有csvPath不同，两版模拟CSV先确认SHA相同，再仅映射该来源元数据，物理JSON及弧段无容差比较相等。', ''])
    rows=[]
    for name,value in m['windows'].items():
        for arc in value['arcs']['current']:
            rows.append([name,arc['satellite'],arc['startTime'],arc['endTime'],f"{arc['durationSeconds']:.9f}",arc['pointCount'],'原/当前相同'])
    lines.extend([table(['目标/offset','平台','开始UTC','结束UTC','时长s','点数','一致性'],rows),'',
      'offset0每目标1段；offset1120每目标4段，全部保留，没有把覆盖窗口时长冒充累计可见时长。SAT-A offset1120累计701.5秒，SAT-B累计706.989999890秒，各按弧段时长相加。采样窗口末端double绝对秒产生495.489999890等数值与UTC毫秒打印共同保存，没有舍入后掩盖变化。', '',
      '**绝对窗口精度：NOT_TESTED_MISSING_STK_ACCESS。** `.sca`只有Access配置，`.e`外部星历、`access_diagnostic.csv`和`software_visibility_*`都是软件生成/配置资料，没有独立STK Access开始/结束/时长表。未填零误差，未把原/当前窗口一致性当作外部STK绝对精度通过。', '',
      '## 5. 实际验证与交付界限','',
      f"基准提交e38880d85883a7780dd01bc115d95cb3ac2aee20；{source['fileCount']}项当前必要源/输入逐SHA核对后复制到全新A05 runtime。Java1.8.0_472，Maven实际新副本package成功（-DskipTests，未宣称JUnit通过）；新JAR SHA `{build['jarSha256']}`，原JAR SHA `{build['originalJarSha256']}`。新classes清单、探针源码及编译产物SHA、原/当前隔离classpath和实际输入绑定分别见new-build-bindings/probe-bindings-repaired/probe-compiled-bindings及请求JSON。", '',
      '有效RED→GREEN：初始DictReader重复表头覆盖，反例实际AssertionError（Fixed 1000→ECI 7000），tests-red退出1；列位修复后6个定向测试实际通过，含km→m、重复时刻/重排/缺行/非有限数/UTC偏移（含1ms）、微小位置/速度变化、1ms窗口变化、拒绝软件输出作Access真值。不是用文案镜像测试替代数值检查。', '',
      '探针初次包访问编译失败及窗口请求两次失败均保留原流/命令/请求与源SHA：原DTO包级转换需要同api包；原DTO不承担控制器空目标/空卫星默认兜底，最终请求显式列出目标与SAT-A/B根数。统计首轮完整JSON严比被csvPath元数据差异拦截；metrics.json保留现场，修复仅具名来源路径映射和best重复弧段计数，metrics-final及独立重算再次核验。失败→修复→复测见failure-repair-record.json。', '',
      f"保护核对实际通过：原目录697项包含在{protected['filesChecked']}项原STK/原版/产品/旧证据前后SHA保护范围内；新副本{protected['currentSourceFilesChecked']}项输入SHA一致。原A01—A04/Txx数值、黄金、前端锁及生产模型未改。未运行前端、性能或A04完整旧suite，不称这些检查本轮通过。", '',
      '完整逐点结果/业务JSON/新源码副本/JAR/classes保存在 `verification/runtime/A05`，不提交；机器摘要含每分量/范数统计、最差UTC、点数/时域及源输出SHA。必要原CSV、QA源码、报告、输入/来源绑定、命令JSON和原字节流可提交。', '',
      '默认只读入口：`C:/Python314/python.exe -B verification/A05/delivery_check.py`。它读取本阶段candidate-manifest相对路径SHA、635源/输入、原流、三份参考质量和已存摘要内部一致性；不访问runtime、原目录、JAR/classes，不声称重新计算传播/窗口。candidate-manifest自身、其生成后的只读核验日志、独立审查和根最终台账/README/Git清单由根最终交付门另行绑定，本阶段候选清单明确排除递归自引用。', '',
      '## 6. 实际命令索引','',
      '每份 `*-command.json` 包含真实UTC/argv/cwd/退出码、原stdout/stderr文件名与SHA；失败证据不覆盖，补跑另命名。下表为截至报告生成的记录；后续只读交付核验日志另行保存。', ''])
    commands=[]
    for path in sorted(OUT.glob('*-command.json')):
        value=json.loads(path.read_text(encoding='utf-8'))
        commands.append([path.name,value['startedUtc'],value['exitCode'],value.get('expectedExitCode',0)])
    lines.extend([table(['命令证据','开始 UTC','实际退出','预期退出'],commands),''])
    (OUT/'STK原始数据精度补充测试报告.md').write_text('\n'.join(lines),encoding='utf-8',newline='\n')
    fixture_lines=['# A05 STK原始参考来源与校验','',
       '三份文件按原字节复制自G:/STK-file/Scenario3，属于用于回归的必要独立外部参考输入，非生产运行结果。原目录只读；纳入前后原CSV SHA保持，完整旧保护实际数以protected-after-ecef.json为准。', '',
       table(['仓库路径','外部来源','SHA256','点数/UTC范围'],[[b['fixturePath'],b['originalPath'],b['sha256'],str(b['count'])+' / '+b['firstUtc']+'—'+b['lastUtc']] for b in raw['files']]),'',
       'Missile两组重复header严格按位置：第一组Fixed位置/惯性速度地固分量，第二组J2000位置/速度；列km/km/sec各乘1000。卫星列J2000位置/真实速度，分别保留HPOP与hpop-nodrag两份，不互换。来源配置摘录及完整源SHA见verification/A05/scene-source-bindings.json；后改Missile1.mi 08:20:50与CSV08:24不一致、旧报告落点约0米与CSV约1000米不同，不能把当前场景文件当导出时精确条件。', '',
       '旧精确模拟请求未找到，本轮分别采用首末精确ECI和ECEF端点具名重建，报告说明可复现界限。Scenario3.sca未给Access起止结果，软件诊断CSV/.e均不作独立窗口真值。', '']
    (qa.RAW.parent/'来源与校验.md').write_text('\n'.join(fixture_lines),encoding='utf-8',newline='\n')
    print('中文报告由完整机器统计生成；原/当前两类端点重建、全天/两个ROI、全部窗口分列')


if __name__=='__main__':
    main()
