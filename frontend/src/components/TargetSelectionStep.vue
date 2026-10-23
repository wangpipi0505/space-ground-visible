<script>
export default { name: 'TargetSelectionStep' }
</script>

<script setup>
defineProps({
  form: { type: null, required: true },
  belongOptions: { type: Array, required: true },
  loading: { type: Boolean, required: true },
  targets: { type: Array, required: true },
  filterBreakdown: { type: null },
  total: { type: Number, required: true },
  pageNumber: { type: Number, required: true },
  pageSize: { type: Number, required: true },
})
const emit = defineEmits(['query', 'pageChange'])
</script>

<template>
  <el-card shadow="never" class="planning-card">
    <template #header><div class="compact-card-header"><h2>目标筛选</h2></div></template>
    <el-form label-position="top" class="filter-form target-filter-form">
      <el-form-item label="国籍（数据字典）">
        <el-select v-model="form.belongs" multiple filterable clearable placeholder="从目标库实际 BELONG_ 值中选择">
          <el-option v-for="option in belongOptions" :key="option.code" :label="`${option.code}（${option.count}）`" :value="option.code" />
        </el-select>
      </el-form-item>
      <el-form-item label="历元约束（YYDDD）">
        <el-input v-model="form.minTleEpochCode" maxlength="5" inputmode="numeric" placeholder="例如：23340" />
        <small>严格保留历元晚于该值的目录目标；23340 表示 2023 年第 340 天。</small>
      </el-form-item>
      <el-form-item label="建议近似传播时长（天）">
        <el-input-number v-model="form.maxApproximationDays" :min="0" :precision="0" controls-position="right" />
        <small>超出建议时长的目标仍会尝试计算，并在任务结果中标注星历时效风险。</small>
      </el-form-item>
      <el-form-item label="最小质量（kg）"><el-input-number v-model="form.minWeight" :min="0" controls-position="right" /></el-form-item>
      <el-form-item label="排除名称">
        <el-select v-model="form.excludeNameMode" data-test="target-name-exclude-mode">
          <el-option label="文本包含" value="TEXT" /><el-option label="原始正则" value="REGEX" />
        </el-select>
        <el-input v-model="form.excludeNameText" data-test="target-name-exclude" :placeholder="form.excludeNameMode === 'REGEX' ? '例如：STARLINK|ONEWEB' : '输入需排除的名称片段'" clearable />
      </el-form-item>
    </el-form>
    <div class="target-query-action"><el-button data-test="query-targets" type="primary" :loading="loading" @click="emit('query')">按当前条件加载目标</el-button><span>国籍选项和数量由目标库 BELONG_ 实时返回；最小质量表示仅计算质量大于该值的目标。</span></div>
    <p v-if="filterBreakdown" data-test="target-filter-breakdown" class="catalog-hint">
      筛选命中（按条件顺序叠加）：国籍后 {{ filterBreakdown.belongsHit }}，质量后 {{ filterBreakdown.weightHit }}，历元约束后 {{ filterBreakdown.epochConstraintHit ?? filterBreakdown.epochHit }}，名称排除后 {{ filterBreakdown.excludeHit }}；<template v-if="filterBreakdown.ephemerisWarningHit">其中 {{ filterBreakdown.ephemerisWarningHit }} 个目标超出建议星历时效，仍会尝试计算。</template><template v-if="filterBreakdown.maxApproximationDays">（建议近似 {{ filterBreakdown.maxApproximationDays }} 天）</template>。
    </p>
    <el-table :data="targets" row-key="id" max-height="280" size="small" show-overflow-tooltip empty-text="按筛选条件加载目标目录">
      <el-table-column prop="id" label="ID" width="70" /><el-table-column prop="name" label="目标名称" min-width="150" />
      <el-table-column prop="objectId" label="OBJECT_ID" min-width="125" /><el-table-column prop="noradCatId" label="NORAD_CAT_ID" min-width="125" />
      <el-table-column prop="belong" label="国籍" width="90" /><el-table-column prop="weight" label="WEIGHT(kg)" min-width="112" />
      <el-table-column prop="rcsSize" label="RCS_SIZE" min-width="100" /><el-table-column prop="launchDate" label="LAUNCH" min-width="110" />
      <el-table-column prop="catalogComment" label="COMMENT" min-width="170" />
      <el-table-column prop="modelType" label="轨道模型" width="108" />
    </el-table>
    <el-pagination v-if="total > 0" class="table-pagination" background layout="total, prev, pager, next" :current-page="pageNumber" :page-size="pageSize" :total="total" @current-change="emit('pageChange', $event)" />
  </el-card>
</template>
