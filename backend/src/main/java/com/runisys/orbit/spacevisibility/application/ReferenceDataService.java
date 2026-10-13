package com.runisys.orbit.spacevisibility.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.runisys.orbit.spacevisibility.api.SpaceApiModels;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.SatelliteEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.StationEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.SatelliteMapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.StationMapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.TargetMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

@Service
public class ReferenceDataService {

    private static final long MAX_PAGE_SIZE = 200L;

    private final SatelliteMapper satelliteMapper;
    private final StationMapper stationMapper;
    private final TargetMapper targetMapper;

    public ReferenceDataService(SatelliteMapper satelliteMapper, StationMapper stationMapper, TargetMapper targetMapper) {
        this.satelliteMapper = satelliteMapper;
        this.stationMapper = stationMapper;
        this.targetMapper = targetMapper;
    }

    /**
     * 查询我方卫星。传入空标识集合时不附加筛选条件，适合基础数据完整加载。
     *
     * @param ids 需要查询的卫星主键，可为空
     * @return 按主键升序排列的卫星实体
     */
    public List<SatelliteEntity> findSatellites(List<Long> ids) {
        LambdaQueryWrapper<SatelliteEntity> query = new LambdaQueryWrapper<SatelliteEntity>()
                .orderByAsc(SatelliteEntity::getId);
        if (ids != null && !ids.isEmpty()) {
            query.in(SatelliteEntity::getId, ids);
        }
        return satelliteMapper.selectList(query);
    }

    /**
     * 按主键读取我方卫星；载荷配置属于卫星长期属性，读取不到时直接提示调用方。
     *
     * @param satelliteId 我方卫星主键
     * @return 已存在的我方卫星实体
     */
    public SatelliteEntity requireSatellite(Long satelliteId) {
        SatelliteEntity satellite = satelliteMapper.selectById(satelliteId);
        if (satellite == null) {
            throw new IllegalArgumentException("我方卫星不存在");
        }
        return satellite;
    }

    /**
     * 更新我方卫星的长期载荷 JSON，不影响轨道、目录或其他卫星属性。
     *
     * @param satelliteId 我方卫星主键
     * @param payloadJson 已完成参数校验的载荷 JSON
     */
    public void updateSatellitePayloadJson(Long satelliteId, String payloadJson) {
        SatelliteEntity satellite = new SatelliteEntity();
        satellite.setId(satelliteId);
        satellite.setPayloadJson(payloadJson);
        if (satelliteMapper.updateById(satellite) == 0) {
            throw new IllegalArgumentException("我方卫星不存在");
        }
    }

    /**
     * 查询地面站长期基础信息，不包含一次规划临时设置的光照和保护时长。
     *
     * @param ids 需要查询的地面站标识，可为空
     * @return 按站点标识升序排列的地面站实体
     */
    public List<StationEntity> findStations(List<String> ids) {
        LambdaQueryWrapper<StationEntity> query = new LambdaQueryWrapper<StationEntity>()
                .orderByAsc(StationEntity::getId);
        if (ids != null && !ids.isEmpty()) {
            query.in(StationEntity::getId, ids);
        }
        return stationMapper.selectList(query);
    }

    /**
     * 使用 MyBatis-Plus Lambda 条件构造器筛选目标，并在查询结果上执行名称正则排除。
     * 空标识、国籍和质量条件均表示不限制对应维度。
     *
     * @param ids 目标主键集合，可为空
     * @param belongs 国籍集合，可为空
     * @param minWeight 质量下限（kg），可为空
     * @param maxWeight 质量上限（kg），可为空
     * @param excludeNameRegex 需要排除的目标名称正则表达式，可为空
     * @return 满足筛选条件的目标实体列表
     */
    public List<TargetEntity> findTargets(List<Long> ids,
                                          List<String> belongs,
                                          BigDecimal minWeight,
                                          BigDecimal maxWeight,
                                          String excludeNameRegex) {
        return findTargets(ids, belongs, minWeight, maxWeight, null, null, excludeNameRegex);
    }

    /**
     * 按目标标识、国籍、质量、轨道历元和名称排除规则加载用于观测规划的目标。
     *
     * @param ids 目标主键集合，为空表示遍历全部目标
     * @param belongs 国籍集合，为空表示不限制
     * @param minWeight 质量下限（kg）
     * @param maxWeight 质量上限（kg）
     * @param epochStartTime 轨道历元下限（UTC）
     * @param epochEndTime 轨道历元上限（UTC）
     * @param excludeNameRegex 目标名称排除正则
     * @return 满足所有条件的目标实体
     */
    public List<TargetEntity> findTargets(List<Long> ids,
                                          List<String> belongs,
                                          BigDecimal minWeight,
                                          BigDecimal maxWeight,
                                          Instant epochStartTime,
                                          Instant epochEndTime,
                                          String excludeNameRegex) {
        List<TargetEntity> targets = targetMapper.selectList(createTargetQuery(ids, belongs, minWeight, maxWeight,
                epochStartTime, epochEndTime));
        return excludeTargetsByName(targets, excludeNameRegex);
    }

    /**
     * 按用户筛选条件加载用于观测规划的目标。任务规划时间与发射日期不参与目标筛选：
     * 未发射或星历较旧的目标同样进入计算，星历时效仅以风险提示形式输出，不改变候选集合。
     */
    public List<TargetEntity> findTargetsForPlanning(List<Long> ids,
                                                      List<String> belongs,
                                                      BigDecimal minWeight,
                                                      BigDecimal maxWeight,
                                                      Instant epochStartTime,
                                                      Instant epochEndTime,
                                                      String excludeNameRegex) {
        return findTargets(ids, belongs, minWeight, maxWeight, epochStartTime, epochEndTime, excludeNameRegex);
    }

    /**
     * 按前端已确认的目标 ID 直接读取规划对象。该入口刻意不接受国籍、质量、历元或名称条件：
     * 调用方传入的是一次目标筛选得到的快照，规划阶段不得再次筛选而让计算集合偏离页面结果。
     *
     * @param ids 目标筛选接口返回的完整命中 ID 集合
     * @return 按主键升序读取的目标实体
     */
    public List<TargetEntity> findTargetsByIds(List<Long> ids) {
        return findTargets(ids, null, null, null, null, null, null);
    }

    /**
     * 统一执行名称正则排除，保证普通目录查询和任务时间查询的表达式语义完全一致。
     * 正则不便可靠下推到各类数据库，因此在已收敛的结果集上执行并将语法错误转换为中文参数提示。
     */
    private List<TargetEntity> excludeTargetsByName(List<TargetEntity> targets, String excludeNameRegex) {
        if (excludeNameRegex == null || excludeNameRegex.trim().isEmpty()) {
            return targets;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(excludeNameRegex);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("排除目标正则表达式无效");
        }
        return targets.stream()
                .filter(target -> target.getName() == null || !pattern.matcher(target.getName()).find())
                .collect(Collectors.toList());
    }

    /**
     * 分页查询我方卫星目录，查询时只选取前端列表展示所需字段，避免传输轨道两行数和载荷配置。
     *
     * @param pageNumber 页码，从 1 开始
     * @param pageSize 每页记录数，最大为 200
     * @return 卫星目录分页结果
     */
    public SpaceApiModels.PageResponse<SpaceApiModels.SatelliteSummaryResponse> satellitePage(long pageNumber,
                                                                                                 long pageSize,
                                                                                                 String keyword) {
        Page<SatelliteEntity> page = new Page<SatelliteEntity>(validatePageNumber(pageNumber), validatePageSize(pageSize));
        LambdaQueryWrapper<SatelliteEntity> query = new LambdaQueryWrapper<SatelliteEntity>()
                .select(SatelliteEntity::getId, SatelliteEntity::getName, SatelliteEntity::getModelType,
                        SatelliteEntity::getType, SatelliteEntity::getScid, SatelliteEntity::getObjectId,
                        SatelliteEntity::getObjectName, SatelliteEntity::getNoradCatId, SatelliteEntity::getCountry,
                        SatelliteEntity::getPeriod, SatelliteEntity::getCatalogInclination, SatelliteEntity::getApogee,
                        SatelliteEntity::getPerigee, SatelliteEntity::getRcsSize, SatelliteEntity::getRcsValue,
                        SatelliteEntity::getLaunchDate, SatelliteEntity::getCatalogComment, SatelliteEntity::getWeight)
                .orderByAsc(SatelliteEntity::getId);
        applySatelliteKeyword(query, keyword);
        satelliteMapper.selectPage(page, query);
        List<SpaceApiModels.SatelliteSummaryResponse> records = page.getRecords().stream()
                .map(SpaceApiModels.SatelliteSummaryResponse::new)
                .collect(Collectors.toList());
        return new SpaceApiModels.PageResponse<SpaceApiModels.SatelliteSummaryResponse>(
                page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * 返回下拉选择器所需的我方卫星轻量字段，数据库查询不会读取轨道两行数和载荷 JSON。
     *
     * @return 全部我方卫星的轻量选择项
     */
    public List<SpaceApiModels.SatelliteOptionResponse> allSatelliteOptions() {
        LambdaQueryWrapper<SatelliteEntity> query = new LambdaQueryWrapper<SatelliteEntity>()
                .select(SatelliteEntity::getId, SatelliteEntity::getName, SatelliteEntity::getModelType,
                        SatelliteEntity::getType, SatelliteEntity::getScid, SatelliteEntity::getNoradCatId)
                .orderByAsc(SatelliteEntity::getId);
        return satelliteMapper.selectList(query).stream()
                .map(SpaceApiModels.SatelliteOptionResponse::new)
                .collect(Collectors.toList());
    }

    /**
     * 从目标表 BELONG_ 的实时数据生成筛选字典，避免前端或服务端维护会随导入源漂移的国籍常量表。
     * 空值和纯空白值不是可选筛选项，其他值逐字保留并按编码升序稳定输出。
     */
    public List<SpaceApiModels.BelongOptionResponse> belongOptions() {
        LambdaQueryWrapper<TargetEntity> query = new LambdaQueryWrapper<TargetEntity>()
                .select(TargetEntity::getBelong);
        Map<String, Long> counts = new TreeMap<String, Long>();
        for (TargetEntity target : targetMapper.selectList(query)) {
            String belong = target.getBelong();
            if (belong == null || belong.trim().isEmpty()) {
                continue;
            }
            String code = belong.trim();
            Long previous = counts.get(code);
            counts.put(code, previous == null ? 1L : previous + 1L);
        }
        return counts.entrySet().stream()
                .map(entry -> new SpaceApiModels.BelongOptionResponse(entry.getKey(), entry.getValue(),
                        "BELONG_=" + entry.getKey() + "，当前目标数=" + entry.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * 分页查询空间目标；名称正则需要在 Java 中执行时先完成筛选，再对筛选结果切页。
     *
     * @param ids 目标主键集合，可为空
     * @param belongs 国籍集合，可为空
     * @param minWeight 质量下限，可为空
     * @param maxWeight 质量上限，可为空
     * @param excludeNameRegex 名称排除正则，可为空
     * @param pageNumber 页码，从 1 开始
     * @param pageSize 每页记录数，最大为 200
     * @return 目标分页查询结果
     */
    public SpaceApiModels.TargetQueryResponse findTargetPage(List<Long> ids,
                                                              List<String> belongs,
                                                              BigDecimal minWeight,
                                                              BigDecimal maxWeight,
                                                              Instant epochStartTime,
                                                              Instant epochEndTime,
                                                              String excludeNameRegex,
                                                              long pageNumber,
                                                              long pageSize) {
        long validatedPageNumber = validatePageNumber(pageNumber);
        long validatedPageSize = validatePageSize(pageSize);
        if (excludeNameRegex != null && !excludeNameRegex.trim().isEmpty()) {
            List<TargetEntity> filteredTargets = findTargets(ids, belongs, minWeight, maxWeight,
                    epochStartTime, epochEndTime, excludeNameRegex);
            int fromIndex = (int) Math.min((validatedPageNumber - 1L) * validatedPageSize, filteredTargets.size());
            int toIndex = (int) Math.min(fromIndex + validatedPageSize, filteredTargets.size());
            return new SpaceApiModels.TargetQueryResponse(filteredTargets.size(), validatedPageNumber,
                    validatedPageSize, filteredTargets.subList(fromIndex, toIndex),
                    buildTargetFilterBreakdown(ids, belongs, minWeight, maxWeight, epochStartTime, epochEndTime,
                            filteredTargets.size(), 0L, 0L), targetIdsOf(filteredTargets));
        }
        Page<TargetEntity> page = new Page<TargetEntity>(validatedPageNumber, validatedPageSize);
        targetMapper.selectPage(page, createTargetQuery(ids, belongs, minWeight, maxWeight,
                epochStartTime, epochEndTime));
        return new SpaceApiModels.TargetQueryResponse(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords(),
                buildTargetFilterBreakdown(ids, belongs, minWeight, maxWeight, epochStartTime, epochEndTime,
                        page.getTotal(), 0L, 0L), findTargetIds(ids, belongs, minWeight, maxWeight,
                        epochStartTime, epochEndTime));
    }

    /**
     * 读取未分页的命中 ID 列表。只选择主键列，避免为把筛选结果交给规划而重复传输完整轨道字段。
     */
    private List<Long> findTargetIds(List<Long> ids,
                                     List<String> belongs,
                                     BigDecimal minWeight,
                                     BigDecimal maxWeight,
                                     Instant epochStartTime,
                                     Instant epochEndTime) {
        LambdaQueryWrapper<TargetEntity> query = createTargetQuery(ids, belongs, minWeight, maxWeight,
                epochStartTime, epochEndTime).select(TargetEntity::getId);
        return targetIdsOf(targetMapper.selectList(query));
    }

    /** 将轻量查询或名称正则过滤后的实体统一转换为稳定排序的主键集合。 */
    private List<Long> targetIdsOf(List<TargetEntity> targets) {
        return targets.stream().map(TargetEntity::getId).collect(Collectors.toList());
    }

    /**
     * 分页预览按用户筛选条件进入计算的目标。与 {@link #findTargetsForPlanning} 使用同一组
     * 用户条件（任务规划时间与发射日期不参与筛选），防止页面数量与任务实际传播的目标集合不一致。
     */
    public SpaceApiModels.TargetQueryResponse findTargetPageForPlanning(List<Long> ids,
                                                                         List<String> belongs,
                                                                         BigDecimal minWeight,
                                                                         BigDecimal maxWeight,
                                                                         Instant epochStartTime,
                                                                         Instant epochEndTime,
                                                                         String excludeNameRegex,
                                                                         long pageNumber,
                                                                         long pageSize) {
        return findTargetPage(ids, belongs, minWeight, maxWeight, epochStartTime, epochEndTime,
                excludeNameRegex, pageNumber, pageSize);
    }

    /**
     * 统计每个筛选层顺序叠加后的剩余记录数。名称正则由 Java 执行，故最终数量由已完成正则过滤的
     * 结果传入；其余三个层次在数据库侧以 COUNT 查询完成，避免为页面提示加载整张目标表。
     */
    /** 统计用户筛选条件逐层叠加后的命中数；任务规划时间与发射日期不参与目标筛选。 */
    public SpaceApiModels.TargetFilterBreakdownResponse buildTargetFilterBreakdown(List<Long> ids,
                                                                                      List<String> belongs,
                                                                                      BigDecimal minWeight,
                                                                                      BigDecimal maxWeight,
                                                                                      Instant epochStartTime,
                                                                                      Instant epochEndTime,
                                                                                      long excludeHit,
                                                                                      long ephemerisWarningHit,
                                                                                      long maxApproximationDays) {
        long belongsHit = countTargets(ids, belongs, null, null, null, null);
        long weightHit = countTargets(ids, belongs, minWeight, maxWeight, null, null);
        long epochHit = countTargets(ids, belongs, minWeight, maxWeight, epochStartTime, epochEndTime);
        return new SpaceApiModels.TargetFilterBreakdownResponse(belongsHit, weightHit, epochHit, excludeHit,
                ephemerisWarningHit, maxApproximationDays);
    }

    /**
     * 仅用于筛选命中说明的计数查询。该查询刻意不带排序：COUNT 无需顺序，避免数据库在大目录上
     * 产生额外排序开销，也避免聚合查询携带 ORDER BY 的兼容性问题。
     */
    private long countTargets(List<Long> ids,
                              List<String> belongs,
                              BigDecimal minWeight,
                              BigDecimal maxWeight,
                              Instant epochStartTime,
                              Instant epochEndTime) {
        return targetMapper.selectCount(createTargetCountQuery(ids, belongs, minWeight, maxWeight,
                epochStartTime, epochEndTime));
    }

    /**
     * 构建可复用的目标筛选 Lambda 条件，确保分页查询和规划查询采用相同的目录筛选规则。
     *
     * @param ids 目标主键集合，可为空
     * @param belongs 国籍集合，可为空
     * @param minWeight 质量下限，可为空
     * @param maxWeight 质量上限，可为空
     * @return 目标查询条件
     */
    private LambdaQueryWrapper<TargetEntity> createTargetQuery(List<Long> ids,
                                                                 List<String> belongs,
                                                                 BigDecimal minWeight,
                                                                 BigDecimal maxWeight) {
        return createTargetQuery(ids, belongs, minWeight, maxWeight, null, null);
    }

    /** 构建包含轨道历元范围的目标 Lambda 查询条件。 */
    private LambdaQueryWrapper<TargetEntity> createTargetQuery(List<Long> ids,
                                                                 List<String> belongs,
                                                                 BigDecimal minWeight,
                                                                 BigDecimal maxWeight,
                                                                 Instant epochStartTime,
                                                                 Instant epochEndTime) {
        LambdaQueryWrapper<TargetEntity> query = new LambdaQueryWrapper<TargetEntity>()
                .orderByAsc(TargetEntity::getId);
        applyTargetFilters(query, ids, belongs, minWeight, maxWeight, epochStartTime, epochEndTime);
        return query;
    }

    /** 构建不带排序的目标计数条件，供筛选命中分解使用。 */
    private LambdaQueryWrapper<TargetEntity> createTargetCountQuery(List<Long> ids,
                                                                      List<String> belongs,
                                                                      BigDecimal minWeight,
                                                                      BigDecimal maxWeight,
                                                                      Instant epochStartTime,
                                                                      Instant epochEndTime) {
        LambdaQueryWrapper<TargetEntity> query = new LambdaQueryWrapper<TargetEntity>();
        applyTargetFilters(query, ids, belongs, minWeight, maxWeight, epochStartTime, epochEndTime);
        return query;
    }

    /** 将所有可下推到数据库的目标筛选条件应用到查询对象；名称正则仍由 Java 侧执行。 */
    private void applyTargetFilters(LambdaQueryWrapper<TargetEntity> query,
                                    List<Long> ids,
                                    List<String> belongs,
                                    BigDecimal minWeight,
                                    BigDecimal maxWeight,
                                    Instant epochStartTime,
                                    Instant epochEndTime) {
        if (ids != null && !ids.isEmpty()) {
            query.in(TargetEntity::getId, ids);
        }
        if (belongs != null && !belongs.isEmpty()) {
            query.in(TargetEntity::getBelong, belongs);
        }
        if (minWeight != null) {
            query.gt(TargetEntity::getWeight, minWeight);
        }
        if (maxWeight != null) {
            query.le(TargetEntity::getWeight, maxWeight);
        }
        if (epochStartTime != null) {
            query.ge(TargetEntity::getEpochTime, LocalDateTime.ofInstant(epochStartTime, ZoneOffset.UTC));
        }
        if (epochEndTime != null) {
            query.le(TargetEntity::getEpochTime, LocalDateTime.ofInstant(epochEndTime, ZoneOffset.UTC));
        }
    }

    /**
     * 校验页码，避免无效页码导致数据库产生不可预期的偏移量。
     *
     * @param pageNumber 页码
     * @return 已校验页码
     */
    private long validatePageNumber(long pageNumber) {
        if (pageNumber < 1L) {
            throw new IllegalArgumentException("页码必须大于 0");
        }
        return pageNumber;
    }

    /**
     * 校验并限制单页记录数，避免单次请求重新触发全量目录读取。
     *
     * @param pageSize 每页记录数
     * @return 已校验页大小
     */
    private long validatePageSize(long pageSize) {
        if (pageSize < 1L || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("每页记录数必须在 1 到 200 之间");
        }
        return pageSize;
    }

    /**
     * 将任务卫星选择表的关键字应用到名称、目标名称、目录编号和模型标识字段，支持按任一目录标识快速筛选。
     *
     * @param query 卫星目录 Lambda 查询条件
     * @param keyword 用户输入的筛选关键字，可为空
     */
    private void applySatelliteKeyword(LambdaQueryWrapper<SatelliteEntity> query, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return;
        }
        String normalizedKeyword = keyword.trim();
        query.and(condition -> condition.like(SatelliteEntity::getName, normalizedKeyword)
                .or().like(SatelliteEntity::getObjectName, normalizedKeyword)
                .or().like(SatelliteEntity::getObjectId, normalizedKeyword)
                .or().like(SatelliteEntity::getNoradCatId, normalizedKeyword)
                .or().like(SatelliteEntity::getScid, normalizedKeyword));
    }

    /**
     * 获取全部我方卫星，供基础数据加载和卫星复选框使用。
     *
     * @return 全部我方卫星
     */
    public List<SatelliteEntity> allSatellites() {
        return findSatellites(Collections.<Long>emptyList());
    }

    /**
     * 获取全部持久化地面站基础信息。
     *
     * @return 全部地面站
     */
    public List<StationEntity> allStations() {
        return findStations(Collections.<String>emptyList());
    }
}
